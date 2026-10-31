package com.vncode.app.features.print;

import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.wb.WbStickerService;
import com.vncode.app.models.Shop;
import com.vncode.app.models.Sticker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Exports the WB-provided shipping sticker only; it never buys or assigns KIZ. */
public final class WbReshipmentLabelService {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000L;
    private static final float WIDTH_POINTS = 58f * 72f / 25.4f;
    private static final float HEIGHT_POINTS = 40f * 72f / 25.4f;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    @FunctionalInterface
    interface StickerReader {
        List<Sticker> read(String apiKey, List<Long> orderIds) throws IOException;
    }

    private final StickerReader reader;
    private final java.util.function.Consumer<Shop> ownerCheck;

    public WbReshipmentLabelService() {
        this(new WbStickerService()::getStickers, ListingIdentity::require);
    }

    WbReshipmentLabelService(StickerReader reader, java.util.function.Consumer<Shop> ownerCheck) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.ownerCheck = Objects.requireNonNull(ownerCheck, "ownerCheck");
    }

    /** Request exactly one label and verify its order id before writing the PDF. */
    public void export(Shop shop, long orderId, Path output) throws IOException {
        MarketplaceGuard.requireWildberries(shop);
        if (orderId <= 0 || output == null) {
            throw new IllegalArgumentException("WB_LABEL_INVALID_ORDER_OR_PATH");
        }
        Shop scope = new Shop(shop.getId(), shop.getName(), shop.getMarketplace(), shop.getClientId(), shop.getApiKey());
        ownerCheck.accept(scope);
        List<Sticker> stickers = reader.read(scope.getApiKey(), List.of(orderId));
        ownerCheck.accept(scope);
        if (stickers == null || stickers.size() != 1 || stickers.getFirst() == null
                || !Objects.equals(stickers.getFirst().getOrderId(), orderId)) {
            throw new IOException("WB_LABEL_ORDER_MISMATCH_OR_UNAVAILABLE");
        }
        byte[] image = decodePng(stickers.getFirst().getFile());
        Path target = output.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("WB_LABEL_OUTPUT_DIRECTORY_UNAVAILABLE");
        }
        Path temporary = Files.createTempFile(parent, ".vncode-wb-label-", ".pdf.tmp");
        try {
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(WIDTH_POINTS, HEIGHT_POINTS));
                document.addPage(page);
                PDImageXObject picture = PDImageXObject.createFromByteArray(document, image, "WB-label");
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float scale = Math.min(WIDTH_POINTS / picture.getWidth(), HEIGHT_POINTS / picture.getHeight());
                    float width = picture.getWidth() * scale;
                    float height = picture.getHeight() * scale;
                    content.drawImage(picture, (WIDTH_POINTS - width) / 2, (HEIGHT_POINTS - height) / 2, width, height);
                }
                document.save(temporary.toFile());
            }
            ownerCheck.accept(scope);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] decodePng(String file) throws IOException {
        if (file == null || file.isBlank() || file.length() > (MAX_IMAGE_BYTES * 4L / 3L + 8)) {
            throw new IOException("WB_LABEL_IMAGE_UNAVAILABLE");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(file);
        } catch (IllegalArgumentException ex) {
            throw new IOException("WB_LABEL_INVALID_IMAGE", ex);
        }
        if (bytes.length < PNG_MAGIC.length || bytes.length > MAX_IMAGE_BYTES) {
            throw new IOException("WB_LABEL_INVALID_IMAGE");
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) throw new IOException("WB_LABEL_INVALID_IMAGE");
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) throw new IOException("WB_LABEL_INVALID_IMAGE");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("WB_LABEL_INVALID_IMAGE");
            ImageReader imageReader = readers.next();
            try {
                imageReader.setInput(stream, true, true);
                int width = imageReader.getWidth(0);
                int height = imageReader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("WB_LABEL_INVALID_IMAGE");
                }
            } finally {
                imageReader.dispose();
            }
        } catch (RuntimeException ex) {
            throw new IOException("WB_LABEL_INVALID_IMAGE", ex);
        }
        return bytes;
    }
}
