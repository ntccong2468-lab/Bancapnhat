package com.vncode.app.shared;

import javafx.css.PseudoClass;
import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Commit the actual editor text before a document preview can be prepared. */
public final class DocumentDateInput {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private DocumentDateInput() {}
    public static void configure(DatePicker picker) {
        picker.setConverter(new StringConverter<>() {
            public String toString(LocalDate value) { return value == null ? "" : FORMAT.format(value); }
            public LocalDate fromString(String text) { return parse(text); }
        });
        picker.getEditor().textProperty().addListener((o,a,b) -> picker.getEditor()
                .pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), false));
    }
    public static LocalDate parse(String text) {
        if (text == null || text.isBlank()) return null;
        return LocalDate.parse(text.strip(), FORMAT);
    }
    public static void commit(DatePicker picker) {
        try { picker.setValue(parse(picker.getEditor().getText())); }
        catch (RuntimeException invalid) {
            picker.getEditor().pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
            throw new IllegalArgumentException("date.invalid");
        }
    }
}
