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
        PrintTemplate template = printTemplateService.getDefaultTemplate();
        PrintJobOptions printOptions = printPreferenceService.load();
        File barcodeStaging = null;
        File detailsStaging = null;
        try {
            List<Order> orders = toOrders(repository.findItems(job.id()));
            barcodeStaging = AtomicFilePublisher.stagingFile(outputFile, ".pdf");
            detailsStaging = AtomicFilePublisher.stagingFile(detailsFile, ".pdf");
            barcodePrintService.export(template, orders, barcodeStaging, printOptions);
            orderDetailsPdfExporter.export(detailsStaging, orders, new OrderDetailsPdfExporter.PrintDetailsMetadata(
                    job.supplyId(),
                    job.supplyName(),
                    job.shopName(),
                    job.printedAt(),
                    job.itemCount()
            ));
            AtomicFilePublisher.publish(barcodeStaging, outputFile);
            AtomicFilePublisher.publish(detailsStaging, detailsFile);
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
