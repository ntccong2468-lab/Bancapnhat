package com.tuandev.fbsbarcode.features.finance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceSyncPlannerTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Test
    void prioritizesLatestThirtyDaysAndPersistsCursorWindow() {
        FinanceSyncState finance = state("FINANCE_RECENT", "FINANCE", "INITIAL_30", "IDLE",
                null, null, "0");
        FinanceSyncState advertising = state("ADVERTISING_RECENT", "ADVERTISING", "INITIAL_30", "IDLE",
                null, null, "0");
        FinanceWorkItem work = new FinanceSyncPlanner().next(List.of(advertising, finance), NOW, TODAY).orElseThrow();
        assertEquals("FINANCE_RECENT", work.state().streamName());
        assertEquals(TODAY.minusDays(29), work.from());
        assertEquals(TODAY, work.to());
        assertTrue(work.newWindow());

        FinanceSyncState resumed = state("FINANCE_RECENT", "FINANCE", "INITIAL_30", "ERROR",
                TODAY.minusDays(29), TODAY, "987654321");
        FinanceWorkItem resume = new FinanceSyncPlanner().next(List.of(resumed), NOW, TODAY).orElseThrow();
        assertEquals("987654321", resume.cursor());
        assertEquals(TODAY.minusDays(29), resume.from());

        FinanceSyncState ozon = state("OZON_FINANCE_RECENT", "OZON_FINANCE", "INITIAL_30", "IDLE",
                null, null, "0");
        FinanceWorkItem ozonWork = new FinanceSyncPlanner().next(List.of(ozon), NOW, TODAY).orElseThrow();
        assertEquals(TODAY.minusDays(27), ozonWork.from());
        assertEquals(TODAY, ozonWork.to());
    }

    @Test
    void backfillWaitsForItsOwnRecentApiThenUsesRequiredPhases() {
        FinanceSyncState backfill = state("FINANCE_BACKFILL", "FINANCE", "WAITING_RECENT", "IDLE",
                null, null, "0");
        assertTrue(new FinanceSyncPlanner().next(List.of(backfill), NOW, TODAY).isEmpty());

        FinanceSyncState financeRecent = completedRecent("FINANCE_RECENT", "FINANCE");
        FinanceWorkItem work = new FinanceSyncPlanner().next(
                List.of(backfill, financeRecent), NOW, TODAY).orElseThrow();
        assertEquals("BACKFILL_31_90", work.phase());
        assertEquals(TODAY.minusDays(89), work.from());
        assertEquals(TODAY.minusDays(30), work.to());
        assertEquals(90, FinanceSyncPlanner.maxWindowDays("FINANCE"));
        assertEquals(31, FinanceSyncPlanner.maxWindowDays("ADVERTISING"));
        assertEquals(28, FinanceSyncPlanner.maxWindowDays("OZON_FINANCE"));

        FinanceSyncState advertisingBackfill = state(
                "ADVERTISING_BACKFILL", "ADVERTISING", "WAITING_RECENT", "IDLE", null, null, "0");
        assertTrue(new FinanceSyncPlanner().next(
                List.of(advertisingBackfill, financeRecent), NOW, TODAY).isEmpty());
    }

    @Test
    void prioritizesWeeklyReconciliationAndUsesPreviousMondayThroughSunday() {
        FinanceSyncState weekly = state("FINANCE_WEEKLY", "FINANCE", "WEEKLY_RECONCILIATION", "IDLE",
                null, null, "0");
        FinanceSyncState recent = state("FINANCE_RECENT", "FINANCE", "PERIODIC_3", "IDLE",
                null, null, "0");
        FinanceWorkItem work = new FinanceSyncPlanner().next(List.of(recent, weekly), NOW, TODAY).orElseThrow();
        assertEquals("FINANCE_WEEKLY", work.state().streamName());
        assertEquals(LocalDate.of(2026, 8, 17), work.from());
        assertEquals(LocalDate.of(2026, 8, 23), work.to());
    }

    @Test
    void ozonBackfillWaitsForOzonRecentStream() {
        FinanceSyncState backfill = state("OZON_FINANCE_BACKFILL", "OZON_FINANCE",
                "WAITING_RECENT", "IDLE", null, null, "0");
        assertTrue(new FinanceSyncPlanner().next(List.of(backfill), NOW, TODAY).isEmpty());
        FinanceSyncState recent = completedRecent("OZON_FINANCE_RECENT", "OZON_FINANCE");
        FinanceWorkItem work = new FinanceSyncPlanner().next(List.of(backfill, recent), NOW, TODAY).orElseThrow();
        assertEquals("BACKFILL_31_90", work.phase());
        assertEquals(TODAY.minusDays(55), work.from());
        assertEquals(TODAY.minusDays(28), work.to());
    }

    private static FinanceSyncState state(String stream, String family, String phase, String status,
                                          LocalDate from, LocalDate to, String cursor) {
        return new FinanceSyncState(1, stream, family, phase, status, TODAY, from, to, cursor,
                NOW, null, null, null);
    }

    private static FinanceSyncState completedRecent(String stream, String family) {
        return new FinanceSyncState(1, stream, family, "PERIODIC_3", "IDLE", TODAY,
                null, null, "0", NOW.plusSeconds(3600), null, NOW, null);
    }
}
