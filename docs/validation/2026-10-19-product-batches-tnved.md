# VNCode 1.5.1 — TN VED, Catalog and product editing recheck

## Live Catalog evidence

Read-only API scan at **19 October 2026, 14:20:50 Europe/Moscow**: 1,277 / 1,277 cards, including 1,240 with a published active version, 34 draft cards with `errors`, two ordinary drafts and one `notsigned` draft. One published card also has a draft revision. All 34 errored cards were read individually in batches of 25; the permit registry lookup was also repeated. No seller data was mutated.

All 34 errored cards reference a permit whose returned scope is **6101, 6201, 6202, 6211**. Their groups are **6110: 24, 6106: 7, 6103: 3**. That permit does not cover those products. Seven male T-shirt cards also have a 6106 tariff code whose reference description is for female blouses. These are verified validation problems, not a claim that the list API returned each moderator's full rejection explanation: it does not return that explanation without the original feed status.

The product determines the correct tariff classification. The permit must cover it; VNCode must never rewrite TN VED merely to fit a permit. Existing remotely rejected cards remain rejected until the product/document data is corrected and resubmitted. A source code fix or a local build does not repair those remote cards.

## Registration behavior

The existing v2 default template resolves an active Catalog category from the product's TN VED, validates the selected permit by number/date against registry status, dates and tariff scope before allocating a new GTIN or sending a feed, and blocks clear gender/tariff contradictions. This change also chooses a **single registry-verified valid permit automatically**, even if marketplace metadata originally marked it unverified. The cached registry response is re-evaluated for each product's TN VED; a result for 6110 cannot authorize a 6106 card. Multiple unresolved choices still need review.

Per-card remote states and rejected attribute details, signing and publication readback remain in the preceding 1.5.1 change. Registration is complete only after the exact card is read as published. A feed receipt or source refresh is not registration success.

## Product edits and speed

- WB full-card edits are sent in windows of at most **100 cards**, additionally bounded by the official 10,000,000-byte / 3,000-card ceiling. Preserve all untouched sizes, barcodes, attributes and permits. A 1,000-card edit uses ten write requests rather than 1,000; the WB update quota remains enforced.
- Ozon brand, gender, material and TN VED edits use **`POST /v1/product/attributes/update`**, at most 100 products per request. This official endpoint changes only requested attributes and returns a root `task_id`, followed through `/v1/product/import/info`. It does not require a product price or resubmit the barcode set, PDFs, media or other attributes. Product IDs remain pinned through pending readback, even when several selected products share a task.
- A further read-only lookup of the seller’s live Ozon category confirmed both `Материал` (4496, dictionary 1503) and `Состав материала` (4604, free text), plus `Материал подкладки` (4309). Material edits and Catalog default composition now prefer the composition field; do not send percentage strings into the material-name dictionary or substitute lining. Resolve IDs from each category’s dictionary, rather than hardcoding these sample IDs.
- Ozon attribute validation reads omit certificates and prices; after verified success the complete cache is refreshed separately. Document edits retain certificate validation, upload and binding readback. Certificate upload is a separate per-product asset phase; the ten-request attribute benchmark is not a certificate upload benchmark.
- For substantial selections, a run-scoped owned-catalog index reads product pages once and refreshes before each write window. Dictionaries are shared by category within one operation. Shared import-task and WB error-list pages are read once per readback poll. A fresh operation discards these caches. Small selections in a disproportionately large catalog retain individual reads.
- Every HTTP window has an atomic SQLite claim and result checkpoint. Do not hold a write transaction or an HTTP request slot during network/rate-limit waits. Check current owner and license immediately before send. Keep existing marketplace limiters and FBS request reservations.
- A lost response or server error triggers readback without replay. An explicit 429 releases the unsent batch for a later retry. Rejected Ozon task members retain their own error without rejecting successful siblings. A remotely changed later window is blocked before sending stale data. Count saved only after exact marketplace readback; keep the existing Apply/Hủy dialogs and bottom-center status pill.

## Protocol sources

Official WB content update contract and limits: `https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml` (already verified in the GTIN bulk contract).

Official Ozon Swagger: `https://docs.ozon.ru/api/seller/swagger.json` (SHA-256 `4c5756ae6b68177eae367786656f1b4ebdc00f2ca76c0311cf312a011ad1f9e4`); inspected the downloaded 19 October protocol artifact, including `v1ProductUpdateAttributesRequest.items.maxItems = 100`, request attributes and root `v1ProductUpdateAttributesResponse.task_id`. Publication, import status, certificate uploads and approval remain distinct.

## Verification scope

The machine-readable evidence next to this report records final test counts and simulated request counts. The batch tests use actual application readers, adapters, claims, SQLite, and reconciliation with synthetic HTTP responses. They do **not** write 1,000 real seller products. Real Catalog verification in this report is read-only. Windows CryptoPro signing and a production seller write through the application remain unverified in this Linux environment; no production license or signature was bypassed.

Saved into 1.5.1 development. No Latest publication or seller update notification is part of this recheck.

Final checks: **525 related regression tests passed**, followed by **48 focused tests passed** for the final composition/UI changes. The latest result of each of 101 test classes across the broader runs totals 694 passing cases; this is a combined matrix, not a single 694-test invocation. The earlier broad run found an expired-license readback regression, which was corrected and rechecked. Both 10,000-GTIN pipeline tests passed in the broad run; their code was not changed by the material-only follow-up.

Built `target/VNcode-1.5.1.jar` successfully; SHA-256 `dc7f3aec3ee0dc4e23c05940c279e51f9598988c2fbbf10a81498a7ddaaaa31b`. Production JAR contains the updated adapters/session and default template v2, and excludes test license fixtures. [Machine-readable results](2026-10-19-product-batches-tnved-evidence.json). This is a local application JAR, not a published Windows installer.
