# VNCode update baseline

The baseline for subsequent updates is the published **VNCode 1.5.2**.

- Immutable tag: `v1.5.2`
- Source: `6fe8ef21871657a38151897ddc13c3b1b5028fd5`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.2
- Installer: `VN-code-1.5.2-Windows-x64.exe` (146716672 bytes)
- SHA-256: `4f1b28dbb8eaffa4f86a5512f8155b00f7c7688d744e778e87c9f03120feb31c`
- Native build and fresh full Java execution: `39337203593`; publication: `39338177252`.
- Previous baseline: **1.5.1**, tag `v1.5.1`, source `f9da70ca98734b8c5daed0a6ef49b38df9723f54`; its published tag, assets and evidence remain immutable.

Build subsequent versions on this source and later fixes. Never move or overwrite published tags. Preserve schema 6 seller KIZ occurrences/migration, full product synchronization, GTIN batching and per-variant readback, Liquid Glass settings, bulk product editing, bottom-centre background status, continuous priority FBS synchronization, KN3 replacement, paid KIZ purchasing, updater/startup progress, safe snapshot retention and the selected blue VN logo.

Registration uses `vncode-catalog-official-v3`, based on published Catalog API 5.68. Preserve valid TN VED, exact good_id for edits, dynamic dependent mandatory fields/types, explicit seller review of permit-scope warnings, and uncertain-request/checkpoint protections. Do not infer a moderation reason or permit legal suitability from registry prefixes or another green card.

Recorded verification: 1954 fresh full Windows Java tests (1952 passed, 2 platform skips), native launcher/schema/DPAPI/workspace/DPI checks, 288 layouts and 17 data-preserving upgrades including 1.5.1. Public installer bytes were compared with GitHub and CI before publication. The production updater verified that installed version 1.5.1 is offered 1.5.2 with the expected installer URL, digest and size. This does not establish delivery of a notification to a seller desktop. Subsequent versions must also run the pinned 1.5.2 upgrade probe; those later results are not established by this baseline.

Evidence: [status](VN-code-1.5.2-status.json), [request report](VN-code-1.5.2-request-report.md), [native build](../validation/2026-10-19-vncode-152-windows-native.json), [publication receipt](../validation/2026-10-19-vncode-152-owner-publication.json), [rules](../validation/2026-10-19-catalog-official-rules.md). Publication follows the continuing owner request with disclosed limits. Seller-desktop writes, enterprise CryptoPro signing, new live marketplace/Catalog writes in this version, production license acceptance and bulk red-KIZ validation on upload remain unverified or unfinished; keep their acceptance flags false. Earlier live WB write/readback evidence belongs to 1.4.19.
