# Catalog submission confirmation and processing filter

## Actual API observations

Read-only production checks on 2026-10-20, using the configured Catalog and WB credentials:

- Catalog: 1,277 cards across two pages, including 1,241 published, 34 errors, and two plain drafts. Counts are unchanged from the earlier same-day read. No newer update timestamp was reported by the listing.
- WB: 388 products and 1,982 variants across four pages. Exact barcode membership finds one variant with a published GTIN from this Catalog account. Membership alone does not establish product identity or document eligibility.
- Catalog returned 130 already allocated draft GTINs; none have a visible card in the account listing. The read did not allocate GTINs and cannot exclude local reservations or uncertain earlier requests.
- The 46 inspected cards returned no genuine feed ID or moderation explanation. No request IDs were guessed. A failed live app request cannot be reconciled from aggregate counts alone.
- The first list attempt received HTTP 429 with a 31-second Retry-After; calls stopped and resumed after the delay. A probe-only invalid detail delimiter received HTTP 400 and was corrected; that was not an app registration response.

Evidence: [current API read](2026-10-20-catalog-submission-readonly.json), [write readiness check](2026-10-20-catalog-submission-readiness.json). Both files contain sanitized aggregates, not API keys, product identifiers, GTIN values, or permit numbers.

## Code changes

- A verified Catalog API key authenticates Catalog calls directly. A new True API/CryptoPro challenge is no longer requested unnecessarily before Catalog preflight or submission. Existing ownership, account scope, license, certificate prerequisites, document validation, and actual signing checks remain.
- An accepted feed is saved but does not increment the created-card count. An exact GTIN read with a positive Catalog card ID must confirm the card. Failed/Error feeds are rejected even without a detailed message. Foreign feed identities cannot borrow another card as proof.
- WB and Ozon retain accepted requests in a durable waiting-for-readback state. Pending imports do not block the remaining selected products. Readback does not repeat the create POST or allocate a replacement GTIN. HTTP 429 stops the current pass and preserves the server's retry delay.
- Pending receipt wording is distinct from confirmed moderation. Progress read failures are visible and recover automatically; Ozon reports do not reuse an older published snapshot as proof after a failed read.
- Actual Catalog drafts are included in the processing filter. Active batches refresh local processing rows when the card stage changes even if counters do not. Filters and search remain available during background work.

## Validation and limits

Final automated regression: **455 related Java tests passed**, with zero failures, errors or skips; **55 actual FXML smoke tests passed** in a separate JVM, and **10 entrypoint contracts passed**. This includes exact-card HTTP readback, delayed import/restart without a second POST, permanent identity failure, API-key versus signer separation, processing filters/live refresh, preserving 600 loaded rows, and real JavaFX window activation. [Machine-readable results](2026-10-20-catalog-startup-regression.json).

Initial failures exposed incorrect multi-card test identities and Ozon's filter event handling; these were corrected before the final run. FXML smoke tests use a separate JVM to prevent delayed fixture modal/update callbacks crossing into a different temporary profile. The startup assertion that the first window does not open SQL was preserved.

No live product was created, signed, or published by these checks. The cloud read did not establish an actual selected product-to-verified-permit binding, an unreserved GTIN, or the seller's Windows CryptoPro session. It therefore does not prove a successful production registration or signing operation. The default registration template and TN VED/document rules were not changed.

This is a source correction after the existing 1.5.4 release. The published 1.5.4 installer and release evidence are unchanged.
