package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.features.print.PrintElementType;
import com.tuandev.fbsbarcode.features.print.PrintFieldKey;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateElement;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTextAlign;

import java.util.ArrayList;
import java.util.List;

public class FboPrintTemplateService extends PrintTemplateService {
    public FboPrintTemplateService() {
        super(new FboPrintTemplateRepository());
    }

    @Override
    public void ensureDefaultTemplateExists() {
        super.ensureDefaultTemplateExists();
        repository.findDefault()
                .map(record -> {
                    PrintTemplate template = fromJson(record.layoutJson());
                    template.setId(record.id());
                    template.setName(record.name());
                    template.setDefaultTemplate(record.isDefault());
                    return template;
                })
                .filter(this::isPreviousSystemDefault)
                .ifPresent(template -> {
                    PrintTemplate reset = createSystemDefaultTemplate(template.getName());
                    reset.setId(template.getId());
                    reset.setDefaultTemplate(template.isDefaultTemplate());
                    saveTemplate(reset);
                });
    }

    @Override
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

        elements.add(textField(i18n.tr("template.palette.brand"), PrintFieldKey.BRAND, mm(22), mm(2.5), mm(34), 13, 9, true, PrintTextAlign.CENTER, 2));
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

    private boolean isPreviousSystemDefault(PrintTemplate template) {
        if (template == null || !template.isDefaultTemplate() || template.getElements() == null) {
            return false;
        }
        return template.getElements().stream()
                .filter(element -> element.getType() == PrintElementType.TEXT_FIELD && element.getFieldKey() == PrintFieldKey.ARTICLE)
                .findFirst()
                .map(article -> Math.abs(article.getY() - mm(10)) < 0.25)
                .orElse(false)
                || template.getElements().stream()
                .filter(element -> element.getType() == PrintElementType.STICKER_TAIL)
                .findFirst()
                .map(stickerTail -> !stickerTailLabel().equals(stickerTail.getLabel())
                        || (Math.abs(stickerTail.getX() - mm(49)) < 0.25
                        && Math.abs(stickerTail.getWidth() - mm(6)) < 0.25
                        && stickerTail.getAlign() == PrintTextAlign.RIGHT))
                .orElse(false);
    }

    @Override
    protected String stickerTailLabel() {
        return i18n.tr("template.palette.pair_no");
    }
}
