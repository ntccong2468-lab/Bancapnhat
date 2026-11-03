# Product editing and Catalog registration audit — 2026-11-03

Scope: the five product editors on WB/Ozon, registration preflight, source review, and Catalog feed dispatch. This branch depends on the unmerged Ozon/FBS improvement branch. No installer or production release is part of this change.

## Confirmed problems and changes

- Ozon import tasks with `skipped` stayed pending forever. Official `GetImportProductsInfoResponseResultItem` defines this as no changes. It now proceeds to fresh product/attribute readback; success still requires exact expected values. A terminal `failed` task with an empty error array now reports failure instead of polling indefinitely.
- A category-specific invalid product preview blocked valid products in the same selection. Only publishable items are sent; invalid items remain in the same durable report. Missing/ambiguous attribute dictionaries produce a blocked per-product preview rather than aborting the selection. Tests cover brand, composition, gender and TN VED and assert the invalid product receives no write.
- An editable material percentage spinner could send its previous value when Apply was invoked before losing focus. The editor now commits the visible percentage before preparing its payload and blocks non-numeric/out-of-range input.
- Ozon's `Item-Retry-After` is measured in minutes. Product HTTP cooldown calculation now honors it and takes the longest applicable server header.
- Document verification in registration had a digits-only TN VED extraction that rejected Ozon's code-plus-description labels. Document checks and dispatch now use the same marketplace tariff parser, which rejects conflicting or invalid source values rather than deriving a code from documents.
- The source review dialog did not apply the app theme and reserved a blank target column for ordinary creates. It now uses the shared theme, keeps relevant identity columns, wraps guidance and cell text, and shows sanitized preparation errors.

## Evidence and skills

- REA workflow: traced UI → preview → durable claim → API adapter → fresh readback in the available source. No REA MCP or native decompiler was used.
- TASTE app-screen guidance and IMPECCABLE hardening/craft-floor were applied to the existing JavaFX design, preserving light/dark support and list space. The mechanical Impeccable detector returned no findings on the named Java files. Including the stylesheet reported three existing Inter-font warnings outside changed lines; the established app font was retained. This detector does not establish browser/native accessibility coverage.
- OpenQodex 0.16.0 scanned changed files locally with Gitleaks. Semgrep was disabled in offline mode; this is neither a full Java analysis nor an independent AI review.
- Ozon official OpenAPI snapshot: `https://docs.ozon.ru/api/seller/swagger.json`, SHA-256 `ac32c424b516a1018bae1e6baaa39819711bd2ecb842bcd170e5d4cc71f6562d`. Contracts checked: `/v1/product/attributes/update`, `/v1/product/import/info`, certificate endpoints, and item-quota header units.
- Existing read-only configured credentials returned HTTP 200 for WB card/attribute reads, Ozon product/attribute/dictionary reads, and Catalog categories for the full marketplace TN VED and a one-row product list. No seller identifiers or credential values are included here.

## Validation and limits

The new task-outcome tests first reproduced two failures. The new mixed-validity and percentage tests each reproduced a failure before correction. After correction, a focused pass completed 128 tests; a subsequent pass including the final UI wrapping changes completed 120 tests with no failures, errors or skips. Four cooldown tests also passed.

An additional 25 HTTP/dialog tests passed while capturing the source review and all five product editors in Vietnamese. A final 22-test pass after the scoped dark-control CSS fix passed as well. Screenshots contain synthetic data, not seller values; 22 captures cover both themes for five WB editors, five Ozon editors and the source review.

After extending mixed-validity handling to missing/ambiguous material, gender and TN VED dictionaries, the final product regression run passed 88 tests with no failures, errors or skips.

Catalog integration tests exercise actual HTTP requests against localhost, feed receipts, later card visibility, rejection, throttling, response loss and restart/reconciliation. These are not production Catalog writes. UI evidence is rendered under JavaFX/Xvfb with synthetic data, not a Windows installer test.

At the initial audit stage, no live writes had been performed. The subsequent explicitly authorized live acceptance completed ten existing-value marketplace writes and one real new Catalog registration, exposing and fixing an Ozon revision bug. The new Catalog card was independently read back in moderation, not published. See [the separate live acceptance report](2026-11-03-product-edit-catalog-live-acceptance.md) for exact source commits, receipts, limitations and remaining Windows/signing checks. No Gmail, production license activation or paid KIZ action was performed.
