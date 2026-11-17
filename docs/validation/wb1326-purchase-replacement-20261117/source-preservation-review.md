# Full pending-work and preservation review

As of 2026-11-17T14:30:50.507692+00:00: source/security preservation passes within the declared purchase replacement scope. Verified affected-scope2296 cases and actual packaged Launcher smoke passed; no full Java-suite or external Windows/paid acceptance is claimed.

The pre-replacement working tree contains 3,947 backed-up files. Archive SHA256 `8e73de42982b9e50bc13e9776a4dd4ef4e8074deb11f5b6d177b8faf5e360533`; CRC check passed. No backed-up file is missing. 3,892 files remain byte-identical; 55 changed files are approved purchase implementation/tests, localization or project memory.

## All pending work relative to HEAD

| Group | Already pending at backup | Pending now at inventory time |
|---|---:|---:|
| ECC instructions and validators | 26 | 26 |
| First public VNCode and WBCode purchase research | 73 | 73 |
| KIZ purchase, protocol, circulation and signing engine | 12 | 66 |
| Localized purchase and key-wait messages | 4 | 4 |
| Other shared safeguards or documentation | 1 | 5 |
| Ozon paid-code acceptance, replacement and combined PDF | 30 | 30 |
| Ozon registration entry and WB-style FBS layout | 106 | 106 |
| Project memory | 3 | 3 |
| Purchase shortage consent, stock policy, mapping and signing resume | 10 | 18 |
| Registration and shop navigation preservation | 3 | 3 |
| Schema 6 to 7 downgrade fence | 0 | 1 |
| Startup recovery and UI inspection | 2 | 3 |
| WBCode 1.3.26 replacement evidence | 0 | 42 |

The full path inventory is in `FULL-PENDING-INVENTORY.json`; the earlier dirty work is not discarded or replaced by the Git HEAD. Counts are a timestamped inventory and may grow when parent adds final reports.

## Intentionally replaced purchase internals

- One-GTIN-only remote emission replaced by durable homogeneous batches of up to10 GTINs.
- Single shared purchase queue replaced by per-shop4/2/2 stage pools with per-GTIN FIFO.
- Global Catalog revision purchase gate replaced by pinned-method local permit validation; pure native variant identity remains.
- Foreground-shop signing prerequisite replaced by verified multi-shop startup admission plus durable pause/key/profile epochs.
- Optional new-purchase circulation replaced by mandatory true; raw historical explicit false consent remains bound.
- Old implicit low-level retry replaced by bounded, evidence-based known-negative recovery; unknown outcomes reconcile.

## Preserved source contracts

| Group | Files | Baseline comparison |
|---|---:|---|
| ozon_paid_acceptance_and_label | 80 | Identical |
| seller_import_and_stock_policy | 10 | Identical |
| catalog_registration | 113 | Identical |
| gtin_sync_and_mapping | 38 | Identical |
| wb_api_orders_products | 64 | Identical |
| ui_glass_styles | 3 | Identical |
| ui_fbs_views | 3 | Identical |
| license_updater | 11 | Identical |

Protected hashes prove the source was retained; they do not prove every unexecuted seller/API/UI scenario. Shared purchase changes are validated separately in `PARITY-REVIEW.json`.

## Scoped security review

- TLS validation, license/update classes and dependencies are unchanged from the backed-up working baseline.
- Paid order, consuming-code and signed-document calls each have one canonical production send boundary with no implicit mutation replay.
- Signing epochs bind shop, original account, certificate and app-data profile; durable pauses are not cleared by automatic startup.
- Exact CIS/GTIN/quantity/owner checks, original consumption/reservation states and Ozon accepted-slot rules remain.
- New local context/download-target state uses the existing settings security boundary; no API key/token/private key is written by the new context model.
- Destructive uniqueness DDL is transactional with schema7 fence and pre6 snapshot; actual old binary refuses7.

No new production JWT/private-key literal or TLS-bypass construction was found in 2,804 inspected added source lines. Existing build/dependency/agent configurations remain byte-identical to this task's complete baseline. This is not an external security-service audit.

## Boundaries

- This is a scoped source/security review, not a whole-application runtime acceptance.
- No Maven, paid/live API, Windows private profile, CryptoPro signing or license operation executed by the reviewer.
- Bounded affected regression and actual packaged Launcher smoke passed; final documentation/source ZIP refresh remains with parent.
