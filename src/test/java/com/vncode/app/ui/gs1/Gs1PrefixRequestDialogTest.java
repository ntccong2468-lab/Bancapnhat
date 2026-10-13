package com.vncode.app.ui.gs1;

import com.google.gson.JsonObject;
import com.vncode.app.config.Database;
import com.vncode.app.features.gs1.Gs1PrefixRequestModels.ProfileDefaults;
import com.vncode.app.features.gs1.Gs1PrefixRequestModels.Profile;
import com.vncode.app.features.gs1.Gs1PrefixRequestModels.Quantity;
import com.vncode.app.features.gs1.Gs1PrefixRequestModels.RequestDraft;
import com.vncode.app.features.gs1.Gs1PrefixRequestService;
import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.features.gs1.Gs1SignatureInk;
import com.vncode.app.features.gs1.Gs1WordPrefixService;
import com.vncode.app.integration.gs1.Gs1MailClient;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.gs1.NationalCatalogGs1Client;
import com.vncode.app.integration.gs1.Gs1MailAccount;
import com.vncode.app.features.gs1.Gs1PrefixDocumentService;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the user's reviewed-document workflow without choosing local files or sending mail. */
class Gs1PrefixRequestDialogTest {
    private static final String INN = "7707083893";
    private static final ProfileDefaults DEFAULTS = new ProfileDefaults("Catalog Fixture Company", INN,
            List.of("4601234", "4605678"), "catalog@fixture.ru", "+7 (900) 123-45-67", "Certificate Fixture Signer");
    @TempDir Path directory;
    private final List<Gs1PrefixRequestDialog> opened = new ArrayList<>();
    private Gs1PrefixRequestService service;
    private AppLanguage previousLanguage;

    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyRunning) { }
    }
    @BeforeEach void setUp() throws Exception {
        System.setProperty("vncode.appdata.dir", directory.toString());
        Database.initDatabase(); service = new Gs1PrefixRequestService(new Gs1Repository());
        previousLanguage = I18nService.getInstance().getCurrentLanguage();
    }
    @AfterEach void tearDown() throws Exception {
        for (var editor : opened) awaitIdle(editor);
        fx(() -> {
            for (var editor : opened) {
                var dialog = dialog(editor);
                if (dialog.isShowing()) dialog.close();
                else if (!(boolean) field(editor, "disposed"))
                    dialog.getOnHidden().handle(new DialogEvent(dialog, DialogEvent.DIALOG_HIDDEN));
            }
            I18nService.getInstance().setLanguage(previousLanguage); return null;
        });
        System.clearProperty("vncode.appdata.dir");
    }

    @Test void sourcedContactFieldsAndEveryGcpAppearWithoutUploadingProfileScreenshots() throws Exception {
        var editor = create(snapshot(0, 0));
        fx(() -> {
            assertEquals(DEFAULTS.legalName(), text(editor, "gs1PrefixCompany").getText());
            assertEquals(DEFAULTS.email(), text(editor, "gs1PrefixEmail").getText());
            assertEquals(DEFAULTS.phone(), text(editor, "gs1PrefixPhone").getText());
            assertEquals(DEFAULTS.signerName(), text(editor, "gs1PrefixSigner").getText());
            assertEquals(INN, text(editor, "gs1PrefixInn").getText());
            assertEquals("4601234, 4605678", text(editor, "gs1PrefixGcps").getText());
            assertFalse(text(editor, "gs1PrefixInn").isEditable());
            assertFalse(text(editor, "gs1PrefixGcps").isEditable());
            assertEquals(I18nService.getInstance().tr("gs1.prefix.import_word"), button(editor, "gs1PrefixImportWord").getText());
            assertEquals(I18nService.getInstance().tr("gs1.prefix.attach_signed"), button(editor, "gs1PrefixAttachSigned").getText());
            assertNull(find(dialog(editor).getDialogPane(), "gs1PrefixUploadProfile"));
            var standard = quantity(editor, Quantity.STANDARD); var extended = quantity(editor, Quantity.EXTENDED);
            assertEquals(2, standard.getToggleGroup().getToggles().size());
            assertTrue(standard.isSelected()); assertFalse(extended.isSelected());
            assertEquals(999, ((Quantity) standard.getUserData()).value());
            assertEquals(99999, ((Quantity) extended.getUserData()).value());
            assertTrue(mail(editor).getText().contains("999 кодов GTIN"));
            extended.fire(); assertTrue(mail(editor).getText().contains("99999 кодов GTIN"));
            assertEquals(Gs1PrefixRequestService.RECIPIENT, "mail@gs1ru.org");
            return null;
        });
    }

    @Test void positiveAndUnknownAggregateQuotaBlockProgressDespiteAReviewedSignedDocument() throws Exception {
        var signed = signedDocument();
        for (var source : List.of(snapshot(0, 12), snapshot(0, null))) {
            var editor = create(source);
            fx(() -> {
                installReviewedDocument(editor, signed);
                assertTrue(next(editor).isDisabled(), "A zero first prefix never permits a request while another range remains or is unverified");
                assertNull(dialog(editor).getResult());
                next(editor).fire(); assertNull(dialog(editor).getResult());
                return null;
            });
        }
        assertTrue(new Gs1Repository().requests(INN).isEmpty(), "Blocked document review must not create a durable request");
    }

    @Test void actualUnsignedPdfPreviewCannotBecomeAReviewedRequest() throws Exception {
        var editor = create(snapshot(0, 0)); installTemplate(editor);
        fx(() -> { button(editor, "gs1PrefixPreviewWord").fire(); return null; }); awaitIdle(editor);
        fx(() -> {
            assertNotNull(image(editor).getImage(), "The displayed preview is a rendered PDF, not an empty placeholder");
            assertTrue(review(editor).isDisabled()); assertTrue(next(editor).isDisabled());
            review(editor).setSelected(true);
            assertTrue(next(editor).isDisabled(), "Even a stale checked flag cannot approve an unsigned preview");
            assertNull(dialog(editor).getResult()); return null;
        });
    }

    @Test void signedPdfIsShownAndItsExactBytesBecomeTheConfirmedDraft() throws Exception {
        var editor = create(snapshot(0, 0)); generateSigned(editor);
        var attachment = fx(() -> {
            assertNotNull(image(editor).getImage()); assertFalse(review(editor).isDisabled());
            assertFalse(review(editor).isSelected()); assertTrue(next(editor).isDisabled());
            return (Gs1MailClient.Attachment) field(editor, "document");
        });
        try (var pdf = Loader.loadPDF(attachment.bytes())) {
            String filled = new PDFTextStripper().getText(pdf);
            assertTrue(filled.contains(DEFAULTS.legalName())); assertTrue(filled.contains(INN));
            for (var gcp : DEFAULTS.gcps()) assertTrue(filled.contains(gcp));
            assertTrue(filled.contains(DEFAULTS.signerName()));
        }
        fx(() -> {
            review(editor).fire(); assertFalse(next(editor).isDisabled()); next(editor).fire();
            var result = dialog(editor).getResult(); assertNotNull(result);
            assertArrayEquals(attachment.bytes(), result.reviewedDocument().bytes());
            assertEquals(DEFAULTS.gcps(), result.profile().gcps()); assertEquals(Quantity.STANDARD, result.quantity());
            return null;
        });
        assertTrue(new Gs1Repository().requests(INN).isEmpty(), "The dialog returns a draft; preparing and sending remain separate actions");
    }

    @Test void changingGeneratedDocumentFieldsDropsThePdfAndItsReview() throws Exception {
        var editor = create(snapshot(0, 0)); generateSigned(editor);
        fx(() -> {
            review(editor).fire(); assertFalse(next(editor).isDisabled());
            text(editor, "gs1PrefixCompany").setText("Corrected Catalog Company");
            assertFalse(review(editor).isSelected()); assertTrue(review(editor).isDisabled());
            assertTrue(next(editor).isDisabled()); assertNull(image(editor).getImage());
            assertTrue(mail(editor).getText().contains("Corrected Catalog Company"));
            return null;
        });
    }

    @Test void changingCapacityOrMailCommentRequiresFreshReviewAndUsesTheNewMail() throws Exception {
        var editor = create(snapshot(0, 0)); generateSigned(editor);
        fx(() -> {
            review(editor).fire(); assertFalse(next(editor).isDisabled());
            quantity(editor, Quantity.EXTENDED).fire();
            assertFalse(review(editor).isSelected()); assertTrue(next(editor).isDisabled());
            assertNotNull(image(editor).getImage(), "Changing mail capacity does not alter the fixed application template");
            assertTrue(mail(editor).getText().contains("99999 кодов GTIN"));
            review(editor).fire(); assertFalse(next(editor).isDisabled());
            ((TextArea) find(dialog(editor).getDialogPane(), "gs1PrefixComment")).setText("Reviewed fixture comment");
            assertFalse(review(editor).isSelected()); assertTrue(next(editor).isDisabled());
            assertTrue(mail(editor).getText().contains("Reviewed fixture comment"));
            review(editor).fire(); next(editor).fire();
            assertEquals(Quantity.EXTENDED, dialog(editor).getResult().quantity());
            assertEquals("Reviewed fixture comment", dialog(editor).getResult().comment()); return null;
        });
    }

    @Test void newDialogMessagesAndControlsAreTranslatedInAllFourLanguages() throws Exception {
        fx(() -> {
            var i18n = I18nService.getInstance();
            for (var language : AppLanguage.values()) {
                i18n.setLanguage(language); var editor = createOnFx(snapshot(0, 8));
                for (var key : List.of("gs1.prefix.profile_source", "gs1.prefix.template_notice", "gs1.prefix.preview_notice",
                        "gs1.prefix.confirm", "gs1.prefix.import_word", "gs1.prefix.download_word", "gs1.prefix.preview_word",
                        "gs1.prefix.sign", "gs1.prefix.attach_signed", "gs1.prefix.quantity.STANDARD", "gs1.prefix.quantity.EXTENDED",
                        "gs1.prefix.codes_available", "gs1.prefix.unknown_quota", "gs1.prefix.unsigned_preview")) {
                    assertNotEquals(key, i18n.tr(key), language + " requires " + key);
                    assertFalse(i18n.tr(key).isBlank());
                }
                assertEquals(i18n.tr("gs1.prefix.quantity.STANDARD"), quantity(editor, Quantity.STANDARD).getText());
                assertEquals(i18n.tr("gs1.prefix.attach_signed"), button(editor, "gs1PrefixAttachSigned").getText());
            }
            return null;
        });
    }

    @Test void storedPrefixPdfRequiresReviewAndLegacyDraftWithoutCheckpointCannotSend() throws Exception {
        var repository = new Gs1Repository(); var signed = signedDocument();
        repository.saveMailAccount(INN, new Gs1MailAccount("smtp.fixture", 465, "imap.fixture", 993,
                "fixture", "owner@fixture.ru", "dpapi:fixture"));
        var profile = new Profile(DEFAULTS.legalName(), INN, DEFAULTS.gcps(), DEFAULTS.email(), DEFAULTS.phone(), DEFAULTS.signerName());
        var prepared = service.prepare(new RequestDraft(profile, Quantity.STANDARD, "Reviewed fixture", signed), snapshot(0, 0), true);
        var legacy = repository.createRequestWithAttachments(INN, "MORE_CODES", Gs1PrefixRequestService.RECIPIENT,
                "Legacy fixture", "Old unchecked draft", List.of(signed));
        var expected = new Image(new ByteArrayInputStream(new Gs1PrefixDocumentService().previewTemplate(signed.bytes()).png()));
        fx(() -> {
            var pane = new Gs1Pane(); new Scene(pane, 1000, 850);
            for (var value : java.util.Map.of("shop", new Shop(1, "Fixture", "fixture"), "inn", INN, "environment", "production").entrySet()) {
                var field = Gs1Pane.class.getDeclaredField(value.getKey()); field.setAccessible(true); field.set(pane, value.getValue());
            }
            try {
                inspectStoredPreview(pane, prepared, expected, true);
                inspectStoredPreview(pane, legacy, expected, false);
            } finally { pane.dispose(); }
            return null;
        });
        assertEquals(Gs1Repository.State.DRAFT, repository.request(INN, prepared.id()).state());
        assertEquals(Gs1Repository.State.DRAFT, repository.request(INN, legacy.id()).state());
        assertTrue(repository.messages(INN, prepared.id()).isEmpty());
        assertTrue(repository.messages(INN, legacy.id()).isEmpty());
    }

    /** The nested modal is inspected only after its actual stage and asynchronous PDF are visible. */
    private static void inspectStoredPreview(Gs1Pane pane, Gs1Repository.Request request, Image expected, boolean reviewedCheckpoint) throws Exception {
        var failure = new AtomicReference<Throwable>(); var inspected = new java.util.concurrent.atomic.AtomicBoolean();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var poll = new Timeline(); poll.setCycleCount(Timeline.INDEFINITE);
        poll.getKeyFrames().add(new KeyFrame(Duration.millis(50), event -> {
            for (var window : List.copyOf(Window.getWindows())) if (window instanceof Stage stage && stage.isShowing()) {
                var root = stage.getScene().getRoot();
                var candidate = root instanceof DialogPane ? root : root.lookup(".dialog-pane");
                if (!(candidate instanceof DialogPane dialog)) continue;
                var image = (ImageView) find(dialog, "gs1PrefixStoredPdf");
                if (image == null) continue;
                if (image.getImage() == null && System.nanoTime() < until) continue;
                try {
                    assertNotNull(image.getImage(), "Stored PDF preview must finish while the actual modal is shown");
                    var actual = image.getImage(); assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
                    for (int y = 0; y < expected.getHeight(); y += 5) for (int x = 0; x < expected.getWidth(); x += 5)
                        assertEquals(expected.getPixelReader().getArgb(x, y), actual.getPixelReader().getArgb(x, y), "Preview must render the frozen attachment");
                    var confirmation = (CheckBox) find(dialog, "gs1PrefixStoredPdfReview");
                    assertFalse(confirmation.isDisabled()); assertFalse(confirmation.isSelected());
                    var sendType = dialog.getButtonTypes().stream().filter(type -> type.getText().equals(I18nService.getInstance().tr("gs1.send"))).findFirst().orElseThrow();
                    var send = (Button) dialog.lookupButton(sendType); assertTrue(send.isDisabled());
                    confirmation.fire(); assertEquals(!reviewedCheckpoint, send.isDisabled(), "Legacy drafts lack the durable PDF review checkpoint");
                    inspected.set(true);
                } catch (Throwable problem) { failure.set(problem); }
                finally { poll.stop(); ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire(); }
                return;
            }
            if (System.nanoTime() >= until) {
                failure.set(new AssertionError("Stored PDF preview dialog did not appear")); poll.stop();
                for (var window : List.copyOf(Window.getWindows())) if (window instanceof Stage stage && stage.isShowing()) stage.close();
            }
        }));
        var show = Gs1Pane.class.getDeclaredMethod("previewLetter", Gs1Repository.Request.class); show.setAccessible(true);
        poll.play(); try { show.invoke(pane, request); } finally { poll.stop(); }
        if (failure.get() != null) throw new AssertionError("Stored PDF preview failed", failure.get());
        assertTrue(inspected.get());
    }

    private Gs1PrefixRequestDialog create(NationalCatalogGs1Client.AccountSnapshot source) throws Exception {
        return fx(() -> createOnFx(source));
    }
    private Gs1PrefixRequestDialog createOnFx(NationalCatalogGs1Client.AccountSnapshot source) {
        var editor = new Gs1PrefixRequestDialog(null, DEFAULTS, service, service.preflight(INN, source, Instant.now()));
        opened.add(editor); return editor;
    }
    private static NationalCatalogGs1Client.AccountSnapshot snapshot(Integer first, Integer second) {
        var form = new JsonObject(); var applicant = new JsonObject(); applicant.addProperty("inn", INN); form.add("applicant", applicant);
        var date = LocalDate.now(ZoneId.of("Europe/Moscow"));
        var member = new Gs1Membership(INN, DEFAULTS.legalName(), true, false, date.plusYears(1),
                List.of(new Gs1Membership.Prefix("4601234", first, List.of()), new Gs1Membership.Prefix("4605678", second, List.of())),
                Instant.now(), Gs1Membership.CATALOG_SOURCE);
        return new NationalCatalogGs1Client.AccountSnapshot(member, form, false);
    }
    private void installTemplate(Gs1PrefixRequestDialog editor) throws Exception {
        var source = template();
        fx(() -> { field(editor, "template", source); invoke(editor, "updateButtons"); return null; });
    }
    private void generateSigned(Gs1PrefixRequestDialog editor) throws Exception {
        installTemplate(editor);
        fx(() -> {
            var ink = (Gs1SignatureInk) field(editor, "ink"); drawSignature(ink);
            invoke(editor, "updateButtons"); button(editor, "gs1PrefixCreateSignedPdf").fire(); return null;
        }); awaitIdle(editor);
        fx(() -> { assertNotNull(field(editor, "document"), "Signed generation must produce a real attachment"); return null; });
    }
    private static Gs1MailClient.Attachment signedDocument() throws Exception {
        try (var ink = new Gs1SignatureInk()) {
            drawSignature(ink);
            return new Gs1WordPrefixService().signedPdf(template(), new Gs1WordPrefixService.Fields(DEFAULTS.legalName(), INN,
                    DEFAULTS.gcps(), LocalDate.now(ZoneId.of("Europe/Moscow")), DEFAULTS.signerName()), ink);
        }
    }
    private static void drawSignature(Gs1SignatureInk ink) {
        ink.start(.1, .6); ink.append(.25, .2); ink.append(.45, .75); ink.append(.75, .25); ink.end();
    }
    private static byte[] template() throws Exception {
        try (var word = new XWPFDocument()) {
            for (var value : List.of("Директору", "Ассоциации", "идентификации ГС1 РУС", "Получателю", "", "ЗАЯВЛЕНИЕ",
                    "OLD FIXTURE (ИНН 7811089030, номер предприятия в ЮНИСКАН/ГС1 РУС: 4601111) просит предоставить дополнительный префикс в связи с окончанием ранее присвоенного диапазона.",
                    "", "«1» января 2020 г.", "OLD FIXTURE SIGNER     ____________________",
                    "Фамилия, имя, отчество                          подпись", "М.П.")) {
                var run = word.createParagraph().createRun(); run.setFontFamily("Times New Roman"); run.setFontSize(12); run.setText(value);
            }
            var output = new ByteArrayOutputStream(); word.write(output); return output.toByteArray();
        }
    }
    /** Only document injection is used for quota tests; generation and confirmation are tested above. */
    private static void installReviewedDocument(Gs1PrefixRequestDialog editor, Gs1MailClient.Attachment signed) throws Exception {
        field(editor, "document", signed); field(editor, "actualPreview", true); field(editor, "wordDocument", true);
        invoke(editor, "updateButtons"); review(editor).setSelected(true);
    }
    private static void awaitIdle(Gs1PrefixRequestDialog editor) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            if (fx(() -> !(boolean) field(editor, "processing"))) return;
            Thread.sleep(10);
        }
        fail("PDF generation did not finish");
    }
    @SuppressWarnings("unchecked") private static Dialog<RequestDraft> dialog(Gs1PrefixRequestDialog editor) throws Exception {
        return (Dialog<RequestDraft>) field(editor, "dialog");
    }
    private static TextField text(Gs1PrefixRequestDialog editor, String id) throws Exception { return (TextField) find(dialog(editor).getDialogPane(), id); }
    private static Button button(Gs1PrefixRequestDialog editor, String id) throws Exception { return (Button) find(dialog(editor).getDialogPane(), id); }
    private static RadioButton quantity(Gs1PrefixRequestDialog editor, Quantity choice) throws Exception { return (RadioButton) find(dialog(editor).getDialogPane(), "gs1PrefixQuantity" + choice.name()); }
    private static TextArea mail(Gs1PrefixRequestDialog editor) throws Exception { return (TextArea) find(dialog(editor).getDialogPane(), "gs1PrefixMailPreview"); }
    private static ImageView image(Gs1PrefixRequestDialog editor) throws Exception { return (ImageView) find(dialog(editor).getDialogPane(), "gs1PrefixActualPdf"); }
    private static CheckBox review(Gs1PrefixRequestDialog editor) throws Exception { return (CheckBox) find(dialog(editor).getDialogPane(), "gs1PrefixReviewConfirmation"); }
    /** Controls expose content separately until their JavaFX skins are created, including collapsed panes. */
    private static Node find(Node node, String id) {
        if (node == null) return null; if (id.equals(node.getId())) return node;
        var children = new ArrayList<Node>();
        if (node instanceof ScrollPane scroll) children.add(scroll.getContent());
        if (node instanceof SplitPane split) children.addAll(split.getItems());
        if (node instanceof TitledPane titled) children.add(titled.getContent());
        if (node instanceof DialogPane dialog) children.add(dialog.getContent());
        if (node instanceof Parent parent) children.addAll(parent.getChildrenUnmodifiable());
        for (var child : children) { var found = find(child, id); if (found != null) return found; }
        return null;
    }
    private static Button next(Gs1PrefixRequestDialog editor) throws Exception { return (Button) dialog(editor).getDialogPane().lookupButton((ButtonType) field(editor, "next")); }
    private static Object field(Gs1PrefixRequestDialog editor, String name) throws Exception { var value = declared(name); return value.get(editor); }
    private static void field(Gs1PrefixRequestDialog editor, String name, Object value) throws Exception { declared(name).set(editor, value); }
    private static Field declared(String name) throws Exception { var field = Gs1PrefixRequestDialog.class.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void invoke(Gs1PrefixRequestDialog editor, String method) throws Exception { var action = Gs1PrefixRequestDialog.class.getDeclaredMethod(method); action.setAccessible(true); action.invoke(editor); }
    private static <T> T fx(Callable<T> work) throws Exception { var task = new FutureTask<>(work); Platform.runLater(task); return task.get(25, TimeUnit.SECONDS); }
}
