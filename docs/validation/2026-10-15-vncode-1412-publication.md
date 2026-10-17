# VNCode 1.4.12 publication

Published at 2026-10-15T17:57:27Z (20:57:27 Europe/Moscow) following the owner's explicit instruction to publish the installer and notes for in-app update notification.

- Release: https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.12
- Public Latest API: https://api.github.com/repos/ntccong2468-lab/Bancapnhat/releases/latest
- Verified Windows workflow: https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38776235230
- Immutable build/tag source: fdce2e185d17afaf2a72643700b8ddf233f1d525
- Installer: VN-code-1.4.12-Windows-x64.exe, 145091584 bytes
- SHA-256: 66b34a542bca66b812a5c7f8685596196f0de0d9e6d0fc3d2b8d67178d15b8a1
- 20 uploaded assets, Published, stable, Latest. Installer and proof/Portal asset hashes were compared against the downloaded successful Windows build before promotion.
- Public unauthenticated Latest API returned 1.4.12, release notes, installer URL, SHA-256 and byte size.
- The compiled desktop UpdateApiClient/UpdateInfo parser selected the correct Windows installer, notes, digest and size from the actual public API response. Version comparison identifies 1.4.12 as newer than 1.4.9/1.4.10/1.4.11.
- Public installer HTTP Range GET returned 206, MZ bytes, and total size 145091584. No GitHub login is required for these public URLs.
- The app checks updates at startup and through its manual update action. Publication makes the update discoverable; delivery to every running seller desktop has not been observed.

The standard automated publisher still requires production license acceptance. This manual publication follows the owner's request after the missing acceptance had been disclosed; its gate and license verification code were not weakened. complete and licenseIntegration.productionReady remain false, and no production license acceptance evidence was fabricated. The public GitHub release body explicitly discloses that exact-version production activation has not been accepted, and that the installer has no Authenticode signature.

No live seller KIZ assignment, marketplace writes, purchases or GS1 email were performed. Native Windows/DPAPI and automated tests are not evidence of live backend activation. The original release-notes.md asset remains the verified Draft feature notes; the GitHub body adds the publication/testing disclosure.
