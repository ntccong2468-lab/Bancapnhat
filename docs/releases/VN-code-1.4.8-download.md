# VNCode 1.4.8 — Published/Latest

- [Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.8/VN-code-1.4.8-Windows-x64.exe)
- [Bản phát hành và nội dung cập nhật](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.8)

Sửa lỗi tải đến 100% rồi báo `Task must only be used from the FX Application Thread`. Các bản 1.4.4–1.4.7 vẫn chứa lỗi cho đến khi thay bộ cài: chọn **Chỉ tải bộ cài** hoặc tải EXE trực tiếp, đóng VNCode rồi cài một lần thủ công. Không gỡ app hoặc xóa dữ liệu trước. Bộ cài có Java đi kèm; Portal 0.3.5 và license key ID vncode-2 giữ nguyên.

| Bằng chứng | Giá trị |
|---|---|
| Source commit / tag v1.4.8 | `7643b6c859c722486193a30e028da7be88c10bf6` |
| Build Windows đầy đủ | [38680028079 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38680028079) |
| Chuẩn bị/kiểm chứng release assets | [38682442217 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38682442217) |
| Installer artifact | `11947923973` |
| Release ID | `413422987` |
| EXE bytes | `144735232` |
| SHA-256 | `b51ae21d97265af87c4dbdd74e0d8b2d02532feff981980552b174e211e6354c` |
| Published (Moscow) | 15/10/2026 07:39:28 +03:00 |

Windows Java/FXML: 1.237/1.239 tests passed in 217 suites, zero failures/errors; two platform-specific cases skipped. Both new controller handoff cases passed on Windows. Local regression first reproduced the exact owner exception before the fix, then all 23 focused update tests and 53 release-contract tests passed after the fix.

Native launcher/schema migration/history, GS1/Catalog DPAPI, independent install/uninstall, two ten-shop datasets at DPI 100/125/150 and 272 desktop layouts passed. Seven preserved-data upgrade baselines passed: 1.1.34, 1.2.0, 1.2.1, 1.2.2, 1.3.0, 1.4.0 license-test and 1.4.6. Exact native upgrade from 1.4.4 or 1.4.7 was not separately executed.

Public Latest was checked without GitHub login. The shipped 1.4.8 updater classes recognized Published 1.4.8 as AVAILABLE for a 1.4.4 client, selected the Windows installer metadata, downloaded the public EXE and verified its exact byte size and SHA-256. This library probe ran on Linux with the session's HTTPS proxy and trusted CA; it did not execute the Windows EXE. The controller test uses an injected installer/exit action. Native installer execution is checked separately in the Windows run above; these checks do not represent a customer clicking through a complete live update.

Published per the owner's explicit request. Live seller mutations, production multi-device/offline/revocation acceptance and low-end Windows performance remain unverified; corresponding completion/production flags stay false. No checksum, signature, license or snapshot guard was removed.
