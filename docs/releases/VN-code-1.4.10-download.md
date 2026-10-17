# VNCode 1.4.10 — Published/Latest

- [Bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.10/VN-code-1.4.10-Windows-x64.exe)
- [Portal 0.3.6 và Worker API dự phòng](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.10/VNCode-Portal-0.3.6-License-Network.zip)
- [Hướng dẫn triển khai Cloudflare](../../website/license-portal/DEPLOY-LICENSE-NETWORK-VI.md)
- [Bản phát hành và nội dung cập nhật](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.10)

App tự chọn kết nối license: thăm dò không gửi thông tin tài khoản, tự chuyển HTTP/1.1/IPv4 trước khi cần và giữ IPv6. Nếu phép thử nhanh không phản hồi, vẫn thử yêu cầu thật tới primary với thời gian chờ đầy đủ. Backup cố định `license.vncode.id.vn` chỉ nhận thông tin sau khi app xác minh nonce, origin và chữ ký issuer. Mất phản hồi có thể phục hồi một lần bằng challenge mới và cùng danh tính thiết bị; không phát lại proof đã gửi.

Cần triển khai Portal 0.3.6 trước, rồi `npm run deploy:gateway`, theo hướng dẫn trong ZIP. Không tạo database mới, không chạy lại schema hoặc thay khóa ký vncode-2. Gói có 52 tệp, không chứa private key hoặc cấu hình credentials cục bộ. Gateway dùng Service Binding tới Portal hiện có; cùng dữ liệu, quyền và giới hạn thiết bị. Cả hai đường vẫn dùng Cloudflare và phụ thuộc Portal service chung.

| Bằng chứng | Giá trị |
|---|---|
| Source / tag v1.4.10 | `735890f58937e7ee9d3c534956b27d51eed72d82` |
| Windows đầy đủ | [38694827579 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38694827579) |
| Windows license/updater riêng | [38694830498 — 184 tests passed](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38694830498) |
| Kiểm chứng / chuẩn bị release | [38697879356 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38697879356) |
| Installer artifact | `11954527132` |
| Release ID | `413518941` |
| EXE bytes | `144763904` |
| EXE SHA-256 | `e6017013c999455c85adce4c371e1bf1b3da65ab80c222136327bdc096cea587` |
| Portal ZIP bytes | `111443` |
| Portal ZIP SHA-256 | `35b05b8cb301220a3aab9c670f10802da6bfbe6bd58a89c0ada0a7231b988dbc` |
| Published (Moscow) | 15/10/2026 10:21:16 +03:00 |

Windows Java/FXML: 1.262/1.264 passed, 219 suites, zero failures/errors. Two platform-specific cases skipped: non-Windows secret-protector behavior and POSIX private-file permissions. Native launcher/history migration, GS1/Catalog DPAPI, independent install/uninstall, 272 layouts and both ten-shop datasets at DPI 100/125/150 passed. Seven preserved-data upgrade baselines passed: 1.1.34, 1.2.0, 1.2.1, 1.2.2, 1.3.0, 1.4.0 and 1.4.6. Upgrade from exactly 1.4.9 was not separately executed.

Local focused Java/JavaFX: 73 passed; Portal: 60 passed; release contracts: 53 passed. Tests cover profile selection, full-budget trusted-primary fallback after unsuccessful fast probes, no credentials to an unverified backup, signed endpoint identity binding/expiry/tampering, fresh-challenge recovery after the real Worker redeems a code and its response is lost, and two-device activation with third-device denial. Real Worker tests use ephemeral fixture issuer/device keys and an in-memory D1-compatible adapter.

Public Latest returned 1.4.10 without authentication. The shipped updater classes recognized an update for 1.4.4, selected the Windows EXE among all 18 release assets, and downloaded/verified its exact public size/SHA-256 on Linux with session proxy/trusted CA settings; no EXE execution in this probe. All other 17 public files were independently downloaded and checked against their GitHub size/SHA-256. Native execution was checked separately on Windows CI.

The shipped license client reached primary read-only in HTTP/2 and HTTP/1.1; empty activation requests returned expected 401 and synthetic status credentials were rejected. No real account/device/license was created. Live primary still returned Portal 0.3.5; backup was not reachable before owner deployment. No production Worker, DNS or WAF changes were performed.

Actual activation on the affected second-machine hotspot and real VN/RU carriers remains unverified. Production multi-device/offline/revocation acceptance and low-end hardware measurements remain outstanding; completion/production flags remain false. Fixtures do not establish that every Internet connection can reach Cloudflare.

Users on 1.4.8+ can use the in-app update. On 1.4.4–1.4.7, download the EXE directly or use download-only, close VNCode and install manually once to replace the old JavaFX updater handoff. Preserve existing data and license.
