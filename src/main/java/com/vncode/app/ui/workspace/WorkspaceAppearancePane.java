package com.vncode.app.ui.workspace;

import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Native settings route backed by the same saved appearance choices as the application chrome. */
public final class WorkspaceAppearancePane extends BorderPane {
    private final I18nService i18n = I18nService.getInstance();
    private final Map<Label, String> copyKeys = new java.util.LinkedHashMap<>();
    private final Map<ThemeService.ThemeMode, ToggleButton> themeButtons = new EnumMap<>(ThemeService.ThemeMode.class);
    private final ToggleGroup themeGroup = new ToggleGroup();
    private final CheckBox glassEnabled = new CheckBox();
    private final CheckBox reducedMotion = new CheckBox();
    private final Slider transparency = new Slider(0, 100, ThemeService.DEFAULT_TRANSPARENCY);
    private final Label percentage = new Label();
    private final ComboBox<AppLanguage> language = new ComboBox<>();
    private final Button translation = actionButton("appearanceTranslationSettings");
    private final Button update = actionButton("appearanceCheckUpdate");
    private final Button account = actionButton("appearanceAccount");
    private final Button printer = actionButton("appearancePrinterSettings");
    private final Button about = actionButton("appearanceAbout");
    private final FlowPane actions = new FlowPane(10, 10);
    private final VBox integrations = new VBox(14);
    private final Runnable appearanceListener = this::refreshControls;
    private final Consumer<AppLanguage> languageListener = ignored -> onFxThread(this::applyTranslations);
    private boolean updating;
    private boolean observing;

    public WorkspaceAppearancePane() {
        getStyleClass().addAll("app-page", "appearance-pane");
        setMinWidth(0);
        var header = new VBox(6, copy("settings.menu", "h1"), copy("settings.appearance.description", "appearance-description"));
        header.getStyleClass().add("appearance-header");
        header.setPadding(new Insets(24, 28, 18, 28));
        setTop(header);

        var appearance = new VBox(18);
        appearance.getStyleClass().add("appearance-section");
        appearance.getChildren().add(copy("settings.appearance.title", "h3"));
        var options = new FlowPane(6, 6);
        options.getStyleClass().add("appearance-theme-options");
        for (var mode : new ThemeService.ThemeMode[]{ThemeService.ThemeMode.LIGHT, ThemeService.ThemeMode.DARK, ThemeService.ThemeMode.SYSTEM}) {
            var button = new ToggleButton();
            button.setId("appearanceTheme" + switch (mode) { case LIGHT -> "Light"; case DARK -> "Dark"; case SYSTEM -> "System"; });
            button.setUserData(mode);
            button.setToggleGroup(themeGroup);
            button.setMinWidth(0);
            button.setWrapText(true);
            themeButtons.put(mode, button);
            options.getChildren().add(button);
        }
        var themeLabel = copy("settings.theme", "appearance-row-title");
        themeLabel.setLabelFor(themeButtons.get(ThemeService.ThemeMode.LIGHT));
        appearance.getChildren().add(new VBox(8, themeLabel, options));

        reducedMotion.setId("appearanceReducedMotion");
        glassEnabled.setId("appearanceGlassEnabled");
        appearance.getChildren().add(toggleRow("settings.reduced_motion", "settings.reduced_motion.hint", reducedMotion));
        appearance.getChildren().add(toggleRow("settings.glass.enabled", "settings.glass.chrome_hint", glassEnabled));

        transparency.setId("appearanceGlassTransparency");
        transparency.setMinWidth(100);
        transparency.setMaxWidth(Double.MAX_VALUE);
        transparency.setBlockIncrement(5);
        transparency.setMajorTickUnit(25);
        transparency.setShowTickMarks(true);
        HBox.setHgrow(transparency, Priority.ALWAYS);
        percentage.setMinWidth(48);
        percentage.setAlignment(Pos.CENTER_RIGHT);
        var materialLabel = copy("settings.glass.transparency", "appearance-row-title");
        materialLabel.setLabelFor(transparency);
        var sliderRow = new HBox(14, transparency, percentage);
        sliderRow.setAlignment(Pos.CENTER_LEFT);
        appearance.getChildren().add(new VBox(8, materialLabel, sliderRow, copy("settings.glass.readability", "appearance-description")));

        language.setId("appearanceLanguage");
        language.getItems().setAll(AppLanguage.values());
        language.setMaxWidth(260);
        var languageLabel = copy("sidebar.language", "appearance-row-title");
        languageLabel.setLabelFor(language);
        appearance.getChildren().add(new VBox(8, languageLabel, language));

        integrations.getStyleClass().add("appearance-section");
        integrations.getChildren().setAll(copy("settings.integrations.title", "h3"), actions);
        actions.getStyleClass().add("appearance-actions");
        actions.getChildren().setAll(translation, update, about, printer, account);
        var content = new VBox(30, appearance, integrations);
        content.getStyleClass().add("appearance-content");
        content.setMinWidth(0);
        content.setMaxWidth(820);
        content.setPadding(new Insets(0, 28, 28, 28));
        var scroll = new ScrollPane(content);
        scroll.getStyleClass().add("appearance-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinWidth(0);
        setCenter(scroll);

        themeGroup.selectedToggleProperty().addListener((observable, previous, selected) -> {
            if (updating) return;
            if (selected == null) {
                if (previous != null) themeGroup.selectToggle(previous);
                return;
            }
            ThemeService.switchTheme(((ThemeService.ThemeMode) selected.getUserData()).getKey());
        });
        glassEnabled.setOnAction(event -> ThemeService.setGlassEnabled(glassEnabled.isSelected()));
        reducedMotion.setOnAction(event -> ThemeService.setReducedMotion(reducedMotion.isSelected()));
        transparency.valueProperty().addListener((observable, previous, value) -> {
            percentage.setText(Math.round(value.doubleValue()) + "%");
            if (updating) return;
            ThemeService.previewGlassTransparency(value.doubleValue());
            if (!transparency.isValueChanging()) ThemeService.setGlassTransparency(value.doubleValue());
        });
        transparency.valueChangingProperty().addListener((observable, previous, changing) -> {
            if (!updating && !changing) ThemeService.setGlassTransparency(transparency.getValue());
        });
        language.setOnAction(event -> {
            if (!updating && language.getValue() != null) i18n.setLanguage(language.getValue());
        });
        sceneProperty().addListener((observable, previous, scene) -> {
            if (scene == null) stopObserving();
            else {
                startObserving();
                applyTranslations();
            }
        });
        refreshCallbackVisibility();
        applyTranslations();
    }

    private Label copy(String key, String styleClass) {
        var label = new Label();
        label.setWrapText(true);
        label.setMinWidth(0);
        label.getStyleClass().add(styleClass);
        copyKeys.put(label, key);
        return label;
    }

    private HBox toggleRow(String titleKey, String hintKey, CheckBox control) {
        var title = copy(titleKey, "appearance-row-title");
        title.setLabelFor(control);
        var copy = new VBox(4, title, copy(hintKey, "appearance-description"));
        copy.setMinWidth(0);
        copy.getStyleClass().add("appearance-row-copy");
        HBox.setHgrow(copy, Priority.ALWAYS);
        var row = new HBox(24, copy, control);
        row.getStyleClass().add("appearance-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Button actionButton(String id) {
        var button = new Button();
        button.setId(id);
        button.setWrapText(true);
        button.setMinWidth(0);
        button.setVisible(false);
        button.setManaged(false);
        return button;
    }

    private void refreshControls() {
        onFxThread(() -> {
            updating = true;
            try {
                themeGroup.selectToggle(themeButtons.get(ThemeService.getThemeMode()));
                glassEnabled.setSelected(ThemeService.isGlassEnabled());
                transparency.setDisable(!glassEnabled.isSelected());
                transparency.setValue(ThemeService.getGlassTransparency());
                percentage.setText(Math.round(transparency.getValue()) + "%");
                reducedMotion.setSelected(ThemeService.isReducedMotion());
                reducedMotion.setDisable(Platform.getPreferences().isReducedMotion());
                language.setValue(i18n.getCurrentLanguage());
            } finally {
                updating = false;
            }
        });
    }

    public void applyTranslations() {
        for (var entry : copyKeys.entrySet()) entry.getKey().setText(i18n.tr(entry.getValue()));
        for (var entry : themeButtons.entrySet()) entry.getValue().setText(i18n.tr("settings.theme." + entry.getKey().getKey()));
        glassEnabled.setAccessibleText(i18n.tr("settings.glass.enabled"));
        reducedMotion.setAccessibleText(i18n.tr("settings.reduced_motion"));
        transparency.setAccessibleText(i18n.tr("settings.glass.transparency"));
        language.setAccessibleText(i18n.tr("sidebar.language"));
        translation.setText(i18n.tr("news.translation_settings"));
        update.setText(i18n.tr("settings.check_version"));
        account.setText(i18n.tr("license.account.title"));
        printer.setText(i18n.tr("settings.printer"));
        about.setText(i18n.tr("settings.about"));
        refreshControls();
    }

    private void startObserving() {
        if (observing) return;
        observing = true;
        i18n.addListener(languageListener);
        ThemeService.addAppearanceListener(appearanceListener);
    }

    private void stopObserving() {
        if (!observing) return;
        observing = false;
        i18n.removeListener(languageListener);
        ThemeService.removeAppearanceListener(appearanceListener);
    }

    /** Called by the owner when the workspace is closed. Scene detachment also releases listeners. */
    public void dispose() {
        stopObserving();
    }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private void setCallback(Button button, Runnable callback) {
        button.setOnAction(callback == null ? null : event -> callback.run());
        button.setVisible(callback != null);
        button.setManaged(callback != null);
        refreshCallbackVisibility();
    }

    private void refreshCallbackVisibility() {
        boolean visible = actions.getChildren().stream().anyMatch(Node::isManaged);
        integrations.setVisible(visible);
        integrations.setManaged(visible);
    }

    public void setOnTranslationSettings(Runnable callback) { setCallback(translation, callback); }
    public void setOnCheckUpdate(Runnable callback) { setCallback(update, callback); }
    public void setOnAccount(Runnable callback) { setCallback(account, callback); }
    public void setOnPrinterSettings(Runnable callback) { setCallback(printer, callback); }
    public void setOnAbout(Runnable callback) { setCallback(about, callback); }
}
