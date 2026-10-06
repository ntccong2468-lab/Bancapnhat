package com.tuandev.fbsbarcode.features.print;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tuandev.fbsbarcode.shared.I18nService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PrintTemplateService {
    public static final double POINTS_PER_MM = 72d / 25.4d;
    public static final double PAGE_WIDTH_MM = 58d;
    public static final double PAGE_HEIGHT_MM = 40d;
    public static final double PAGE_WIDTH = PAGE_WIDTH_MM * POINTS_PER_MM;
    public static final double PAGE_HEIGHT = PAGE_HEIGHT_MM * POINTS_PER_MM;
    public static final String PRINT_PREFIX_ARTICLE = "Арт";
    public static final String PRINT_PREFIX_COLOR = "Цвет";
    public static final String PRINT_PREFIX_SIZE = "Раз";
    public static final String PRINT_PREFIX_RU_SIZE = "RU";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    protected final PrintTemplateRepository repository;
    protected final I18nService i18n = I18nService.getInstance();

    public PrintTemplateService() {
        this(new PrintTemplateRepository());
    }

    protected PrintTemplateService(PrintTemplateRepository repository) {
        this.repository = repository;
    }

    public void ensureDefaultTemplateExists() {
        repository.normalizePageSize(PAGE_WIDTH, PAGE_HEIGHT);
        if (repository.count() == 0) {
            PrintTemplate template = createSystemDefaultTemplate(i18n.tr("template.default_name"));
            saveTemplate(template);
            repository.setDefault(template.getId());
            return;
        }
        repository.findDefault()
                .map(this::fromRecord)
                .ifPresent(template -> {
                    if (upgradeLegacySystemDefaultTemplate(template)) {
                        saveTemplate(template);
                    }
                });
    }

    private static double snapToMillimeterGrid(double value) {
        return Math.round(value / POINTS_PER_MM) * POINTS_PER_MM;
    }

    protected static double mm(double value) {
        return value * POINTS_PER_MM;
    }

    private boolean upgradeLegacySystemDefaultTemplate(PrintTemplate template) {
        if (template == null || !template.isDefaultTemplate() || template.getElements() == null || template.getElements().isEmpty()) {
            return false;
        }
        PrintTemplateElement brand = findField(template, PrintFieldKey.BRAND);
        PrintTemplateElement name = findField(template, PrintFieldKey.NAME);
        PrintTemplateElement color = findField(template, PrintFieldKey.COLOR);
        PrintTemplateElement article = findField(template, PrintFieldKey.ARTICLE);
        PrintTemplateElement size = findField(template, PrintFieldKey.SIZE);
        PrintTemplateElement barcodeText = findField(template, PrintFieldKey.BARCODE);
        PrintTemplateElement separator = findElementByType(template, PrintElementType.SEPARATOR_LINE);
        PrintTemplateElement barcode = findElementByType(template, PrintElementType.BARCODE_CODE128);
        PrintTemplateElement stickerTail = findElementByType(template, PrintElementType.STICKER_TAIL);
        if (brand == null || color == null || article == null || size == null
                || barcodeText == null || separator == null || barcode == null || stickerTail == null) {
            return false;
        }
        boolean legacyLayout = name != null
                && approximately(brand.getX(), 70d)
                && approximately(brand.getY(), 8d)
                && approximately(brand.getWidth(), 84d)
                && approximately(name.getX(), 70d)
                && approximately(name.getY(), 20d)
                && approximately(name.getWidth(), 84d)
                && approximately(name.getHeight(), 14d)
                && approximately(color.getX(), 70d)
                && approximately(color.getY(), 35d)
                && approximately(article.getX(), 70d)
                && approximately(article.getY(), 47d)
                && approximately(size.getX(), 70d)
                && approximately(size.getY(), 59d)
                && approximately(separator.getX(), 10d)
                && approximately(separator.getY(), 67d)
                && approximately(separator.getWidth(), 144d)
                && approximately(barcode.getX(), 12d)
                && approximately(barcode.getY(), 72d)
                && approximately(barcodeText.getY(), 99d)
                && approximately(stickerTail.getX(), 134d)
                && approximately(stickerTail.getY(), 99d);
        boolean snappedSystemDefaultV1 = name != null
                && approximately(brand.getX(), mm(25))
                && approximately(brand.getY(), mm(3))
                && approximately(brand.getWidth(), mm(30))
                && approximately(brand.getHeight(), 10d)
                && approximately(name.getX(), mm(25))
                && approximately(name.getY(), mm(7))
                && approximately(name.getHeight(), 12d)
                && approximately(color.getX(), mm(25))
                && approximately(color.getY(), mm(12))
                && approximately(color.getHeight(), 12d)
                && approximately(article.getX(), mm(25))
                && approximately(article.getY(), mm(17))
                && approximately(article.getHeight(), 12d)
                && approximately(size.getX(), mm(25))
                && approximately(size.getY(), mm(21))
                && approximately(size.getHeight(), 12d)
                && approximately(separator.getX(), mm(4))
                && approximately(separator.getY(), mm(25))
                && approximately(barcode.getX(), mm(4))
                && approximately(barcode.getY(), mm(27))
                && approximately(barcode.getHeight(), 25d)
                && approximately(barcodeText.getY(), mm(36))
                && approximately(stickerTail.getX(), mm(47))
                && approximately(stickerTail.getY(), mm(36));
        boolean sharedFboFbsTailLabel = approximately(stickerTail.getX(), mm(49))
                && approximately(stickerTail.getY(), mm(36.5))
                && !stickerTailLabel().equals(stickerTail.getLabel());
        boolean oldStickerTailDefault = approximately(stickerTail.getX(), mm(49))
                && approximately(stickerTail.getY(), mm(36.5))
                && approximately(stickerTail.getWidth(), mm(6))
                && stickerTail.getAlign() == PrintTextAlign.RIGHT;
        if (oldStickerTailDefault) {
            stickerTail.setX(133);
            stickerTail.setWidth(24);
            stickerTail.setAlign(PrintTextAlign.LEFT);
            return true;
        }
        if (!legacyLayout && !snappedSystemDefaultV1 && !sharedFboFbsTailLabel) {
            return false;
        }
        PrintTemplate systemDefault = createSystemDefaultTemplate(template.getName());
        template.setElements(systemDefault.getElements());
        return true;
    }

    private static PrintTemplateElement findField(PrintTemplate template, PrintFieldKey key) {
        return template.getElements().stream()
                .filter(element -> element.getType() == PrintElementType.TEXT_FIELD)
                .filter(element -> element.getFieldKey() == key)
                .findFirst()
                .orElse(null);
    }

    private static PrintTemplateElement findElementByType(PrintTemplate template, PrintElementType type) {
        return template.getElements().stream()
                .filter(element -> element.getType() == type)
                .findFirst()
                .orElse(null);
    }

    private static boolean approximately(double actual, double expected) {
        return Math.abs(actual - expected) < 0.25d;
    }

    public List<PrintTemplate> loadTemplates() {
        ensureDefaultTemplateExists();
        List<PrintTemplate> result = new ArrayList<>();
        for (PrintTemplateRepository.TemplateRecord record : repository.findAll()) {
            result.add(fromRecord(record));
        }
        return result;
    }

    public PrintTemplate getDefaultTemplate() {
        ensureDefaultTemplateExists();
        return repository.findDefault()
                .map(this::fromRecord)
                .orElseGet(() -> loadTemplates().getFirst());
    }

    public Optional<PrintTemplate> findById(int id) {
        ensureDefaultTemplateExists();
        return repository.findById(id).map(this::fromRecord);
    }

    public PrintTemplate createTemplate(String name) {
        validateName(name, null);
        PrintTemplate template = createSystemDefaultTemplate(name);
        saveTemplate(template);
        return template;
    }

    public PrintTemplate duplicateTemplate(int templateId, String name) {
        validateName(name, templateId);
        PrintTemplate source = findById(templateId).orElseThrow(() -> new IllegalArgumentException(i18n.tr("template.error.not_found")));
        PrintTemplate duplicate = fromJson(toJson(source));
        duplicate.setId(null);
        duplicate.setName(name);
        duplicate.setDefaultTemplate(false);
        saveTemplate(duplicate);
        return duplicate;
    }

    public void renameTemplate(int templateId, String name) {
        validateName(name, templateId);
        repository.rename(templateId, name.trim());
    }

    public void deleteTemplate(int templateId) {
        List<PrintTemplate> templates = loadTemplates();
        if (templates.size() <= 1) {
            throw new IllegalStateException(i18n.tr("template.error.keep_one"));
        }
        boolean deletingDefault = templates.stream().anyMatch(t -> t.getId() == templateId && t.isDefaultTemplate());
        repository.delete(templateId);
        if (deletingDefault) {
            repository.findAll().stream().findFirst().ifPresent(record -> repository.setDefault(record.id()));
        }
    }

    public void setDefaultTemplate(int templateId) {
        repository.setDefault(templateId);
    }

    public void resetTemplateToSystemDefault(int templateId) {
        PrintTemplate current = findById(templateId).orElseThrow(() -> new IllegalArgumentException(i18n.tr("template.error.not_found")));
        PrintTemplate reset = createSystemDefaultTemplate(current.getName());
        reset.setId(templateId);
        reset.setDefaultTemplate(current.isDefaultTemplate());
        saveTemplate(reset);
    }

    public void saveTemplate(PrintTemplate template) {
        validateTemplate(template);
        String json = toJson(template);
        if (template.getId() == null) {
            int id = repository.insert(template.getName().trim(), PAGE_WIDTH, PAGE_HEIGHT, template.isDefaultTemplate(), json);
            template.setId(id);
        } else {
            repository.update(template.getId(), template.getName().trim(), PAGE_WIDTH, PAGE_HEIGHT, template.isDefaultTemplate(), json);
        }
    }

    public PrintTemplate createSystemDefaultTemplate(String name) {
        PrintTemplate template = new PrintTemplate();
        template.setName(name);
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        List<PrintTemplateElement> elements = new ArrayList<>();

        PrintTemplateElement kiz = PrintTemplateElement.create(
                PrintElementType.KIZ_DATAMATRIX,
                i18n.tr("template.palette.kiz"),
                mm(2.5),
                mm(5),
                50,
                50
        );
        kiz.setZIndex(1);
        elements.add(kiz);

        PrintTemplateElement brand = textField(i18n.tr("template.palette.brand"), PrintFieldKey.BRAND, mm(22), mm(2.5), mm(34), 13, 9, true, PrintTextAlign.CENTER, 2);
        elements.add(brand);
        elements.add(textField(i18n.tr("template.palette.subject"), PrintFieldKey.SUBJECT_NAME, "", mm(22), mm(7.2), mm(34), 10, 8, true, PrintTextAlign.LEFT, 3));
        elements.add(textField(i18n.tr("template.palette.article"), PrintFieldKey.ARTICLE, PRINT_PREFIX_ARTICLE, mm(22), mm(12.5), mm(34), 16, 8, true, PrintTextAlign.LEFT, 4));
        elements.add(textField(i18n.tr("template.palette.color"), PrintFieldKey.COLOR, PRINT_PREFIX_COLOR, mm(22), mm(18.5), mm(34), 16, 8, true, PrintTextAlign.LEFT, 5));
        elements.add(textField(i18n.tr("template.palette.size"), PrintFieldKey.SIZE, PRINT_PREFIX_SIZE, mm(22), mm(24.5) - 1, mm(34), 8, 8, true, PrintTextAlign.LEFT, 6));

        PrintTemplateElement separator = PrintTemplateElement.create(PrintElementType.SEPARATOR_LINE, i18n.tr("template.palette.separator"), mm(2.5), mm(27), mm(53), 1);
        separator.setZIndex(7);
        elements.add(separator);

        PrintTemplateElement barcode = PrintTemplateElement.create(PrintElementType.BARCODE_CODE128, i18n.tr("template.palette.barcode"), mm(2.5), mm(28.2), mm(53), 22);
        barcode.setShowHumanReadable(false);
        barcode.setZIndex(8);
        elements.add(barcode);

        elements.add(textField(i18n.tr("template.palette.barcode_text"), PrintFieldKey.BARCODE, null, mm(2.5), mm(37), mm(47), 10, 8, false, PrintTextAlign.CENTER, 9));

        PrintTemplateElement stickerTail = PrintTemplateElement.create(PrintElementType.STICKER_TAIL, stickerTailLabel(), 133, mm(36.5), 24, 9);
        stickerTail.setFontSize(9);
        stickerTail.setBold(true);
        stickerTail.setAlign(PrintTextAlign.LEFT);
        stickerTail.setZIndex(10);
        elements.add(stickerTail);

        template.setElements(elements);
        return template;
    }

    public List<ElementPaletteItem> getPaletteItems() {
        return List.of(
                new ElementPaletteItem(i18n.tr("template.palette.kiz"), PrintElementType.KIZ_DATAMATRIX, null),
                new ElementPaletteItem(i18n.tr("template.palette.barcode"), PrintElementType.BARCODE_CODE128, null),
                new ElementPaletteItem(i18n.tr("template.palette.brand"), PrintElementType.TEXT_FIELD, PrintFieldKey.BRAND),
                new ElementPaletteItem(i18n.tr("template.palette.name"), PrintElementType.TEXT_FIELD, PrintFieldKey.NAME),
                new ElementPaletteItem(i18n.tr("template.palette.subject"), PrintElementType.TEXT_FIELD, PrintFieldKey.SUBJECT_NAME),
                new ElementPaletteItem(i18n.tr("template.palette.color"), PrintElementType.TEXT_FIELD, PrintFieldKey.COLOR),
                new ElementPaletteItem(i18n.tr("template.palette.article"), PrintElementType.TEXT_FIELD, PrintFieldKey.ARTICLE),
                new ElementPaletteItem(i18n.tr("template.palette.size"), PrintElementType.TEXT_FIELD, PrintFieldKey.SIZE),
                new ElementPaletteItem(i18n.tr("template.palette.ru_size"), PrintElementType.TEXT_FIELD, PrintFieldKey.RU_SIZE),
                new ElementPaletteItem(i18n.tr("template.palette.static_text"), PrintElementType.STATIC_TEXT, null),
                new ElementPaletteItem(i18n.tr("template.palette.barcode_text"), PrintElementType.TEXT_FIELD, PrintFieldKey.BARCODE),
                new ElementPaletteItem(stickerTailLabel(), PrintElementType.STICKER_TAIL, null),
                new ElementPaletteItem(i18n.tr("template.palette.separator"), PrintElementType.SEPARATOR_LINE, null)
        );
    }

    public PrintTemplateElement createElementFromPalette(ElementPaletteItem item, int zIndex) {
        if (item.type() == PrintElementType.KIZ_DATAMATRIX) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), mm(4), mm(4), mm(18), mm(18));
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.BARCODE_CODE128) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), mm(4), mm(27), mm(49), 23);
            element.setShowHumanReadable(false);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.SEPARATOR_LINE) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), mm(4), mm(25), mm(51), 1);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.STICKER_TAIL) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), 133, mm(36.5), 24, 9);
            element.setFontSize(9);
            element.setBold(true);
            element.setAlign(PrintTextAlign.LEFT);
            element.setZIndex(zIndex);
            return element;
        }
        if (item.type() == PrintElementType.STATIC_TEXT) {
            PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), mm(25), mm(11), mm(30), 12);
            element.setContent(i18n.tr("template.static_text_default"));
            element.setFontSize(8);
            element.setAlign(PrintTextAlign.LEFT);
            element.setZIndex(zIndex);
            return element;
        }
        PrintTemplateElement element = PrintTemplateElement.create(item.type(), item.label(), mm(25), mm(11), mm(30), 12);
        element.setFieldKey(item.fieldKey());
        element.setPrefix(defaultPrefix(item.fieldKey()));
        element.setFontSize(8);
        element.setAlign(PrintTextAlign.LEFT);
        element.setZIndex(zIndex);
        return element;
    }

    public String toJson(PrintTemplate template) {
        PrintTemplate copy = new PrintTemplate();
        copy.setName(template.getName());
        copy.setPageWidth(PAGE_WIDTH);
        copy.setPageHeight(PAGE_HEIGHT);
        copy.setDefaultTemplate(template.isDefaultTemplate());
        copy.setElements(sortedElements(template.getElements()));
        return GSON.toJson(copy);
    }

    public PrintTemplate fromJson(String json) {
        PrintTemplate template = GSON.fromJson(json, PrintTemplate.class);
        if (template.getElements() == null) {
            template.setElements(List.of());
        }
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        return template;
    }

    private PrintTemplate fromRecord(PrintTemplateRepository.TemplateRecord record) {
        PrintTemplate template = fromJson(record.layoutJson());
        template.setId(record.id());
        template.setName(record.name());
        template.setPageWidth(PAGE_WIDTH);
        template.setPageHeight(PAGE_HEIGHT);
        template.setDefaultTemplate(record.isDefault());
        return template;
    }

    private void validateName(String name, Integer currentId) {
        String safeName = name == null ? "" : name.trim();
        if (safeName.isBlank()) {
            throw new IllegalArgumentException(i18n.tr("template.error.name_blank"));
        }
        boolean exists = repository.findAll().stream()
                .anyMatch(template -> safeName.equalsIgnoreCase(template.name())
                        && (currentId == null || template.id() != currentId));
        if (exists) {
            throw new IllegalArgumentException(i18n.tr("template.error.name_exists"));
        }
    }

    private void validateTemplate(PrintTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException(i18n.tr("template.error.invalid"));
        }
        validateName(template.getName(), template.getId());
        if (template.getElements() == null || template.getElements().isEmpty()) {
            throw new IllegalArgumentException(i18n.tr("template.error.empty_elements"));
        }

        boolean hasKiz = false;
        boolean hasBarcode = false;
        boolean hasStickerTail = false;

        List<PrintTemplateElement> elements = sortedElements(template.getElements());
        for (int index = 0; index < elements.size(); index++) {
            PrintTemplateElement element = elements.get(index);
            if (element.getId() == null || element.getId().isBlank()) {
                throw new IllegalArgumentException(i18n.tr("template.error.element_id"));
            }
            clampToPage(element);
            element.setZIndex(index + 1);
            if (element.getType() == PrintElementType.KIZ_DATAMATRIX) {
                hasKiz = true;
            } else if (element.getType() == PrintElementType.BARCODE_CODE128) {
                hasBarcode = true;
            } else if (element.getType() == PrintElementType.STICKER_TAIL) {
                hasStickerTail = true;
            } else if (element.getType() == PrintElementType.TEXT_FIELD && element.getFieldKey() == null) {
                throw new IllegalArgumentException(i18n.tr("template.error.field_key"));
            } else if (element.getType() == PrintElementType.STATIC_TEXT && safeTrim(element.getContent()).isBlank()) {
                throw new IllegalArgumentException(i18n.tr("template.error.static_text_blank"));
            }
            if (element.getPrefix() != null) {
                element.setPrefix(element.getPrefix().trim());
            }
            if (element.getContent() != null) {
                element.setContent(element.getContent().trim());
            }
        }
        if (!hasKiz || !hasBarcode || !hasStickerTail) {
            throw new IllegalArgumentException(i18n.tr("template.error.required_elements"));
        }
    }

    public void clampToPage(PrintTemplateElement element) {
        element.setWidth(Math.max(0, Math.min(PAGE_WIDTH, element.getWidth())));
        element.setHeight(Math.max(0, Math.min(PAGE_HEIGHT, element.getHeight())));
        element.setX(Math.max(0, Math.min(PAGE_WIDTH - element.getWidth(), element.getX())));
        element.setY(Math.max(0, Math.min(PAGE_HEIGHT - element.getHeight(), element.getY())));
        if (element.getFontSize() < 0f) {
            element.setFontSize(0f);
        }
    }

    private static List<PrintTemplateElement> sortedElements(List<PrintTemplateElement> elements) {
        List<PrintTemplateElement> copy = new ArrayList<>(elements);
        copy.sort(Comparator.comparingInt(PrintTemplateElement::getZIndex).thenComparing(PrintTemplateElement::getLabel, Comparator.nullsLast(String::compareToIgnoreCase)));
        return copy;
    }

    protected static PrintTemplateElement textField(String label, PrintFieldKey fieldKey, String prefix, double x, double y, double width, double height,
                                                  float fontSize, boolean bold, PrintTextAlign align, int zIndex) {
        PrintTemplateElement element = PrintTemplateElement.create(PrintElementType.TEXT_FIELD, label, x, y, width, height);
        element.setFieldKey(fieldKey);
        element.setPrefix(prefix == null ? defaultPrefix(fieldKey) : prefix);
        element.setFontSize(fontSize);
        element.setBold(bold);
        element.setAlign(align);
        element.setZIndex(zIndex);
        return element;
    }

    protected static PrintTemplateElement textField(String label, PrintFieldKey fieldKey, double x, double y, double width, double height,
                                                  float fontSize, boolean bold, PrintTextAlign align, int zIndex) {
        return textField(label, fieldKey, null, x, y, width, height, fontSize, bold, align, zIndex);
    }

    private static String defaultPrefix(PrintFieldKey fieldKey) {
        if (fieldKey == null) {
            return null;
        }
        return switch (fieldKey) {
            case COLOR -> PRINT_PREFIX_COLOR;
            case ARTICLE -> PRINT_PREFIX_ARTICLE;
            case SIZE -> PRINT_PREFIX_SIZE;
            case RU_SIZE -> PRINT_PREFIX_RU_SIZE;
            case SUBJECT_NAME -> "";
            default -> null;
        };
    }

    protected String stickerTailLabel() {
        return i18n.tr("template.palette.path2");
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public record ElementPaletteItem(String label, PrintElementType type, PrintFieldKey fieldKey) {
        @Override
        public String toString() {
            return label;
        }
    }
}
