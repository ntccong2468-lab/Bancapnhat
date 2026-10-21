package com.vncode.app.features.print.history;

import com.google.zxing.WriterException;
import com.vncode.app.features.print.BarcodePrintService;
import com.vncode.app.features.print.OrderDetailsPdfExporter;
import com.vncode.app.features.print.OrderExportWorkflow;
import com.vncode.app.features.print.PrintJobOptions;
import com.vncode.app.features.print.PrintPreferenceService;
import com.vncode.app.features.print.PrintTemplate;
import com.vncode.app.features.print.PrintTemplateService;
import com.vncode.app.models.Order;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PrintHistoryService {
    private final PrintHistoryRepository repository = new PrintHistoryRepository();
    private final ImageCacheRepository imageCacheRepository = new ImageCacheRepository();
    private final PrintTemplateService printTemplateService = new PrintTemplateService();
    private final BarcodePrintService barcodePrintService = new BarcodePrintService();
    private final OrderDetailsPdfExporter orderDetailsPdfExporter = new OrderDetailsPdfExporter();
    private final PrintPreferenceService printPreferenceService = new PrintPreferenceService();

    public long recordSuccessfulJob(Shop shop, String supplyId, String supplyName, String printedAt, PrintTemplate template, List<Order> orders) {
        List<PrintHistoryItem> items = snapshotItems(orders);
        return repository.insertSuccessfulJob(
                shop.getId(),
                shop.getName(),
                shop.getMarketplace().name(),
                supplyId,
                supplyName,
                printedAt,
                orders.size(),
                template.getId(),
                template.getName(),
                printTemplateService.toJson(template),
                items
        );
    }

    public long recordPreparedFboJob(Shop shop,PrintTemplate template,List<Order> orders) {
        return repository.insertPreparedFboJob(shop,template.getName(),printTemplateService.toJson(template),snapshotItems(orders));
    }
    public long recordPreparedFboJob(Shop shop,String templateName,String layoutJson,List<Order> orders) {
        return repository.insertPreparedFboJob(shop,templateName,layoutJson,snapshotItems(orders));
    }
    public long recordPreparedJob(Shop shop,String supplyId,String supplyName,String printedAt,
                                  PrintTemplate template,List<Order> orders) {
        return repository.insertPreparedJob(shop,supplyId,supplyName,printedAt,template.getId(),
                template.getName(),printTemplateService.toJson(template),snapshotItems(orders));
    }
    public long recordPreparedKizJob(Shop shop,
            com.vncode.app.features.kizmapping.ZnackKizLabelMetadata metadata,
            List<com.vncode.app.models.Kiz> codes,String gtin) {
        var orders=new ArrayList<Order>();
        for(var code:codes){var order=new Order();order.setId((long)code.getId());
            order.setName(metadata.productName());order.setSize(metadata.size());order.setKiz(code.getCode());orders.add(order);}
        var layout=new com.google.gson.JsonObject();layout.addProperty("kind","znack-kiz-label/v1");
        layout.addProperty("gtin",com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin));
        layout.add("metadata",new com.google.gson.Gson().toJsonTree(metadata));
        return repository.insertPreparedJob(shop,"KIZ/"+java.util.UUID.randomUUID(),"Nhãn KIZ",
                java.time.Instant.now().toString(),null,"Nhãn KIZ",layout.toString(),snapshotItems(orders));
    }
    private List<PrintHistoryItem> snapshotItems(List<Order> orders) {
        List<PrintHistoryItem> items = new ArrayList<>(orders.size());
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            String imageCacheKey = imageCacheKey(order.getImageUrl());
            if (imageCacheKey != null && order.getImage() != null && order.getImage().length > 0) {
                imageCacheRepository.saveImage(imageCacheKey, order.getImageUrl(), order.getImage(), "image/png");
            }
            items.add(new PrintHistoryItem(
                    0L,
                    index,
                    order.getId() == null ? 0L : order.getId(),
                    order.getBrand(),
                    order.getName(),
                    order.getSubjectName(),
                    order.getSize(),
                    order.getRuSize(),
                    order.getColor(),
                    order.getArticle(),
                    order.getBarcode(),
                    order.getSticker(),
                    order.getStickerCode(),
                    order.getKiz(),
                    imageCacheKey,
                    order.getId() == null ? null : String.valueOf(order.getId()),
                    null
            ));
        }
        return List.copyOf(items);
    }

    public long recordFailedJob(Shop shop, String supplyId, String supplyName, String printedAt, PrintTemplate template, int itemCount, String errorMessage) {
        return repository.insertFailedJob(
                shop.getId(),
                shop.getName(),
                shop.getMarketplace().name(),
                supplyId,
                supplyName,
                printedAt,
                itemCount,
                template == null ? null : template.getId(),
                template == null ? null : template.getName(),
                template == null ? printTemplateService.toJson(printTemplateService.getDefaultTemplate()) : printTemplateService.toJson(template),
                errorMessage
        );
    }

    public List<PrintHistoryJobSummary> getJobs(int shopId) {
        return repository.findJobsByShop(shopId);
    }

    public List<PrintHistoryItem> getItems(long printJobId) {
        return repository.findItems(printJobId);
    }

    public boolean hasSuccessfulJobForSupply(int shopId, String supplyId) {
        return supplyId != null && !supplyId.isBlank() && repository.hasSuccessfulJobForSupply(shopId, supplyId);
    }

    public OrderExportWorkflow.ExportResult reprint(PrintHistoryJobSummary job, File outputFile, File detailsFile) throws IOException, WriterException {
        if(job==null||!job.canReprint())throw new IllegalArgumentException("Print history is not recoverable");
        boolean ozonCombined=job.isFbo()&&"OZON".equalsIgnoreCase(job.marketplace())&&com.vncode.app.integration.ozon.OzonFboBarcodePdfExporter.isFrozenLayout(job.templateLayoutJson());
        PrintTemplate template = ozonCombined||job.isKizExport()?null:job.templateLayoutJson()==null||job.templateLayoutJson().isBlank()?printTemplateService.getDefaultTemplate():printTemplateService.fromJson(job.templateLayoutJson());
        PrintJobOptions printOptions = printPreferenceService.load();
        File barcodeStaging = null;
        File detailsStaging = null;
        try {
            List<Order> orders = toOrders(repository.findItems(job.id()));
            barcodeStaging = AtomicFilePublisher.stagingFile(outputFile, ".pdf");
            detailsStaging = AtomicFilePublisher.stagingFile(detailsFile, ".pdf");
            if(job.isKizExport()){
                var layout=com.google.gson.JsonParser.parseString(job.templateLayoutJson()).getAsJsonObject();
                if(!"znack-kiz-label/v1".equals(layout.get("kind").getAsString()))throw new IOException("Không nhận dạng được bố cục nhãn KIZ đã lưu.");
                var metadata=new com.google.gson.Gson().fromJson(layout.get("metadata"),com.vncode.app.features.kizmapping.ZnackKizLabelMetadata.class);
                var codes=orders.stream().map(order->new com.vncode.app.models.Kiz(Math.toIntExact(order.getId()),order.getKiz())).toList();
                new com.vncode.app.features.print.ZnackKizLabelPdfExporter().write(codes,metadata,barcodeStaging);
            }
            else if(ozonCombined)com.vncode.app.integration.ozon.OzonFboBarcodePdfExporter.renderFrozen(job.templateLayoutJson(),orders,barcodeStaging);
            else if(job.isFbo())com.vncode.app.features.print.GenerateBarcode.exportTemplatePages(template,orders,barcodeStaging);
            else barcodePrintService.export(template, orders, barcodeStaging, printOptions);
            orderDetailsPdfExporter.export(detailsStaging, orders, new OrderDetailsPdfExporter.PrintDetailsMetadata(
                    job.supplyId(),
                    job.supplyName(),
                    job.shopName(),
                    job.printedAt(),
                    job.itemCount()
            ));
            AtomicFilePublisher.publish(barcodeStaging, outputFile);
            AtomicFilePublisher.publish(detailsStaging, detailsFile);
            if("pdf_ready".equalsIgnoreCase(job.status()))repository.finishPrintJob(job.shopId(),job.id(),"pdf_ready","success");
            return new OrderExportWorkflow.ExportResult(orders, List.of(), 0L, List.of());
        } finally {
            AtomicFilePublisher.deleteQuietly(barcodeStaging);
            AtomicFilePublisher.deleteQuietly(detailsStaging);
        }
    }

    private List<Order> toOrders(List<PrintHistoryItem> items) {
        List<Order> orders = new ArrayList<>(items.size());
        List<String> imageKeys = items.stream()
                .map(PrintHistoryItem::imageCacheKey)
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        java.util.Map<String, byte[]> imagesByKey = imageCacheRepository.findImages(imageKeys);
        for (PrintHistoryItem item : items) {
            Order order = new Order(
                    item.orderId(),
                    null,
                    item.brand(),
                    item.name(),
                    item.size(),
                    item.color(),
                    item.article(),
                    item.sticker(),
                    item.barcode()
            );
            order.setSubjectName(item.subjectName());
            order.setRuSize(item.ruSize());
            order.setStickerCode(item.stickerCode());
            order.setKiz(item.kiz());
            order.setImageUrl(item.imageCacheKey());
            if (item.imageCacheKey() != null && !item.imageCacheKey().isBlank()) {
                order.setImage(imagesByKey.get(item.imageCacheKey()));
            }
            orders.add(order);
        }
        return orders;
    }

    public static String imageCacheKey(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        String trimmed = imageUrl.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
