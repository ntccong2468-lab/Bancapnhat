package com.vncode.app.ui.license;

import com.vncode.app.features.license.LicenseModels.Snapshot;
import com.vncode.app.features.license.LicenseModels.Status;
import com.vncode.app.features.license.LicenseService;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Gmail registration and activation; all network and secret storage work belongs to the service. */
public final class LicensePane extends ScrollPane {
    interface Actions {
        Snapshot snapshot();
        AutoCloseable subscribe(Consumer<Snapshot> listener);
        CompletableFuture<Snapshot> startRegistration(String email);
        CompletableFuture<Snapshot> checkStatus();
        CompletableFuture<Snapshot> activate(String code);
        CompletableFuture<Snapshot> refresh();
        CompletableFuture<Snapshot> logout();
    }

    private final Actions actions;
    private final I18nService i18n = I18nService.getInstance();
    private final Label title = label("licenseTitle", "welcome-title");
    private final Label description = label("licenseDescription", "text-secondary");
    private final Label registrationTitle = label("licenseRegistrationTitle", "h3");
    private final Label gmailLabel = label("licenseGmailLabel", "text-secondary");
    private final TextField gmail = new TextField();
    private final Button register = button("licenseRegister", true);
    private final Label registeredEmail = label("licenseRegisteredEmail", "text-secondary");
    private final Label status = label("licenseAccountStatus", "h3");
    private final Label expiry = label("licenseExpiry", "text-secondary");
    private final Label offlineExpiry = label("licenseOfflineExpiry", "text-secondary");
    private final Label limits = label("licenseLimits", "text-secondary");
    private final Label activationTitle = label("licenseActivationTitle", "h3");
    private final Label activationHelp = label("licenseActivationHelp", "text-secondary");
    private final PasswordField license = new PasswordField();
    private final Button activate = button("licenseActivate", true);
    private final Button check = button("licenseCheck", false);
    private final Button refresh = button("licenseRefresh", false);
    private final Button reset = button("licenseReset", false);
    private final Label feedback = label("licenseFeedback", "text-danger");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final AutoCloseable subscription;
    private volatile boolean disposed;
    private boolean requestInFlight;
    private long generation;
    private String localError;

    public LicensePane(LicenseService service) {
        this(new Actions() {
            public Snapshot snapshot() { return service.snapshot(); }
            public AutoCloseable subscribe(Consumer<Snapshot> listener) { return service.subscribe(listener); }
            public CompletableFuture<Snapshot> startRegistration(String email) { return service.startRegistration(email); }
            public CompletableFuture<Snapshot> checkStatus() { return service.checkStatus(); }
            public CompletableFuture<Snapshot> activate(String value) { return service.activate(value); }
            public CompletableFuture<Snapshot> refresh() { return service.refresh(); }
            public CompletableFuture<Snapshot> logout() { return service.logout(); }
        });
    }

    LicensePane(Actions actions) {
        this.actions = Objects.requireNonNull(actions);
        setId("licensePane");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        gmail.setId("licenseGmail");
        gmail.setMaxWidth(560);
        license.setId("licenseKey");
        license.setMaxWidth(560);
        progress.setId("licenseProgress");
        progress.setPrefSize(24, 24);
        progress.setMaxSize(24, 24);

        VBox registration = card(registrationTitle, gmailLabel, gmail, register);
        VBox account = card(registeredEmail, status, expiry, offlineExpiry, limits,
                new FlowPane(10, 10, check, refresh, reset));
        VBox activation = card(activationTitle, activationHelp, license, activate);
        VBox body = new VBox(12, title, description, registration, account, activation, progress, feedback);
        body.setPadding(new Insets(16));
        body.setMinWidth(0);
        setContent(body);

        gmail.textProperty().addListener((observable, oldValue, newValue) -> updateAvailability(actions.snapshot()));
        license.textProperty().addListener((observable, oldValue, newValue) -> updateAvailability(actions.snapshot()));
        register.setOnAction(event -> run(() -> actions.startRegistration(gmail.getText().trim())));
        activate.setOnAction(event -> {
            String value = license.getText().trim();
            license.clear();
            run(() -> actions.activate(value));
        });
        check.setOnAction(event -> run(actions::checkStatus));
        refresh.setOnAction(event -> run(actions::refresh));
        reset.setOnAction(event -> confirmReset());
        subscription = actions.subscribe(ignored -> onFx(() -> render(actions.snapshot())));
        applyTranslations();
    }

    public void applyTranslations() {
        if (disposed) return;
        title.setText(tr("title"));
        description.setText(tr("description"));
        registrationTitle.setText(tr("registration"));
        gmailLabel.setText(tr("gmail"));
        gmail.setPromptText(tr("gmail.prompt"));
        register.setText(tr("register"));
        activationTitle.setText(tr("activation"));
        activationHelp.setText(tr("activation.help"));
        license.setPromptText(tr("key.prompt"));
        activate.setText(tr("activate"));
        check.setText(tr("check"));
        refresh.setText(tr("refresh"));
        reset.setText(tr("reset"));
        render(actions.snapshot());
    }

    private void render(Snapshot snapshot) {
        if (disposed) return;
        String email = snapshot.email() == null ? "" : snapshot.email();
        if (!email.isBlank() && !email.equals(gmail.getText())) gmail.setText(email);
        if (snapshot.status() == Status.UNREGISTERED && email.isBlank() && !gmail.isEditable()) gmail.clear();
        registeredEmail.setText(email.isBlank() ? tr("email.none") :
                format(snapshot.status() == Status.VERIFYING_EMAIL ? "email.verifying" : "email.registered", email));
        status.setText(statusText(snapshot.status()));
        expiry.setText(snapshot.expiresAt() > 0 ? format("expires", date(snapshot.expiresAt())) : "");
        visible(expiry, snapshot.expiresAt() > 0);
        offlineExpiry.setText(snapshot.offlineExpiresAt() > 0 ? format("offline.expires", date(snapshot.offlineExpiresAt())) : "");
        visible(offlineExpiry, snapshot.status() == Status.OFFLINE && snapshot.offlineExpiresAt() > 0);
        limits.setText(format("limits", snapshot.maxDevices(), snapshot.maxShops()));
        visible(limits, snapshot.maxDevices() > 0 && snapshot.maxShops() > 0);
        String code = localError != null ? localError : snapshot.errorCode();
        feedback.setText(errorText(code));
        visible(feedback, code != null && !code.isBlank());
        updateAvailability(snapshot);
    }

    private void updateAvailability(Snapshot snapshot) {
        boolean busy = disposed || requestInFlight || snapshot.busy();
        boolean registering = snapshot.status() == Status.UNREGISTERED || snapshot.status() == Status.REGISTERING
                || snapshot.status() == Status.VERIFYING_EMAIL
                || snapshot.status() == Status.UNVERIFIED && snapshot.expiresAt() == 0
                || "REGISTRATION_EXPIRED".equals(snapshot.errorCode());
        boolean registered = !registering && snapshot.email() != null && !snapshot.email().isBlank();
        gmail.setEditable(registering && !busy);
        gmail.setDisable(busy);
        register.setDisable(busy || !registering || gmail.getText().trim().isEmpty());
        license.setDisable(busy);
        activate.setDisable(busy || license.getText().trim().isEmpty());
        boolean hasLease = snapshot.expiresAt() > 0 || snapshot.status() == Status.ACTIVE || snapshot.status() == Status.OFFLINE;
        check.setDisable(busy || !registered || hasLease);
        refresh.setDisable(busy || !hasLease);
        reset.setDisable(busy || snapshot.status() == Status.UNREGISTERED);
        visible(progress, busy && !disposed);
    }

    private void run(Supplier<CompletableFuture<Snapshot>> operation) {
        if (disposed || requestInFlight || actions.snapshot().busy()) return;
        requestInFlight = true;
        localError = null;
        long request = ++generation;
        render(actions.snapshot());
        try {
            operation.get().whenComplete((result, failure) -> onFx(() -> {
                if (request != generation) return;
                requestInFlight = false;
                if (failure != null) localError = "unexpected";
                render(actions.snapshot());
            }));
        } catch (RuntimeException failure) {
            requestInFlight = false;
            localError = "unexpected";
            render(actions.snapshot());
        }
    }

    private void confirmReset() {
        ButtonType confirm = new ButtonType(tr("reset"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, tr("reset.help"), confirm, cancel);
        dialog.setTitle(tr("title"));
        dialog.setHeaderText(tr("reset.confirm"));
        if (getScene() != null && getScene().getWindow() != null) dialog.initOwner(getScene().getWindow());
        if (dialog.showAndWait().orElse(cancel) == confirm) run(actions::logout);
    }

    private void onFx(Runnable action) {
        if (disposed) return;
        Runnable guarded = () -> { if (!disposed) action.run(); };
        if (Platform.isFxApplicationThread()) guarded.run();
        else Platform.runLater(guarded);
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        generation++;
        license.clear();
        try { subscription.close(); } catch (Exception ignored) { }
    }

    public static String statusText(Status value) {
        return I18nService.getInstance().tr("license.account.status." + value.name().toLowerCase(Locale.ROOT));
    }

    private String errorText(String code) {
        if (code == null || code.isBlank()) return "";
        String normalized = switch (code.toUpperCase(Locale.ROOT)) {
            case "LICENSE_NETWORK" -> "network";
            case "LICENSE_UNAVAILABLE" -> "unavailable";
            case "LICENSE_HTTP_ERROR" -> "http_error";
            case "LICENSE_REDIRECT_REJECTED" -> "redirect_rejected";
            case "LICENSE_BUSY" -> "busy";
            case "LICENSE_RECHECK_REQUIRED" -> "saved_registration";
            case "LICENSE_CHANGED" -> "device_limit_or_license_changed";
            case "LICENSE_EXPIRED" -> "subscription_inactive";
            case "LICENSE_TRUST_NOT_CONFIGURED" -> "trust_not_configured";
            case "LICENSE_CLOCK_ROLLBACK" -> "clock";
            case "LICENSE_CLOCK_SKEW" -> "clock_skew";
            case "LICENSE_STORE_INVALID", "LICENSE_DEVICE_KEY_INVALID" -> "saved_registration";
            case "LICENSE_PROTOCOL_INVALID" -> "verification";
            default -> code.toLowerCase(Locale.ROOT);
        };
        String translated = i18n.tr("license.account.error." + normalized, "");
        // Show only recognized protocol codes, never upstream messages or credentials.
        if (translated.isBlank()) return tr("error.unexpected");
        String diagnostic = code.toUpperCase(Locale.ROOT);
        return diagnostic.matches("[A-Z][A-Z0-9_]{1,80}")
                ? translated + " [" + diagnostic + "]" : tr("error.unexpected");
    }

    private String date(long epochSeconds) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(i18n.getCurrentLanguage().toLocale()).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds));
    }

    private String tr(String suffix) { return i18n.tr("license.account." + suffix); }
    private String format(String suffix, Object... args) { return new MessageFormat(tr(suffix), i18n.getCurrentLanguage().toLocale()).format(args); }
    private static void visible(javafx.scene.Node node, boolean value) { node.setVisible(value); node.setManaged(value); }
    private static VBox card(javafx.scene.Node... nodes) {
        VBox box = new VBox(12, nodes);
        box.setPadding(new Insets(20));
        box.setMinWidth(0);
        box.getStyleClass().add("card");
        return box;
    }
    private static Label label(String id, String style) {
        Label label = new Label();
        label.setId(id);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add(style);
        return label;
    }
    private static Button button(String id, boolean primary) {
        Button button = new Button();
        button.setId(id);
        button.getStyleClass().add(primary ? "btn-primary" : "btn-secondary");
        return button;
    }
}
