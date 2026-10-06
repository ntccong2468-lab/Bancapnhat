package com.tuandev.fbsbarcode.integration.znack;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies a Национальный каталог (НКМТ) product-card status. Only a {@code published} card
 * (signed with УКЭП) can be used to order KIZ; every other status — draft, on moderation, "requires
 * changes" ({@code errors} / Требует изменений, e.g. no trademark), awaiting signature, or archived
 * — is not orderable, so such cards must never be stored or pushed to the seller portal.
 *
 * <p>True API delivers machine codes ({@code good_status} / {@code good_detailed_status}); the
 * Russian labels are matched too as a safety net. Unknown/blank statuses are treated as publishable
 * so an unrecognised value never silently wipes the catalog. See Catalog_of_Marked_Goods_API:
 * {@code good_detailed_status}.
 */
public final class ZnackCardStatus {
    private static final Set<String> NON_PUBLISHED = Set.of(
            "draft", "moderation", "errors", "notsigned", "archived",
            "черновик", "на модерации", "требует изменений", "ожидает подписания", "в архиве", "архив");

    private ZnackCardStatus() {
    }

    /** True when either status field reports a known non-published (non-orderable) state. */
    public static boolean isErrored(String cardStatus, String cardDetailedStatus) {
        return isNonPublished(cardStatus) || isNonPublished(cardDetailedStatus);
    }

    private static boolean isNonPublished(String status) {
        return status != null && NON_PUBLISHED.contains(status.strip().toLowerCase(Locale.ROOT));
    }
}
