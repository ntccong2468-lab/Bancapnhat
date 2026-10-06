package com.tuandev.fbsbarcode.features.print;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.features.fbo.FboPrintTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintTemplateServiceTest {
    private final PrintTemplateService service = new PrintTemplateService();

    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("wcode.appdata.dir");
    }

    @Test
    void shouldCreateDefaultTemplateShape() {
        PrintTemplate template = service.createSystemDefaultTemplate("Test");

        assertEquals("Test", template.getName());
        assertEquals(PrintTemplateService.PAGE_WIDTH, template.getPageWidth());
        assertEquals(PrintTemplateService.PAGE_HEIGHT, template.getPageHeight());
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.KIZ_DATAMATRIX));
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.BARCODE_CODE128));
        assertTrue(template.getElements().stream().anyMatch(item -> item.getType() == PrintElementType.STICKER_TAIL));
        assertEquals("", template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.SUBJECT_NAME).findFirst().orElseThrow().getPrefix());
        assertEquals(PrintTemplateService.PRINT_PREFIX_COLOR, template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.COLOR).findFirst().orElseThrow().getPrefix());
        assertEquals(PrintTemplateService.PRINT_PREFIX_ARTICLE, template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.ARTICLE).findFirst().orElseThrow().getPrefix());
        assertEquals(PrintTemplateService.PRINT_PREFIX_SIZE, template.getElements().stream().filter(item -> item.getFieldKey() == PrintFieldKey.SIZE).findFirst().orElseThrow().getPrefix());
    }

    @Test
    void shouldKeepFbsAndFboDefaultContentLayoutAligned() {
        PrintTemplate fbs = service.createSystemDefaultTemplate("FBS");
        PrintTemplate fbo = new FboPrintTemplateService().createSystemDefaultTemplate("FBO");

        assertSameLayout(field(fbs, PrintFieldKey.BRAND), field(fbo, PrintFieldKey.BRAND));
        assertSameLayout(field(fbs, PrintFieldKey.SUBJECT_NAME), field(fbo, PrintFieldKey.SUBJECT_NAME));
        assertSameLayout(field(fbs, PrintFieldKey.ARTICLE), field(fbo, PrintFieldKey.ARTICLE));
        assertSameLayout(field(fbs, PrintFieldKey.COLOR), field(fbo, PrintFieldKey.COLOR));
        assertSameLayout(field(fbs, PrintFieldKey.SIZE), field(fbo, PrintFieldKey.SIZE));
        assertSameLayout(type(fbs, PrintElementType.SEPARATOR_LINE), type(fbo, PrintElementType.SEPARATOR_LINE));
        assertSameLayout(type(fbs, PrintElementType.BARCODE_CODE128), type(fbo, PrintElementType.BARCODE_CODE128));
        assertSameLayout(type(fbs, PrintElementType.STICKER_TAIL), type(fbo, PrintElementType.STICKER_TAIL));

        PrintTemplateElement brand = field(fbs, PrintFieldKey.BRAND);
        assertEquals(9f, brand.getFontSize());
        assertTrue(brand.isBold());
        assertEquals(PrintTextAlign.CENTER, brand.getAlign());
        assertTrue(field(fbs, PrintFieldKey.SUBJECT_NAME).isBold());
        assertTrue(field(fbs, PrintFieldKey.ARTICLE).isBold());
        assertTrue(field(fbs, PrintFieldKey.COLOR).isBold());
        assertTrue(field(fbs, PrintFieldKey.SIZE).isBold());
        assertEquals(PrintTemplateService.mm(12.5), field(fbs, PrintFieldKey.ARTICLE).getY(), 0.25d);
        assertEquals(PrintTemplateService.mm(18.5), field(fbs, PrintFieldKey.COLOR).getY(), 0.25d);
        assertEquals(PrintTemplateService.mm(24.5) - 1, field(fbs, PrintFieldKey.SIZE).getY(), 0.25d);
        PrintTemplateElement stickerTail = type(fbs, PrintElementType.STICKER_TAIL);
        assertEquals(133, stickerTail.getX(), 0.01d);
        assertEquals(24, stickerTail.getWidth(), 0.01d);
        assertEquals(PrintTextAlign.LEFT, stickerTail.getAlign());
    }

    @Test
    void shouldRoundTripTemplateJson() {
        PrintTemplate source = service.createSystemDefaultTemplate("Roundtrip");
        String json = service.toJson(source);

        PrintTemplate restored = service.fromJson(json);

        assertEquals(source.getElements().size(), restored.getElements().size());
        assertEquals(PrintTemplateService.PAGE_WIDTH, restored.getPageWidth());
        assertEquals(PrintTemplateService.PAGE_HEIGHT, restored.getPageHeight());
        assertNotNull(restored.getElements().getFirst().getLabel());
    }

    @Test
    void shouldCreatePaletteElementWithExpectedDefaults() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.type() == PrintElementType.BARCODE_CODE128)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 3);

        assertEquals(PrintElementType.BARCODE_CODE128, element.getType());
        assertEquals(3, element.getZIndex());
        assertTrue(element.getWidth() > 0);
        assertTrue(element.getHeight() > 0);
    }

    @Test
    void shouldCreateTextPaletteElementWithDefaultPrefix() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.fieldKey() == PrintFieldKey.RU_SIZE)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 4);

        assertEquals(PrintTemplateService.PRINT_PREFIX_RU_SIZE, element.getPrefix());
    }

    @Test
    void shouldExposeRuSizeInPaletteWithoutAddingItToDefaultTemplate() {
        assertTrue(service.getPaletteItems().stream().anyMatch(item -> item.fieldKey() == PrintFieldKey.RU_SIZE));
        assertFalse(hasField(service.createSystemDefaultTemplate("Default"), PrintFieldKey.RU_SIZE));
    }

    @Test
    void shouldCreateStaticTextPaletteElementWithEditableContent() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.type() == PrintElementType.STATIC_TEXT)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 5);

        assertEquals(PrintElementType.STATIC_TEXT, element.getType());
        assertEquals(com.tuandev.fbsbarcode.shared.I18nService.getInstance().tr("template.static_text_default"), element.getContent());
        assertEquals(5, element.getZIndex());
    }

    @Test
    void shouldCreateStickerTailPaletteElementWithDefaultLayout() {
        PrintTemplateService.ElementPaletteItem paletteItem = service.getPaletteItems().stream()
                .filter(item -> item.type() == PrintElementType.STICKER_TAIL)
                .findFirst()
                .orElseThrow();

        PrintTemplateElement element = service.createElementFromPalette(paletteItem, 6);

        assertEquals(133, element.getX(), 0.01d);
        assertEquals(24, element.getWidth(), 0.01d);
        assertEquals(PrintTextAlign.LEFT, element.getAlign());
        assertEquals(6, element.getZIndex());
    }

    @Test
    void shouldKeepEditedDefaultFbsTemplateAfterReload() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        PrintTemplateService templateService = new PrintTemplateService();
        templateService.ensureDefaultTemplateExists();
        PrintTemplate template = templateService.getDefaultTemplate();
        replaceSubjectWithName(template, templateService);

        templateService.saveTemplate(template);
        PrintTemplate reloaded = templateService.getDefaultTemplate();

        assertTrue(hasField(reloaded, PrintFieldKey.NAME));
        assertFalse(hasField(reloaded, PrintFieldKey.SUBJECT_NAME));
    }

    @Test
    void shouldUpgradeOldFbsStickerTailDefaultAfterReload() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        PrintTemplateService templateService = new PrintTemplateService();
        templateService.ensureDefaultTemplateExists();
        PrintTemplate template = templateService.getDefaultTemplate();
        setOldStickerTailLayout(template);
        templateService.saveTemplate(template);

        PrintTemplate reloaded = new PrintTemplateService().getDefaultTemplate();

        PrintTemplateElement stickerTail = type(reloaded, PrintElementType.STICKER_TAIL);
        assertEquals(133, stickerTail.getX(), 0.01d);
        assertEquals(24, stickerTail.getWidth(), 0.01d);
        assertEquals(PrintTextAlign.LEFT, stickerTail.getAlign());
    }

    @Test
    void shouldKeepEditedDefaultFboTemplateAfterReload() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboPrintTemplateService templateService = new FboPrintTemplateService();
        templateService.ensureDefaultTemplateExists();
        PrintTemplate template = templateService.getDefaultTemplate();
        replaceSubjectWithName(template, templateService);

        templateService.saveTemplate(template);
        PrintTemplate reloaded = templateService.getDefaultTemplate();

        assertTrue(hasField(reloaded, PrintFieldKey.NAME));
        assertFalse(hasField(reloaded, PrintFieldKey.SUBJECT_NAME));
    }

    @Test
    void shouldUpgradeOldFboStickerTailDefaultAfterReload() {
        System.setProperty("wcode.appdata.dir", tempDir.toString());
        Database.initDatabase();
        FboPrintTemplateService templateService = new FboPrintTemplateService();
        templateService.ensureDefaultTemplateExists();
        PrintTemplate template = templateService.getDefaultTemplate();
        setOldStickerTailLayout(template);
        templateService.saveTemplate(template);

        PrintTemplate reloaded = new FboPrintTemplateService().getDefaultTemplate();

        PrintTemplateElement stickerTail = type(reloaded, PrintElementType.STICKER_TAIL);
        assertEquals(133, stickerTail.getX(), 0.01d);
        assertEquals(24, stickerTail.getWidth(), 0.01d);
        assertEquals(PrintTextAlign.LEFT, stickerTail.getAlign());
    }

    private static PrintTemplateElement field(PrintTemplate template, PrintFieldKey key) {
        return template.getElements().stream()
                .filter(element -> element.getType() == PrintElementType.TEXT_FIELD)
                .filter(element -> element.getFieldKey() == key)
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasField(PrintTemplate template, PrintFieldKey key) {
        return template.getElements().stream()
                .anyMatch(element -> element.getType() == PrintElementType.TEXT_FIELD && element.getFieldKey() == key);
    }

    private static void replaceSubjectWithName(PrintTemplate template, PrintTemplateService templateService) {
        template.getElements().removeIf(element -> element.getType() == PrintElementType.TEXT_FIELD
                && element.getFieldKey() == PrintFieldKey.SUBJECT_NAME);
        PrintTemplateService.ElementPaletteItem nameItem = templateService.getPaletteItems().stream()
                .filter(item -> item.fieldKey() == PrintFieldKey.NAME)
                .findFirst()
                .orElseThrow();
        PrintTemplateElement name = templateService.createElementFromPalette(nameItem, template.getElements().size() + 1);
        name.setX(PrintTemplateService.mm(22));
        name.setY(PrintTemplateService.mm(7.2));
        template.getElements().add(name);
    }

    private static void setOldStickerTailLayout(PrintTemplate template) {
        PrintTemplateElement stickerTail = type(template, PrintElementType.STICKER_TAIL);
        stickerTail.setX(PrintTemplateService.mm(49));
        stickerTail.setY(PrintTemplateService.mm(36.5));
        stickerTail.setWidth(PrintTemplateService.mm(6));
        stickerTail.setAlign(PrintTextAlign.RIGHT);
    }

    private static PrintTemplateElement type(PrintTemplate template, PrintElementType type) {
        return template.getElements().stream()
                .filter(element -> element.getType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static void assertSameLayout(PrintTemplateElement first, PrintTemplateElement second) {
        assertEquals(first.getX(), second.getX(), 0.01d);
        assertEquals(first.getY(), second.getY(), 0.01d);
        assertEquals(first.getWidth(), second.getWidth(), 0.01d);
        assertEquals(first.getHeight(), second.getHeight(), 0.01d);
        assertEquals(first.getFontSize(), second.getFontSize(), 0.01d);
        assertEquals(first.isBold(), second.isBold());
        assertEquals(first.getAlign(), second.getAlign());
    }
}
