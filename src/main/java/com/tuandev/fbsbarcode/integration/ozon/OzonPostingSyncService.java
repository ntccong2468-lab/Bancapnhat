package com.tuandev.fbsbarcode.integration.ozon;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/** Rolling-overlap posting sync so remote status changes are observed without a fragile offset cursor. */
public final class OzonPostingSyncService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20_000;
    private static final Duration INITIAL_WINDOW = Duration.ofDays(30);
    private static final Duration OVERLAP = Duration.ofDays(3);
    private static final Duration ACTIVE_CUTOFF_PAST = Duration.ofDays(180);
    private static final Duration ACTIVE_CUTOFF_FUTURE = Duration.ofDays(180);

    private final int shopId;
    private final OzonApiClient api;
    private final OzonPostingRepository postings;
    private final OzonSyncStateRepository state;

    public OzonPostingSyncService(int shopId, OzonApiClient api) {
        this(shopId, api, new OzonPostingRepository(), new OzonSyncStateRepository());
    }

    OzonPostingSyncService(
            int shopId,
            OzonApiClient api,
            OzonPostingRepository postings,
            OzonSyncStateRepository state) {
        if (shopId <= 0) throw new IllegalArgumentException("shopId must be positive");
        this.shopId = shopId;
        this.api = Objects.requireNonNull(api, "api");
        this.postings = Objects.requireNonNull(postings, "postings");
        this.state = Objects.requireNonNull(state, "state");
    }

    public OzonSyncReport sync() throws IOException {
        Instant end = Instant.now();
        Instant start = startFor(state.find(shopId), end);
        int postingCount = 0;
        int itemCount = 0;
        String cursor = "";
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                OzonJson.PostingPage page = OzonJson.parsePostingPage(
                        api.listPostings(start.toString(), end.toString(), cursor, PAGE_SIZE));
                postings.upsertPage(shopId, page.postings());
                postingCount += page.postings().size();
                itemCount += page.postings().stream().mapToInt(posting -> posting.items().size()).sum();
                if (!page.hasNext()) {
                    state.advancePostings(shopId, end.toString());
                    return new OzonSyncReport(0, postingCount, itemCount);
                }
                if (page.postings().isEmpty() || page.cursor().isBlank() || page.cursor().equals(cursor)) {
                    throw new IOException("Ozon returned an invalid posting cursor.");
                }
                cursor = page.cursor();
            }
            throw new IOException("Ozon postings exceeded the safe pagination bound.");
        } catch (OzonApiException exception) {
            state.recordSafeError(shopId, exception.kind());
            throw exception;
        } catch (IOException exception) {
            state.recordSafeError(shopId, "invalid_response");
            throw exception;
        } catch (RuntimeException exception) {
            state.recordSafeError(shopId, "local_storage");
            throw exception;
        }
    }

    /** Refreshes Ozon's current actionable FBS queue, including changes made outside WCode. */
    public int syncUnfulfilled() throws IOException {
        Instant now = Instant.now();
        String cutoffFrom = now.minus(ACTIVE_CUTOFF_PAST).toString();
        String cutoffTo = now.plus(ACTIVE_CUTOFF_FUTURE).toString();
        int postingCount = 0;
        String cursor = "";
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                OzonJson.PostingPage page = OzonJson.parsePostingPage(api.listUnfulfilledPostings(
                        cutoffFrom, cutoffTo, cursor, PAGE_SIZE));
                postings.upsertPage(shopId, page.postings());
                postingCount += page.postings().size();
                if (!page.hasNext()) {
                    return postingCount;
                }
                if (page.postings().isEmpty() || page.cursor().isBlank() || page.cursor().equals(cursor)) {
                    throw new IOException("Ozon returned an invalid unfulfilled posting cursor.");
                }
                cursor = page.cursor();
            }
            throw new IOException("Ozon unfulfilled postings exceeded the safe pagination bound.");
        } catch (OzonApiException exception) {
            state.recordSafeError(shopId, exception.kind());
            throw exception;
        } catch (IOException exception) {
            state.recordSafeError(shopId, "invalid_response");
            throw exception;
        } catch (RuntimeException exception) {
            state.recordSafeError(shopId, "local_storage");
            throw exception;
        }
    }

    public OzonPostingDto refresh(String postingNumber, boolean withExemplars) throws IOException {
        OzonPostingDto posting = OzonJson.parsePostingDetail(api.getPosting(postingNumber, withExemplars));
        postings.upsertDetail(shopId, posting);
        return posting;
    }

    /**
     * Refreshes actionable postings through the detail endpoint. The list endpoint is useful for
     * queue discovery, but the detail response is the authority used for KIZ requirements.
     */
    public int refreshActiveDetails() throws IOException {
        List<OzonPostingDto> active = postings.findActive(shopId, 1000, 0);
        int refreshed = 0;
        for (OzonPostingDto posting : active) {
            refresh(posting.postingNumber(), false);
            refreshed++;
        }
        return refreshed;
    }

    private static Instant startFor(OzonSyncState state, Instant end) {
        if (state.postingsChangedSince() == null || state.postingsChangedSince().isBlank()) {
            return end.minus(INITIAL_WINDOW);
        }
        try {
            Instant candidate = Instant.parse(state.postingsChangedSince()).minus(OVERLAP);
            Instant oldest = end.minus(INITIAL_WINDOW);
            return candidate.isBefore(oldest) ? oldest : candidate;
        } catch (DateTimeParseException ignored) {
            return end.minus(INITIAL_WINDOW);
        }
    }
}
