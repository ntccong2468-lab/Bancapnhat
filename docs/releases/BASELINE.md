# VNCode update baseline

The baseline for subsequent updates is the published **VNCode 1.5.0**.

The current development target is **VNCode 1.5.1**, as requested on 19 October 2026. It includes GTIN batching (`fec7f2a`) and marketplace KN3/replacement (`40fa850`) and retains all published 1.5.0 fixes. Continue new fixes on this source; record them in [1.5.1 notes](VN-code-1.5.1.md). This is a saved development version, not a new published baseline. Keep the existing 1.5.0 tag, installer, Latest state and publication evidence unchanged until 1.5.1 is released.

- Immutable tag: `v1.5.0`
- Application source: `2eea069123737e2c9f7bcddcecaa51f21a1645dc`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.0
- Installer: `VN-code-1.5.0-Windows-x64.exe` (145660928 bytes)
- SHA-256: `b5b5ddd562213aa655c3183bc8e7e998ba58d42abd1563442a397865a5faea3f`
- Windows build: `39130866288`; publication: `39134818816`, attempt 2.
- Previous baseline: **1.4.19**, tag `v1.4.19`, source `d25718c6a69f94f08d9515d36f7444650b1eebd1`; its tag and release remain immutable.

Build subsequent versions on this application source and its later fixes; never move or overwrite published tags. Preserve schema 6 seller KIZ occurrences and migration, full product synchronization, per-variant GTIN progress and TN VED identity checks, transparent Liquid Glass settings, bulk product editing, bottom-centre background status, continuous priority FBS synchronization, and the existing paid KIZ purchasing flow. Retain staged update progress, cancellation before installer launch, early startup progress, safe metadata-first snapshot retention, sequential registration checkpoint completion, and the actual Catalog error/moderation/draft states.

Fresh Windows verification passed: 1792 Java tests (1790 passed, 2 platform skips), startup/DPAPI, three DPI levels, 288 layouts and 15 upgrade paths including 1.4.19. The complete public EXE download matches its checksum. The real production updater from both verified 1.4.19 and 1.5.0 JARs reports AVAILABLE for installed 1.4.19 and CURRENT for 1.5.0. This does not prove that a notification appeared on an individual seller's desktop.

Evidence is linked in [1.5.0 status](VN-code-1.5.0-status.json) and the [request report](VN-code-1.5.0-request-report.md). Publication follows the continuing owner request with disclosed limits. Licensed seller-desktop writes, enterprise CryptoPro signing, new live marketplace/Catalog writes in this version, production license acceptance and bulk marketplace red-KIZ validation on upload remain unverified or unfinished; do not infer them from native or automated checks. Earlier live WB API write/readback evidence belongs to 1.4.19.
