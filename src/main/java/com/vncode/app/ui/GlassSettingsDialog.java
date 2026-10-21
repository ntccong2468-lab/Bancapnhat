package com.vncode.app.ui;

import com.vncode.app.shared.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;

public final class GlassSettingsDialog {
    private GlassSettingsDialog() {}
    public static void show() {
        var i18n=I18nService.getInstance();int original=ThemeService.getGlassTransparency();
        var dialog=new Dialog<ButtonType>();dialog.setTitle(i18n.tr("settings.glass.title"));
        var save=new ButtonType(i18n.tr("settings.glass.save"),ButtonBar.ButtonData.OK_DONE);
        var cancel=new ButtonType(i18n.tr("settings.glass.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(save,cancel);
        var slider=new Slider(0,100,original);slider.setId("glassTransparencySlider");slider.setBlockIncrement(5);slider.setMajorTickUnit(25);slider.setShowTickMarks(true);slider.setShowTickLabels(true);slider.setMaxWidth(Double.MAX_VALUE);
        slider.setAccessibleText(i18n.tr("settings.glass.transparency"));
        var percent=new Label(original+"%");percent.setMinWidth(45);
        var label=new Label(i18n.tr("settings.glass.transparency"));var row=new HBox(10,label,percent);HBox.setHgrow(label,Priority.ALWAYS);label.setMaxWidth(Double.MAX_VALUE);
        var sample=new VBox(8,new Label(i18n.tr("settings.glass.preview")),new Label(i18n.tr("settings.glass.readability")),new Button(i18n.tr("settings.glass.sample")));
        sample.getStyleClass().addAll("card","glass-settings-preview");
        for(var n:sample.getChildren())if(n instanceof Label text){text.setWrapText(true);text.setMinWidth(0);}
        var note=new Label(i18n.tr("settings.glass.hint"));note.setWrapText(true);
        var content=new VBox(14,row,slider,sample,note);content.setPadding(new Insets(8));content.setMinWidth(0);content.setPrefWidth(450);
        dialog.getDialogPane().setContent(content);dialog.setResizable(true);AlertService.applyTheme(dialog);
        slider.valueProperty().addListener((observable,before,value)->{percent.setText(Math.round(value.doubleValue())+"%");ThemeService.previewGlassTransparency(value.doubleValue());});
        try {
            if(dialog.showAndWait().orElse(cancel)==save)ThemeService.setGlassTransparency(slider.getValue());
            else ThemeService.previewGlassTransparency(original);
        } catch(RuntimeException failure) { ThemeService.previewGlassTransparency(original);throw failure; }
    }
}
