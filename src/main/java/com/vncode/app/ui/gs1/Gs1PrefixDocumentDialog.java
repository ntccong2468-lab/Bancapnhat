package com.vncode.app.ui.gs1;

import com.vncode.app.features.gs1.Gs1PrefixDocumentService;
import com.vncode.app.features.gs1.Gs1PrefixDocumentService.*;
import com.vncode.app.features.gs1.Gs1SignatureInk;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.I18nService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Optional;
import java.util.function.Consumer;

/** The operator supplies and reviews the official 01.04.01 blank and its field positions.
 * PDF/image parsing happens off the FX thread; raw ink lives only while this dialog is open.
 */
public final class Gs1PrefixDocumentDialog {
    private enum Target { COMPANY_NAME, INN, GS1_CODE, DATE, SIGNER_NAME, SIGNATURE }
    private final Dialog<Gs1MailClient.Attachment> dialog = new Dialog<>();
    private final Gs1PrefixDocumentService documents = new Gs1PrefixDocumentService();
    private final Gs1SignatureInk ink = new Gs1SignatureInk();
    private final EnumMap<Field, Area> placements = new EnumMap<>(Field.class);
    private final TextField legalName, inn, gs1Code, signerName;
    private final DatePicker date;
    private final ComboBox<Target> target = new ComboBox<>();
    private final Canvas pageCanvas = new Canvas(430, 610), signatureCanvas = new Canvas(430, 120);
    private final Label status = new Label();
    private final CheckBox verified = new CheckBox(tr("gs1.document_confirm"));
    private final Button loadPdf = new Button(tr("gs1.document_load_pdf")), loadPhoto = new Button(tr("gs1.document_load_photo"));
    private final Button clearInk = new Button(tr("gs1.document_clear_signature")), generate = new Button(tr("gs1.document_preview"));
    private final ButtonType attachType = new ButtonType(tr("gs1.document_attach"), ButtonBar.ButtonData.OK_DONE);
    private final VBox fields;
    private Area signatureArea;
    private byte[] blank;
    private Image blankPreview;
    private Gs1MailClient.Attachment prepared;
    private boolean photoMode, processing, disposed, finalPreview;
    private double dragX, dragY;

    private Gs1PrefixDocumentDialog(Window owner, String company, String taxId, String memberCode, String signer, LocalDate initialDate) {
        if (owner != null) dialog.initOwner(owner);
        AlertService.applyTheme(dialog); Gs1Dialogs.centerOnOwner(dialog);
        dialog.setTitle(tr("gs1.document_title")); dialog.setResizable(true);
        legalName = new TextField(safe(company)); inn = new TextField(safe(taxId)); inn.setEditable(false);
        gs1Code = new TextField(safe(memberCode)); signerName = new TextField(safe(signer)); date = new DatePicker(initialDate);
        legalName.setId("gs1PrefixCompany"); gs1Code.setId("gs1PrefixMemberCode"); signerName.setId("gs1PrefixSigner");
        target.getItems().setAll(Target.values()); target.setValue(Target.COMPANY_NAME);
        target.setConverter(new StringConverter<>() {
            @Override public String toString(Target value) { return value == null ? "" : tr("gs1.document_field." + value.name().toLowerCase(java.util.Locale.ROOT)); }
            @Override public Target fromString(String value) { throw new UnsupportedOperationException(); }
        });
        fields = new VBox(7, labeled("gs1.field.companyName", legalName), labeled("gs1.field.inn", inn),
                labeled("gs1.document_field.gs1_code", gs1Code), labeled("gs1.document_field.date", date),
                labeled("gs1.document_field.signer_name", signerName), labeled("gs1.document_place_field", target));
        Label notice = new Label(tr("gs1.document_source_notice")); notice.setWrapText(true);
        Label placementNotice = new Label(tr("gs1.document_place_notice")); placementNotice.setWrapText(true);
        Label signatureNotice = new Label(tr("gs1.document_signature_notice")); signatureNotice.setWrapText(true);
        signatureCanvas.setId("gs1PrefixSignature"); pageCanvas.setId("gs1PrefixPreview");
        Pane signatureBox = new Pane(signatureCanvas); signatureBox.setStyle("-fx-border-color: #777; -fx-background-color: white;");
        VBox editor = new VBox(10, notice, new HBox(8, loadPdf, loadPhoto), fields, placementNotice, signatureNotice,
                signatureBox, clearInk, generate, verified, status); editor.setPadding(new Insets(10)); editor.setPrefWidth(460);
        ScrollPane editorScroll = new ScrollPane(editor); editorScroll.setFitToWidth(true); editorScroll.setPrefViewportHeight(620);
        ScrollPane previewScroll = new ScrollPane(pageCanvas); previewScroll.setPrefViewportWidth(450); previewScroll.setPrefViewportHeight(620);
        HBox body = new HBox(10, editorScroll, previewScroll); HBox.setHgrow(editorScroll, Priority.ALWAYS); HBox.setHgrow(previewScroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body); dialog.getDialogPane().getButtonTypes().addAll(attachType, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(960); dialog.getDialogPane().setPrefHeight(700);
        status.setWrapText(true); verified.setWrapText(true); verified.setId("gs1PrefixReviewConfirmation");
        loadPdf.setOnAction(event -> choose(false)); loadPhoto.setOnAction(event -> choose(true));
        generate.setOnAction(event -> prepare()); clearInk.setOnAction(event -> { ink.clear(); invalidate(); drawInk(); });
        for (var input : new TextField[]{legalName, gs1Code, signerName}) input.textProperty().addListener((o, a, b) -> invalidate());
        date.valueProperty().addListener((o, a, b) -> invalidate()); verified.selectedProperty().addListener((o, a, b) -> updateButtons());
        installPlacement(); installInk(); drawInk(); drawPage(); updateButtons();
        dialog.setResultConverter(button -> button == attachType && prepared != null && verified.isSelected() ? prepared : null);
        dialog.setOnHidden(event -> {
            disposed = true; ink.close(); signatureCanvas.getGraphicsContext2D().clearRect(0, 0, signatureCanvas.getWidth(), signatureCanvas.getHeight());
            pageCanvas.getGraphicsContext2D().clearRect(0, 0, pageCanvas.getWidth(), pageCanvas.getHeight());
            if (blank != null) Arrays.fill(blank, (byte) 0); blank = null; blankPreview = null; prepared = null;
            legalName.clear(); gs1Code.clear(); signerName.clear();
        });
    }

    public static Optional<Gs1MailClient.Attachment> show(Window owner, String legalName, String inn, String gs1Code, String signerName) {
        return new Gs1PrefixDocumentDialog(owner, legalName, inn, gs1Code, signerName,
                LocalDate.now(ZoneId.of("Europe/Moscow"))).dialog.showAndWait();
    }
    public static Optional<Gs1MailClient.Attachment> show(Window owner, Applicant defaults) {
        return new Gs1PrefixDocumentDialog(owner, defaults.legalName(), defaults.inn(), defaults.gs1Code(), defaults.signerName(), defaults.date()).dialog.showAndWait();
    }

    private void choose(boolean photo) {
        FileChooser chooser = new FileChooser(); chooser.setTitle(tr(photo ? "gs1.document_load_photo" : "gs1.document_load_pdf"));
        chooser.getExtensionFilters().add(photo ? new FileChooser.ExtensionFilter("JPG / PNG", "*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG") :
                new FileChooser.ExtensionFilter("PDF", "*.pdf", "*.PDF"));
        var file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow()); if (file == null) return;
        run(new Task<Loaded>() {
            @Override protected Loaded call() throws Exception {
                byte[] source;
                try (var input = Files.newInputStream(file.toPath())) { source = input.readNBytes(Gs1MailClient.MAX_ATTACHMENT + 1); }
                if (source.length > Gs1MailClient.MAX_ATTACHMENT) { Arrays.fill(source, (byte) 0); throw new IllegalArgumentException("gs1.document_size"); }
                try {
                    if (photo) {
                        var attachment = documents.paperPhoto(source);
                        return new Loaded(null, documents.previewTemplate(attachment.bytes()), attachment);
                    }
                    return new Loaded(source, documents.previewTemplate(source), null);
                } catch (Exception failure) {
                    Arrays.fill(source, (byte) 0); throw failure;
                } finally { if (photo) Arrays.fill(source, (byte) 0); }
            }
        }, loaded -> {
            if (blank != null) Arrays.fill(blank, (byte) 0); blank = loaded.blank();
            photoMode = photo; placements.clear(); signatureArea = null; ink.clear(); drawInk();
            blankPreview = image(loaded.preview().png()); prepared = loaded.photo(); finalPreview = photo;
            verified.setSelected(false); fields.setDisable(photo); drawPage(); status.setText(tr(photo ? "gs1.document_photo_ready" : "gs1.document_blank_ready"));
        });
    }
    private record Loaded(byte[] blank, Preview preview, Gs1MailClient.Attachment photo) { }
    private record Generated(Gs1MailClient.Attachment attachment, Preview preview) { }

    private void prepare() {
        if (blank == null || photoMode) return;
        Applicant applicant;
        Layout layout;
        try {
            applicant = new Applicant(legalName.getText(), inn.getText(), gs1Code.getText(), date.getValue(), signerName.getText());
            layout = new Layout(placements, signatureArea);
            if (ink.isEmpty()) throw new IllegalArgumentException("gs1.document_signature_required");
        } catch (IllegalArgumentException | NullPointerException failure) { status.setText(tr("gs1.document_incomplete")); return; }
        // Ink is snapshotted into an independent short-lived buffer before handing off to the worker.
        Gs1SignatureInk workerInk = copyInk(); byte[] source = blank.clone();
        run(new Task<Generated>() {
            @Override protected Generated call() throws Exception {
                try (workerInk) {
                    var attachment = documents.signedTemplate(source, applicant, layout, workerInk);
                    return new Generated(attachment, documents.previewTemplate(attachment.bytes()));
                } finally { Arrays.fill(source, (byte) 0); }
            }
        }, result -> {
            prepared = result.attachment(); finalPreview = true; verified.setSelected(false);
            var output = image(result.preview().png()); pageCanvas.getGraphicsContext2D().clearRect(0, 0, pageCanvas.getWidth(), pageCanvas.getHeight());
            renderImage(output); status.setText(tr("gs1.document_ready"));
        }, () -> { workerInk.close(); Arrays.fill(source, (byte) 0); });
    }
    private Gs1SignatureInk copyInk() {
        Gs1SignatureInk result = new Gs1SignatureInk(); var copy = ink.snapshot();
        try {
            for (var stroke : copy) {
                if (stroke.length == 0) continue; result.start(stroke[0][0], stroke[0][1]);
                for (int i = 1; i < stroke.length; i++) result.append(stroke[i][0], stroke[i][1]); result.end();
            }
            return result;
        } finally { for (var stroke : copy) for (var point : stroke) Arrays.fill(point, 0); }
    }
    private void installPlacement() {
        pageCanvas.setOnMousePressed(event -> {
            if (processing || photoMode || blank == null || event.getButton() != MouseButton.PRIMARY) return;
            dragX = event.getX(); dragY = event.getY();
        });
        pageCanvas.setOnMouseReleased(event -> {
            if (processing || photoMode || blank == null || event.getButton() != MouseButton.PRIMARY) return;
            double x = Math.max(0, Math.min(dragX, event.getX())), y = Math.max(0, Math.min(dragY, event.getY()));
            double width = Math.min(pageCanvas.getWidth() - x, Math.abs(event.getX() - dragX));
            double height = Math.min(pageCanvas.getHeight() - y, Math.abs(event.getY() - dragY));
            if (width < 12 || height < 8) { status.setText(tr("gs1.document_place_notice")); return; }
            Area area = new Area(x / pageCanvas.getWidth(), 1 - (y + height) / pageCanvas.getHeight(), width / pageCanvas.getWidth(), height / pageCanvas.getHeight());
            var selected = target.getValue(); if (selected == Target.SIGNATURE) signatureArea = area;
            else placements.put(Field.valueOf(selected.name()), area);
            invalidate(); status.setText(tr("gs1.document_place_progress").replace("{0}", Integer.toString(placements.size() + (signatureArea == null ? 0 : 1))));
        });
    }
    private void installInk() {
        signatureCanvas.setOnMousePressed(event -> { if (!event.isSynthesized() && event.getButton() == MouseButton.PRIMARY) startInk(event.getX(), event.getY()); });
        signatureCanvas.setOnMouseDragged(event -> { if (!event.isSynthesized() && event.isPrimaryButtonDown()) appendInk(event.getX(), event.getY()); });
        signatureCanvas.setOnMouseReleased(event -> { if (!event.isSynthesized()) ink.end(); });
        signatureCanvas.setOnTouchPressed(event -> { if (event.getTouchPoints().size() == 1) startInk(event.getTouchPoint().getX(), event.getTouchPoint().getY()); event.consume(); });
        signatureCanvas.setOnTouchMoved(event -> { if (event.getTouchPoints().size() == 1) appendInk(event.getTouchPoint().getX(), event.getTouchPoint().getY()); event.consume(); });
        signatureCanvas.setOnTouchReleased(event -> { ink.end(); event.consume(); });
    }
    private void startInk(double x, double y) {
        if (processing || photoMode || disposed) return;
        try { ink.start(clamp(x / signatureCanvas.getWidth()), clamp(y / signatureCanvas.getHeight())); invalidate(); drawInk(); }
        catch (IllegalStateException failure) { status.setText(tr("gs1.document_ink_limit")); }
    }
    private void appendInk(double x, double y) {
        if (processing || photoMode || disposed) return;
        try { ink.append(clamp(x / signatureCanvas.getWidth()), clamp(y / signatureCanvas.getHeight())); invalidate(); drawInk(); }
        catch (IllegalStateException failure) { status.setText(tr("gs1.document_ink_limit")); }
    }
    private void drawInk() {
        var graphics = signatureCanvas.getGraphicsContext2D(); graphics.setFill(Color.WHITE); graphics.fillRect(0, 0, signatureCanvas.getWidth(), signatureCanvas.getHeight());
        graphics.setStroke(Color.BLACK); graphics.setLineWidth(2); var copy = ink.snapshot();
        try {
            for (var stroke : copy) {
                if (stroke.length < 2) continue; graphics.beginPath(); graphics.moveTo(stroke[0][0] * signatureCanvas.getWidth(), stroke[0][1] * signatureCanvas.getHeight());
                for (int i = 1; i < stroke.length; i++) graphics.lineTo(stroke[i][0] * signatureCanvas.getWidth(), stroke[i][1] * signatureCanvas.getHeight()); graphics.stroke();
            }
        } finally { for (var stroke : copy) for (var point : stroke) Arrays.fill(point, 0); }
    }
    private void invalidate() {
        if (disposed || photoMode) return; prepared = null; finalPreview = false; verified.setSelected(false); drawPage(); updateButtons();
    }
    private void drawPage() {
        var graphics = pageCanvas.getGraphicsContext2D(); graphics.setFill(Color.WHITE); graphics.fillRect(0, 0, pageCanvas.getWidth(), pageCanvas.getHeight());
        if (blankPreview == null) return; renderImage(blankPreview);
        if (photoMode || finalPreview) return;
        placements.forEach((field, area) -> drawArea(area, tr("gs1.document_field." + field.name().toLowerCase(java.util.Locale.ROOT))));
        if (signatureArea != null) drawArea(signatureArea, tr("gs1.document_field.signature"));
    }
    private void renderImage(Image image) {
        double width = 430, height = width * image.getHeight() / image.getWidth();
        pageCanvas.setWidth(width); pageCanvas.setHeight(height); pageCanvas.getGraphicsContext2D().drawImage(image, 0, 0, width, height);
    }
    private void drawArea(Area area, String label) {
        double x = area.x() * pageCanvas.getWidth(), y = (1 - area.y() - area.height()) * pageCanvas.getHeight();
        var graphics = pageCanvas.getGraphicsContext2D(); graphics.setStroke(Color.DODGERBLUE); graphics.setFill(Color.color(.1, .5, 1, .15));
        graphics.fillRect(x, y, area.width() * pageCanvas.getWidth(), area.height() * pageCanvas.getHeight());
        graphics.strokeRect(x, y, area.width() * pageCanvas.getWidth(), area.height() * pageCanvas.getHeight()); graphics.setFill(Color.DARKBLUE); graphics.fillText(label, x, Math.max(12, y - 2));
    }
    private <T> void run(Task<T> task, Consumer<T> success) { run(task, success, () -> { }); }
    private <T> void run(Task<T> task, Consumer<T> success, Runnable rejectedCleanup) {
        processing = true; updateButtons(); status.setText(tr("gs1.document_working"));
        task.setOnSucceeded(event -> {
            processing = false;
            if (disposed) { if (task.getValue() instanceof Loaded loaded && loaded.blank() != null) Arrays.fill(loaded.blank(), (byte) 0); return; }
            success.accept(task.getValue()); updateButtons();
        });
        task.setOnFailed(event -> { processing = false; if (!disposed) { status.setText(tr("gs1.document_error")); updateButtons(); } });
        AppTaskExecutor.execute(task, failure -> { rejectedCleanup.run(); processing = false; if (!disposed) { status.setText(tr("gs1.document_error")); updateButtons(); } });
    }
    private void updateButtons() {
        loadPdf.setDisable(processing); loadPhoto.setDisable(processing); clearInk.setDisable(processing || photoMode);
        fields.setDisable(processing || photoMode); signatureCanvas.setDisable(processing || photoMode); verified.setDisable(processing || prepared == null);
        generate.setDisable(processing || blank == null || photoMode);
        dialog.getDialogPane().lookupButton(attachType).setDisable(processing || prepared == null || !verified.isSelected());
    }
    private static VBox labeled(String key, javafx.scene.Node input) { return new VBox(3, new Label(tr(key)), input); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static Image image(byte[] png) { return new Image(new ByteArrayInputStream(png)); }
    private static String tr(String key) { return I18nService.getInstance().tr(key); }
}
