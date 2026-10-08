package com.vncode.app.ui.report;

import com.vncode.app.BuildConfig;
import com.vncode.app.integration.znack.ZnackErrorMessages;
import com.vncode.app.shared.AlertService;

/** Local diagnostics for the owner to review and copy; no vendor reporting service. */
public final class ErrorReportDialog {
    private ErrorReportDialog() {}

    /** Opens a copyable diagnostic dialog on the FX thread. */
    public static void show(String shopName, String entity, String action, String rawError) {
        String message = ZnackErrorMessages.display(rawError);
        String errorCode = ZnackErrorMessages.errorCode(rawError);
        String details = String.join("\n", BuildConfig.getAppName() + " " + BuildConfig.getAppVersion(),
                shopName == null ? "" : shopName, entity == null ? "" : entity,
                action == null ? "" : action, errorCode, message);
        AlertService.showDetailedError(message, details);
    }
}
