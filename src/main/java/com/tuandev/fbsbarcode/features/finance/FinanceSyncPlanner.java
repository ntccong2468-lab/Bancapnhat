package com.tuandev.fbsbarcode.features.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class FinanceSyncPlanner {
    static final LocalDate HISTORY_FLOOR = LocalDate.of(2024, 1, 29);

    Optional<FinanceWorkItem> next(List<FinanceSyncState> states, Instant now, LocalDate today) {
        return states.stream()
                .filter(state -> !"COMPLETE".equals(state.phase()))
                .filter(state -> state.due(now))
                .map(state -> workFor(state, states, today))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(FinanceWorkItem::priority)
                        .thenComparing(item -> item.state().streamName()));
    }

    private Optional<FinanceWorkItem> workFor(FinanceSyncState state, List<FinanceSyncState> all, LocalDate today) {
        if ("FINANCE_WEEKLY".equals(state.streamName())) {
            LocalDate from = state.windowFrom() == null
                    ? WeeklyFinanceSchedule.previousWeekFrom(today) : state.windowFrom();
            LocalDate to = state.windowTo() == null
                    ? WeeklyFinanceSchedule.previousWeekTo(today) : state.windowTo();
            return Optional.of(new FinanceWorkItem(state, "WEEKLY_RECONCILIATION", from, to,
                    state.cursor(), state.windowFrom() == null, -10));
        }
        boolean recent = state.streamName().endsWith("_RECENT");
        if (recent) {
            return recentWork(state, today);
        }
        if ("WAITING_RECENT".equals(state.phase())) {
            String recentStream = switch (state.apiFamily()) {
                case "FINANCE" -> "FINANCE_RECENT";
                case "ADVERTISING" -> "ADVERTISING_RECENT";
                case "OZON_FINANCE" -> "OZON_FINANCE_RECENT";
                default -> throw new IllegalArgumentException("Unknown finance API family: " + state.apiFamily());
            };
            boolean ready = all.stream().anyMatch(candidate -> recentStream.equals(candidate.streamName())
                    && "PERIODIC_3".equals(candidate.phase()));
            if (!ready) return Optional.empty();
            LocalDate anchor = anchor(state, today);
            LocalDate phaseEnd = "OZON_FINANCE".equals(state.apiFamily())
                    ? anchor.minusDays(28) : anchor.minusDays(30);
            return Optional.of(new FinanceWorkItem(state, "BACKFILL_31_90",
                    initialWindowFrom(state.apiFamily(), "BACKFILL_31_90", anchor, phaseEnd), phaseEnd,
                    "0", true, 20 + familyOffset(state)));
        }
        if (state.phase().startsWith("BACKFILL_")) {
            if (state.windowFrom() != null && state.windowTo() != null) {
                return Optional.of(new FinanceWorkItem(state, state.phase(), state.windowFrom(), state.windowTo(),
                        state.cursor(), false, phasePriority(state.phase()) + familyOffset(state)));
            }
        }
        return Optional.empty();
    }

    private Optional<FinanceWorkItem> recentWork(FinanceSyncState state, LocalDate today) {
        if ("INITIAL_30".equals(state.phase())) {
            if (state.windowFrom() != null) {
                return Optional.of(new FinanceWorkItem(state, state.phase(), state.windowFrom(), state.windowTo(),
                        state.cursor(), false, familyOffset(state)));
            }
            LocalDate anchor = anchor(state, today);
            int days = Math.min(30, maxWindowDays(state.apiFamily()));
            return Optional.of(new FinanceWorkItem(state, state.phase(), anchor.minusDays(days - 1L), anchor,
                    "0", true, familyOffset(state)));
        }
        if ("PERIODIC_3".equals(state.phase())) {
            if (state.windowFrom() != null) {
                return Optional.of(new FinanceWorkItem(state, state.phase(), state.windowFrom(), state.windowTo(),
                        state.cursor(), false, familyOffset(state)));
            }
            return Optional.of(new FinanceWorkItem(state, state.phase(), today.minusDays(2), today,
                    "0", true, familyOffset(state)));
        }
        return Optional.empty();
    }

    static LocalDate initialWindowFrom(String apiFamily, String phase, LocalDate anchor) {
        LocalDate phaseEnd = phaseEnd(phase, anchor);
        return initialWindowFrom(apiFamily, phase, anchor, phaseEnd);
    }

    private static LocalDate initialWindowFrom(String apiFamily, String phase, LocalDate anchor,
                                               LocalDate phaseEnd) {
        LocalDate phaseStart = phaseStart(phase, anchor);
        int maxDays = maxWindowDays(apiFamily);
        return phaseEnd.minusDays(maxDays - 1L).isBefore(phaseStart)
                ? phaseStart : phaseEnd.minusDays(maxDays - 1L);
    }

    static int maxWindowDays(String apiFamily) {
        return switch (apiFamily) {
            case "FINANCE" -> 90;
            case "OZON_FINANCE" -> 28;
            default -> 31;
        };
    }

    static LocalDate phaseStart(String phase, LocalDate anchor) {
        return switch (phase) {
            case "BACKFILL_31_90" -> anchor.minusDays(89);
            case "BACKFILL_91_180" -> anchor.minusDays(179);
            case "BACKFILL_OLDER" -> HISTORY_FLOOR;
            default -> throw new IllegalArgumentException("Unknown finance phase: " + phase);
        };
    }

    static LocalDate phaseEnd(String phase, LocalDate anchor) {
        return switch (phase) {
            case "BACKFILL_31_90" -> anchor.minusDays(30);
            case "BACKFILL_91_180" -> anchor.minusDays(90);
            case "BACKFILL_OLDER" -> anchor.minusDays(180);
            default -> throw new IllegalArgumentException("Unknown finance phase: " + phase);
        };
    }

    private static int phasePriority(String phase) {
        return switch (phase) {
            case "BACKFILL_31_90" -> 20;
            case "BACKFILL_91_180" -> 30;
            default -> 40;
        };
    }

    private static int familyOffset(FinanceSyncState state) {
        return switch (state.apiFamily()) {
            case "FINANCE" -> 0;
            case "ADVERTISING" -> 1;
            default -> 2;
        };
    }

    private static LocalDate anchor(FinanceSyncState state, LocalDate today) {
        return state.anchorDate() == null ? today : state.anchorDate();
    }
}
