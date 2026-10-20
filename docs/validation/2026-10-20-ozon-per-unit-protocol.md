Ozon per-unit package protocol research — 20 October 2026

Official references:
- https://docs.ozon.ru/api/seller/#operation/PostingAPI_ShipFbsPostingV4
- https://docs.ozon.ru/api/seller/#operation/FbsSplit
- https://docs.ozon.ru/api/seller/#operation/PostingAPI_GetFbsPostingV3
- https://docs.ozon.ru/api/seller/#operation/PostingAPI_FbsPostingProductExemplarSetV6
- https://docs.ozon.ru/api/seller/#operation/PostingAPI_FbsPostingProductExemplarStatusV5

Access and provenance:
The official documentation currently returns an HTTP307 redirect loop in this cloud environment. The schema was read from third-party copies and cross-checked with SDK implementations, not represented as a successful direct official-document fetch.
- https://raw.githubusercontent.com/dev-ik/seller-sdk/main/docs/ozon/swagger.json
- https://raw.githubusercontent.com/dev-ik/seller-sdk/main/docs/ozon/swagger.meta.json
  Metadata cites https://docs.ozon.ru/api/seller/swagger.json, retrieved2026-10-19, SHA256 4c5756ae6b68177eae367786656f1b4ebdc00f2ca76c0311cf312a011ad1f9e4.
- https://github.com/gam6itko/ozon-seller/blob/master/src/Service/V4/Posting/FbsService.php
- https://github.com/diPhantxm/ozon-api-client/blob/master/ozon/fbs.go

Observed schema/description:
- POST/v4/posting/fbs/ship accepts posting_number, packages[{products[{product_id,quantity}]}], with.additional_data. Its documented two-unit split example sends two packages, each containing the same product_id and quantity1. product_id is OzonSKU, not the seller catalog product ID.
- This endpoint splits and assembles into awaiting_deliver. The original VNCode AcceptAll action already invokes this assembly operation. Main v4ship product schema has no exemplar selector.
- Response result[] contains the actual posting numbers. additional_data contains per-posting products and mandatory_mark arrays. Do not synthesize child suffixes or assume whether the original number is retained.
- HTTP200 does not guarantee assembly. Read /v3/posting/fbs/get; substatus ship_failed means failure. with.related_postings=true returns related_postings.related_posting_numbers. with.product_exemplars=true requests actual exemplars. parent_posting_number identifies the parent.
- POST/v1/posting/fbs/split performs splitting without assembly, request postings[{products[{product_id,quantity}]}], response parent_posting plus postings. Exact accepted status list, parent inclusion in postings and KIZ-transfer behavior are not explicitly described in the fetched schema/SDK; do not claim guarantees.
- v6exemplar/set requires the full products/exemplars set. HTTP200 only creates a task; use v5exemplar/status to confirm.

Application safety conclusions:
Persist the entire frozen SKU/quantity plan and actual returned posting numbers. Preserve existing KIZ ledger state. Reconcile actual child exemplars/marks and unit conservation before remapping; never blindly copy parent item indexes or reallocate consumed KIZ. There is no documented idempotency key in the request schemas. After an uncertain response, read related postings and remain pending if proof is incomplete; do not blindly replay.
MAX_UNIT_PACKAGES=100 is a VNCode application guard, not a published Ozon API maximum.

No production mutations, GTIN allocations, KIZ updates or source edits were performed during protocol research.
