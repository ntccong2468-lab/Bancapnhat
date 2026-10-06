package com.tuandev.fbsbarcode.integration.ozon;

import com.tuandev.fbsbarcode.features.print.history.PrintHistoryItem;
import com.tuandev.fbsbarcode.features.print.history.PrintHistoryRepository;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Records an Ozon bundle without duplicating KIZ values or enabling the WB reprint workflow. */
public final class OzonPrintHistoryService {
    private static final int MAX_ITEM_COUNT = 100_000;
    private static final String TEMPLATE_NAME = "Ozon FBS official label + KIZ bundle";
    private static final String TEMPLATE_LAYOUT = "{\"kind\":\"OZON_FBS_BUNDLE\",\"version\":1}";
    private final PrintHistoryRepository repository;

    public OzonPrintHistoryService() {
        this(new PrintHistoryRepository());
    }

    OzonPrintHistoryService(PrintHistoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public long recordSuccessfulJob(Shop shop, OzonPostingDto posting, Instant printedAt) {
        MarketplaceGuard.requireOzon(shop);
        Objects.requireNonNull(posting, "posting");
        Objects.requireNonNull(printedAt, "printedAt");

        List<PrintHistoryItem> items = new ArrayList<>(posting.items().size());
        int itemCount = 0;
        int sortIndex = 0;
        String externalOrderId = firstNonBlank(posting.orderId(), posting.orderNumber());
        for (OzonPostingItemDto item : posting.items()) {
            itemCount = Math.addExact(itemCount, item.quantity());
            if (itemCount > MAX_ITEM_COUNT) {
                throw new IllegalArgumentException("Ozon print history item count is too large");
            }
            items.add(new PrintHistoryItem(
                    0L,
                    sortIndex++,
                    0L,
                    null,
                    item.name(),
                    null,
                    null,
                    null,
                    null,
                    item.offerId(),
                    item.sku(),
                    null,
                    null,
                    null,
                    null,
                    externalOrderId,
                    firstNonBlank(item.productId(), item.sku(), item.offerId())
            ));
        }
        String displayOrder = firstNonBlank(posting.orderNumber(), posting.orderId(), posting.postingNumber());
        return repository.insertSuccessfulJob(
                shop.getId(),
                shop.getName(),
                "OZON",
                posting.postingNumber(),
                "Ozon FBS " + displayOrder,
                printedAt.toString(),
                itemCount,
                null,
                TEMPLATE_NAME,
                TEMPLATE_LAYOUT,
                items
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
