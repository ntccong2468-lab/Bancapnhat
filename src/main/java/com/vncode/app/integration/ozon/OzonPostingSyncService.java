package com.vncode.app.integration.ozon;

import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
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
        LicenseRuntime.require(SELLER_API);
        api.requireSyncOwner(shopId);
        Instant end = Instant.now();
        Instant start = startFor(state.find(shopId), end);
        int postingCount = 0;
        int itemCount = 0;
        String cursor = "";
        var cursors=new java.util.HashSet<String>();
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("OZON_SYNC_CANCELLED");
                LicenseRuntime.require(SELLER_API);
                OzonJson.PostingPage page = OzonJson.requirePostingPage(
                        api.listPostings(start.toString(), end.toString(), cursor, PAGE_SIZE));
                if (page.hasNext() && !cursors.add(page.cursor())) throw new IOException("FBS_INVALID_CURSOR");
                api.requireSyncOwner(shopId);
                postings.upsertPage(shopId, page.postings());
                postingCount += page.postings().size();
                itemCount += page.postings().stream().mapToInt(posting -> posting.items().size()).sum();
                if (!page.hasNext()) {
                    state.advancePostings(shopId, end.toString());
                    return new OzonSyncReport(0, postingCount, itemCount);
                }
                cursor = page.cursor();
            }
            throw new IOException("Ozon postings exceeded the safe pagination bound.");
        } catch (LicenseDeniedException paused) {
            throw paused;
        } catch (OzonApiException exception) {
            reportFailure(exception.kind(),exception);
            throw exception;
        } catch (IOException exception) {
            reportFailure("invalid_response",exception);
            throw exception;
        } catch (RuntimeException exception) {
            reportFailure("local_storage",exception);
            throw exception;
        }
    }

    /** Refreshes Ozon's current actionable FBS queue, including changes made outside VN code. */
    public int syncUnfulfilled() throws IOException {
        LicenseRuntime.require(SELLER_API);
        api.requireSyncOwner(shopId);
        Instant now = Instant.now();
        String cutoffFrom = now.minus(ACTIVE_CUTOFF_PAST).toString();
        String cutoffTo = now.plus(ACTIVE_CUTOFF_FUTURE).toString();
        int postingCount = 0;
        String cursor = "";
        var cursors=new java.util.HashSet<String>();
        try {
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("OZON_SYNC_CANCELLED");
                LicenseRuntime.require(SELLER_API);
                OzonJson.PostingPage page = OzonJson.requirePostingPage(api.listUnfulfilledPostings(
                        cutoffFrom, cutoffTo, cursor, PAGE_SIZE));
                if (page.hasNext() && !cursors.add(page.cursor())) throw new IOException("FBS_INVALID_CURSOR");
                api.requireSyncOwner(shopId);
                postings.upsertPage(shopId, page.postings());
                postingCount += page.postings().size();
                if (!page.hasNext()) {
                    return postingCount;
                }
                cursor = page.cursor();
            }
            throw new IOException("Ozon unfulfilled postings exceeded the safe pagination bound.");
        } catch (LicenseDeniedException paused) {
            throw paused;
        } catch (OzonApiException exception) {
            reportFailure(exception.kind(),exception);
            throw exception;
        } catch (IOException exception) {
            reportFailure("invalid_response",exception);
            throw exception;
        } catch (RuntimeException exception) {
            reportFailure("local_storage",exception);
            throw exception;
        }
    }

    public OzonPostingDto refresh(String postingNumber, boolean withExemplars) throws IOException {
        LicenseRuntime.require(SELLER_API);
        api.requireSyncOwner(shopId);
        OzonPostingDto posting = OzonJson.requirePostingDetail(api.getPosting(postingNumber, withExemplars),postingNumber);
        api.requireSyncOwner(shopId);
        postings.upsertDetail(shopId, posting);
        return posting;
    }

    /**
     * Refreshes actionable postings through the detail endpoint. The list endpoint is useful for
     * queue discovery, but the detail response is the authority used for KIZ requirements.
     */
    public int refreshActiveDetails() throws IOException {
        LicenseRuntime.require(SELLER_API);
        int refreshed = 0;String cursor="";
        for(int page=0;page<MAX_PAGES;page++){
            List<String> active=postings.findActiveNumbersAfter(shopId,cursor,PAGE_SIZE);
            if(active.isEmpty())return refreshed;
            for(String number:active){if(Thread.currentThread().isInterrupted())throw new IOException("Ozon detail refresh interrupted");LicenseRuntime.require(SELLER_API);refresh(number,false);refreshed++;}
            cursor=active.getLast();
        }
        throw new IOException("Ozon active detail pagination limit exceeded");
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
    private void reportFailure(String code,Exception failure){
        try{state.recordSafeError(shopId,code);}catch(RuntimeException reporting){failure.addSuppressed(reporting);}
    }

}
