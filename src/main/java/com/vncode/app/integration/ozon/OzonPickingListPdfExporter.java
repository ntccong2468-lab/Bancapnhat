package com.vncode.app.integration.ozon;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.vncode.app.features.fbo.FboProductImageService;
import com.vncode.app.features.print.GenerateBarcode;
import com.vncode.app.features.ozon.OzonProductVariant;
import com.vncode.app.features.supply.OrderSortPreferenceService;
import com.vncode.app.shared.NaturalOrderComparator;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.I18nService;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Creates a separate A4 warehouse picking list without buyer PII or raw KIZ values. */
final class OzonPickingListPdfExporter {
    private static final int IMAGE_READ_AHEAD = 16;
    private static final int MAX_IMAGE_OBJECTS = 128;
    private record PickingRow(String postingNumber, OzonPostingTrackingStore.Tracking tracking, OzonPostingItemDto item) {}
    private final OzonCatalogRepository catalog = new OzonCatalogRepository();
    private final FboProductImageService images;

    OzonPickingListPdfExporter() { this(new FboProductImageService()); }
    OzonPickingListPdfExporter(FboProductImageService images) {
        this.images = java.util.Objects.requireNonNull(images);
    }

    void export(File target, Shop shop, OzonPostingDto posting) throws IOException {
        export(target, shop, List.of(posting));
    }

    void exportBatch(File target, Shop shop, List<OzonPostingDto> postings) throws IOException {
        export(target, shop, postings);
    }

    private void export(File target, Shop shop, List<OzonPostingDto> postings) throws IOException {
        exportBatch(target,shop,postings,null);
    }

    /** Current PDF observations take precedence over tracking from a different API response. */
    void exportBatch(File target,Shop shop,List<OzonPostingDto> postings,Map<String,String> carrierNumbers) throws IOException {
        List<OzonPostingDto> safePostings = postings == null ? List.of() : postings.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        final Map<String, OzonPostingTrackingStore.Tracking> tracking;
        try { tracking = OzonPostingTrackingStore.forExport(shop, safePostings); }
        catch (java.sql.SQLException error) { throw new IOException("OZON_TRACKING_READ_FAILED", error); }
        try (PdfWriter writer = new PdfWriter(target);
                PdfDocument pdf = new PdfDocument(writer);
                Document document = new Document(pdf, PageSize.A4)) {
            document.setMargins(24, 24, 24, 24);
            document.setFont(GenerateBarcode.getArialFont());
            var products = catalog.findBySkus(shop.getId(),safePostings.stream().flatMap(p->p.items().stream()).map(OzonPostingItemDto::sku).distinct().toList());
            float[] widths = carrierNumbers==null?new float[]{30,115,60,200,100,35}:new float[]{25,95,48,150,85,28,116};
            Table table = new Table(widths, true);
            table.setWidth(UnitValue.createPercentValue(100));
            header(table, tr("ozon.picking.column.index"));
            header(table, tr("ozon.picking.column.posting"));
            header(table, tr("ozon.dashboard.col.image"));
            header(table, tr("ozon.picking.column.name"));
            header(table, tr("ozon.dashboard.item.article"));
            header(table, tr("fbo.column.quantity"));
            if(carrierNumbers!=null)header(table,tr("ozon.picking.column.carrier"));
            // Large-table mode flushes completed rows/pages instead of retaining every renderer.
            document.add(table);
            // Images are independent reads. Schedule a bounded window ahead so the existing
            // image coordinator can use its two network workers while rows remain ordered.
            List<PickingRow> rows = safePostings.stream()
                    .flatMap(posting -> posting.items().stream()
                            .map(item -> new PickingRow(posting.postingNumber(), tracking.get(posting.postingNumber()), item)))
                    .sorted(Comparator.comparing((PickingRow row) -> OzonProductVariant.from(
                                    row.item(), products.get(row.item().sku())),
                                    OzonProductVariant.comparator(new OrderSortPreferenceService().load()))
                            .thenComparing(PickingRow::postingNumber, NaturalOrderComparator::compareIgnoreCase))
                    .toList();
            Map<String,PdfImageXObject> imageObjects = new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String,PdfImageXObject> eldest) {
                    return size() > MAX_IMAGE_OBJECTS;
                }
            };
            int rowNumber = 0;
            for (int offset = 0; offset < rows.size(); offset += IMAGE_READ_AHEAD) {
                List<PickingRow> window = rows.subList(offset, Math.min(offset + IMAGE_READ_AHEAD, rows.size()));
                Map<String,CompletableFuture<byte[]>> prefetched = prefetchImages(window, products, imageObjects);
                for (PickingRow row : window) {
                    OzonPostingItemDto item = row.item();
                    OzonProductDto product = products.get(item.sku());
                    table.addCell(cell(String.valueOf(++rowNumber), TextAlignment.CENTER));
                    table.addCell(carrierNumbers==null?postingCell(row.postingNumber(),row.tracking()):cell(row.postingNumber(),TextAlignment.LEFT));
                    table.addCell(imageCell(imageObject(product, prefetched, imageObjects)));
                    table.addCell(cell(first(item.name(), product == null ? "" : product.name()), TextAlignment.LEFT));
                    table.addCell(cell(first(
                            product == null ? "" : product.article(), item.offerId()), TextAlignment.LEFT));
                    table.addCell(cell(String.valueOf(item.quantity()), TextAlignment.CENTER, true));
                    if(carrierNumbers!=null)table.addCell(carrierCell(carrierNumbers.get(row.postingNumber())));
                    if (rowNumber % 32 == 0) table.flush();
                }
            }
            table.complete();
        }
    }

    private Map<String,CompletableFuture<byte[]>> prefetchImages(List<PickingRow> rows,
            Map<String,OzonProductDto> products, Map<String,PdfImageXObject> imageObjects) {
        Map<String,CompletableFuture<byte[]>> reads = new LinkedHashMap<>();
        // The shared coordinator prioritises the most recently scheduled interactive read.
        // Submit in reverse order so the first row is ready first.
        for (int i = rows.size() - 1; i >= 0; i--) {
            OzonProductDto product = products.get(rows.get(i).item().sku());
            String url = product == null ? "" : product.primaryImageUrl();
            if (url != null && !url.isBlank() && !imageObjects.containsKey(url) && !reads.containsKey(url)) {
                try {
                    // The picking list renders this image at at most 38 points. A 96×128
                    // thumbnail is sufficient there and avoids embedding full-resolution
                    // marketplace photos in a thousand-row document.
                    reads.put(url, images.loadThumbnailBytes(url));
                } catch (RuntimeException unavailable) {
                    reads.put(url, CompletableFuture.completedFuture(null));
                }
            }
        }
        return reads;
    }

    private static PdfImageXObject imageObject(OzonProductDto product,
            Map<String,CompletableFuture<byte[]>> reads, Map<String,PdfImageXObject> imageObjects) {
        String imageUrl = product == null ? "" : product.primaryImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) return null;
        PdfImageXObject existing = imageObjects.get(imageUrl);
        if (existing != null) return existing;
        try {
            CompletableFuture<byte[]> read = reads.get(imageUrl);
            byte[] bytes = read == null ? null : read.join();
            if (bytes == null || bytes.length == 0) return null;
            PdfImageXObject image = new PdfImageXObject(ImageDataFactory.create(bytes));
            imageObjects.put(imageUrl, image);
            return image;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Cell imageCell(PdfImageXObject imageObject) {
        Cell cell = new Cell().setHeight(46)
                .setKeepTogether(true)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(4);
        if (imageObject == null) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
        try {
            Image image = new Image(imageObject);
            image.scaleToFit(38, 38).setHorizontalAlignment(HorizontalAlignment.CENTER);
            return cell.add(image);
        } catch (RuntimeException exception) {
            return cell.add(new Paragraph("-").setFontSize(9));
        }
    }

    private static void header(Table table, String value) {
        table.addHeaderCell(new Cell().add(new Paragraph(value).setBold().setFontSize(9))
                .setKeepTogether(true)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setBackgroundColor(ColorConstants.LIGHT_GRAY));
    }

    private static Cell cell(String value, TextAlignment alignment) {
        return cell(value, alignment, false);
    }

    private static Cell postingCell(String postingNumber, OzonPostingTrackingStore.Tracking tracking) {
        Paragraph paragraph = new Paragraph().setFontSize(9).add(new Text(safe(postingNumber)));
        if (tracking != null) {
            String label = tr(tracking.verified() ? "ozon.picking.tracking" : "ozon.picking.tracking.stale");
            paragraph.add(new Text("\n" + label + ": " + safe(tracking.number())).setFontSize(8));
        } else paragraph.add(new Text("\n" + tr("ozon.picking.tracking.missing")).setFontSize(8));
        return new Cell().add(paragraph).setKeepTogether(true)
                .setTextAlignment(TextAlignment.LEFT).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static Cell carrierCell(String number) {
        Paragraph paragraph=new Paragraph().setFontSize(11);
        if(number==null||number.isBlank())paragraph.add(new Text(tr("ozon.picking.carrier.unreadable")).setFontSize(8));
        else {
            int split=number.length()-4;
            if(split>=0&&number.substring(split).matches("[0-9]{4}")){
                paragraph.add(new Text(number.substring(0,split)));
                paragraph.add(new Text(number.substring(split)).setBold().setFontColor(ColorConstants.WHITE)
                        .setBackgroundColor(ColorConstants.BLACK,2,1,2,1));
            }else paragraph.add(new Text(number).setBold());
        }
        return new Cell().add(paragraph).setKeepTogether(true).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static Cell cell(String value, TextAlignment alignment, boolean bold) {
        Paragraph paragraph = new Paragraph(safe(value)).setFontSize(bold ? 11 : 9);
        if (bold) paragraph.setBold();
        return new Cell().add(paragraph)
                .setKeepTogether(true)
                .setTextAlignment(alignment).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").strip();
    }

    private static String first(String preferred, String fallback) {
        String safe = safe(preferred);
        return safe.isBlank() ? safe(fallback) : safe;
    }

    private static String tr(String key) {
        return I18nService.getInstance().tr(key);
    }
}
