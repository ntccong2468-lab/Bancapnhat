# FBO/FBW Supply Order Tracking

Status: approved for implementation
Date: 2026-08-25
Owner: WCode desktop

## Objective

Add a dedicated **FBO supply orders** page to WCode so a seller can track inbound supply requests created for marketplace warehouses:

- Wildberries fulfillment-by-Wildberries supplies, named **FBW** by Wildberries;
- Ozon fulfillment-by-Ozon supply requests, named **FBO** by Ozon.

This page is separate from the existing **FBO packing** page. FBO packing remains a product/SKU barcode and KIZ printing workflow. The new page tracks the lifecycle, warehouse, time slot, quantities, and product contents of an inbound supply request.

The first release is read-only. Its only purpose is to show each supply request's current status and the products/quantities that the user placed in that request. It must not create, cancel, edit, reschedule, or otherwise mutate a remote supply request.

## Confirmed API contracts

The contracts below were checked against official marketplace documentation and, on 2026-08-25, against the configured local WB and Ozon shops using read-only requests. Tokens, shop identifiers, order identifiers, phone numbers, and product data were not printed or persisted during verification.

### Wildberries FBW

Base URL: `https://supplies-api.wildberries.ru`
Authorization: `Authorization: <WB token>`
Required token category: **Supplies**.

Official reference: [Wildberries FBW supplies API](https://dev.wildberries.ru/docs/openapi/orders-fbw).

#### List supplies

`POST /api/v1/supplies?limit={1..1000}&offset={offset}`

- Empty JSON body `{}` is accepted and returns the latest supplies; the documented default is the latest 1,000.
- Optional `dates[]` filters contain `from`, `till`, and `type`.
- Optional `statusIDs[]` contains status IDs 1 through 6.
- Response fields confirmed live: `preorderID`, nullable `supplyID`, `statusID`, `boxTypeID`, `createDate`, `supplyDate`, `factDate`, `updatedDate`, and masked `phone`.
- WCode must never store `phone`.
- Official limit per seller: 30 requests/minute, 2-second interval, burst 10. WCode will not use the burst allowance; it will keep at least 2 seconds between WB FBW calls for each shop.

Status mapping:

| WB `statusID` | Meaning | WCode group |
|---:|---|---|
| 1 | Not planned | PREPARING |
| 2 | Planned | READY |
| 3 | Unloading allowed | READY |
| 4 | Accepting | IN_PROGRESS |
| 5 | Accepted | COMPLETED |
| 6 | Unloaded at gate | IN_PROGRESS |

Wildberries removed textual `statusName`, `boxTypeName`, and `virtualTypeName` fields in November 2025. WCode must rely on numeric IDs and preserve unknown IDs instead of failing. See [WB API change notice](https://dev.wildberries.ru/release-notes?id=200).

#### Supply details

`GET /api/v1/supplies/{ID}?isPreorderID={true|false}`

- Use `isPreorderID=true` with `preorderID` when `supplyID` is not assigned yet.
- Use `isPreorderID=false` with `supplyID` after the supply exists.
- Important response fields confirmed live: status and box/virtual type IDs; planned, actual, and updated dates; planned, actual, and transit warehouse IDs/names; total, unloading, accepted, ready-for-sale, and depersonalized quantities; acceptance cost; acceptance, delivery, and storage coefficients; rejection reason.

#### Supply goods

`GET /api/v1/supplies/{ID}/goods?limit={1..1000}&offset={offset}&isPreorderID={true|false}`

Fields confirmed live: `barcode`, `vendorCode`, `nmID`, `needKiz`, `tnved`, `techSize`, `color`, `supplierBoxAmount`, `quantity`, `unloadingQuantity`, `acceptedQuantity`, and `readyForSaleQuantity`.

#### Supply packages

`GET /api/v1/supplies/{ID}/package`

Returns `packageCode`, package `quantity`, and barcode quantities. This endpoint is documented but is not needed by the first tracking UI.

#### Known WB limitation

The list endpoint is the only supported discovery endpoint. An [official WB developer-community report](https://dev.wildberries.ru/forum/2085) describes cases where supplies visible in Seller Portal with planned or unloading-allowed statuses did not appear in the list response even though detail lookup by a known ID worked. WCode cannot discover an ID that WB omits. The UI must therefore show the last successful sync time and a concise marketplace-data limitation message, without claiming complete Seller Portal parity.

### Ozon FBO

Base URL: `https://api-seller.ozon.ru`
Authorization headers: `Client-Id` and `Api-Key`
Official reference: [Ozon Seller API](https://docs.ozon.ru/api/seller/).

The old `/v2/supply-order/list` and `/v2/supply-order/get` endpoints were retired. Ozon announced migration to v3 in [October 2025](https://t.me/s/OzonSellerAPI?before=581), removed v2 from documentation in [January 2026](https://t.me/s/OzonSellerAPI/592), and both v2 endpoints returned HTTP 404 during the live verification. WCode must use v3 only.

#### Count requests by state

`POST /v1/supply-order/status/counter` with `{}`

- Returns `items[]` with `count` and `order_state`.
- Counter state values are prefixed, for example `ORDER_STATE_READY_TO_SUPPLY`.
- This is useful for status counters but must not be the only source of truth for local rows.

#### List supply requests

`POST /v3/supply-order/list`

Validated request shape:

```json
{
  "filter": {
    "states": ["READY_TO_SUPPLY"]
  },
  "last_id": "",
  "limit": 100,
  "sort_by": "ORDER_STATE_UPDATED_AT",
  "sort_dir": "DESC"
}
```

- `filter.states` is required and must contain at least one item.
- v3 state values are unprefixed.
- Sort options are `ORDER_CREATION`, `ORDER_STATE_UPDATED_AT`, `TIMESLOT_FROM_UTC`, and `TIMESLOT_FROM_LOCAL`.
- Directions are `ASC` and `DESC`.
- Response contains `order_ids[]` and `last_id` cursor.
- Cursor is saved only after its page is committed, so app restart can resume safely.

#### Get request details

`POST /v3/supply-order/get`

Request: `{"order_ids":["..."]}`. WCode will use conservative batches of at most 50 IDs.

Order fields confirmed live:

- `order_id`, `order_number`, `created_date`, `state`, `state_updated_date`;
- `data_filling_deadline`;
- `drop_off_warehouse`;
- `timeslot.timeslot.from/to` and `timeslot.timezone_info`;
- order flags such as `is_virtual`, `is_pickup`, `is_econom`, `is_quant`, and `is_super_fbo`;
- `supplies[]`.

Supply fields confirmed live:

- `supply_id`, `bundle_id`, `state`, `is_crossdock`, `macrolocal_cluster_id`;
- `storage_warehouse` and `supply_tags`.

Ozon deprecated `orders.supplies.storage_warehouse.arrival_date` in February 2026. WCode must not use it as a canonical delivery date; it will use the request time slot and status timestamps. See the [official Ozon change notice](https://t.me/s/OzonSellerAPI/619).

#### Get supply contents

`POST /v1/supply-order/bundle`

- `bundle_ids` supports supply-content identifiers returned by v3 get.
- Page with `last_id`, `limit` (use at most 100), `query`, and `sort_field`.
- Response contains `items[]`, `total_count`, `has_next`, and `last_id`.
- Product fields confirmed live: `sku`, `product_id`, `offer_id`, `barcode`, `name`, `icon_path`, `quantity`, `quant`, volume, shipment type, placement zone, and tags.

Product contents are fetched lazily when the user opens an order, then cached locally. This keeps the initial refresh fast and avoids a burst of one bundle request per supply.

#### Ozon status mapping

| Ozon state | WCode group |
|---|---|
| `DATA_FILLING` | PREPARING |
| `READY_TO_SUPPLY` | READY |
| `ACCEPTED_AT_SUPPLY_WAREHOUSE` | IN_PROGRESS |
| `IN_TRANSIT` | IN_PROGRESS |
| `ACCEPTANCE_AT_STORAGE_WAREHOUSE` | IN_PROGRESS |
| `REPORTS_CONFIRMATION_AWAITING` | REVIEW |
| `REPORT_REJECTED` | ISSUE |
| `COMPLETED` | COMPLETED |
| `REJECTED_AT_SUPPLY_WAREHOUSE` | ISSUE |
| `CANCELLED` | CANCELLED |
| `OVERDUE` | ISSUE |
| any new value | UNKNOWN, with raw value shown |

Ozon does not publish a stable endpoint-specific numeric quota on the accessible supply-order reference. WCode will apply a conservative per-shop FBO read limiter of one request per second, honor `Retry-After`, stop paging on 429, and leave cached data visible. It must not retry continuously.

## User experience

### Navigation

- Add a new sidebar item **FBO supply orders** / **Đơn hàng FBO** for both WB and Ozon shops.
- Keep the existing **FBO packing** / **Đóng hàng FBO** item unchanged.
- The selected marketplace determines which API adapter and localized marketplace terminology are used; the common screen layout remains the same.

### Page layout

Header:

- title and marketplace label;
- last successful sync time;
- background-sync progress/error banner;
- search field;
- manual refresh icon button with tooltip.

Status controls show counts:

- All;
- Preparing;
- Ready;
- In transit / accepting;
- Review / issue;
- Completed;
- Cancelled.

Master table columns:

- request/supply number;
- marketplace status badge;
- destination warehouse;
- delivery time slot or planned date;
- total quantity;
- accepted quantity;
- last marketplace update.

Detail pane:

- request and supply identifiers;
- route (drop-off/transit/storage warehouse where available);
- schedule, status, cross-dock/virtual flags, rejection reason where available;
- quantity progress;
- product table with image, name, article/offer, SKU/barcode, size, color, planned quantity, accepted quantity, and KIZ requirement when supplied by WB.

The page reads local cache immediately. Network sync runs in the background and updates the table without clearing it. API errors produce one concise inline message; they do not block navigation and do not erase the previous successful data.

## Storage design

Use the existing operational `database.db`, not `wcode_analytics.db`. The schema is additive and shop-scoped; deleting a shop cascades through all new tables.

Separate marketplace tables preserve different semantics while a feature repository maps them into a common view model.

### WB tables

`wb_fbw_orders`

- primary key `(shop_id, preorder_id)`;
- nullable `supply_id` plus status/type IDs, dates, warehouses, quantities, coefficients, cost, rejection reason, and sync timestamps;
- no phone and no raw response JSON.

`wb_fbw_order_items`

- primary key `(shop_id, preorder_id, item_key)`;
- barcode, vendor code, nmID, KIZ flag, TNVED, size/color, and quantity progress.

`wb_fbw_sync_state`

- one row per shop;
- last list/detail success, current offset/checkpoint, last error, and next permitted sync time.

### Ozon tables

`ozon_fbo_orders`

- primary key `(shop_id, order_id)`;
- order number/state/group, dates, drop-off warehouse, time slot/timezone, supported flags, and sync timestamps.

`ozon_fbo_supplies`

- primary key `(shop_id, order_id, supply_id)`;
- bundle ID, state, cross-dock flag, storage warehouse, cluster, and supply tags.

`ozon_fbo_supply_items`

- primary key `(shop_id, order_id, supply_id, item_key)`;
- SKU/product/offer/barcode, display fields, quantities, volume, shipment type, placement zone, and tags.

`ozon_fbo_sync_state`

- one row per shop;
- committed list cursor, last success, last full sync, last error, and next permitted sync time.

Indexes support `(shop_id, status/state, updated_at DESC)`, `(shop_id, planned/timeslot date)`, and child lookups by order/supply. Upserts run in bounded batches inside transactions. Schema initialization is idempotent and does not change or delete existing FBS, KIZ, product, print, or finance records.

## Sync design

### Scheduling and priority

- A dedicated single-thread daemon executor performs FBO/FBW tracking sync; it never runs on the JavaFX Application Thread.
- The executor uses lower thread priority than order closing, KIZ reservation/push, and printing.
- Opening the page renders cached rows first and starts a sync only when data is stale.
- Manual refresh schedules one job; repeated clicks coalesce into the running job.
- While the page remains open, active supplies are checked at most every 10 minutes.
- Completed/cancelled history is refreshed less often. The default UI includes active requests and recently completed history; older cached rows remain searchable.

### WB algorithm

1. Call list once with a page size up to 1,000, observing the 2-second per-shop limiter.
2. Upsert summary rows in one transaction; use `preorderID` as the stable order key.
3. Refresh details only for new/changed/active rows. Space every detail call by at least 2 seconds.
4. Fetch goods when a row is selected or when its detail cache is stale; paginate up to 1,000 goods per call.
5. Commit item pages transactionally and update the checkpoint only after commit.
6. On 429 or transient failure, stop the current pass, honor server cooldown, and keep cached rows.

### Ozon algorithm

1. Read the status counter once and derive supported states, retaining the known `OVERDUE` state.
2. Page `/v3/supply-order/list`, sorted by status-update time descending, with a bounded page count for the initial foreground-visible refresh.
3. Save each cursor only after the ID page is durably processed.
4. Fetch `/v3/supply-order/get` in batches of at most 50 IDs, upsert order/supply records in transactions.
5. Fetch `/v1/supply-order/bundle` only for the selected/stale supply, using pages of at most 100.
6. Keep at least one second between FBO calls for the shop. On 429, honor cooldown and stop the pass.

### Data freshness and restart behavior

- Active rows: stale after 10 minutes.
- Completed/cancelled rows: stale after 24 hours.
- Detail contents: stale after one hour while active and immutable after a terminal state unless the marketplace state changes.
- Unknown or missing fields are nullable and never cause the whole response to be rejected.
- Interrupted synchronization resumes from the last committed cursor. A full reconciliation periodically restarts from the newest page to capture marketplace adjustments.

## Tech stack and project conventions

- Java 25 project conventions already used by the main WCode build;
- JavaFX/FXML for UI;
- OkHttp and Gson for HTTP/JSON;
- SQLite through JDBC with WAL, foreign keys, busy timeout, bounded transactions, and additive schema support;
- JUnit 5, MockWebServer, and TestFX-compatible FXML smoke tests;
- existing `I18nService`, `AlertService`, `FxmlViewLoader`, shop selection, theme, and lifecycle patterns.

No new framework or remote service is introduced.

## Commands

Primary verification commands:

```bash
node --test tools/*.test.mjs
./mvnw -B clean verify
./mvnw -B -DskipTests package
```

Manual JavaFX acceptance after automated tests:

```bash
./mvnw -q javafx:run
```

## Testing strategy

- API contract tests use sanitized fixtures matching the fields confirmed from live WB/Ozon responses.
- Client tests verify endpoint path, headers, request body, cursor/offset pagination, unknown fields, 429 handling, bounded response sizes, and redacted exceptions.
- Schema/repository tests verify idempotent initialization, transactional upsert, cursor commit ordering, no phone/raw JSON persistence, indexes, and shop-delete cascade.
- Sync tests verify click coalescing, active-versus-terminal freshness, no busy retry, rate-limiter spacing, partial-page restart, and cached-data preservation on failure.
- Status-mapping tests cover every documented value and unknown future values.
- Controller tests cover filtering/search/counts, stale-response rejection after shop changes, cache-first rendering, and non-blocking progress/error state.
- FXML smoke tests load the new view and validate both marketplace modes.
- I18n key-parity tests cover Vietnamese, English, Russian, and Chinese resources.
- Full suite and package build must pass before handoff.

## Boundaries

Included:

- read-only tracking for seller-created inbound WB FBW and Ozon FBO supply requests;
- status counters, local cache, request details, and product contents;
- background/manual synchronization and all four existing UI languages.

Excluded from this release:

- customer FBO postings (`/v2/posting/fbo/list`), which are marketplace-fulfilled retail shipments rather than inbound supply requests;
- creating a supply request;
- cancelling, editing contents, choosing/changing a time slot, managing a vehicle/pass, printing marketplace supply documents, or uploading KIZ for FBO supplies;
- changing existing FBS, KIZ, FBO packing, finance, or print workflows.

## Success criteria

1. A WB or Ozon shop can open **FBO supply orders** and immediately see cached data.
2. Refresh updates statuses and counts in the background without blocking JavaFX.
3. Selecting a request shows its supply route, quantities, and cached/fetched product contents.
4. WB requests use `preorderID` safely before a `supplyID` exists.
5. Ozon uses v3 list/get; no retired v2 supply-order call exists in production code.
6. Marketplace rate limits and cooldowns are isolated per shop/API family; repeated refresh clicks do not create a request burst.
7. Unknown statuses remain visible and do not crash or disappear.
8. No WB phone, credential, buyer PII, or raw API body is stored or logged.
9. Existing databases migrate additively; upgrading users retain shops, orders, KIZ, products, print history, and finance data.
10. Full tests and package build pass on the existing release branch.

## Implementation plan

Each task is intentionally bounded to at most five primary files. Tests are written before or together with production code.

### Task 1 — Common domain and status mapping

Primary files:

- `features/fbosupply/FboSupplyStatusGroup.java`
- `features/fbosupply/FboSupplyOrder.java`
- `features/fbosupply/FboSupplyLine.java`
- `features/fbosupply/FboSupplyItem.java`
- `features/fbosupply/FboSupplyStatusMapperTest.java`

Acceptance: all WB/Ozon states map deterministically; unknown values map to `UNKNOWN` while preserving raw status.

### Task 2 — WB FBW read client and limiter

Primary files:

- `integration/wb/fbw/WbFbwApiClient.java`
- `integration/wb/fbw/WbFbwRateLimiter.java`
- `integration/wb/fbw/WbFbwJson.java`
- `integration/wb/fbw/WbFbwApiClientTest.java`

Acceptance: list/detail/goods/package requests match the official contract; each shop is spaced by at least two seconds; 429 stops the pass and registers cooldown.

### Task 3 — Ozon FBO read client contract

Primary files:

- `integration/ozon/OzonApiClient.java`
- `integration/ozon/OzonFboJson.java`
- `integration/ozon/OzonFboApiClientTest.java`
- `integration/ozon/OzonApiRateLimiterTest.java`

Acceptance: v3 list/get and v1 counter/bundle map current live field names; v2 is never called; cursor and state formats are tested.

### Task 4 — Additive schemas

Primary files:

- `integration/wb/WbSchemaSupport.java`
- `integration/ozon/OzonSchemaSupport.java`
- `integration/fbosupply/FboSupplySchemaTest.java`

Acceptance: initialization is repeatable, old data is untouched, all new children cascade on shop deletion, and no disallowed PII/raw-body column exists.

### Task 5 — Marketplace repositories

Primary files:

- `integration/wb/fbw/WbFbwRepository.java`
- `integration/ozon/OzonFboSupplyRepository.java`
- `features/fbosupply/FboSupplyRepository.java`
- `integration/fbosupply/FboSupplyRepositoryTest.java`

Acceptance: batch upserts and child replacement are transactional; list/detail queries are shop-scoped and indexed; checkpoints are committed last.

### Task 6 — Background synchronization

Primary files:

- `features/fbosupply/FboSupplyExecutor.java`
- `integration/wb/fbw/WbFbwSyncService.java`
- `integration/ozon/OzonFboSyncService.java`
- `features/fbosupply/FboSupplySyncCoordinator.java`
- `features/fbosupply/FboSupplySyncCoordinatorTest.java`

Acceptance: sync never executes on the JavaFX thread, refresh clicks coalesce, active/terminal freshness is respected, and failure leaves cached data intact.

### Task 7 — Page controller and view

Primary files:

- `ui/fbosupply/FboSupplyOrdersController.java`
- `ui/fbosupply/FboSupplyOrderRow.java`
- `ui/fbosupply/FboSupplyItemRow.java`
- `resources/.../ui/fbosupply/fbo-supply-orders-view.fxml`
- `ui/fbosupply/FboSupplyOrdersControllerTest.java`

Acceptance: cache-first master/detail UI supports counts, search, filters, selection, progress, and inline errors for both marketplaces.

### Task 8 — Navigation and workspace integration

Primary files:

- `ui/shop/ShopSidebarController.java`
- `resources/.../ui/shop/shop-sidebar-view.fxml`
- `ui/workspace/HomeController.java`
- `ui/FxmlSmokeTest.java`
- `ui/workspace/WorkspaceStateTest.java`

Acceptance: the new page is reachable for WB and Ozon, existing FBO packing remains reachable, shop switching cannot display a stale result from the previous shop, and all FXML loads.

### Task 9 — Styling and i18n

Primary files:

- `resources/.../styles/theme.css`
- `resources/.../i18n/messages_vi.properties`
- `resources/.../i18n/messages_en.properties`
- `resources/.../i18n/messages_ru.properties`
- `resources/.../i18n/messages_zh.properties`

Acceptance: status colors remain readable in light/dark themes and every visible string is translated with key parity across all languages.

### Task 10 — Regression and packaging

Run the full automated suite, package the app, then manually open a WB and Ozon shop and verify cache-first rendering, background progress, status counts, detail loading, shop switching, and existing FBS/KIZ/FBO packing flows.

## Approved product decisions

The user confirmed that “FBO orders” means inbound supply requests created by the seller for WB/Ozon warehouses. WCode only needs to track their state and show which products/quantities the seller placed in each request. Remote create, cancel, reschedule, and edit actions remain outside this version.
