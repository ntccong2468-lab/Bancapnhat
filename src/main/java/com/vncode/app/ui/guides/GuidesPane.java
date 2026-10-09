package com.vncode.app.ui.guides;

import com.vncode.app.shared.I18nService;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/** Local getting-started instructions; viewing a guide performs no seller operation. */
public final class GuidesPane extends ScrollPane {
    private final VBox body = new VBox(18);
    public GuidesPane() {
        setFitToWidth(true);
        body.getStyleClass().add("welcome-body");
        setContent(body);
        applyTranslations();
    }
    public void applyTranslations() {
        body.getChildren().clear();
        body.getChildren().add(label("guides.title", "welcome-title"));
        body.getChildren().add(label("guides.introduction", "text-secondary"));
        for (String key : java.util.List.of("wb", "ozon", "labels", "gtin", "update")) {
            VBox section = new VBox(12, label("guides." + key + ".title", "h3"),
                    label("guides." + key + ".body", "text-secondary"));
            section.getStyleClass().add("guide-section");
            section.setMinWidth(0);
            body.getChildren().add(section);
        }
    }
    private static Label label(String key, String style) {
        Label label = new Label(I18nService.getInstance().tr(key));
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(style);
        return label;
    }
}
