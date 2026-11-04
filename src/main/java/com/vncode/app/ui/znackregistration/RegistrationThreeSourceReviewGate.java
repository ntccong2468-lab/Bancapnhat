package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Read-only review gate shared by the registration UI and regression tests.
 * Never guesses missing identities and never authorizes a remote mutation.
 */
public final class RegistrationThreeSourceReviewGate {
    private RegistrationThreeSourceReviewGate() {}

    public record Blocked(String article, long nmId, long chrtId, String status, String reason) {}

    public static List<Blocked> blocked(List<Sku> selected,
                                        Predicate<Sku> notSubmitted,
                                        Predicate<Sku> reviewableDraft,
                                        Predicate<Sku> reviewableReplacement) {
        List<Blocked> result = new ArrayList<>();
        for (Sku row : List.copyOf(selected)) {
            if (notSubmitted.test(row) || reviewableDraft.test(row) || reviewableReplacement.test(row)) continue;
            String reason;
            if (row.hasFeed() && row.goodId() == null) {
                reason = "FEED_EXISTS_GOOD_ID_UNKNOWN";
            } else if (row.goodId() != null && row.goodId() > 0 && !row.hasFeed()) {
                reason = "CATALOG_ID_WITHOUT_FEED";
            } else if (!row.hasGtin()) {
                reason = "GTIN_IDENTITY_UNRESOLVED";
            } else {
                reason = "REGISTRATION_STATE_REQUIRES_RECONCILIATION";
            }
            result.add(new Blocked(row.vendorCode(), row.nmId(), row.chrtId(),
                    row.status().name(), reason));
        }
        return List.copyOf(result);
    }

    public static String warning(List<Blocked> blocked) {
        if (blocked.isEmpty()) return "";
        StringBuilder message = new StringBuilder(
                "Не удалось подтвердить источник карточки (WB / VN Code / Честный ЗНАК).\n"
              + "Операция не отправлена. Сначала синхронизируйте источники и проверьте карточки:\n");
        int shown = Math.min(blocked.size(), 20);
        for (int i = 0; i < shown; i++) {
            Blocked item = blocked.get(i);
            message.append("\n• ").append(safe(item.article())).append(" (nmId=")
                    .append(item.nmId()).append(", chrtId=").append(item.chrtId())
                    .append(") · ").append(item.status()).append(" · ").append(item.reason());
        }
        if (blocked.size() > shown) message.append("\n... и ещё ").append(blocked.size() - shown);
        message.append("\n\nНе создавайте новый GTIN, пока не проверен статус существующей карточки.");
        return message.toString();
    }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "(без артикула)";
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }
}
