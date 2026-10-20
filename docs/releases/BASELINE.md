# VNCode update baseline

The baseline for subsequent updates is the published **VNCode 1.5.4**.

- Immutable tag: `v1.5.4`
- Source: `688bd01982898e18c9770300fa20cfe29bde1245`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.4
- Installer: `VN-code-1.5.4-Windows-x64.exe` (146868224 bytes)
- SHA-256: `e7079a4aca07c1ae2fec26e46c4e3a3d77096a14b1c0485a88cefe95a3f7a18b`
- Native build and fresh full Windows Java execution: `39415265129`; publication: `39417571803`.
- Previous baseline: **1.5.3**. All prior tags, assets and pinned upgrade inputs below remain immutable.

Recorded final verification: Linux clean verify 2088 tests (2086 passed, 2 platform skips); Windows fresh Java 2088 tests (2086 passed, 2 platform skips), no failures/errors; release tools 92 tests. All 24 native proof files passed, including 288 UI layouts and 19 data-preserving upgrades. The upgrade from 1.5.3 ran the exact published EXE with `/passive /norestart` and verified its embedded MSI. Public installer bytes agree with the published checksum, GitHub asset digest, build-info and acceptance receipt. The actual updater offers 1.5.4 to 1.5.3 with matching URL/hash/size; seller desktop popup delivery is not established.

Preserve schema 6 seller data, the existing registration template and permit TN VED rules, durable sequential registration, uncertain-request safeguards, verified GTIN readback, priority FBS, KIZ checkpoints and the Ozon combined label. Preserve the pinned SQLite/WAL snapshot backup, real progress and integrity/restore checks; retain canonical purchase request identities, non-replayed paid-code issuance, partial-code counts and exact-GTIN buffer reconciliation. Runtime license, signature and TLS checks remain enforced.

Evidence: [status](VN-code-1.5.4-status.json), [request report](VN-code-1.5.4-request-report.md), [Windows/native/public proof](../validation/2026-10-20-vncode-154-windows-native.json), [publication receipt](../validation/2026-10-20-vncode-154-owner-publication.json). Full production acceptance remains incomplete. This release does not establish a real paid KIZ purchase, new Catalog create/repair/signature, live seller marketplace GTIN write, enterprise CryptoPro signing, production license or multi-unit Ozon split. All corresponding acceptance flags remain false.

## Source changes after 1.5.4

The next build uses `vncode-catalog-signed-reference-v4`, documented in [the automatic registration prompt](../registration/default-znack-prompt.md). It incorporates a read-only comparison with 24 actually signed/published Catalog cards, current category dictionaries and verified permit regulations. Registration fills known WB/Ozon facts automatically; manufacturing country displays **Россия** (Catalog transport `RU`) when the source has no explicit country, following the owner's instruction. The live green samples do not themselves establish a manufacturing country. Preserve source/seller overrides, current schema validation, permit checks and durable submission/signing guards. [Validation](../validation/2026-10-20-signed-card-defaults.md): 527 related tests passed and Java packaging passed. These source changes do not alter the immutable published 1.5.4 installer or establish a new production registration.

## Previous published baseline 1.5.3 (immutable reference)

The baseline for subsequent updates is the published **VNCode 1.5.3**.

- Immutable tag: `v1.5.3`
- Source: `69ffc7ecb60a91c874510522c4aae3c5be069ccd`
- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.3
- Installer: `VN-code-1.5.3-Windows-x64.exe` (146864128 bytes)
- SHA-256: `ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31`
- Native build and fresh full Windows Java execution: `39396769729`; publication: `39398012877`.
- Previous baseline: **1.5.2**. All prior tags/assets and pinned upgrade inputs below remain immutable.

Recorded final verification: Linux clean verify: 2072 tests (2070 passed, 2 platform skips); Windows fresh Java: 2072 tests (2070 passed, 2 platform skips), no failures/errors; all 23 native proof files including 288 UI layouts and 18 data-preserving upgrade probes. The upgrade from 1.5.2 ran the exact published EXE with `/passive /norestart` and verified its embedded MSI. Public EXE bytes match GitHub's digest, the checksum, CI build-info and the actual acceptance receipt. The actual updater offers 1.5.3 to 1.5.2 with matching URL/hash/size; this does not establish a popup reached a seller desktop.

Preserve schema 6 seller data, the 1.5.2 registration template/permit TN VED scope, durable sequential registration, uncertain-request guards, exact GTIN readback evidence, priority FBS and KIZ workflows. WB FBS method remains identical to 1.5.2; Ozon adds pending per-unit shipment readback. Preserve the Ozon combined label, updater snapshot/data checks and explicit runtime licensing/signing trust checks.

Evidence: [status](VN-code-1.5.3-status.json), [request report](VN-code-1.5.3-request-report.md), [Windows/native/public proof](../validation/2026-10-20-vncode-153-windows-native.json), [publication receipt](../validation/2026-10-20-vncode-153-owner-publication.json). Full production acceptance remains incomplete. No new production Catalog create/repair, seller-desktop marketplace GTIN write, enterprise CryptoPro signing, production license or multi-unit Ozon split was established by this release. Keep those acceptance flags false; no seller desktop notification delivery is claimed.

## Previous published baseline 1.5.2 (immutable reference)

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
