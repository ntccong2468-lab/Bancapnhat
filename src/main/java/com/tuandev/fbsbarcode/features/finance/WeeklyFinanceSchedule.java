package com.tuandev.fbsbarcode.features.finance;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Persistent WB reconciliation schedule chosen for the seller's Vietnam workday. */
final class WeeklyFinanceSchedule {
    static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalTime RUN_TIME = LocalTime.of(16, 0);

    private WeeklyFinanceSchedule() {
    }

    static Instant firstDueAt(Instant now) {
        ZonedDateTime localNow = now.atZone(VIETNAM_ZONE);
        ZonedDateTime thisMonday = mondayAtRunTime(localNow.toLocalDate());
        return localNow.isBefore(thisMonday) ? thisMonday.toInstant() : now;
    }

    static Instant nextDueAt(Instant now) {
        ZonedDateTime localNow = now.atZone(VIETNAM_ZONE);
        ZonedDateTime candidate = mondayAtRunTime(localNow.toLocalDate());
        if (!candidate.isAfter(localNow)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate.toInstant();
    }

    static LocalDate previousWeekFrom(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    static LocalDate previousWeekTo(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(1);
    }

    private static ZonedDateTime mondayAtRunTime(LocalDate date) {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return ZonedDateTime.of(monday, RUN_TIME, VIETNAM_ZONE);
    }
}
