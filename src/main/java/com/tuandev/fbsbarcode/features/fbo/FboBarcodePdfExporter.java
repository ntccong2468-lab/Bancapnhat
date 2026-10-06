package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.features.print.GenerateBarcode;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FboBarcodePdfExporter {
    private final FboPrintTemplateService templateService;

    public FboBarcodePdfExporter() {
        this(new FboPrintTemplateService());
    }

    FboBarcodePdfExporter(FboPrintTemplateService templateService) {
        this.templateService = templateService;
    }

    public void export(List<FboBarcodePrintItem> items, File outputFile) throws IOException {
        List<FboPrintPage> pages = new ArrayList<>();
        int pairNumber = 1;
        for (FboBarcodePrintItem item : items == null ? List.<FboBarcodePrintItem>of() : items) {
            for (int i = 0; i < Math.max(0, item.quantity()); i++) {
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                pages.add(FboPrintPage.barcode(item.product(), pairNumber));
                pairNumber++;
            }
        }
        exportPages(pages, outputFile, () -> {
        });
    }

    public void exportPlan(FboPrintPlan plan, File outputFile) throws IOException {
        exportPlan(plan, outputFile, () -> {
        });
    }

    public void exportPlan(FboPrintPlan plan, File outputFile, Runnable beforePublish) throws IOException {
        exportPages(plan == null ? List.of() : plan.pages(), outputFile, beforePublish);
    }

    public void exportSinglePage(FboProductSku product, File outputFile) throws IOException {
        exportPages(List.of(FboPrintPage.barcode(product, 1)), outputFile, () -> {
        });
    }

    private void exportPages(List<FboPrintPage> pages, File outputFile, Runnable beforePublish) throws IOException {
        File staging = AtomicFilePublisher.stagingFile(outputFile, ".pdf");
        try {
            PrintTemplate template = templateService.getDefaultTemplate();
            GenerateBarcode.exportTemplatePages(template, toOrders(pages), staging);
            if (beforePublish != null) {
                beforePublish.run();
            }
            AtomicFilePublisher.publish(staging, outputFile);
        } finally {
            AtomicFilePublisher.deleteQuietly(staging);
        }
    }

    private List<Order> toOrders(List<FboPrintPage> pages) {
        List<Order> orders = new ArrayList<>();
        for (FboPrintPage page : pages == null ? List.<FboPrintPage>of() : pages) {
            if (page == null || page.product() == null) {
                continue;
            }
            orders.add(toOrder(page));
        }
        return orders;
    }

    private Order toOrder(FboPrintPage page) {
        FboProductSku product = page.product();
        Order order = new Order();
        order.setId(product.nmId());
        order.setNmId(product.nmId());
        order.setBrand(product.brand());
        order.setName(product.title());
        order.setSubjectName(product.subjectName());
        order.setArticle(product.vendorCode());
        order.setColor(product.color());
        order.setSize(product.size());
        order.setRuSize(product.ruSize());
        order.setBarcode(page.kind() == FboPrintPage.Kind.BARCODE ? product.sku() : null);
        order.setKiz(page.kind() == FboPrintPage.Kind.KIZ ? page.kizCode() : null);
        order.setStickerTail(String.valueOf(Math.max(1, page.pairNumber())));
        order.setImageUrl(product.imageUrl());
        order.setRequiresKiz(product.requiresKiz());
        return order;
    }
}
