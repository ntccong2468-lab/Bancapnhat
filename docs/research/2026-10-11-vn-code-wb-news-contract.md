# VN code: official WB news contract

Retrieved directly from [WB OpenAPI YAML](https://dev.wildberries.ru/api/swagger/yaml/en/01-general.yaml) on 11 October 2026 (HTTP 200, 71,169 bytes). SHA-256: `35f944cc2e67685c9e83e8068277e2ad1ba906552c18aaca79058e531041452f`. HTML documentation returned HTTP 498, but the official downloadable schema remained available. Repository mirrors were used to locate the source URL only.

`getV2News`: GET `https://common-api.wildberries.ru/api/communications/v2/news`; HeaderApiKey uses `Authorization`. Supply `from` (date) or `fromID` (uint64, inclusive). Response `data` contains at most 100 items: id, date (date-time), header, content (plaintext), optional tag types. News text is rendered as JavaFX text; foreign/executable links are not accepted as a source. The seller portal is the source link when the response has no item permalink.

Current per-seller limits distinguish token categories: personal, service and base-with-secret: 1/minute with burst 10; base: 1/hour with burst 1. VN code conservatively uses one request/hour until the token category is authoritatively known. A full 100-item page stores its inclusive cursor and resumes at the next allowed refresh rather than bursting. Background refresh still checks at 30 minutes and honors the stricter vendor interval; Retry-After delays survive restarts. Each item and read revision is bound to shop/marketplace/credential fingerprint. Token rotation hides the previous rights scope. VN code's own announcements retain their separate source label and cache.

No seller credentials or private feed were used during development. Tests use synthetic HTTP fixtures; real account access depends on a seller token with the documented rights.
