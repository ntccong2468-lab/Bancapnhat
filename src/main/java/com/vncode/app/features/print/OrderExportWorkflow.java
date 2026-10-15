package com.vncode.app.features.print;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.google.zxing.WriterException;
import com.vncode.app.features.kiz.KizService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExportWorkflow.class);
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
            public Map<String,String> activeGtinsByBarcode(int shopId){return new ZnackCardRegistrationRepository().activeGtinsByBarcode(shopId);}
            public Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities){
                try{
                    var current=new com.vncode.app.features.shop.ShopRepository().findById(shopId);
                    var result=new com.vncode.app.features.kiz.KizStockReplenisher().ensure(current,quantities);
                    if(result.waiting())throw new com.vncode.app.features.kiz.KizAssignmentException("KIZ_PURCHASE_PENDING","","");
                    return inventoryService.reserveAvailableBulk(shopId,quantities);
                }catch(RuntimeException failed){throw failed;}catch(Exception failed){throw new IllegalStateException("KIZ_REPLENISH_FAILED",failed);}
            }
            public void release(int shopId,List<Kiz> codes){inventoryService.release(shopId,codes);}
            public void validate(Shop shop)throws IOException{KizService.requireCurrentShop(shop.getId(),shop.getApiKey());}
            public void requireNoPending(int shopId,List<Long> ids)throws IOException{new KizAttachmentCheckpointRepository().requireNoPending(shopId,ids);}
        }:planning;
    }

    public ExportResult export(ExportRequest request) throws IOException, WriterException {
        MarketplaceGuard.requireWildberries(request.shop());
        LicenseRuntime.require(Permission.PRINT_NEW);
        try{return ShopOperationCoordinator.withActiveShop(request.shop().getId(),()->exportCurrent(request));}
        catch(IOException|WriterException|RuntimeException failure){throw failure;}
        catch(Exception unexpected){throw new IOException("WB_EXPORT_FAILED");}
    }

    private ExportResult exportCurrent(ExportRequest request)throws IOException,WriterException {
        planning.validate(request.shop());
        List<Order> workingOrders = copyOrders(request.orders());
        List<Kiz> usedKizs = List.of();
        Set<Long> replaceExistingOrderIds = Set.of();
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        String printedAt = Instant.now().toString();
        boolean successRecorded = false;
        boolean inventoryConsumed = false;
        File barcodeStaging = null;
        File detailsStaging = null;
        try {
            KizAssignmentResult assignmentResult = assignKizCodes(workingOrders, request.shop());
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
            inventoryService.consume(request.shop().getId(), usedKizs);
            inventoryConsumed = true;
            AtomicFilePublisher.publish(barcodeStaging, request.outputFile());
            AtomicFilePublisher.publish(detailsStaging, request.detailsFile());
            long printJobId = printHistoryService.recordSuccessfulJob(request.shop(), request.supplyId(), request.supplyName(), printedAt, template, workingOrders);
            successRecorded = true;
            return new ExportResult(workingOrders, usedKizs, printJobId,
                    buildKizAttachmentAssignments(workingOrders, usedKizs, replaceExistingOrderIds));
        } catch (IOException | WriterException | RuntimeException ex) {
            if (!inventoryConsumed) {
                inventoryService.release(request.shop().getId(), usedKizs);
            }
            if (!successRecorded) {
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
        MarketplaceGuard.requireWildberries(shop);
        LicenseRuntime.require(Permission.PRINT_NEW);
        ShopOperationCoordinator.withActiveShop(shop.getId(),()->{KizAssignmentResult result=assignKizCodes(copyOrders(orders),shop);inventoryService.release(shop.getId(),result.usedKizs());return null;});
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
        planning.validate(shop);
        Set<Long> uniqueOrders=new java.util.HashSet<>();
        for (Order order : orders) {
            if(order==null||order.getId()!=null&&order.getId()>0&&!uniqueOrders.add(order.getId()))throw new IllegalArgumentException("DUPLICATE_KIZ_ORDER");
            order.setKiz(null);
        }

        List<Kiz> usedKizs = new ArrayList<>();
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
        java.util.Map<Long, String> mappingByNmId = planning.mappings(shop.getId(), nmIds);
        Set<Long> kizRequiredNmIds = planning.required(shop.getId(), nmIds);
        Map<String,String> activeGtinsByBarcode=planning.activeGtinsByBarcode(shop.getId());
        java.util.Map<String, List<Order>> ordersByGtin = new java.util.LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            KizService.SgtinMetadata sgtinMetadata = order.getId() == null ? null : metadataByOrderId.get(order.getId());
            String gtin = order.getBarcode()==null?null:activeGtinsByBarcode.get(order.getBarcode().strip());
            if(gtin==null)gtin=order.getNmId() == null ? null : mappingByNmId.get(order.getNmId());
            boolean requiresKiz = order.isRequiresKiz() || isProductKizRequired(order, kizRequiredNmIds)
                    || (sgtinMetadata != null && sgtinMetadata.available());
            if (sgtinMetadata != null && sgtinMetadata.available()) {
                order.setRequiresKiz(true);
                if (sgtinMetadata.hasAppliedValue()) {
                    String appliedGtin = KizService.extractGtin(sgtinMetadata.appliedValue());
                    if (gtin == null || com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,appliedGtin)) {
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
            if (gtin == null) {
                throw new com.vncode.app.features.kiz.KizAssignmentException("KIZ_GTIN_MAPPING_MISSING",String.valueOf(order.getId()),String.valueOf(order.getNmId()));
            }
            ordersByGtin.computeIfAbsent(GtinNormalizer.normalize(gtin), key -> new ArrayList<>()).add(order);
        }

        try {
            Map<String,Integer> quantities=new java.util.LinkedHashMap<>();
            ordersByGtin.forEach((gtin,gtinOrders)->quantities.put(gtin,gtinOrders.size()));
            if(!quantities.isEmpty()) {
                LicenseRuntime.require(Permission.PRINT_NEW);
                planning.validate(shop);
                var reserved=planning.reserve(shop.getId(),quantities);
                usedKizs.addAll(reserved.values().stream().flatMap(List::stream).toList());
                var uniqueCodes=new java.util.HashSet<String>();
                for(Kiz code:usedKizs)if(code==null||code.getCode()==null||!uniqueCodes.add(KizService.scannerSafeCode(code.getCode())))throw new IllegalStateException("DUPLICATE_KIZ_RESERVATION");
            for (java.util.Map.Entry<String, List<Order>> entry : ordersByGtin.entrySet()) {
                List<Order> gtinOrders = entry.getValue();
                List<Kiz> kizList = reserved.get(entry.getKey());
                if(kizList==null||kizList.size()!=gtinOrders.size())throw new IllegalStateException("KIZ_INVENTORY_SHORTAGE");
                for (int i = 0; i < gtinOrders.size(); i++) {
                Kiz kiz = kizList.get(i);
                    gtinOrders.get(i).setKiz(kiz.getCode());
                }
            }
            }
        } catch (RuntimeException e) {
            planning.release(shop.getId(), usedKizs);
            throw e;
        }

        return new KizAssignmentResult(usedKizs, Set.copyOf(replaceExistingOrderIds));
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
                                                                              List<Kiz> usedKizs,
                                                                              Set<Long> replaceExistingOrderIds) {
        if (orders == null || orders.isEmpty() || usedKizs == null || usedKizs.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, Kiz> kizByCode = usedKizs.stream()
                .collect(java.util.stream.Collectors.toMap(Kiz::getCode, value -> value, (left, right) -> left));
        List<KizAttachmentAssignment> assignments = new ArrayList<>();
        for (Order order : orders) {
            if (order.getId() == null || order.getKiz() == null || order.getKiz().isBlank()) {
                continue;
            }
            if (!order.isRequiresKiz()) {
                continue;
            }
            Kiz sourceKiz = kizByCode.get(order.getKiz());
            if (sourceKiz == null) {
                LOGGER.warn("Không tìm thấy KIZ nguồn để enqueue background attach cho order {}", order.getId());
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
            File detailsFile
    ) {
    }

    public record ExportResult(
            List<Order> exportedOrders,
            List<Kiz> consumedKizs,
            long printJobId,
            List<KizAttachmentAssignment> kizAttachments
    ) {
    }

    public record KizAttachmentAssignment(Long orderId, String kizCode, Kiz sourceKiz, boolean replaceExisting) {
        @Override public String toString(){return "KizAttachmentAssignment[orderId="+orderId+", replaceExisting="+replaceExisting+"]";}
    }

    interface KizPlanningData {
        Map<Long,KizService.SgtinMetadata> metadata(Shop shop,List<Long> ids)throws IOException;
        Map<Long,String> mappings(int shopId,List<Long> ids);
        Set<Long> required(int shopId,List<Long> ids);
        default Map<String,String> activeGtinsByBarcode(int shopId){return Map.of();}
        default void validate(Shop shop)throws IOException{}
        default void requireNoPending(int shopId,List<Long> ids)throws IOException{}
        Map<String,List<Kiz>> reserve(int shopId,Map<String,Integer> quantities);
        void release(int shopId,List<Kiz> codes);
    }

    record KizAssignmentResult(List<Kiz> usedKizs, Set<Long> replaceExistingOrderIds) {
    }
}
