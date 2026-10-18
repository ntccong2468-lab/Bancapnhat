# VNCode update baseline

The baseline for subsequent updates is the published **VNCode 1.4.19**.

- Immutable tag: `v1.4.19`
- Application source: `d25718c6a69f94f08d9515d36f7444650b1eebd1`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.19
- Installer: `VN-code-1.4.19-Windows-x64.exe` (145632256 bytes)
- SHA-256: `e2d43bdca42031b6e583dea8efa298addc142e46fadfb2a21ca00b6e5cc31084`
- Windows build: `39114287816`; publication: `39116801568`.
- Previous baseline: **1.4.18**, tag `v1.4.18`, source `e77a746128fa51f183a2a193275b6b1ef894c251`; its tag and release remain immutable.

Build subsequent versions on this application source and its later fixes; never move or overwrite published tags. Preserve schema 6 seller KIZ occurrences and migration, full product synchronization, per-variant GTIN progress and TN VED identity checks, transparent Liquid Glass settings, bulk product editing, bottom-centre background status, continuous priority FBS synchronization, and the existing paid KIZ purchasing flow.

Fresh Windows verification passed: 1766 Java tests (1764 passed, 2 platform skips), startup/DPAPI, three DPI levels, 288 layouts and 14 upgrade paths including 1.4.18. The complete public EXE download matches its checksum. The production updater made a real request and reports AVAILABLE for installed 1.4.18.

Evidence is linked in [1.4.19 status](VN-code-1.4.19-status.json). Live WB API GTIN storage was verified separately. Licensed seller-desktop button acceptance, enterprise CryptoPro signing and production license acceptance remain unverified; do not infer them from native or automated checks.
