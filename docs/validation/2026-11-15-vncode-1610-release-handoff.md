# VN Code 1.6.10 — đã phát hành ngày 15.11.2026

**Đã lên Latest lúc 13:40:34 ngày 15.11.2026 (Moscow, UTC+03:00).** Release **445977967** bất biến, tag **v1.6.10**, đủ **16 tệp**. [Mở bản phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.10).

Mã app/test/build là `b74b5edfd46636e8779c94f467eef01c03c50a14`; mã công cụ phát hành là `cbdee5962d8d76a5f76e172c2f17eaf8dec3cb54`. Tài liệu bổ sung sau build giữ nguyên mọi input runtime, test, POM và wrapper.

## Những phần đã gộp

- Tiếp tục đúng lô mua KIZ đang chờ ký bằng hồ sơ và yêu cầu gốc; giữ phạm vi cửa hàng và chống mua lặp. Không đánh dấu tải mã là đã lưu thông. [Nguồn VNCode đầu và sửa KIZ](2026-11-14-kiz-source-repair.md).
- Ozon ghép toàn bộ mã mẫu và size, không phân biệt hoa/thường/khoảng trắng; loại `B3152NAU` và `152NAUDASH` khỏi `152NAU`. `2XL/XXL` tương đương; `52` chỉ là alias khi chính biến thể có căn cứ. Dùng chung cho đăng ký, GTIN và liên kết mua KIZ; giữ quy tắc WB và liên kết thủ công. [Chi tiết](2026-11-14-ozon-exact-model-size.md).
- Kính nổi trên các phần bên trong app, cả nền sáng/tối: trang, panel, bảng/danh sách, ô nhập, nút, menu, Cài đặt và báo cáo. Giữ vùng làm việc lớn và năm nút sửa sản phẩm; chuyển động theo ngữ cảnh có Giảm chuyển động.
- Báo cáo bo tròn với Scene trong suốt/titlebar riêng; sửa focus che chữ, logical attachment trước Scene, quyền nền caller qua đổi theme và viền kín 1px còn thừa ở bảng Tài chính/FBO. Giữ giấy trắng/canvas, handler/result/owner/modality/binding và quyền trợ năng OS. [UI, ảnh và video](ios-motion-20261114/REPORT.md).
- Kế thừa chức năng và dữ liệu của 1.6.9: đủ 805 runtime, không xóa đường dẫn cũ; 603 Java nghiệp vụ ngoài ui/shared giữ nguyên byte từ mốc trước UI a860. Không thêm dependency/schema/API wire trong phần UI. Kiểm byte không thay kiểm thử hành vi.

Các repo OpenRewrite/Spoon, ArchUnit/JaCoCo/PIT vẫn là [đề xuất nghiên cứu](../research/2026-11-14-code-quality-proposals.md), chưa tự cài hoặc refactor diện rộng.

## Kiểm chứng bản đã công bố

**Full Windows: 4.700 ca/585 suite; 4.698 đạt, 0 failure, 0 error, 2 skip đúng hệ điều hành.** Hai skip là kiểm POSIX permissions và nhánh bảo vệ secret dành cho ngoài Windows. Các regression mới bắt buộc không bị skip. [JUnit](vncode-1610-20261115/full-junit.json), [build 43641520595](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43641520595).

Native startup/migration 3→6/rollback, DPAPI Windows, cài/gỡ/cài song song và passive EXE nâng từ bộ 1.6.9 đã pin đều đạt. Coexistence dùng fixture WBCode 1.1.75; không suy ra đã chạy WBCode mới nhất trên máy seller.

Verifier độc lập kiểm đủ 16 tệp, source 3.513 file đúng b74 hoặc chỉ khác line ending UTF-8; JAR 2.026 class/763 main Java/42 resource, không thiếu hoặc lẫn test class; 18 suite cũ nguyên nguồn/count, 29 suite mới, 9 regression bắt buộc và 5 stress 300/10.000. MSI nhúng trong EXE được so byte độc lập. [Receipt](vncode-1610-20261115/package-review.json), [map 16 tệp](vncode-1610-20261115/independent-asset-map.json). Bộ verifier đạt 28 control; 44 bằng chứng motion hỏng đều bị chặn. Kiểm riêng 13 proof gốc và 3 DB profile tổng hợp.

Publisher đạt **200/200 Node, 0 skip**, syntax/actionlint và giữ nguyên helper 1.6.9. Nguồn, run/attempt, artifact/digest và draft ID đều pin từ phản hồi thật. [Publisher 43705139132](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43705139132), [receipt](vncode-1610-20261115/publication-1.6.10.json).

Sau công bố đã tải thật đủ 16 tệp public và so byte/hash với map CI độc lập, API, manifest, checksum và provenance. Giữ nguyên **19 release, 306 asset và 48 raw tag cũ**; sau công bố là 20/322/49. [Public receipt](vncode-1610-20261115/public-readback-receipt.json).

**Updater công khai 1.6.9 nguyên bản báo AVAILABLE 1.6.10**, GET HTTP 200/TLS 1.3, chọn đúng MSI; 75 JAR giữ nguyên trước/sau. Probe chạy trên Linux qua Cloud proxy/CA đã xác minh, chọn Windows sau network; không chứng minh anonymous access hoặc popup/cài trên máy Windows seller.

UI 201/21 local và 65 ảnh đã review có phạm vi riêng, không cộng vào tổng 4.700. Ảnh/video dùng Linux/JavaFX/SQLite tổng hợp, không phải dữ liệu seller hoặc benchmark FPS.

## Tải xuống và mã nguồn

| Tệp | Kích thước | SHA-256 |
| --- | ---: | --- |
| [EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.10/VN-code-1.6.10-Windows-x64.exe) | 149,539,328 byte | `2fcb4916119fd8971487778d760b47c2a281582427f1db77c2137a7dcf365bf0` |
| [MSI](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.10/VN-code-1.6.10-Windows-x64.msi) | 148,870,409 byte | `e12a5983a99d7665d36db0632bd6403301d5f2a658c5d73584d8bc14438d4fd0` |

Source ZIP chính thức trong 16 assets là archive đúng commit CI b74; tài liệu bên trong phản ánh checkpoint trước build. Gói bổ sung tài liệu cuối có tên **VN-code-1.6.10-source-floating-glass-windowsfix-20261114.zip**. Hậu tố ngày là ngày công việc nguồn. App/test/build vẫn cùng b74; docs-only gate và guard chạy trên chính ZIP bàn giao, comment commit/hash/receipt để bên ngoài tránh tự tham chiếu. Hai ZIP cũ 163045…/15ab17… và preview đã giao giữ nguyên. Không thay asset bất biến để cập nhật tài liệu sau build.

## Giới hạn còn lại

Security b74 có **8 finding Java cũ**, gồm **1 mức cao 7,5** làm gate Java FAIL; 0 mới/0 được giải quyết trong kết quả đã so 1.6.9. 107 reference đánh số khác đã giải về cùng URI/rule, mọi field/dòng/codeflow/fingerprint còn lại giữ nguyên. Lỗi extraction GS1 cũ giới hạn coverage; informational/note khác được ghi riêng. Không waiver hoặc gọi toàn bộ security xanh. [Báo cáo](ios-motion-20261114/windows-b74/security/REPORT.md).

Kiểm thử wire/stress dùng endpoint và dữ liệu tổng hợp. Lượt UI/release này không gửi Gmail, kích hoạt license, mua KIZ có phí, ký bằng chứng thư thật hoặc ghi API seller. Quyết định của Catalog/sàn và hoạt động trên máy seller không được suy từ build thành công.
