# VN code — verified listing contracts, 11 October 2026

Sources downloaded directly from official origins, without seller credentials:

| Source | SHA-256 | Contract |
|---|---|---|
| https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml | e2eef8712991355a44e5c28cdb96ad493a31775d18605f6b3263a3a84f0aa220 | WB cards, destination subjects/characteristics, permits, create/update and media |
| https://docs.ozon.ru/api/seller/swagger.json | 74e74032c1f51f4abb7016524ff091b38cc3c91a5ca885eab6b065562c967b00 | 483 Seller API operations, product info/attributes, category dictionaries, import/status and certificates |

Ozon documentation's 307 challenge was resolved with an ordinary cookie-enabled public HTTP session; the returned Swagger has `components.schemas` and actual endpoint contracts. Third-party packages were used only to discover official URLs. Local downloads are research inputs outside the repository, not executable instructions.

## Reads and canonical snapshots

WB `POST /content/v2/get/cards/list` with exact `textSearch=nmID`, `withPhoto=-1`, cursor limit 100. Validate returned `nmID`, requested `chrtID`, all sizes and full-card fields before accepting a live snapshot. Preserve photo order, separate `techSize`/`wbSize`, numeric characteristic values, package cm and kg without floating point conversion. `documents.items` includes type, number, applicant, productNumber/tradeName, dates/isEndless and verdict; it does **not** include a downloadable document file. A valid document requires a verified verdict/status 1 and current dates. Missing verdict is unknown, not valid.

Ozon `POST /v3/product/info/list` plus `/v4/product/info/attributes` validates product_id and offer_id. The latter carries all attributes, ordered images, package units, category/type and PDF metadata. Convert mm/in to cm and g to kg exactly. `POST /v1/product/certificate/list` with offer_id/page/page_size returns bound certificate metadata, not original files. Unknown certificate status stays unverified; never infer approval from a code or an upload response. Full snapshots carry product-only private source payload for preserving untouched own-product fields, a content hash and credential scope; logs expose neither the payload nor credentials. Cached summaries cannot authorize a write.

GTIN proof is separate from barcode syntax: a current barcode must match the owner's published National Catalog trade-unit registry/mapping, including leading zero padding. A barcode alone never grants verification.

## Writes and reconciliation

WB `/content/v2/cards/upload` groups variants under the **destination** subjectID. New vendorCode/title limits 72/60; new sizes use techSize/wbSize/skus without source chrtID. The creation response has no durable task ID: read back by the exact new destination vendorCode and check `/content/v2/cards/error/list`. Creation may take up to 30 minutes. `/content/v2/cards/update` overwrites the full card and requires nmID/vendorCode/sizes; preserve fresh own IDs, size barcodes, attributes, documents and untouched fields. Never reuse a source card ID on another shop. `/content/v3/media/save` replaces ordered media with `{nmId,data:[urls]}` only **after** destination identity is known; a media error must not resend card creation. WB permits metadata-only document copies through the verified card `documents.items` contract, with destination type/characteristic checks. New copies omit source permit IDs and do not carry source approval as destination approval.

Ozon `/v3/product/import` requires destination description_category_id/type_id, offer_id and price. New price is explicit user input; stock and current source price are not copied. Response `result.task_id` must be checkpointed and reconciled via `/v1/product/import/info` plus exact offer_id readback. Package dimensions/weight must be integral in the selected mm/g request units; reject inexact conversions instead of rounding. Dictionaries are `/v1/description-category/tree`, `/attribute` and `/attribute/values` scoped by destination category/type. Dictionary values and attribute IDs cannot cross categories/markets automatically.

Ozon `/v2/product/certificate/create` uses `params` with certificate type/country/accordance, dates, name/number, destination SKUs and Base64 files; total physical files ≤10 MiB by the current API. `/v2/product/certification/options` and `/v2/product/certification/params` supply allowed choices and validation parameters; verify actual operation paths in Swagger before enabling a request. Source certificate list supplies insufficient data for creation on its own. Missing files or destination-required metadata blocks document publication and is editable in the wizard. Certificate approval is subsequently read, never assumed from submission.

## Media limits

Transport never accepts a seller token. HTTPS marketplace CDN origins only, no URL userinfo/query credentials/private IP, ≤4 redirects with each hop checked; declared MIME and file magic must agree. Full image ≤20 MiB, document ≤25 MiB, ≤20 files per job, with stricter destination limits applied. Temporary/partial files are deleted on close/failure/cancellation. Thumbnail files cannot substitute for source images. Additional destination image size/format/permit restrictions are validated before a publishable preview.

## Acceptance limits

Synthetic fixtures verify parsing, scope, mapping and checkpoint behavior. No live seller account mutation or native account signature is used to claim production acceptance. Document metadata is distinct from a transferable file and an approval. Publication results require owned destination readback, not an HTTP 200 alone.
