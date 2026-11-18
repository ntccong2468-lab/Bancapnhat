# VN Code 1.6.11 — đã phát hành lên Latest

Đã công bố **1.6.11 lúc 15:52:03 MSK ngày 18.11.2026**. Release `449977778`/tag `v1.6.11` immutable, gắn đúng nguồn app **`cf3d8d695f7caa3b39d934145b7ff0445c50e194`**. Đã tải và đối chiếu đủ **16 tệp public** với gói Windows được kiểm định.

- [Tải bộ cài Windows EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.11/VN-code-1.6.11-Windows-x64.exe)
- [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.11) · [Hướng dẫn thao tác](USER-GUIDE.md)

## Xác nhận cập nhật xuống app

Bản **1.6.10 đã được cung cấp trước đó**: Latest công bố ngày 15.11 và JAR 1.6.9 công bố thật đã nhận `AVAILABLE 1.6.10`, đúng MSI/hash/kích thước.

Sau công bố mới, **JAR 1.6.10 công bố thật nhận `AVAILABLE 1.6.11`**, qua HTTP 200/TLS1.3, đúng MSI 149.140.745 byte và SHA-256 `6c54e58d9a8b50430f019166645bbbf95201d15e6d4985ec7a2c9998e01bdccb`. Giữ nguyên 75 JAR gốc, kiểm nguồn class và không cài thay lên máy seller. [Receipt updater](updater-after.json).

Đây là xác nhận cơ chế cập nhật tìm đúng bản mới, **không phải quan sát máy của bạn đã hiện popup hoặc cài xong**.

## Đã gom những phần nào

| Nhóm | Thay đổi trong bản phát hành |
|---|---|
| Mua KIZ WB/Ozon/FBO | Engine chung theo luồng WBCode 1.3.26; tối đa 10 GTIN/lô, hàng đợi tạo/tải/lưu thông 4/2/2. Giữ hồ sơ/số lượng/quyền ký qua restart; đối soát phản hồi chưa rõ, phục hồi tải thiếu; chỉ báo lưu thông khi trạng thái và owner đã được kiểm chứng. |
| Ozon | Đăng ký Znack và mua KIZ theo phần thiếu của đơn; kiểm Ozon chấp nhận đúng mã/sản phẩm/slot. Giữ vận đơn gốc theo lô và nhãn kết hợp, bố cục FBS theo WB đã duyệt; sửa toolbar bị cắt khi cửa sổ nhỏ. |
| GS1 | Trạng thái nháp/đã gửi/từ chối và lý do; gia nhập lại khi Catalog cho sửa. Wizard sáu bước, địa chỉ/từ điển, xem XML. Thỏa thuận có PDF, consent, ký đúng bytes rồi đọc lại đúng hợp đồng/EDO. |
| GS1: phục hồi và thư | Chống POST gửi lặp; chưa rõ kết quả chỉ đối soát. Hủy ký trước gửi có thể mở lại PDF sau khi chứng minh vẫn là nháp. Mẫu tiền tố mặc định/custom, xem PDF/thư, SMTP/IMAP riêng và điều hướng lỗi quota/166. |
| Sản phẩm | Chọn nhiều danh mục, tối đa 100; giữ phân trang/lựa chọn và năm nút sửa thương hiệu, chất liệu, giới tính, TN VED, giấy tờ. |
| Ozon “In KIZ” | PDF KIZ riêng theo lô từ mã đã gán, có STT/mã đơn. Không tự mua/gán/giao từ nút này; không thay luồng nhãn kết hợp. Sửa kẹt busy khi đổi shop A→B→A. |
| Dữ liệu và công cụ | Schema 6→7 có snapshot, giữ ID/CIS/chứng từ/mã đã dùng. Giữ sửa ECC/UI/bộ nhớ trước đó. Buộc XML mẫu GS1 giữ LF trên Windows, không nới checksum hay test. |

[Chi tiết luồng mua KIZ và các sửa trước](../wb1326-purchase-replacement-20261117/REPORT.md) · [Ma trận đủ 19 hướng dẫn, sơ đồ GS1/Ozon và ảnh](../wbcode-guides-gs1-20261118/REPORT.md).

## Kiểm định thực tế

| Kiểm tra | Kết quả |
|---|---|
| Full Windows `44282349988`, attempt1 | **PASS: 5.283 ca/644 suite; 5.281 đạt; 0 failure/error; 2 OS skip đúng danh tính**. Không lọc bỏ full suite. |
| Native/cài đặt | **PASS** migration 3→7, DPAPI, cài mới/coexistence/gỡ và nâng cấp thụ động từ 1.6.10 schema 6→7. |
| Review gói độc lập | **PASS** đủ 16 asset, 4.097 tệp nguồn, 74 thư viện, rawXML LF, EXE chứa đúng MSI. Root đối chiếu lại bytes. [Package review](windows-package-review.json). |
| Linux V5 và Launcher | **1.023 ca/96 suite; 0 failure/error; 1 NTFS skip**, `clean verify` exit 0. JAR thật mở workspace 5,586 giây, thoát/nhả khóa, schema 7/integrity OK/FK0. [Ảnh Launcher dữ liệu mẫu](launcher-v5.png). |
| Công cụ | Node 244/244 và actionlint **PASS**. Các scope chồng lấn không cộng thành tổng mới. |
| Công bố và tải public | Publisher `44307702686` **SUCCESS**; Latest/source/immutable và 16 tệp public đúng package; giữ nguyên 20 release/322 asset/49 tag cũ. [Publication](publication-1.6.11.json), [kiểm public](public-verification.json). |
| Updater sau công bố | **PASS:** JAR 1.6.10 thật nhận 1.6.11 và đúng MSI. [Kết quả](updater-after.json). |

Hai Windows skip là `Gs1PrefixTemplateStoreTest.privatePermissionsAreAppliedWhereSupported` và `WindowsSecretProtectorTest.neverFallsBackToPlaintextOffWindows`. Một skip của Linux là kiểm NTFS junction dành cho Windows; không gọi các ca đó đã chạy trên sai hệ điều hành.

## Security vẫn có giới hạn được giữ rõ

**Security CI vẫn FAIL:** Java 9 finding gồm một high; **8 finding giữ từ bản công bố 1.6.10 và 1 medium lock đã có từ candidate đầu**. Cả 9 khớp các candidate trước, không có finding mới ở nguồn cuối. JS 0 finding/PASS; lint PASS. Lỗi extraction GS1SignatureInk cũ còn tồn tại.

Không suppression, waiver hoặc thay ngưỡng gate. High liên quan thumbprint chứng thư và medium liên quan chuyển quyền lock đã được review caller; chưa đánh dấu đã sửa. Không kết luận toàn bộ bảo mật đạt. [Security receipt](security.json) · [Giải thích và giới hạn](security-summary.md).

## Nguồn tải về và khả năng khôi phục

[ZIP mã nguồn public](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.11/VN-code-1.6.11-source.zip): **109.288.217 byte**, SHA-256 `bb9ca553116116d921fee9f38648edabfd831b1cf57b73f6c52420808fb4bddf`.

Gói có 4.097 tệp tương đương Git sau đối chiếu xuống dòng của checkout Windows; riêng XML mẫu GS1 phải khớp bytes LF và checksum gốc. Không nhầm hash ZIP nguồn với bộ cài. Source/tag/assets đã phát hành được giữ immutable. Database đã lên schema 7 không mở trực tiếp bằng app cũ; khôi phục cần snapshot tương thích.

Ba candidate lỗi trước, log BLOCKED đầu tiên và lần dispatch publisher sai ref được giữ làm lịch sử; không dùng làm bằng chứng thành công. Helper kiểm gói đã sửa riêng pin routing 38→39 sau khi chứng minh enum 13→14 thêm `KEY_NOT_CONNECTED`, test nguồn không đổi, đủ 38 case cũ và 39 case hiện tại PASS. Có control âm; không sửa test app hoặc nới gate để công bố.

## Phần chưa nghiệm thu trên dịch vụ/máy seller

Đã đối chiếu 19 bài/19 MP4 bằng 305 khung hình mỗi 4 giây và khung cuối; **không xem/nghe liên tục toàn bộ video**. Không dùng ASR không đáng tin làm căn cứ.

Lượt phát hành này không mua KIZ SUZ có phí, ký chứng thư seller, gửi GS1/Catalog/sàn, nhận/giao đơn thật, gửi mail, kích hoạt license hay Gmail. Các bài 300/10.000 dùng dữ liệu tổng hợp, không phải số thẻ gửi lên Catalog thật. GS1 dùng hộp thư seller cấu hình, không backend riêng WBCode.

Ảnh Linux/Xvfb không chứng minh compositor/DPI/Narrator Windows, chuyển động/tốc độ trên máy seller hoặc in/quét vật lý. Chưa thêm QR thanh toán hay API bàn giao Ozon chưa xác minh; không bật in lại Ozon bằng dữ liệu thiếu proof.

[Biên nhận phát hành cuối](final-release.json) gắn các kết quả trên với cùng nguồn, run, package, publication và updater. Bản cập nhật được cung cấp thật; những giới hạn nghiệm thu này vẫn cần được phân biệt khi đánh giá một lỗi cụ thể trên cửa hàng.
