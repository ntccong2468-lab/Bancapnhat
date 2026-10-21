# National Catalog country contract — phase C

This report addresses sections B/C of the owner-provided master request. It records the documented contract and focused read-only evidence; it does not certify registration, signing, marketplace writes, a build or a release.

The fresh observations were made on **2026-10-20 at 22:50–22:51 UTC**. The report filename follows the phase-C documentation date. No card list was scanned, GTIN allocated or production object changed during this check.

## Sources and version

- [Official National Catalog API documentation](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/) returned HTTP 200 and still identified **5.68, dated 28 September 2026**. The downloaded HTML SHA-256 was `d2d0f01e96a060f73ba55f48d819292e7d5d0f9092e9129a80d1e44405130491`.
- [§3.3.3 — production-country dictionary](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/#_метод_получить_справочник_стран_производства).
- [§2.2 — authentication and common parameters](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/#_универсальные_параметры_запросов).
- [§2.4 — HTTP ETag](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/#_http_etag_контроль_версий).
- [§2.5 — HTTP response codes](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/#_коды_ответов), and [§2.1 — request limits](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/#_лимит_запросов_к_api).
- Earlier sanitized evidence: [actual response envelope](2026-10-21-catalog-country-envelope.json) and [focused hotfix history](2026-10-21-catalog-country-hotfix.md).

The fresh machine-readable observation is in `/tmp/vncode-20261021-country-contract-readonly.json`. It contains paths, response metadata and public taxonomy information, without credentials, GTINs, company details or permit numbers. Its two Catalog requests were `GET /v3/dictionary/isocountry` and `GET /v3/attributes?cat_id=31326&attr_type=a`, both HTTP 200.

## Confirmed dictionary representation

The documented method is **GET `/v3/dictionary/isocountry`**. Authentication is the configured `apikey` or GIS MT bearer token; authentication values must not appear in diagnostics.

Both the official JSON example and the live response use this shape:

```json
{
  "apiversion": 3,
  "result": {
    "_etag": "${server_dictionary_hash}",
    "_list": [
      {"country_iso": "RU", "country_name": "Россия"}
    ]
  }
}
```

The example above is abbreviated; it is not the full dictionary.

| Check | Fresh observation |
|---|---|
| Top-level fields | `apiversion`, `result` |
| API version | Integer `3` |
| `result` fields | `_etag`, `_list` |
| `_etag` | Nonempty string |
| `_list` | 254 objects |
| Each object's fields | Exactly `country_iso`, `country_name` |
| Code values | All 254 were uppercase ASCII strings with two letters |
| Name values | All 254 were nonblank strings |
| Distinct ISO values | 254; no duplicate codes |
| Name collisions | None after NFKC, case, whitespace and `ё`/`е` normalization |
| Russia | `country_iso="RU"`, `country_name="Россия"` |

The country fields are described as strings in §3.3.3. The stricter row validation needed to construct an unambiguous application mapping is an application requirement; the documentation does not promise that every future response will pass it. A malformed row must not be silently discarded and the remaining subset cached as a fully verified dictionary. Use the server dictionary rather than an unrelated hardcoded list of permitted countries.

A flat `result` array, a root-level array, arbitrary `data`/`items` wrappers or a recursive search for any array are **not established by this contract or the live response**. The True API proxy's flat-array behavior has not been observed either. Compatibility fixtures are not evidence that a production endpoint returns that representation.

## Completeness and pagination

Section 3.3.3 exposes only authentication parameters and describes the returned country dictionary as `_list`. It documents **no pagination parameter, page size, cursor, total count or continuation flag**. The live response likewise had no metadata outside `result._etag`/`result._list` and no additional top-level fields.

Therefore:

- Read and validate the entire documented `_list` as the single dictionary response. There is no documented second page to fetch.
- Do not invent `page`, `offset`, `next`, `hasMore` or `total` requests or reinterpret a different endpoint's pagination contract.
- **254 is an observation, not a fixed completeness threshold.** A legitimate dictionary update can change its size.
- The current contract cannot distinguish a syntactically valid, unexpectedly shortened `_list` from a valid changed dictionary by a total-count comparison: no authoritative total is supplied. Do not claim a test proved detection of every upstream truncation case.
- If the upstream representation changes or introduces an unverified continuation mechanism, report that contract change and block dependent writes rather than claiming completion from a guessed wrapper or cursor.

Tests called “incomplete pagination” must describe this limitation accurately. They must not establish invented paging fields as supported production behavior.

## Errors and diagnostics

Section 2.5 documents these relevant transport meanings: 400 invalid parameters, 401 authentication missing, 403 access denied, 404 not found, 429 request limit, 500 internal error and 503 service unavailable. For 429, `Retry-After` is documented as seconds until access resumes. Section 2.1 also documents `API-Usage-Limit` and, when a method-specific limit exists, `API-Method-Usage-Limit`.

Section 3.3.3 provides a successful JSON/XML example. It does **not** establish a country-specific business-error JSON wrapper, business-code field or success-status field. No country error response was induced in this check. A country HTTP-200 body that does not satisfy the verified success schema must not authorize a card write; do not invent a business error code by treating another method's `errors` representation as this method's contract.

Observed response headers were:

```text
Content-Type: application/json; charset=UTF-8
Cache-Control: no-store, no-cache, must-revalidate, post-check=0, pre-check=0
API-Usage-Limit: ${observed_usage}
```

No HTTP `ETag`, `Last-Modified`, `Expires`, `X-Request-ID`, `X-Correlation-ID` or `API-Method-Usage-Limit` was observed in these successful responses. No 429 occurred in this focused check. Request IDs and business codes should only be logged when actually provided through a verified source; their absence must remain explicit.

Preserve the original transport/JSON/schema exception and classify the failure at its actual stage. A missing country mapping is not evidence of an invalid GTIN, and a dictionary failure is not evidence that a card was submitted or rejected.

## `_etag` and cache boundaries

The dictionary response documents a body `_etag`. That value can identify the validated representation and be retained as evidence together with a canonical content hash and download time.

**This does not establish conditional HTTP caching for this endpoint.** Section 2.4 explicitly lists `product`, `short-product`, `categories` and `brands` for its HTTP `ETag` / `If-None-Match` / 304 mechanism; it does not list `isocountry`. The focused dictionary response supplied no HTTP `ETag` header and explicitly supplied `Cache-Control: no-store, no-cache, must-revalidate, post-check=0, pre-check=0`. No conditional country request was made based only on the body `_etag`.

Consequently, the two-minute application TTL in earlier code is not an API-mandated lifetime. Do not claim the body `_etag` permits indefinite reuse, guarantees a 304 response or overrides authentication failures and the observed cache directives.

An active operation can retain its already-validated dictionary as its input snapshot so its rows use one consistent mapping. That is distinct from maintaining a reusable HTTP/disk cache or serving a later operation without current verification. Any reuse policy must explicitly account for the response cache directives, environment, configured endpoint, relevant account/profile scope and verification time. It must not reuse a different profile's snapshot or fall back to stale data after 401/403.

Only a completely validated snapshot may replace a previously good one. An HTTP error, HTML/login body, malformed row, ambiguous mapping, empty response or incomplete/unknown representation must leave the previous snapshot intact without authorizing the failed operation to submit.

## Current country attribute

The fresh full schema for category 31326 returned:

| Field | Value |
|---|---|
| `attr_id` | `2630` |
| `attr_name` | `Страна производства` |
| `attr_type` | `r` |
| `attr_preset_only` | `true` |
| `attr_multiplicity` | `true` |
| `attr_preset` / `attr_value_type` | Empty arrays |
| `preset_url` | `https://апи.национальный-каталог.рф/v3/dictionary/isocountry` |

The empty inline preset list points to the external official dictionary. No `national_reference=235` field or alternative reference endpoint was present in this actual attribute. Do not invent one.

The documented feed example in §3.2.2 uses `{"attr_id":2630,"attr_value":"RU"}`. `RU` is the verified Alpha-2 value for this dictionary, **not** a generic category ID, `RUS`, `643` or a value inferred from an INN or interface language. The owner's requested Russia default is a separate confirmed preference; it was not inferred from the sampled green cards.

Attribute 2630 is country of manufacture. Manufacturer/site attribute 2502, producer attribute 2503, a product-origin field and KIZ emission/circulation methods are separate concepts and must not be filled interchangeably.

## Explicit unverified points

- Country-specific business-error body format and any associated business code.
- Conditional 304 support for `isocountry` or an HTTP-cache lifetime sanctioned by this method.
- A different country envelope returned through the authenticated True API proxy.
- Any pagination or total-count mechanism for this endpoint.
- Every possible upstream truncation that still produces a valid JSON dictionary representation.

These unknowns do not prevent implementing and testing the documented single-response parser, mapping, error boundaries and per-operation snapshot. They do prevent claiming broader protocol support or production-write acceptance.
