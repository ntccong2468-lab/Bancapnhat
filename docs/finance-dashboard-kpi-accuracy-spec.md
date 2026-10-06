# Finance dashboard KPI accuracy and date refresh

## Objective

Make the finance dashboard reload immediately when either date changes, present the marketplace financial fields without implying a bank transfer, and display the requested KPIs in a clear accounting order with their share of selected-period revenue.

## Technical context

- Java 25 / JavaFX 25 FXML application.
- Analytics remain isolated in `wcode_analytics.db`.
- Dashboard reads only the `finance_daily` read model through `FinanceDashboardRepository`.
- Background reads continue through `FinanceExecutor`; no analytics query runs on the JavaFX Application Thread.
- Verification commands: focused Maven tests, then `./mvnw -B clean verify`.

## Financial definitions

- Revenue: gross non-return sales in the selected period.
- Marketplace payable: `net_payout`/WB `for_pay`; this is the amount accrued as payable for goods after commission and payment-service effects, not evidence that money reached a bank card/account. WB return rows are converted to negative payable amounts in `finance_daily`. Logistics, storage, penalties, advertising, and other service charges are applied separately when calculating dashboard net profit.
- Commission: net `commission_cost` after return reversals, displayed separately for transparency and never deducted from net profit a second time because it is already reflected in marketplace payable.
- Net profit: existing read-model formula: payable minus penalties, logistics, storage, other costs, and advertising, plus additional payments.
- KPI percentage: KPI amount divided by the absolute selected-period revenue. If revenue is zero, show an em dash instead of a misleading percentage. Net-profit percentage preserves its sign.

## UI behavior and layout

1. Both date pickers call the existing guarded asynchronous `load()` path on user action.
2. KPI reading order is:
   revenue; marketplace payable; commission; returns; logistics; advertising; storage; penalties; other costs; net profit.
3. Use a three-column grid for the first nine cards and a full-width final net-profit card.
4. Each card shows a smaller monetary value followed by “% of revenue”. Color is semantic but supplementary to the title and percentage text.
5. The daily table follows the same accounting order and adds a commission column.
6. Marketplace payable includes visible explanatory copy and a tooltip/accessibility hint clarifying that it is not a bank transfer.
7. All new visible strings are translated in Vietnamese, English, Russian, and Chinese.

## Testing

- Repository/read-model regression test verifies commission aggregation remains accurate and idempotent.
- Snapshot unit tests verify commission aggregation and revenue-share edge cases.
- FXML smoke test verifies date-picker handlers, commission controls, KPI order, and successful controller injection.
- Existing test suite guards startup, database migration, sync, printing, and marketplace behavior.

## Boundaries and data safety

- No edits to the operational order/KIZ schema or data.
- Analytics schema v5 performs a one-time background correction of historical daily payout/commission/acquiring signs while preserving raw source rows.
- No direct aggregation over raw tables from the dashboard.
- No change to sync scheduling, marketplace push behavior, or stored checkpoints.
- Existing user files and unrelated working-tree changes remain untouched.

## Acceptance criteria

- Selecting a valid date in either picker reloads the selected shop's dashboard.
- Invalid ranges still show the existing validation message.
- Commission appears in the KPI grid and daily table.
- KPI order, semantic colors, compact amount typography, and percentages match this specification.
- “Marketplace payable” is explained accurately and is not labeled as money already transferred to a card/account.
- Focused tests and the complete Maven verification pass.
