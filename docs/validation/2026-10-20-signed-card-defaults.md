# Automatic registration defaults from signed Catalog cards

## Read-only evidence

The connected Catalog account listed 1,277 cards. Excluding a published card with an active draft left 1,240 clean published list entries. Twenty-four exact card details were read and confirmed signed/published without an active error, moderation or unsigned draft. They covered six TN VED groups, eight complete codes and four current category schemas. They contained three size systems and both article/model types; these facts must remain specific to each source variant.

The [sanitized card evidence](2026-10-20-signed-card-template-evidence.json) contains structures, public taxonomy values and response hashes, without seller identifiers or copied business values. A green card is evidence of its observed state, not proof that its classification or permit suits another product. No clean published 6109 example was available.

All 24 sampled green cards omitted manufacturing country (2630), factory/site (2502) and producer (2503). A fresh read of the official country dictionary confirmed `country_name=Россия`, `country_iso=RU`. The complete current clothing schema announces optional country attribute 2630 with an external dictionary. [Country evidence](2026-10-20-production-country-evidence.json) records the exact read-only calls and response structure.

## Applied behavior

- Shared default `vncode-catalog-signed-reference-v4` uses the current product, variant, category model and selected verified permit for WB/Ozon. The [readable prompt](../registration/default-znack-prompt.md) describes implemented rules; it does not invoke an AI service.
- Known grammatical forms such as `Костюмы спортивные` select one exact allowed Catalog literal. Similar but different garments and ambiguous choices are not merged.
- Technical regulation can fill automatically from the selected active permit's actual `product_tech_regulations`. Exact document type, number/date, validity, schema choice and data profile remain bound; the response cache is copied, bounded and expires after 120 seconds. No regulation is inferred solely from green examples.
- Manufacturing country defaults to **Россия**, following the owner's request. Seller-facing country controls show Russian names and retain validated ISO values internally. Catalog receives `2630=RU` for Russia. An explicitly declared source country or later seller override takes precedence; unknown or conflicting source countries require correction instead of selecting the mapper's first value.
- Only the actual optional country field is added from the current complete model; factory/company identities are not invented. Country dictionary/model reads are cached and shared across the selection, rather than repeated for each card.
- Blank brand/gender/regulation/material fields do not become the only allowed preset during background completion. Source facts, current dependent required fields and typed sizes remain validated before GTIN allocation. Only unresolved values remain in the correction form.
- Existing permit-scope warnings, ownership/revision checks, GTIN/feed identity, uncertain-submission protection, signing checkpoints and verified marketplace readback remain enforced.

## Verification boundary

Production API operations in this change were reads only. No new production GTIN was allocated, card created/repaired, CryptoPro signature sent or marketplace listing changed. No new Windows installer or Latest release was published by this change. Published 1.5.4 remains immutable.

Automated verification: **527 tests across 50 affected classes passed**, with zero failures, errors or skips in their latest reports. This covers WB/Ozon automatic drafts and workflows, 25-card preflight sharing, current-country/permit caches, real HTTP transport against a local test server, durable registration/signing safeguards, actual JavaFX country dropdown/Apply actions and friendly diagnostics. The initial run exposed a new template test fixture that omitted its asserted full TN VED; the fixture was corrected to supply the same field as the real mapper, and its three tests were rerun successfully. Maven `package` passed. JavaFX tests ran under Linux/Xvfb, not a native seller Windows desktop. [Per-class result hashes and totals](2026-10-20-signed-card-defaults-regression.json).
