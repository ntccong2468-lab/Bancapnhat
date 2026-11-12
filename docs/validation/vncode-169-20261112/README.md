# Chứng cứ phát hành VN Code 1.6.9

Đã công bố **12.11.2026 08:47:19 MSK**, release **442540592**, Latest và immutable. Nguồn ứng dụng đóng băng: `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b`; publisher: `b9635f80f0b125ad33830f71430088358ccc671e`.

- [Kết luận và checksum](final-release.json), [bàn giao](../2026-11-12-vncode-169-release-handoff.md).
- [Kiểm đủ nguồn](source-inclusion.json), [kiểm gói độc lập](package-review.json), [XML thực tế](windows-actual-junit-summary.json).
- [Receipt công bố từ CI](publication-1.6.9.json), [kiểm ZIP receipt](publication-artifact-download.json), [16 tệp tải lại từ public](public-downloads.json).
- [Bảo toàn release/tag cũ](public-preservation-verified.json), [updater 1.6.8 gốc](updater-verification.json).
- [Giới hạn bảo mật](security-summary.md), [so sánh SARIF](security-comparison.json), [thời lượng CI thực tế](actual-ci-timing-comparison.md).
- [Công cụ và cách dùng lại](tools/README.md), [manifest tệp chứng cứ](evidence-manifest.json).

Windows run `43131000128`, **attempt 2**, đạt 4.464 ca/565 suite: 4.462 pass, 0 failure/error, 2 OS skip đúng danh tính. Các bước native/migration/DPAPI/cài mới/coexistence/gỡ và passive EXE upgrade từ 1.6.8 đều đạt. Không cộng test preflight, các tập kiểm riêng hoặc 185 kiểm tra Node publisher vào tổng này.

Attempt 1 được giữ là **FAIL**: GitHub báo runner mất liên lạc, log ZIP hợp lệ nhưng rỗng 22 byte, không artifact/JUnit. Không có kết luận assertion Java; nguyên nhân CPU/memory/network cụ thể chưa xác định. Lượt thứ hai chạy lại toàn bộ cùng nguồn và cùng gate.

Đã tải lại đủ 16 public payload và so byte/hash với gói đã review; giữ nguyên 18 release/290 asset/47 raw tag cũ, sau công bố có 19/306/48. Binary 1.6.8 đã phát hành, cùng 75 JAR nguyên vẹn, nhận `AVAILABLE 1.6.9` và đúng MSI. Probe mạng chạy trên Linux qua proxy/CA của Cloud; chỉ chuyển chọn asset Windows sau mạng. Không quan sát popup/cài trên máy seller và không khẳng định truy cập anonymous độc lập với proxy.

Quét mới không thêm finding so với 1.6.8, nhưng Java CodeQL gate vẫn FAIL do 8 finding kế thừa/1 mức cao; lỗi extraction GS1 cũ còn hạn chế coverage. Không mô tả tất cả security xanh. Lượt phát hành không mua KIZ có phí, gửi/ký Catalog, ghi bài/đơn seller, kích hoạt license, gửi Gmail hoặc deploy portal. Prototype mã hóa credential vẫn PR-only, không nối vào runtime.

Các tệp này được lưu bằng commit chỉ sửa tài liệu sau công bố; chúng không thay thế nguồn/tag/binary đóng băng. Installer/portable/source/history/verification lớn được lưu tại release immutable và artifact CI, không nhân bản vào Git. Receipt gốc giữ nguyên byte; manifest không tự liệt kê checksum của chính nó.
