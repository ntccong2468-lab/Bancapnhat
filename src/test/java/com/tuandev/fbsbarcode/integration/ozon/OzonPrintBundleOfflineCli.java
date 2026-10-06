package com.tuandev.fbsbarcode.integration.ozon;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import com.tuandev.fbsbarcode.models.Shop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Offline visual-acceptance helper. It never calls Ozon or prints raw KIZ/credentials. */
public final class OzonPrintBundleOfflineCli {
    private OzonPrintBundleOfflineCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <app-data-dir> <official-pdf> <label-output-pdf> <picking-output-pdf>");
        }
        Path dataDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path official = requirePdf(Path.of(args[1]));
        Path labelOutput = outputPdf(Path.of(args[2]));
        Path pickingOutput = outputPdf(Path.of(args[3]));
        System.setProperty("wcode.appdata.dir", dataDirectory.toString());
        Database.initDatabase();
        Selection selection = acceptedSelection();
        Shop shop = new Shop(selection.shopId(), selection.shopName(), Marketplace.OZON, "offline", "offline");
        OzonPrintBundleService service = new OzonPrintBundleService(
                new OzonPostingRepository(),
                new OzonExemplarJobRepository(),
                (selectedShop, postingNumber) -> {
                    throw new IllegalStateException("Offline print refuses to prepare or mutate KIZ.");
                },
                (selectedShop, postingNumber, target) -> {
                    Files.copy(official, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return target;
                });
        OzonPrintBundleService.ExportResult result = service.export(
                shop, selection.postingNumber(), labelOutput.toFile(), pickingOutput.toFile());
        OzonExemplarJob job = new OzonExemplarJobRepository().find(selection.shopId(), selection.postingNumber());
        List<OzonExemplarJobRepository.KizBinding> bindings = new OzonExemplarJobRepository().bindings(job.id());
        boolean matrixMatches = dataMatricesMatch(labelOutput.toFile(), result.officialPages(), bindings);
        System.out.println("offline_bundle=ok");
        System.out.println("official_pages=" + result.officialPages());
        System.out.println("kiz_pages=" + result.kizPages());
        System.out.println("total_pages=" + result.totalPages());
        System.out.println("data_matrix_match=" + matrixMatches);
        System.out.println("label_output=" + labelOutput);
        System.out.println("picking_output=" + pickingOutput);
    }

    private static Selection acceptedSelection() throws Exception {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.id,s.name,j.posting_number
                        FROM ozon_exemplar_jobs j JOIN shops s ON s.id=j.shop_id
                        WHERE j.stage='ACCEPTED'
                        ORDER BY j.updated_at DESC,j.id DESC LIMIT 1
                        """);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("No accepted Ozon exemplar job is available.");
            return new Selection(result.getInt(1), result.getString(2), result.getString(3));
        }
    }

    private static Path requirePdf(Path value) {
        Path path = value.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Official PDF is missing or invalid.");
        }
        return path;
    }

    private static Path outputPdf(Path value) throws Exception {
        Path path = value.toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Output must be a PDF file.");
        }
        Files.createDirectories(path.getParent());
        return path;
    }

    private static boolean dataMatricesMatch(
            File bundle,
            int officialPages,
            List<OzonExemplarJobRepository.KizBinding> bindings) throws Exception {
        try (PDDocument document = Loader.loadPDF(bundle)) {
            for (int index = 0; index < bindings.size(); index++) {
                int pageIndex = index < officialPages
                        ? index * 2 + 1
                        : officialPages * 2 + index - officialPages;
                String decoded = KizService.scannerSafeCode(decodeDataMatrix(document, pageIndex));
                String expected = KizService.scannerSafeCode(bindings.get(index).rawCode());
                if (!expected.equals(decoded)) return false;
            }
            return true;
        }
    }

    private static String decodeDataMatrix(PDDocument document, int pageIndex) throws Exception {
        float dpi = 300;
        BufferedImage page = new PDFRenderer(document).renderImageWithDPI(pageIndex, dpi, ImageType.GRAY);
        float scale = dpi / 72f;
        int margin = Math.round(3 * scale);
        int x = Math.max(0, Math.round(7 * scale) - margin);
        int y = Math.max(0, Math.round(
                (document.getPage(pageIndex).getMediaBox().getHeight() - 25 - 62) * scale) - margin);
        int side = Math.min(
                Math.round(62 * scale) + margin * 2,
                Math.min(page.getWidth() - x, page.getHeight() - y));
        BufferedImage barcode = page.getSubimage(x, y, side, side);
        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(barcode)));
        return new DataMatrixReader().decode(
                bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    private record Selection(int shopId, String shopName, String postingNumber) {
    }
}
