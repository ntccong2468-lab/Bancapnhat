# Spec: FBS order sync and catalog hygiene

## Objective

Keep the JavaFX FBS workspaces consistent with Ozon and Wildberries while ensuring only Znack GTIN
cards with product permit documents remain operational.

## Tech stack and commands

- Java 25, JavaFX 25.0.2, OkHttp 4.12.0, Gson 2.13.2, SQLite JDBC 3.45.3.0.
- Focused tests: `./mvnw -B -Dtest=OzonApiClientTest,OzonSyncServiceTest,WbSupplyDeletionTest,ZnackModuleTest,FxmlSmokeTest test`
- Full verification: `./mvnw -B clean verify`

## Project structure and style

- Marketplace HTTP contracts and persistence live in `src/main/java/.../integration/{ozon,wb,znack}`.
- JavaFX behavior lives in `src/main/java/.../ui` with layouts in `src/main/resources/.../ui`.
- Behavioral tests live beside the corresponding integration/UI test packages.
- Keep external identifiers as bounded strings, use parameterized SQL and run every remote operation
  away from the JavaFX Application Thread.

## Functional behavior

### Ozon FBS

1. Synchronize the actionable queue through `POST /v4/posting/fbs/unfulfilled/list` with a bounded
   cutoff window and cursor pagination.
2. Upsert remote status changes before reading the three local groups. In particular, an existing
   `awaiting_packaging` posting that becomes `awaiting_deliver` must move from New to Packing.
3. Refresh the lightweight posting queue when the user opens the Ozon FBS workspace or selects the
   Packing tab. Catalog synchronization remains separate so a catalog error cannot hide current
   orders.
4. Keep the existing rolling `/v4/posting/fbs/list` synchronization for historical transitions.
5. Allocate exactly one exemplar/KIZ per physical item quantity. Membership in both Ozon's
   mandatory and optional requirement lists must never duplicate the same item.
6. For printing, reserve and validate KIZ first, atomically publish/open both PDF files, then run
   exemplar `set` and bounded `status` polling in the background. Shipping remains blocked until
   the durable job reaches `ACCEPTED`.
7. Send `scannerSafeCode`: remove only an optional leading ASCII Group Separator introduced by a
   scanner. Never remove the first GS1 data character (`01` application identifier).

### Ozon catalog and GTIN mapping

1. Creating an Ozon shop starts the same background catalog synchronization used by opening the
   Ozon workspace, even when the finance or another shared view remains visible.
2. Persist product article, category and gender from card attributes/category metadata together
   with image, color and size.
3. A mapping rule belongs to a normalized article and resolves every active catalog SKU/size under
   that article. Legacy SKU rules remain a fallback for safe upgrades.
4. The mapping dialog searches article as well as name/SKU/offer, filters by category and gender,
   and its select-all checkbox affects only the currently visible filtered result set.

### Wildberries supply deletion

1. Show an icon-only delete action next to the supply selector, with accessible text and a tooltip.
2. Enable it only for an active supply whose synchronized order count is zero.
3. Ask for confirmation, then re-read the remote order IDs before calling
   `DELETE /api/v3/supplies/{supplyId}`.
4. Never retry the delete mutation automatically. Remove only the local supply row after remote
   deletion succeeds; keep WB orders and unrelated data intact.

### Znack GTIN eligibility

1. A successfully fetched National Catalog card with at least one complete permit document remains
   in the active GTIN catalog.
2. A successfully fetched card without a complete permit document is synchronized locally and
   soft-deleted into the existing trash view.
3. A failed or malformed catalog batch is indeterminate: preserve existing local state and do not
   activate newly discovered GTINs from that batch.
4. Manual restore remains available. A later sync still re-applies the document requirement.

## Testing strategy

- HTTP contract tests assert Ozon's v4 unfulfilled path/cutoff cursor and WB's guarded deletion.
- Exemplar tests prove one KIZ per physical unit, validate-before-print, print-before-set and exact
  GS1 payload preservation.
- Catalog/repository/UI tests prove article-level resolution, category/gender persistence and
  filtered select-all behavior.
- Repository/service tests prove status transitions, local supply cleanup and no-delete guards.
- Znack tests cover document-present, document-missing and partial-batch behavior.
- FXML smoke tests verify the delete action loads and remains icon-only/accessibly labelled.

## Boundaries

- Always: validate third-party responses, preserve app responsiveness and keep mutations explicit.
- Ask first: deleting a non-empty supply, permanently deleting a GTIN, or changing KIZ ownership.
- Never: retry marketplace mutations blindly, discard KIZ/order history, or move GTINs to trash
  because a document lookup merely timed out.

## Success criteria

- An Ozon web-side status transition appears in Packing after the next lightweight background sync.
- An Ozon print operation opens completed PDFs without waiting for remote `set/status`; KIZ remain
  reserved until background acceptance, and ship still fails closed before acceptance.
- Mapping one article resolves all of its synchronized SKU/size variants.
- A user can delete a confirmed-empty active WB supply and it disappears locally without deleting
  orders.
- A Znack GTIN lacking card documents appears in Trash, while documented GTINs remain operational.
- Focused tests and `./mvnw -B clean verify` pass.

## Implementation tasks

- [x] Add regression tests for Ozon unfulfilled synchronization.
- [x] Add guarded WB supply deletion from API through JavaFX UI.
- [x] Enforce Znack permit-document eligibility during synchronization.
- [x] Split Ozon validate/print from background set/status without duplicating KIZ.
- [x] Add article mapping, filtered select-all and category/gender catalog metadata.
- [x] Run focused and full verification, then review the complete diff.
