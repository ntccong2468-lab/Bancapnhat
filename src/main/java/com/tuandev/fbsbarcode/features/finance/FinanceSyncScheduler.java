package com.tuandev.fbsbarcode.features.finance;

import com.tuandev.fbsbarcode.features.print.KizAttachmentCoordinator;
import com.tuandev.fbsbarcode.integration.wb.finance.WbAdvertisingApiClient;
import com.tuandev.fbsbarcode.integration.wb.finance.WbAnalyticsApiException;
import com.tuandev.fbsbarcode.integration.wb.finance.WbFinanceApiClient;
import com.tuandev.fbsbarcode.integration.wb.finance.WbFinancePage;
import com.tuandev.fbsbarcode.integration.ozon.OzonCredentials;
import com.tuandev.fbsbarcode.integration.ozon.finance.OzonFinanceApiClient;
import com.tuandev.fbsbarcode.integration.ozon.finance.OzonFinanceApiException;
import com.tuandev.fbsbarcode.integration.ozon.finance.OzonFinancePage;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
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
    private final Clock clock;
    private final Map<Integer, Shop> shops = new ConcurrentHashMap<>();
    private volatile Path shopsDatabasePath;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean workRunning = new AtomicBoolean();

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

    public void updateShops(Collection<Shop> currentShops) {
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
            Instant now = clock.instant();
            LocalDate today = LocalDate.now(clock.withZone(WeeklyFinanceSchedule.VIETNAM_ZONE));
            for (Shop shop : snapshots.values()) {
                repository.ensureShopState(shop.getId(), shop.getMarketplace(), today, now);
            }
        });
        start();
    }

    public void requestRecentSync(int shopId) {
        FinanceExecutor.executeSync(() -> repository.requestRecentSync(shopId, clock.instant()));
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
            } catch (RuntimeException exception) {
                LOGGER.warn("Finance analytics tick failed", exception);
            } finally {
                workRunning.set(false);
            }
        });
    }

    void runOneRequest() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(WeeklyFinanceSchedule.VIETNAM_ZONE));
        List<PlannedShopWork> candidates = new ArrayList<>();
        for (Shop shop : shops.values()) {
            repository.ensureShopState(shop.getId(), shop.getMarketplace(), today, now);
            planner.next(repository.loadStates(shop.getId()), now, today)
                    .ifPresent(work -> candidates.add(new PlannedShopWork(shop, work)));
        }
        candidates.sort(Comparator.comparingInt(candidate -> candidate.work().priority()));
        for (PlannedShopWork candidate : candidates) {
            FinanceSyncState state = candidate.work().state();
            Instant allowed = repository.familyNextAllowedAt(candidate.shop().getId(), state.apiFamily());
            if (allowed != null && allowed.isAfter(now)) continue;
            execute(candidate.shop(), candidate.work(), now);
            return; // At most one HTTP request per scheduler tick: never burst analytics calls.
        }
    }

    private void execute(Shop shop, FinanceWorkItem work, Instant now) {
        FinanceSyncState state = work.state();
        if (work.newWindow()) {
            repository.startWindow(state, work.phase(), work.from(), work.to(), now);
            state = repository.loadState(shop.getId(), state.streamName());
            if (state == null) return; // Shop/checkpoint may have been removed while work was queued.
        }
        Duration interval = switch (state.apiFamily()) {
            case "FINANCE" -> FINANCE_INTERVAL;
            case "ADVERTISING" -> ADVERTISING_INTERVAL;
            case "OZON_FINANCE" -> OZON_FINANCE_INTERVAL;
            default -> throw new IllegalStateException("Unsupported finance API family: " + state.apiFamily());
        };
        Instant nextAllowed = now.plus(interval);
        repository.markAttempt(shop.getId(), state.streamName(), nextAllowed);
        state = repository.loadState(shop.getId(), state.streamName());
        if (state == null) return;
        try {
            switch (state.apiFamily()) {
                case "FINANCE" -> syncFinance(shop, state, nextAllowed, now);
                case "ADVERTISING" -> syncAdvertising(shop, state, nextAllowed, now);
                case "OZON_FINANCE" -> syncOzonFinance(shop, state, nextAllowed, now);
                default -> throw new IllegalStateException("Unsupported finance API family: " + state.apiFamily());
            }
        } catch (RuntimeException exception) {
            handleFailure(state, exception, now);
        }
    }

    private void syncFinance(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        String period = "FINANCE_WEEKLY".equals(state.streamName()) ? "weekly" : "daily";
        WbFinancePage page = financeClient.loadPage(
                shop.getApiKey(), state.windowFrom(), state.windowTo(), state.cursor(), period);
        repository.upsertFinanceRows(shop.getId(), page.rows());
        if (page.endOfReport()) {
            finishWindow(state, now);
        } else {
            repository.saveProgress(state, page.nextCursor(), nextAllowed, now);
        }
    }

    private void syncAdvertising(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        repository.upsertAdvertisingRows(shop.getId(),
                advertisingClient.loadCosts(shop.getApiKey(), state.windowFrom(), state.windowTo()));
        finishWindow(state, now);
    }

    private void syncOzonFinance(Shop shop, FinanceSyncState state, Instant nextAllowed, Instant now) {
        OzonFinancePage page = ozonFinanceClient.loadPage(
                new OzonCredentials(shop.getClientId(), shop.getApiKey()),
                state.windowFrom(), state.windowTo(), state.cursor());
        repository.upsertFinanceRows(shop.getId(), page.rows());
        if (page.endOfReport()) {
            finishWindow(state, now);
        } else {
            repository.saveProgress(state, page.nextCursor(), nextAllowed, now);
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
                delay = declared == null || declared.compareTo(floor) < 0 ? floor : declared;
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
        repository.markError(state, now.plus(delay), exception.getMessage());
        LOGGER.warn("Analytics sync deferred for shop {}, stream {}: {}", state.shopId(), state.streamName(), exception.getMessage());
    }

    private static Shop snapshot(Shop source) {
        return new Shop(source.getId(), source.getName(), source.getMarketplace(), source.getClientId(), source.getApiKey());
    }

    private record PlannedShopWork(Shop shop, FinanceWorkItem work) {
    }

    private record BackfillNext(String phase, String status, LocalDate from, LocalDate to, boolean complete) {
    }
}
