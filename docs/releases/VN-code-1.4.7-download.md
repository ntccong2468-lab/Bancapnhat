# VNCode 1.4.7 — Published/Latest

- [Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.7/VN-code-1.4.7-Windows-x64.exe)
- [Bản phát hành và nội dung cập nhật](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.7)
- [Ảnh đối chiếu Ozon FBS](../research/vncode-ozon-fbs-147/REVIEW.md)

App phiên bản cũ kiểm tra GitHub Published/Latest khi mở và định kỳ. Có thể chọn **Cài đặt → Kiểm tra phiên bản** để kiểm tra ngay, đọc nội dung thay đổi và chọn tải/cập nhật. Bộ cài có Java đi kèm. Portal 0.3.5 và license key ID vncode-2 giữ nguyên.

| Bằng chứng | Giá trị |
|---|---|
| Phiên bản | 1.4.7 |
| Source commit | `68fc1b85ef2f838e3cd7bb269c298ce396e3b277` |
| Build Windows đầy đủ | [38635358816 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38635358816) |
| Chuẩn bị/kiểm chứng release assets | [38639318921 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38639318921) |
| Installer artifact | `11928898353` |
| Release ID | `413193348` |
| EXE bytes | `144735232` |
| SHA-256 | `63208a73138ef31393c3237d19af6edb2362ea557295baa4ba90d3692a648d9a` |

Windows: 1.235/1.237 Java/FXML tests passed, zero failures/errors; two POSIX/off-Windows cases do not apply. 53 release-contract tests and 56 Portal tests passed. Native startup/history, GS1/Catalog DPAPI, independent install/uninstall, two ten-shop datasets at DPI 100/125/150 and 272 desktop layouts passed. Seven data-preserving upgrade baselines passed, including Published 1.4.6 → 1.4.7. Ozon UI additionally checks three tabs across four languages, two themes and two sizes (48 layouts), and the 1,000-order selection/paging/background-read tests passed.

Public Latest API and EXE download were checked without a GitHub login. The actual shipped 1.4.6 updater classes recognized 1.4.7 as AVAILABLE, supported the Windows install action, and downloaded/verified the public EXE byte size and SHA-256. This library probe ran on Linux with the cloud session's HTTPS proxy and trusted CA; it did not execute the Windows EXE. Native Windows installation/upgrade evidence is from the separate full Windows run above.

Published per the owner's explicit request. Fixture tests are not live Ozon/Catalog mutations or production multi-device/offline/revocation acceptance. Those unrun production flags remain false in the status/acceptance records; no application signature or licensing checks were disabled.

## Update handoff erratum — 2026-10-15

Owner report confirmed that the UI installer handoff still read a JavaFX Task property on its worker. Earlier library download/hash checks and separate native MSI installation did not exercise that handoff. Use download-only/direct EXE and install manually for the one-time bootstrap. The 1.4.8 controller-level regression reproduces the old failure and verifies the corrected handoff.

[1.4.8 is now Published/Latest](VN-code-1.4.8-download.md), with successful Windows regression/native checks and a verified public download. Install its EXE once manually from an affected version.
