# WB → National Catalog card registration (test scope)

## Confirmed data model

- A WB `vendorCode` (article) represents the color-level product card in the current seller catalog.
- Every real WB size is identified by `sizes[].chrtID`; therefore WCode creates one National Catalog card/GTIN per existing size, never a Cartesian product of colors and sizes.
- This test stops after the National Catalog card is published. It deliberately does not change the WB card or its `sizes[].skus` values.

## Guarded test workflow

1. Read synchronized WB cards and show one row per `chrtID`.
2. Ask for TN VED and resolve active National Catalog categories through the authenticated True API gateway `GET /api/v3/true-api/nk/categories?tnved=...`. For a ten-digit code without a direct catalog mapping, retry its four-digit group as specified by National Catalog API v5.62; retain the full ten-digit code in the product feed and attribute `13933`.
3. Load the current mandatory attribute model through `GET /api/v3/true-api/nk/attributes?cat_id=...&attr_type=m`; do not hard-code one apparel schema.
4. Authenticate using the certificate assigned to the selected shop and call `GET /v3/generate-gtins?exist=1` to check the current monthly GS1/GTIN quota without consuming a new number.
5. Auto-fill values from the WB card. The user completes every missing mandatory value and chooses either declaration attribute `23557` or certificate attribute `23561` with `number:::YYYY-MM-DD`.
6. Reuse an unused draft GTIN already returned by `exist=1`; only generate one when no unclaimed
   draft exists. If the allocation response omits `result.drafts`, read `exist=1` back and reconcile
   the allocated code without repeating the state-changing request. Persist the selected GTIN
   locally before the first feed request so a timeout or restart cannot generate a duplicate.
7. Submit one test entry to `POST /v3/feed`, persist `feed_id`, and poll `GET /v3/feed-status?verbose=true&feed_id=...` every 15 seconds.
8. When moderated, get XML using `POST /v3/feed-product-document`, sign the raw XML with a detached PKCS#7 signature, and submit it with `POST /v3/feed-product-sign-pkcs`.
9. Mark the local row as `PUBLISHED`, keep its GTIN/feed/good checkpoints, and stop. Updating the WB card is outside this test.

## Naming rule used by the test

`<WB title>, <brand>, арт. <vendorCode>, цвет <color>, размер <size>`

The value is editable before creation. This keeps size cards unique and readable while preserving the seller's WB terminology.

## Safety and operational limits

- No GTIN is generated before certificate authentication, quota, TN VED, category, document and every mandatory category attribute pass local validation.
- `/v3/feed` supports at most 500 entries; the test deliberately submits one row at a time.
- National Catalog signing endpoints accept at most 10 cards; the test signs one row at a time.
- The test executable has a separate Windows application identity and stores data under `WCodeZnackRegistrationTestData`. It never scans or migrates production `WCodeData`/legacy directories and never offers a production auto-update.
- No request to `POST /content/v2/cards/update` exists in the test registration workflow.
- The registration table stores GTIN, payload, feed ID, good ID and status per `(shop_id, chrt_id)`. Opening the tab resumes in-progress rows from the stored checkpoint.

## Feed type and photo recovery (1.1.32)

- Preserve the schema's `attr_value_type[]` separately from `attr_field_type` and serialize
  the selected literal type in every `good_attrs` entry. Article values use the schema's
  article type; letter sizes use its international type. Russian sizing is selected when
  the value matches WB `sizes[].wbSize`; ambiguous systems stop before GTIN allocation.
- Refresh attribute types before rebuilding failed/legacy feeds, preserving the already
  allocated GTIN and the distinct full TN VED / feed group on resume.
- When a failed feed includes an explicit inaccessible-photo error, omit that optional
  image on retry, including mixed photo + attribute failures. Existing image-only
  failures retry once without the optional photo. No externally accessible image host
  is introduced.
- Regression coverage checks schema parsing, payload types, checkpoint round trips,
  Russian/international sizing, ambiguous sizing, and mixed photo failures.
- Remote acceptance still requires testing with the user's authenticated catalog account.
  Rollback: install the previous test release; no database migration is introduced.

## Official references

- National Catalog API: https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/
- WB marking-card changes: https://dev.wildberries.ru/release-notes
