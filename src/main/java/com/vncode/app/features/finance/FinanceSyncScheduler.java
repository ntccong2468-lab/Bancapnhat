package com.vncode.app.features.finance;

import com.vncode.app.features.print.KizAttachmentCoordinator;
import com.vncode.app.features.shop.ShopOperationCoordinator;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.wb.finance.WbAdvertisingApiClient;
import com.vncode.app.integration.wb.finance.WbAnalyticsApiException;
import com.vncode.app.integration.wb.finance.WbFinanceApiClient;
import com.vncode.app.integration.wb.finance.WbFinancePage;
import com.vncode.app.integration.ozon.OzonCredentials;
import com.vncode.app.integration.ozon.finance.OzonFinanceApiClient;
import com.vncode.app.integration.ozon.finance.OzonFinanceApiException;
import com.vncode.app.integration.ozon.finance.OzonFinancePage;
import com.vncode.app.models.Shop;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseRuntime;
import static com.vncode.app.features.license.LicenseModels.Permission.SELLER_API;
import com.vncode.app.shared.AppTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FinanceSyncScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceSyncScheduler.class);
    private static final Duration FINANCE_INTERVAL = Duration.ofSeconds(61);
    private static final Duration ADVERTISING_INTERVAL = Duration.ofSeconds(2);
    private static final Duration OZON_FINANCE_INTERVAL = Duration.ofSeconds(2);
    private static final Duration PERIODIC_INTERVAL = Duration.ofHours(6);
    private static final FinanceSyncScheduler INSTANCE = new FinanceSyncScheduler(
            new FinanceAnalyticsRepository(), new WbFinanceApiClient(), new WbAdvertisingApiClient(),
            new OzonFinanceApiClient(), Clock.systemUTC());

    private final FinanceAnalyticsRepository repository;
    private final WbFinanceApiClient financeClient;
    private final WbAdvertisingApiClient advertisingClient;
    private final OzonFinanceApiClient ozonFinanceClient;
    private final FinanceSyncPlanner planner = new FinanceSyncPlanner();
    private final FinancePenaltySyncStore penaltySync = new FinancePenaltySyncStore();
    private final Clock clock;
    private final Map<Integer, Shop> shops = new ConcurrentHashMap<>();
    private volatile Path shopsDatabasePath;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean workRunning = new AtomicBoolean();
    private Runnable afterOwnerCheckedBeforeCheckpoint = () -> {};

    void setAfterOwnerCheckedBeforeCheckpoint(Runnable callback) { afterOwnerCheckedBeforeCheckpoint = java.util.Objects.requireNonNull(callback); }

    FinanceSyncScheduler(FinanceAnalyticsRepository repository, WbFinanceApiClient financeClient,
                         WbAdvertisingApiClient advertisingClient, OzonFinanceApiClient ozonFinanceClient,
                         Clock clock) {
        this.repository = repository;
        this.financeClient = financeClient;
        this.advertisingClient = advertisingClient;
        this.ozonFinanceClient = ozonFinanceClient;
        this.clock = clock;
    }

    public static FinanceSyncScheduler getInstance() {
        return INSTANCE;
    }

    public void updateShops(Collection<Shop> currentShops) { updateShops(currentShops,true); }
    public void updateShops(Collection<Shop> currentShops,boolean ownScheduler) {
        Path databasePath = AnalyticsDatabase.databasePath().toAbsolutePath().normalize();
        shopsDatabasePath = databasePath;
        Map<Integer, Shop> snapshots = new ConcurrentHashMap<>();
        for (Shop shop : currentShops) {
            if (shop.isCredentialConfigured()) {
                snapshots.put(shop.getId(), snapshot(shop));
            }
        }
        shops.keySet().retainAll(snapshots.keySet());
        shops.putAll(snapshots);
        FinanceExecutor.executeSync(() -> {
            if (!databasePath.equals(AnalyticsDatabase.databasePath().toAbsolutePath().normalize())) return;
            try { LicenseRuntime.require(SELLER_API); } catch (LicenseDeniedException paused) { return; }
            Instant now = clock.instant();
            LocalDate today = LocalDate.now(clock.withZone(WeeklyFinanceSchedule.VIETNAM_ZONE));
            for (Shop shop : snapshots.values()) {
                try { ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                    ListingIdentity.require(shop); repository.ensureShopState(shop.getId(), shop.getMarketplace(), today, now); return null;
                }); } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { /* old owner */ }
            }
        });
        if(ownScheduler)start();
    }

    public void requestRecentSync(int shopId) {
        LicenseRuntime.require(SELLER_API);
        FinanceExecutor.executeSync(() -> { try { LicenseRuntime.require(SELLER_API); } catch (LicenseDeniedException paused) { return; } repository.requestRecentSync(shopId, clock.instant()); });
    }

    public void removeShop(int shopId, boolean deleteAnalytics) {
        shops.remove(shopId);
        if (deleteAnalytics) {
            FinanceExecutor.executeSync(() -> repository.deleteShopData(shopId));
        }
    }

    private void start() {
        if (started.compareAndSet(false, true)) {
            FinanceExecutor.scheduleWithFixedDelay(this::tick, 3, 5, TimeUnit.SECONDS);
        }
    }

    private void tick() {
        Path activePath = shopsDatabasePath;
        if (activePath == null
                || !activePath.equals(AnalyticsDatabase.databasePath().toAbsolutePath().normalize())) {
            shops.clear();
            return;
        }
        if (shops.isEmpty() || AppTaskExecutor.hasRunningTasks()
                || KizAttachmentCoordinator.getInstance().hasActiveJobs()
                || !workRunning.compareAndSet(false, true)) {
            return;
        }
        FinanceExecutor.executeSync(() -> {
            try {
                runOneRequest();
            } catch (LicenseDeniedException paused) {
                // Licensing pauses new requests without changing API error/retry state.
            } catch (RuntimeException exception) {
                LOGGER.warn("Finance analytics tick failed", exception);
            } finally {
                workRunning.set(false);
            }
        });
    }

    public void runCoordinatedStep(){
        if(shops.isEmpty()||!workRunning.compareAndSet(false,true))return;
        try{runOneRequest();}finally{workRunning.set(false);}
    }

    void runOneRequest() {
        LicenseRuntime.require(SELLER_API);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(WeeklyFinanceSchedule.VIETNAM_ZONE));
        // User-requested penalty ranges have their own persisted cursor. The same
        // seller/family budget is checked before either this or legacy finance.
        for (Shop shop : shops.values().stream().sorted(Comparator.comparingInt(Shop::getId)).toList()) {
            if (shop.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES) continue;
            try {
                var job=penaltySync.due(shop,now);
                if(job==null)continue;
                Instant legacy=repository.familyNextAllowedAt(shop.getId(),"FINANCE");
                Instant shared=penaltySync.nextAllowed(shop.getId(),"FINANCE");
                if ((legacy!=null&&legacy.isAfter(now))||(shared!=null&&shared.isAfter(now)))continue;
                syncPenalty(shop,job,now);
                return;
            } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { /* old owner */ }
        }
        List<PlannedShopWork> candidates = new ArrayList<>();
        for (Shop shop : shops.values()) {
            try { ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                ListingIdentity.require(shop);
                repository.ensureShopState(shop.getId(), shop.getMarketplace(), today, now);
                planner.next(repository.loadStates(shop.getId()), now, today)
                        .ifPresent(work -> candidates.add(new PlannedShopWork(shop, work)));
                return null;
            }); } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { /* old owner */ }
        }
        candidates.sort(Comparator.comparingInt(candidate -> candidate.work().priority()));
        for (PlannedShopWork candidate : candidates) {
            FinanceSyncState state = candidate.work().state();
            Instant allowed = repository.familyNextAllowedAt(candidate.shop().getId(), state.apiFamily());
            if (allowed != null && allowed.isAfter(now)) continue;
            if ("FINANCE".equals(state.apiFamily())) {
                Instant penaltyAllowed=penaltySync.nextAllowed(candidate.shop().getId(),"FINANCE");
                if(penaltyAllowed!=null&&penaltyAllowed.isAfter(now))continue;
            }
            execute(candidate.shop(), candidate.work(), now);
            return; // At most one HTTP request per scheduler tick: never burst analytics calls.
        }
    }

    private void execute(Shop shop, FinanceWorkItem work, Instant now) {
        LicenseRuntime.require(SELLER_API);
        Duration interval = switch (work.state().apiFamily()) {
            case "FINANCE" -> FINANCE_INTERVAL;
            case "ADVERTISING" -> ADVERTISING_INTERVAL;
            case "OZON_FINANCE" -> OZON_FINANCE_INTERVAL;
            default -> throw new IllegalStateException("Unsupported finance API family: " + work.state().apiFamily());
        };
        Instant nextAllowed = now.plus(interval);
        FinanceSyncState state;
        try {
            state = ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                ListingIdentity.require(shop);
                afterOwnerCheckedBeforeCheckpoint.run();
                FinanceSyncState current = work.state();
                if (work.newWindow()) {
                    repository.startWindow(current, work.phase(), work.from(), work.to(), now);
                    current = repository.loadState(shop.getId(), current.streamName());
                    if (current == null) return null;
                }
                repository.markAttempt(shop.getId(), current.streamName(), nextAllowed);
                if("FINANCE".equals(current.apiFamily())) penaltySync.extendRate(shop.getId(),"FINANCE",nextAllowed);
                return repository.loadState(shop.getId(), current.streamName());
            });
        } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { return; }
        if (state == null) return;
        try {
            LicenseRuntime.require(SELLER_API);
            switch (state.apiFamily()) {
                case "FINANCE" -> syncFinance(shop, state, nextAllowed, now);
                case "ADVERTISING" -> syncAdvertising(shop, state, nextAllowed, now);
                case "OZON_FINANCE" -> syncOzonFinance(shop, state, nextAllowed, now);
                default -> throw new IllegalStateException("Unsupported finance API family: " + state.apiFamily());
            }
        } catch (LicenseDeniedException paused) {
            throw paused;
        } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) {
            // The page/checkpoint belongs to an old owner. Never save it under the new one.
            return;
        } catch (RuntimeException exception) {
            FinanceSyncState failedState=state;
            try { ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
                ListingIdentity.require(shop); handleFailure(failedState, exception, now); return null;
            }); } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { /* old owner */ }
        }
    }

    private void syncFinance(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        requireCurrentOwner(shop);
        String period = "FINANCE_WEEKLY".equals(state.streamName()) ? "weekly" : "daily";
        WbFinancePage page = financeClient.loadPage(
                shop.getApiKey(), state.windowFrom(), state.windowTo(), state.cursor(), period);
        ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop);
            repository.upsertFinanceRows(shop.getId(), page.rows());
            new FinancePenaltyRepository().recordVerifiedPage(shop, page.rows(), clock.instant());
            if (page.endOfReport()) finishWindow(state, now);
            else repository.saveProgress(state, page.nextCursor(), nextAllowed, now);
            return null;
        });
    }

    private void syncAdvertising(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        requireCurrentOwner(shop);
        var rows=advertisingClient.loadCosts(shop.getApiKey(), state.windowFrom(), state.windowTo());
        ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop); repository.upsertAdvertisingRows(shop.getId(),rows); finishWindow(state, now); return null;
        });
    }

    private void syncOzonFinance(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        requireCurrentOwner(shop);
        OzonFinancePage page = ozonFinanceClient.loadPage(
                new OzonCredentials(shop.getClientId(), shop.getApiKey()),
                state.windowFrom(), state.windowTo(), state.cursor());
        ShopOperationCoordinator.withActiveShop(shop.getId(), () -> {
            ListingIdentity.require(shop);
            repository.upsertFinanceRows(shop.getId(), page.rows());
            new FinancePenaltyRepository().recordVerifiedPage(shop, page.rows(), clock.instant());
            if (page.endOfReport()) finishWindow(state, now);
            else repository.saveProgress(state, page.nextCursor(), nextAllowed, now);
            return null;
        });
    }

    private static void requireCurrentOwner(Shop shop) {
        ShopOperationCoordinator.withActiveShop(shop.getId(), () -> { ListingIdentity.require(shop); return null; });
    }

    private void syncPenalty(Shop shop, FinancePenaltySyncStore.Job job, Instant now) {
        requireCurrentOwner(shop);
        var attempt=penaltySync.begin(shop,job,now,now.plus(FINANCE_INTERVAL));
        if(attempt==null)return;
        try {
            requireCurrentOwner(shop);
            WbFinancePage page=financeClient.loadPage(shop.getApiKey(),attempt.from(),attempt.to(),attempt.cursor(),"daily");
            requireCurrentOwner(shop);
            if(page.confirmedTerminal()) penaltySync.commitPage(shop,attempt,page.rows(),page.nextCursor(),true,clock.instant());
            else if(page.endOfReport()) penaltySync.defer(shop,attempt,clock.instant().plus(FINANCE_INTERVAL),"WB_EMPTY_PAGE_UNCONFIRMED");
            else penaltySync.commitPage(shop,attempt,page.rows(),page.nextCursor(),false,clock.instant());
        } catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) {
            // Keep old-owner checkpoint isolated; a late response is never committed.
        } catch (RuntimeException failure) {
            Duration delay=Duration.ofMinutes(15); String code="WB_FINANCE_UNAVAILABLE";
            if(failure instanceof WbAnalyticsApiException api) {
                if(api.statusCode()==429) {
                    Duration declared=api.retryAfter();
                    delay=declared==null?Duration.ofHours(12):(declared.compareTo(FINANCE_INTERVAL)<0?FINANCE_INTERVAL:declared);
                    code=declared==null?"WB_429_DELAY_UNKNOWN":"WB_429_RETRY_AFTER";
                } else if(api.statusCode()==401||api.statusCode()==403) {
                    delay=Duration.ofHours(6); code="WB_FINANCE_PERMISSION";
                }
            } else if(failure instanceof IllegalArgumentException) code="WB_FINANCE_INVALID_PAGE";
            try { penaltySync.defer(shop,attempt,safeRetryAt(clock.instant(),delay),code); }
            catch (SecurityException | ShopOperationCoordinator.ShopUnavailableException changed) { /* old owner */ }
        }
    }

    private void finishWindow(FinanceSyncState state, Instant now) {
        if ("FINANCE_WEEKLY".equals(state.streamName())) {
            repository.completeWindow(state, "WEEKLY_RECONCILIATION", "IDLE", null, null,
                    WeeklyFinanceSchedule.nextDueAt(now), now);
            return;
        }
        if (state.streamName().endsWith("_RECENT")) {
            repository.completeWindow(state, "PERIODIC_3", "IDLE", null, null,
                    now.plus(PERIODIC_INTERVAL), now);
            return;
        }
        BackfillNext next = nextBackfill(state);
        repository.completeWindow(state, next.phase(), next.status(), next.from(), next.to(),
                next.complete() ? null : now, now);
    }

    private BackfillNext nextBackfill(FinanceSyncState state) {
        LocalDate anchor = state.anchorDate();
        String phase = state.phase();
        LocalDate phaseStart = FinanceSyncPlanner.phaseStart(phase, anchor);
        LocalDate nextTo = state.windowFrom().minusDays(1);
        if (!nextTo.isBefore(phaseStart)) {
            int maxDays = FinanceSyncPlanner.maxWindowDays(state.apiFamily());
            LocalDate nextFrom = nextTo.minusDays(maxDays - 1L);
            if (nextFrom.isBefore(phaseStart)) nextFrom = phaseStart;
            return new BackfillNext(phase, "IDLE", nextFrom, nextTo, false);
        }
        String nextPhase = switch (phase) {
            case "BACKFILL_31_90" -> "BACKFILL_91_180";
            case "BACKFILL_91_180" -> "BACKFILL_OLDER";
            default -> "COMPLETE";
        };
        if ("COMPLETE".equals(nextPhase) || FinanceSyncPlanner.phaseEnd(nextPhase, anchor)
                .isBefore(FinanceSyncPlanner.HISTORY_FLOOR)) {
            return new BackfillNext("COMPLETE", "COMPLETE", null, null, true);
        }
        LocalDate to = FinanceSyncPlanner.phaseEnd(nextPhase, anchor);
        LocalDate from = FinanceSyncPlanner.initialWindowFrom(state.apiFamily(), nextPhase, anchor);
        return new BackfillNext(nextPhase, "IDLE", from, to, false);
    }

    private void handleFailure(FinanceSyncState state, RuntimeException exception, Instant now) {
        Duration delay = Duration.ofMinutes(15);
        if (exception instanceof WbAnalyticsApiException apiException) {
            if (apiException.statusCode() == 401 || apiException.statusCode() == 403) {
                delay = Duration.ofHours(6);
            } else if (apiException.statusCode() == 429) {
                Duration declared = apiException.retryAfter();
                Duration floor = "FINANCE".equals(state.apiFamily()) ? FINANCE_INTERVAL : Duration.ofMinutes(1);
                // WB Base tokens may be limited to one request per 12 hours.
                // Do not infer token type: apply the conservative delay only after
                // a real 429 without a declared retry interval.
                delay = declared == null && "FINANCE".equals(state.apiFamily()) ? Duration.ofHours(12)
                        : declared == null || declared.compareTo(floor) < 0 ? floor : declared;
            }
        } else if (exception instanceof OzonFinanceApiException apiException) {
            if (apiException.statusCode() == 401 || apiException.statusCode() == 403) {
                delay = Duration.ofHours(6);
            } else if (apiException.statusCode() == 429) {
                Duration declared = apiException.retryAfter();
                delay = declared == null || declared.compareTo(Duration.ofMinutes(1)) < 0
                        ? Duration.ofMinutes(1) : declared;
            }
        }
        repository.markError(state, safeRetryAt(now,delay), exception.getMessage());
        LOGGER.warn("Analytics sync deferred for shop {}, stream {}: {}", state.shopId(), state.streamName(), exception.getMessage());
    }

    private static Shop snapshot(Shop source) {
        return new Shop(source.getId(), source.getName(), source.getMarketplace(), source.getClientId(), source.getApiKey());
    }

    static Instant safeRetryAt(Instant now,Duration delay) {
        try { return now.plus(delay); }
        catch (java.time.DateTimeException | ArithmeticException overflow) {
            // Use a sortable far-future ISO instant rather than retrying early on malformed input.
            return Instant.parse("9999-12-31T23:59:59Z");
        }
    }

    private record PlannedShopWork(Shop shop, FinanceWorkItem work) {
    }

    private record BackfillNext(String phase, String status, LocalDate from, LocalDate to, boolean complete) {
    }
}
