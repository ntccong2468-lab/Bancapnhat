# VNCode 1.4.9 — Published/Latest

- [Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.9/VN-code-1.4.9-Windows-x64.exe)
- [Bản phát hành và nội dung cập nhật](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.9)
- [Điều tra và kiểm thử kết nối license](../validation/2026-10-15-license-networks.md)

Cải thiện phục hồi kết nối license sau khi đổi Wi-Fi/điểm phát di động hoặc khi một địa chỉ DNS không kết nối được. Tăng thời gian chờ phản hồi, giữ POST kích hoạt dùng một lần và phân biệt Cloudflare browser challenge. Khóa vẫn gắn với thiết bị, không gắn với IP, SIM hay quốc gia. Giữ license hiện tại; Portal 0.3.5 và key ID vncode-2 giữ nguyên.

App 1.4.8 có thể kiểm tra cập nhật và tải bản mới. Nếu đang dùng 1.4.4–1.4.7, tải EXE trực tiếp hoặc chọn **Chỉ tải bộ cài**, đóng VNCode rồi cài thủ công một lần do lỗi handoff JavaFX trong những bản cũ. Không gỡ hoặc xóa dữ liệu trước.

| Bằng chứng | Giá trị |
|---|---|
| Source commit / tag v1.4.9 | `9c9c68b88f4c8b7f3b64e3da3c6af74e417db31d` |
| Build Windows đầy đủ | [38686022279 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38686022279) |
| Kiểm tra license Windows riêng | [38688104707 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38688104707) |
| Chuẩn bị/kiểm chứng release assets | [38689254529 — success](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38689254529) |
| Installer artifact | `11951571193` |
| Release ID | `413462253` |
| EXE bytes | `144735232` |
| SHA-256 | `6330d7628640da5b99560434236fb814ae53a0e1d1933a0a73880812f0f102a7` |
| Published (Moscow) | 15/10/2026 08:54:38 +03:00 |

Windows Java/FXML: 1.243/1.245 passed in 217 suites, zero failures/errors; two platform-specific cases skipped. All 24 WebLicenseApi tests, four clock tests, six license UI tests and both update handoff cases passed. Local focused Java/JavaFX tests: 70 passed; Portal: 58 passed; release contracts: 53 passed. The separate Windows license run was dispatched while the full suite took longer than its previous run; both ultimately passed.

Native launcher/history/schema migration, GS1/Catalog DPAPI, independent install/uninstall, 272 layouts and both ten-shop datasets at Windows DPI 100/125/150 passed. Seven preserved-data upgrade baselines passed: 1.1.34, 1.2.0, 1.2.1, 1.2.2, 1.3.0, 1.4.0 license-test and 1.4.6. Native upgrade from exactly 1.4.4, 1.4.7 or 1.4.8 was not separately run.

Unauthenticated Public Latest returned 1.4.9. The shipped 1.4.9 updater classes selected the Windows asset, recognized an update for a 1.4.4 client and downloaded/verified the public EXE size and SHA-256. This probe ran on Linux with session proxy/trusted CA settings; it did not execute the EXE. Installer execution and upgrade were verified separately on Windows. The shipped license transport also reached the real API read-only, correctly rejecting a synthetic credential without allocating an account/device/license.

Owner curl probes reached public configuration over IPv4 and reportedly IPv6; an unauthenticated desktop status request returned the expected `AUTH_REQUIRED`. New Portal tests prove identity/limits across synthetic VN/RU Wi-Fi/hotspot IPv4/IPv6 metadata. Those results do not establish successful authenticated activation from the new app on the affected carrier. Owner testing on that network remains necessary. Production multi-device/offline/revocation acceptance and low-end hardware measurement remain unverified; completion/production flags stay false. No TLS, signature, device-limit or bounded offline check was removed, and production Cloudflare zone rules were not modified.
