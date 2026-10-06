package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinInventoryService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class ZnackKizExportService {
    private final ZnackGtinInventoryService inventory;
    private final KizMappingRepository mappings;
    private final ZnackKizLabelPdfExporter exporter;

    public ZnackKizExportService() {
        this(new ZnackGtinInventoryService(), new KizMappingRepository(), new ZnackKizLabelPdfExporter());
    }

    ZnackKizExportService(ZnackGtinInventoryService inventory, KizMappingRepository mappings,
                          ZnackKizLabelPdfExporter exporter) {
        this.inventory = inventory;
        this.mappings = mappings;
        this.exporter = exporter;
    }

    public ExportResult export(int shopId, String gtin, int quantity, File target) throws IOException {
        if (shopId <= 0 || quantity <= 0 || target == null) {
            throw new IllegalArgumentException("Shop, quantity and target file are required.");
        }
        String normalized = GtinNormalizer.normalize(gtin);
        List<Kiz> reserved = List.of();
        File staging = null;
        boolean consumed = false;
        try {
            reserved = inventory.reserveAvailable(shopId, normalized, quantity);
            ZnackKizLabelMetadata metadata = mappings.findLabelMetadata(shopId, normalized);
            staging = AtomicFilePublisher.stagingFile(target, ".pdf");
            exporter.write(reserved, metadata, staging);
            inventory.consume(shopId, reserved);
            consumed = true;
            AtomicFilePublisher.publish(staging, target);
            return new ExportResult(target, reserved.size());
        } catch (IOException | RuntimeException error) {
            if (!consumed) inventory.release(shopId, reserved);
            throw error;
        } finally {
            AtomicFilePublisher.deleteQuietly(staging);
        }
    }

    public record ExportResult(File file, int pageCount) {
    }
}
