# Bàn giao — mua KIZ thiếu và tự xuất nhãn

Đã gộp và phát hành **1.5.14 lên Latest** lúc 09:34:12 UTC ngày 24.10.2026; nhánh bàn giao `release/vn-code-1.5.14`, nguồn chốt `09718c80133528808047b01399e004ef9329ff64`. Kế thừa proof mua `ce56ee3`, phần cập nhật/GTIN/Ozon/PDF/vùng hiển thị `585d15c`, thêm continuation và bốn nhóm đơn Ozon. [Biên nhận công bố](2026-10-24-vncode-1514-published-latest.json).

## Đã nối

Chọn Mua KIZ → Xuất nhãn → đối chiếu theo từng GTIN → bảng số cần/sẵn dùng/thiếu/mua → Áp dụng → enqueue phần thiếu → mua/đưa vào lưu thông → đọc xác nhận từng mã → tự xuất vào tệp đã chọn. WB/Ozon dùng chung session xác nhận/chờ; giữ adapter và quy trình gán/kiểm tra riêng của từng sàn. Hủy trước Áp dụng không mua. Viên thuốc theo dõi mua/lưu thông/xuất và tự ẩn khi kết thúc.

Chờ không giữ khóa/reservation, không chiếm worker giao diện: hàng đợi in riêng có giới hạn và vẫn tham gia activity/handoff. Giữ guard bấm in lặp, cửa hàng cũ không mở hộp thoại sau khi chuyển shop, output lease, profile/UUID gốc và giấy tờ/chữ ký/license. Profile của lượt mua mới phục vụ in bật tự lưu thông riêng, không đổi setting chung hoặc pipeline trước đó.

Tự xuất PDF là continuation khi phiên app còn mở. Pipeline mua được lưu bền vững và khôi phục sau restart; chưa có hàng đợi PDF tự chạy sau restart. Nếu cài cập nhật/đóng app lúc chờ, mở lại và bấm xuất nhãn để dùng kho/lượt mua đã lưu. Không có HTTP chạy khi tiến trình đã đóng.

## Bằng chứng

- Full trước phần continuation: 2.963 ca, 2.961 đạt, 2 bỏ qua OS, không lỗi/thất bại. Mã `ce56ee3`, hash `3088a54e0c24b40dc6f4cfc60b666eae06f2a73579a91cbf652f65d586ace9d2`.
- Continuation: 118 ca mục tiêu đạt; 21 ca liên quan cuối đạt sau chỉnh nhãn nút RU/ZH. Có thao tác nút JavaFX thực trên Xvfb, 200 dòng/bốn ngôn ngữ, giả lập WB/Ozon cần 5/sẵn 2 chỉ mua 3 và chưa xuất với RECEIVED. Có ca hủy, pending, owner/config đổi, mất phản hồi, lỗi lưu thông, rollback profile và worker thường vẫn chạy khi hai print chờ.
- Full đầu 2.980 ca có một thất bại ở phép đo thứ tự callback đa luồng. Đã giữ stress 10.000 đơn và chuyển kiểm tra fairness sang một writer bị giữ chủ động; 37 ca liên quan cuối đạt, Node 117/117. Full mới trên 1.5.14 BUILD SUCCESS, 2.986 ca (2.984 đạt, 2 bỏ qua OS), không lỗi/thất bại; log `/tmp/vncode-1514-full-verify.log`, hash `77b135a319ba056f5022885792dc4829344004b899dbbb9f5d857b06230a0fb4` và Windows build đúng SHA trước công bố.
- File `zl.txt`: kết quả API Ozon validation thật trước đó 0/200 đạt, 200 mã báo `MANDATORY_MARK_HAS_INCORRECT_CRYPTOTAIL`, trong đó 7 mã còn `MANDATORY_MARK_IN_INCORRECT_STATUS`. Không gán file bị từ chối hoặc mua KIZ production trong lượt này. File được app đọc/nhập không chứng minh sàn chấp nhận.

## Kiểm tra khi phát hành

Windows native và nâng cấp giữ dữ liệu từ 1.5.13 đã đạt, 2.986 ca/không lỗi. Công bố `40082452101` thành công, đủ 16 assets, tải công khai và checksum 11 assets bắt buộc đạt; JAR updater thật 1.5.13 đọc đúng Latest/MSI/hash/kích thước. Runtime công khai chứa thay đổi mới, 1.147 file source/test/pom trong source ZIP khớp mã chốt. Chưa quan sát thông báo trên máy seller. Thử mua có phí cần fixture cụ thể, đúng GTIN, số lượng, giấy tờ và chứng thư; không dùng mock/CI hoặc enqueue để tuyên bố mua/lưu thông/gán production đã được xác minh.

[Luồng và chi tiết](../fixes/2026-10-24-kiz-print-purchase-continuation.md), [bản sửa proof mua](../fixes/2026-10-24-kiz-purchase-published-identity.md), [bàn giao phần cập nhật/GTIN/Ozon](2026-10-24-update-gtin-ozon-handoff.md).
