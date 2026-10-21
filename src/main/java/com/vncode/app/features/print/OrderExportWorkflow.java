package com.vncode.app.features.print;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.google.zxing.WriterException;
import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.kiz.KizSourceSelection;
import com.vncode.app.features.kiz.KizStockShortageException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.kizmapping.KizMappingRepository;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.integration.wb.WbSupplyWorkflow;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.Kiz;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.features.shop.ShopOperationCoordinator;

public class OrderExportWorkflow {
    private final BarcodePrintService barcodePrintService = new BarcodePrintService();
    private final OrderDetailsPdfExporter orderDetailsPdfExporter = new OrderDetailsPdfExporter();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final WbSupplyWorkflow wbSupplyWorkflow = new WbSupplyWorkflow();
    private final PrintHistoryService printHistoryService = new PrintHistoryService();
    private final KizMappingRepository kizMappingRepository = new KizMappingRepository();
    private final ZnackGtinInventoryService inventoryService = new ZnackGtinInventoryService();
    private final KizPlanningData planning;

    public OrderExportWorkflow() { this(null); }
    OrderExportWorkflow(KizPlanningData planning) {
        this.planning=planning==null?new KizPlanningData(){
            public Map<Long,KizService.SgtinMetadata> metadata(Shop shop,List<Long> ids)throws IOException{return KizService.getSgtinMetadata(shop.getApiKey(),ids);}
            public Map<Long,String> mappings(int shopId,List<Long> ids){return kizMappingRepository.findMappings(shopId,ids);}
            public Set<Long> required(int shopId,List<Long> ids){return kizMappingRepository.findKizRequiredNmIds(shopId,ids);}
            public Map<Long,String> stockCategories(int shopId,List<Long> ids){
                var owner=new com.vncode.app.features.shop.ShopRepository().findById(shopId);
                var bindings=new com.vncode.app.features.kiz.KizStockCategoryRepository().bindings(owner,ids.stream().map(String::valueOf).toList());
                var active=new com.vncode.app.features.kiz.WbShipmentKizRepository().assignmentCategories(owner);
                var result=new java.util.LinkedHashMap<Long,String>();bindings.forEach((id,category)->{if(active.contains(category))result.put(Long.parseLong(id),category);});return Map.copyOf(result);
            }
            public Map<Long,List<Kiz>> reserveCategoryStock(int shopId,Map<Long,Integer> quantities){
                var owner=new com.vncode.app.features.shop.ShopRepository().findById(shopId);
                var demands=quantities.entrySet().stream().map(e->new com.vncode.app.features.kiz.KizCategoryAllocator.Demand(Long.toString(e.getKey()),Long.toString(e.getKey()),e.getValue())).toList();
                var reserved=new com.vncode.app.features.kiz.KizCategoryAllocator().reservePartial(owner,demands);
                var result=new java.util.LinkedHashMap<Long,List<Kiz>>();reserved.forEach((id,codes)->result.put(Long.parseLong(id),codes));return Map.copyOf(result);
            }
            public Map<String,String> activeGtinsByBarcode(int shopId){return new ZnackCardRegistrationRepository().activeGtinsByBarcode(shopId);}
            public Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities){return inventoryService.reserveAvailableBulk(shopId,quantities);}
            public void release(int shopId,List<Kiz> codes){inventoryService.release(shopId,codes);}
            public Map<String,List<Kiz>> reserveForOrders(Shop shop,Map<String,Integer> quantities,Map<Order,String> orders){
                return reserveForOrders(shop,quantities,orders,null);
            }
            public Map<String,List<Kiz>> reserveForOrders(Shop shop,Map<String,Integer> quantities,Map<Order,String> orders,KizSourceSelection sources){
                try{
                    var products=orders.entrySet().stream().map(e->new com.vncode.app.features.kiz.KizPurchaseMappingProof.Product(String.valueOf(e.getKey().getNmId()),e.getKey().getBarcode(),e.getValue())).toList();
                    java.util.function.Consumer<Map<String,Integer>> proof=missing->com.vncode.app.features.kiz.KizPurchaseMappingProof.require(shop,products,missing.keySet());
                    var stock=new com.vncode.app.features.kiz.KizStockReplenisher();
                    var result=sources==null?stock.ensure(shop,quantities,proof):stock.ensurePurchased(shop,quantities,proof);
                    if(result.waiting())throw new com.vncode.app.features.kiz.KizPurchasePendingException(quantities,result);
                    return sources==null?inventoryService.reserveAvailableBulk(shop.getId(),quantities):inventoryService.reservePurchasedBulk(shop.getId(),quantities);
                }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("KIZ_REPLENISH_FAILED",failure);}
            }
            public void validate(Shop shop)throws IOException{KizService.requireCurrentShop(shop.getId(),shop.getApiKey());}
            public void requireNoPending(int shopId,List<Long> ids)throws IOException{new KizAttachmentCheckpointRepository().requireNoPending(shopId,ids);}
        }:planning;
    }

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        MarketplaceGuard.requireWildberries(request.shop());
        LicenseRuntime.require(Permission.PRINT_NEW);
        try(var claim=com.vncode.app.features.print.history.PrintHistoryRepository.claimOrders(request.shop().getId(),request.orders().stream().map(Order::getId).toList())){
            return ShopOperationCoordinator.withActiveShop(request.shop().getId(),()->exportCurrent(request));
        }
        catch(IOException|WriterException|RuntimeException failure){throw failure;}
        catch(Exception unexpected){throw new IOException("WB_EXPORT_FAILED");}
    }

    private ExportResult exportCurrent(ExportRequest request)throws IOException,WriterException {
        planning.validate(request.shop());
        new com.vncode.app.features.print.history.PrintHistoryRepository().requireNoRecoverableOrders(request.shop().getId(),
                request.orders().stream().map(Order::getId).filter(id->id!=null&&id>0).distinct().toList());
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        Set<Long> replaceExistingOrderIds = Set.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        boolean inventoryConsumed = false;
        long preparedJobId=0;
        File barcodeStaging = null;
        File detailsStaging = null;
        try {
            KizAssignmentResult assignmentResult = assignKizCodes(workingOrders, request.shop(),request.kizSources());
            usedKizs = assignmentResult.usedKizs();
            replaceExistingOrderIds = assignmentResult.replaceExistingOrderIds();

            wbSupplyWorkflow.ensureOrderImages(workingOrders);
            barcodeStaging = AtomicFilePublisher.stagingFile(request.outputFile(), ".pdf");
            detailsStaging = AtomicFilePublisher.stagingFile(request.detailsFile(), ".pdf");
            LicenseRuntime.require(Permission.PRINT_NEW);
            exportPdfFiles(template, barcodeStaging, detailsStaging, request.shop(), request.supplyId(),
                    request.supplyName(), printedAt, workingOrders, request.printOptions());
            LicenseRuntime.require(Permission.PRINT_NEW);
            planning.validate(request.shop());
            preparedJobId=printHistoryService.recordPreparedJob(request.shop(),request.supplyId(),request.supplyName(),printedAt,template,workingOrders);
            inventoryService.consumeForPrint(request.shop().getId(), usedKizs,preparedJobId);
            inventoryConsumed = true;
            AtomicFilePublisher.publish(barcodeStaging, request.outputFile());
            AtomicFilePublisher.publish(detailsStaging, request.detailsFile());
            new com.vncode.app.features.print.history.PrintHistoryRepository().finishPrintJob(request.shop().getId(),preparedJobId,"pdf_ready","success");
            successRecorded = true;
            return new ExportResult(workingOrders, usedKizs, preparedJobId,
                    buildKizAttachmentAssignments(workingOrders, assignmentResult.allocatedByOrder(), replaceExistingOrderIds));
        } catch (IOException | WriterException | RuntimeException ex) {
            if (!inventoryConsumed) {
                inventoryService.release(request.shop().getId(), usedKizs);
                if(preparedJobId>0)try{new com.vncode.app.features.print.history.PrintHistoryRepository().finishPrintJob(request.shop().getId(),preparedJobId,"prepared","failed");}
                catch(RuntimeException checkpointFailure){ex.addSuppressed(checkpointFailure);}
            }
            if (!successRecorded&&preparedJobId==0&&!com.vncode.app.features.kiz.KizPurchasePendingException.isPending(ex)) {
                printHistoryService.recordFailedJob(
                        request.shop(),
                        request.supplyId(),
                        request.supplyName(),
                        printedAt,
                        template,
                        request.orders().size(),
                        ex.getMessage()
                );
            }
            throw ex;
        } finally {
            AtomicFilePublisher.deleteQuietly(barcodeStaging);
            AtomicFilePublisher.deleteQuietly(detailsStaging);
        }
    }

    public void verifyKizAvailability(List<Order> orders, Shop shop) throws IOException, IllegalStateException {
        verifyKizAvailability(orders,shop,null);
    }
    public void verifyKizAvailability(List<Order> orders, Shop shop,KizSourceSelection sources) throws IOException, IllegalStateException {
        MarketplaceGuard.requireWildberries(shop);
        LicenseRuntime.require(Permission.PRINT_NEW);
        ShopOperationCoordinator.withActiveShop(shop.getId(),()->{KizAssignmentResult result=assignKizCodes(copyOrders(orders),shop,sources);inventoryService.release(shop.getId(),result.usedKizs());return null;});
    }

    private static List<Order> copyOrders(List<Order> orders) {
        List<Order> copies = new ArrayList<>(orders.size());
        for (Order order : orders) {
            Order copy = new Order(
                    order.getId(),
                    order.getImage(),
                    order.getBrand(),
                    order.getName(),
                    order.getSize(),
                    order.getColor(),
                    order.getArticle(),
                    order.getSticker(),
                    order.getBarcode()
            );
            copy.setKiz(order.getKiz());
            copy.setStickerCode(order.getStickerCode());
            copy.setImageUrl(order.getImageUrl());
            copy.setSubjectName(order.getSubjectName());
            copy.setRuSize(order.getRuSize());
            copy.setCreatedAt(order.getCreatedAt());
            copy.setPrice(order.getPrice());
            copy.setSupplierStatus(order.getSupplierStatus());
            copy.setWbStatus(order.getWbStatus());
            copy.setNmId(order.getNmId());
            copy.setRequiresKiz(order.isRequiresKiz());
            copies.add(copy);
        }
        return copies;
    }

    KizAssignmentResult assignKizCodes(List<Order> orders, Shop shop) throws IOException {
        return assignKizCodes(orders,shop,null);
    }
    KizAssignmentResult assignKizCodes(List<Order> orders, Shop shop,KizSourceSelection sources) throws IOException {
        if(sources!=null&&!sources.anySelected())throw new IllegalStateException("KIZ_SOURCE_REQUIRED");
        boolean useImported=sources==null||sources.sellerImported(),usePurchased=sources==null||sources.purchased();
        planning.validate(shop);
        Set<Long> uniqueOrders=new java.util.HashSet<>();
        for (Order order : orders) {
            if(order==null||order.getId()!=null&&order.getId()>0&&!uniqueOrders.add(order.getId()))throw new IllegalArgumentException("DUPLICATE_KIZ_ORDER");
            order.setKiz(null);
        }

        List<Kiz> usedKizs = new ArrayList<>();
        Map<Long,Kiz> allocatedByOrder=new java.util.LinkedHashMap<>();
        Set<Long> replaceExistingOrderIds = new LinkedHashSet<>();
        List<Long> orderIds = orders.stream()
                .map(Order::getId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        planning.requireNoPending(shop.getId(),orderIds);
        java.util.Map<Long, KizService.SgtinMetadata> metadataByOrderId = planning.metadata(shop, orderIds);
        List<Long> nmIds = orders.stream()
                .map(Order::getNmId)
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        java.util.Map<Long, String> mappingByNmId = usePurchased?planning.mappings(shop.getId(), nmIds):Map.of();
        Set<Long> kizRequiredNmIds = planning.required(shop.getId(), nmIds);
        Map<String,String> activeGtinsByBarcode=usePurchased?planning.activeGtinsByBarcode(shop.getId()):Map.of();
        var categories=planning.stockCategories(shop.getId(),nmIds);
        var needs=new ArrayList<Order>();var gtinForOrder=new java.util.IdentityHashMap<Order,String>();
        java.util.Map<String, List<Order>> ordersByGtin = new java.util.LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            KizService.SgtinMetadata sgtinMetadata = order.getId() == null ? null : metadataByOrderId.get(order.getId());
            String gtin = order.getBarcode()==null?null:activeGtinsByBarcode.get(order.getBarcode().strip());
            if(gtin==null)gtin=order.getNmId() == null ? null : mappingByNmId.get(order.getNmId());
            boolean requiresKiz = order.isRequiresKiz() || isProductKizRequired(order, kizRequiredNmIds)
                    || (sgtinMetadata != null && sgtinMetadata.available()) || (order.getNmId()!=null&&categories.containsKey(order.getNmId()));
            if (sgtinMetadata != null && sgtinMetadata.available()) {
                order.setRequiresKiz(true);
                if(com.vncode.app.features.kiz.status.Kn3Mark.wbState(sgtinMetadata.decision(),sgtinMetadata.appliedValue())==com.vncode.app.features.kiz.status.Kn3Mark.State.REJECTED)
                    throw new com.vncode.app.features.kiz.KizAssignmentException("KIZ_REMOTE_REJECTED",String.valueOf(order.getId()),String.valueOf(order.getNmId()));
                if (sgtinMetadata.hasAppliedValue()) {
                    String appliedGtin = KizService.extractGtin(sgtinMetadata.appliedValue());
                    if (sources!=null || gtin == null || (order.getNmId()!=null&&categories.containsKey(order.getNmId())) || com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,appliedGtin)) {
                        order.setKiz(sgtinMetadata.appliedValue());
                        continue;
                    }
                    replaceExistingOrderIds.add(order.getId());
                }
            }
            if (!requiresKiz) {
                continue;
            }
            order.setRequiresKiz(true);
            needs.add(order);gtinForOrder.put(order,gtin);
        }

        try {
            var categoryQuantities=new java.util.LinkedHashMap<Long,Integer>();
            for(var order:needs)if(useImported&&(order.getNmId()!=null&&categories.containsKey(order.getNmId())))categoryQuantities.merge(order.getNmId(),1,Math::addExact);
            var stock=categoryQuantities.isEmpty()?Map.<Long,List<Kiz>>of():planning.reserveCategoryStock(shop.getId(),categoryQuantities);
            stock.values().forEach(usedKizs::addAll);var stockCursor=new java.util.HashMap<Long,Integer>();
            var shortages=new ArrayList<KizStockShortageException.Missing>();
            for(var order:needs){
                var codes=order.getNmId()==null?List.<Kiz>of():stock.getOrDefault(order.getNmId(),List.of());int index=stockCursor.getOrDefault(order.getNmId(),0);
                if(index<codes.size()){
                    var code=codes.get(index);order.setKiz(code.getCode());
                    if(order.getId()!=null)allocatedByOrder.put(order.getId(),code);
                    stockCursor.put(order.getNmId(),index+1);continue;
                }
                if(!usePurchased){String category=order.getSubjectName();if(category==null||category.isBlank())category=order.getNmId()==null?"":categories.getOrDefault(order.getNmId(),"");shortages.add(new KizStockShortageException.Missing(String.valueOf(order.getId()),String.valueOf(order.getNmId()),category));continue;}
                String gtin=gtinForOrder.get(order);
                if(gtin==null)throw new com.vncode.app.features.kiz.KizAssignmentException("KIZ_GTIN_MAPPING_MISSING",String.valueOf(order.getId()),String.valueOf(order.getNmId()));
                ordersByGtin.computeIfAbsent(GtinNormalizer.normalize(gtin),key->new ArrayList<>()).add(order);
            }
            if(!shortages.isEmpty())throw new KizStockShortageException(shortages);
            Map<String,Integer> quantities=new java.util.LinkedHashMap<>();
            ordersByGtin.forEach((gtin,gtinOrders)->quantities.put(gtin,gtinOrders.size()));
            if(!quantities.isEmpty()) {
                LicenseRuntime.require(Permission.PRINT_NEW);
                planning.validate(shop);
                var purchaseTargets=new java.util.IdentityHashMap<Order,String>();ordersByGtin.forEach((gtin,group)->group.forEach(order->purchaseTargets.put(order,gtin)));
                var reserved=planning.reserveForOrders(shop,quantities,purchaseTargets,sources);
                usedKizs.addAll(reserved.values().stream().flatMap(List::stream).toList());
            for (java.util.Map.Entry<String, List<Order>> entry : ordersByGtin.entrySet()) {
                List<Order> gtinOrders = entry.getValue();
                List<Kiz> kizList = reserved.get(entry.getKey());
                if(kizList==null||kizList.size()!=gtinOrders.size())throw new IllegalStateException("KIZ_INVENTORY_SHORTAGE");
                for (int i = 0; i < gtinOrders.size(); i++) {
                Kiz kiz = kizList.get(i);
                    gtinOrders.get(i).setKiz(kiz.getCode());
                    if(gtinOrders.get(i).getId()!=null)allocatedByOrder.put(gtinOrders.get(i).getId(),kiz);
                }
            }
            }
                // Seller uploads may contain the same text more than once. Reservation
                // identity is the stock row, not the raw text of the uploaded code.
                var uniqueCodes=new java.util.HashSet<Integer>();
                for(Kiz code:usedKizs)if(code==null||code.getCode()==null||!uniqueCodes.add(code.getId()))throw new IllegalStateException("DUPLICATE_KIZ_RESERVATION");
        } catch (IOException | RuntimeException e) {
            planning.release(shop.getId(), usedKizs);
            throw e;
        }

        return new KizAssignmentResult(List.copyOf(usedKizs), Set.copyOf(replaceExistingOrderIds),Map.copyOf(allocatedByOrder));
    }

    private boolean isProductKizRequired(Order order, Set<Long> kizRequiredNmIds) {
        return order != null
                && order.getNmId() != null
                && kizRequiredNmIds != null
                && kizRequiredNmIds.contains(order.getNmId());
    }

    private void exportPdfFiles(PrintTemplate template,
                                File outputFile,
                                File detailsFile,
                                Shop shop,
                                String supplyId,
                                String supplyName,
                                String printedAt,
                                List<Order> orders,
                                PrintJobOptions printOptions) throws IOException, WriterException {
        barcodePrintService.export(template, orders, outputFile, printOptions);
        orderDetailsPdfExporter.export(detailsFile, orders, new OrderDetailsPdfExporter.PrintDetailsMetadata(
                supplyId,
                supplyName,
                shop == null ? null : shop.getName(),
                printedAt,
                orders.size()
        ));
    }

    private static List<KizAttachmentAssignment> buildKizAttachmentAssignments(List<Order> orders,
                                                                              Map<Long,Kiz> allocatedByOrder,
                                                                              Set<Long> replaceExistingOrderIds) {
        if (orders == null || orders.isEmpty() || allocatedByOrder.isEmpty()) {
            return List.of();
        }
        List<KizAttachmentAssignment> assignments = new ArrayList<>();
        for (Order order : orders) {
            if (order.getId() == null || order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }
            if (!order.isRequiresKiz()) {
                continue;
            }
            Kiz sourceKiz = allocatedByOrder.get(order.getId());
            if (sourceKiz == null) {
                continue;
            }
            boolean replaceExisting = replaceExistingOrderIds != null && replaceExistingOrderIds.contains(order.getId());
            assignments.add(new KizAttachmentAssignment(order.getId(), order.getKiz(), sourceKiz, replaceExisting));
        }
        return assignments;
    }

    public record ExportRequest(
            Shop shop,
            String supplyId,
            String supplyName,
            List<Order> orders,
            PrintJobOptions printOptions,
            File outputFile,
            File detailsFile,
            KizSourceSelection kizSources
    ) {
        /** Compatibility for non-shipment callers; the shipment UI supplies an explicit snapshot. */
        public ExportRequest(Shop shop,String supplyId,String supplyName,List<Order> orders,PrintJobOptions printOptions,File outputFile,File detailsFile){this(shop,supplyId,supplyName,orders,printOptions,outputFile,detailsFile,null);}
    }

    public record ExportResult(
            List<Order> exportedOrders,
            List<Kiz> consumedKizs,
            long printJobId,
            List<KizAttachmentAssignment> kizAttachments
    ) {
    }

    public record KizAttachmentAssignment(Long orderId, String kizCode, Kiz sourceKiz, boolean replaceExisting,String expectedRejectedHash) {
        public KizAttachmentAssignment{expectedRejectedHash=java.util.Objects.toString(expectedRejectedHash,"");if(!expectedRejectedHash.isBlank()&&(!replaceExisting||!expectedRejectedHash.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("KIZ_REPLACEMENT_INVALID");}
        public KizAttachmentAssignment(Long orderId,String code,Kiz source,boolean replace){this(orderId,code,source,replace,"");}
        @Override public String toString(){return "KizAttachmentAssignment[orderId="+orderId+", replaceExisting="+replaceExisting+"]";}
    }

    interface KizPlanningData {
        Map<Long,KizService.SgtinMetadata> metadata(Shop shop,List<Long> ids)throws IOException;
        Map<Long,String> mappings(int shopId,List<Long> ids);
        Set<Long> required(int shopId,List<Long> ids);
        default Map<String,String> activeGtinsByBarcode(int shopId){return Map.of();}
        default Map<Long,String> stockCategories(int shopId,List<Long> ids){return Map.of();}
        default Map<Long,List<Kiz>> reserveCategoryStock(int shopId,Map<Long,Integer> quantities){return Map.of();}
        default void validate(Shop shop)throws IOException{}
        default void requireNoPending(int shopId,List<Long> ids)throws IOException{}
        Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities);
        default Map<String,List<Kiz>> reserveForOrders(Shop shop,Map<String,Integer> quantities,Map<Order,String> orders){return reserve(shop.getId(),quantities);}
        default Map<String,List<Kiz>> reserveForOrders(Shop shop,Map<String,Integer> quantities,Map<Order,String> orders,KizSourceSelection sources){return reserveForOrders(shop,quantities,orders);}
        void release(int shopId,List<Kiz> codes);
    }

    record KizAssignmentResult(List<Kiz> usedKizs, Set<Long> replaceExistingOrderIds,Map<Long,Kiz> allocatedByOrder) {
    }
}
