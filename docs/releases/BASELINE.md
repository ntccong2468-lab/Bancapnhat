# VNCode update baseline

The baseline for subsequent updates is the published **VNCode 1.5.1**.

- Immutable tag: `v1.5.1`
- Source: `f9da70ca98734b8c5daed0a6ef49b38df9723f54`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.1
- Installer: `VN-code-1.5.1-Windows-x64.exe` (146651136 bytes)
- SHA-256: `57dae2a5932697789c1cec8acc53de768e458516b0158f0ae83ed96b065d3788`
- Native build: `39294782641`; publication: `39294977095`.
- Full Java execution: run `39286749991`, source `96e20614882488a2d60319fc29f4ca62b6cb9b81`, artifact/JAR pinned by SHA-256. Application/test/dependency/packaging inputs are identical; Java was not executed again in the recovery run. All native checks ran again.
- Previous baseline: **1.5.0**, tag `v1.5.0`, source `2eea069123737e2c9f7bcddcecaa51f21a1645dc`; its published tag, assets and evidence remain immutable.

Build subsequent versions on this source and later fixes. Never move or overwrite published tags. Preserve schema 6 seller KIZ occurrences/migration, full product synchronization, GTIN batching and per-variant readback, Liquid Glass settings, bulk product editing, bottom-centre background status, continuous priority FBS synchronization, KN3 replacement, paid KIZ purchasing, updater/startup progress, safe snapshot retention and the selected blue VN logo.

Registration uses `vncode-catalog-official-v3`, based on published Catalog API 5.68. Preserve valid TN VED, exact good_id for edits, dynamic dependent mandatory fields/types, explicit seller review of permit-scope warnings, and uncertain-request/checkpoint protections. Do not infer a moderation reason or permit legal suitability from registry prefixes or another green card.

Verification: 1924 full Java tests (1922 passed, 2 platform skips) from the verified origin, current native launcher/schema/DPAPI/workspace/DPI checks, 288 layouts and 16 data-preserving upgrades including 1.5.0. Public installer bytes match GitHub and CI. The unchanged production updater made an actual Latest request and returned AVAILABLE for the installed-version test value 1.5.0. This does not establish delivery of a notification to a seller desktop.

Evidence: [status](VN-code-1.5.1-status.json), [request report](VN-code-1.5.1-request-report.md), [rules](../validation/2026-10-19-catalog-official-rules.md), [publication/download/updater](../validation/2026-10-19-vncode-151-published-latest.json). Publication follows the continuing owner request with disclosed limits. Seller-desktop writes, enterprise CryptoPro signing, new live marketplace/Catalog writes in this version, production license acceptance and bulk red-KIZ validation on upload remain unverified or unfinished; keep their acceptance flags false. Earlier live WB write/readback evidence belongs to 1.4.19.
