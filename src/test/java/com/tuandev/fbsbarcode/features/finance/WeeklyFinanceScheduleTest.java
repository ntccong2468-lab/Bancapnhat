package com.tuandev.fbsbarcode.features.finance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeeklyFinanceScheduleTest {
    @Test
    void schedulesMondayAtSixteenVietnamAndCatchesUpAfterMissedRun() {
        assertEquals(Instant.parse("2026-08-24T09:00:00Z"),
                WeeklyFinanceSchedule.firstDueAt(Instant.parse("2026-08-24T07:00:00Z")));

        Instant openedTuesday = Instant.parse("2026-08-25T02:00:00Z");
        assertEquals(openedTuesday, WeeklyFinanceSchedule.firstDueAt(openedTuesday));
        assertEquals(Instant.parse("2026-08-31T09:00:00Z"),
                WeeklyFinanceSchedule.nextDueAt(openedTuesday));
    }

    @Test
    void calculatesThePreviousCompletedWeek() {
        LocalDate tuesday = LocalDate.of(2026, 8, 25);
        assertEquals(LocalDate.of(2026, 8, 17), WeeklyFinanceSchedule.previousWeekFrom(tuesday));
        assertEquals(LocalDate.of(2026, 8, 23), WeeklyFinanceSchedule.previousWeekTo(tuesday));
    }
}
