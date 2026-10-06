package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.shared.AppPaths;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;

/** Produces a copyable, secret-redacted diagnostic report for Znack failures. */
public final class ZnackErrorDetails {
    private ZnackErrorDetails() {
    }

    public static String summary(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = ZnackSanitizer.message(error.getMessage());
        return message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public static String format(Throwable error) {
        if (error == null) {
            return contextHeader() + "\n\nUnknown error";
        }

        StringBuilder details = new StringBuilder("Summary: ").append(summary(error))
                .append("\n").append(contextHeader());
        details.append("\n\nERROR CHAIN");
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            details.append("\n").append(depth + 1).append(". ")
                    .append(current.getClass().getName()).append(": ")
                    .append(current.getMessage() == null ? "" : current.getMessage());
            if (current instanceof ZnackApiClient.ZnackApiException apiError) {
                details.append("\n").append(apiError.diagnosticDetails());
            }
        }

        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        details.append("\n\nSTACK TRACE\n").append(stackTrace);
        return ZnackSanitizer.diagnostic(details.toString());
    }

    public static String formatStored(String details) {
        String safeDetails = ZnackSanitizer.diagnostic(details == null ? "" : details);
        if (safeDetails.startsWith("Summary:") || safeDetails.startsWith("WCode version:")) {
            return safeDetails;
        }
        return "Summary: " + (safeDetails.isBlank() ? "Unknown error" : safeDetails)
                + "\n" + contextHeader() + "\n\nERROR\n" + safeDetails;
    }

    private static String contextHeader() {
        return "WCode version: " + BuildConfig.getAppVersion()
                + "\nBuild profile: " + (AppPaths.isZnackRegistrationTestProfile() ? "znack-registration-test" : "production")
                + "\nTime: " + OffsetDateTime.now()
                + "\nOS: " + System.getProperty("os.name", "") + " " + System.getProperty("os.version", "")
                + "\nJava: " + System.getProperty("java.version", "");
    }
}
