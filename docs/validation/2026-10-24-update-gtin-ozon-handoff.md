# Bàn giao 24.10.2026 — cập nhật, GTIN, Ozon và workspace

Nhánh `fix/update-gtin-ozon-fbs-layout`, nền `b4e3a8d` sau 1.5.13. Chưa tạo bộ cài Windows, chưa đổi Latest/version/tag. Latest vẫn là 1.5.13 đã phát hành trước phiên.

## Đã sửa và kiểm tra

- Tải bộ cài khi còn tác vụ nền; trước installer chặn việc mới, nhường tại checkpoint đã lưu, giữ tham chiếu stock/hash/owner để tiếp tục hàng đợi WB KIZ sau restart. Không ép đóng mutation chưa rõ kết quả. Lô legacy không lưu được phải hoàn tất hoặc dừng handoff an toàn.
- GTIN WB/Ozon giữ lịch sử xác nhận đúng owner/source/product/variant và chỉ ghi delta. Dữ liệu sàn mới trái ngược mở lại đúng biến thể; ghi mới vẫn cần proof hiện hành. Tối ưu snapshot lô lớn và join ledger WB.
- Ozon gán/đọc KIZ trước nhận đơn, gồm mandatory và optional theo miễn KIZ seller. Đơn nhiều đơn vị cần KIZ dùng split chưa assembly, đối soát native IDs/order/warehouse/số lượng, rồi xử lý từng child. Chỉ ship sau mark/exemplar passed và ship_available; timeout giữ receipt để đối soát.
- PDF Ozon dùng task tối đa 1.000, tải một lần/task, ghép bằng posting trên trang, xuất trực tiếp vào PDF đích và giữ hai nhãn đã chốt. In lại subset không in toàn shared task.
- Vùng hiển thị chung cho cả 20 route, cho cuộn tới nút/báo cáo cuối và co bảng/sidebar theo cửa sổ. Cửa sổ thường theo visual bounds/taskbar; maximize do OS quản lý.

`clean verify` cuối: **2.956 ca, 2.954 đạt, 2 bỏ qua theo OS, 0 thất bại/lỗi**, BUILD SUCCESS, 17:34 phút, kết thúc 07:08:32 UTC. Node 117/117; targeted cuối 48/48. FXML smoke 57/57. Layout có 896 + 987 phép kiểm tra, không nút bị cắt. GTIN 10.000 giả lập mỗi sàn xác nhận đủ 10.000: WB 4 lần ghi/214 lần đọc, Ozon 100 lần ghi/272 lần đọc. Nhãn 1.000 đơn giả lập dùng 1 create + 1 get + 1 tải PDF, đảo thứ tự trang và in lại subset đạt.

Lượt full đầu phát hiện hai fixture SKU cũ không phải số; đã sửa fixture theo SKU native. Lượt khác bị dừng để áp dụng optional-mark và batch owner checks, không tính là đạt. Kết luận dựa trên full cuối. Hash cây mã được kiểm tra: `45374c8c7b833c3859712911f024552f59fa7b4174a1e5cf3189facd71b29b6c`.

## File zl.txt

Sau hồi quy đã chạy file thật qua reader/import/SQLite tạm: 200 CIS 31 ký tự, 200 unique, hai GTIN, 0 lỗi cấu trúc, không có DataMatrix đầy đủ. HTTP giả lập xác nhận payload giữ nguyên đủ 200 mã; không phải bằng chứng sàn chấp nhận.

API production `/v5/fbs/posting/product/exemplar/validate`, mỗi request một mã: **200 HTTP 200, 200 mark.valid=false, 0 đạt**. Cả 200 có `MANDATORY_MARK_HAS_INCORRECT_CRYPTOTAIL`; 7 trong đó còn có `MANDATORY_MARK_IN_INCORRECT_STATUS`. Đối chứng sai độ dài bị báo `MANDATORY_MARK_HAS_INVALID_LENGTH`. Các nhóm lỗi chồng nhau, không cộng thành 207 mã.

Có product.valid=true dù exemplar/mark false: không dùng product hoặc HTTP status để báo thành công. Batch 50 exemplar không trả chi tiết từng mã, không dùng để đếm. Kết quả cuối dựa trên 200 request riêng. Thử app với shape từ chối thật chặn đủ 200 trước set, số set bằng 0.

File này không qua bước kiểm tra Ozon; seller cần xuất lại chuỗi KIZ đầy đủ có phần crypto từ nguồn cấp. Không tự tạo crypto hoặc suy ra 7 mã đã bán/đã dùng. Chưa gán/tiêu thụ kho/tách/nhận đơn hoặc mua KIZ production. Không cần seller tìm mã đơn để thử gán một lô đã bị từ chối.

Đã đọc 19 đơn chờ đóng gói để chuẩn bị, barcode OZN chưa chứng minh được GTIN trong file. Catalog trả HTTP 403 chưa rõ nguyên nhân, không suy ra credential sai hoặc thẻ không tồn tại. Dữ liệu tối thiểu và phép thử private ở `/tmp/vncode-file-ozon-check/`; không commit/in raw KIZ, payload, dữ liệu đơn hoặc credential.

## Tiếp tục

- Người dùng vừa gửi thêm ảnh `KIZ_GTIN_MAPPING_MISSING`, yêu cầu kiểm tra luồng mua KIZ. Phần đó đang được truy vết riêng, không thuộc biên nhận full ở trên nếu có mã sửa thêm.
- Nếu có file đủ crypto mới, kiểm tra trước; chỉ gán production khi phê duyệt đúng fixture theo CLAUDE.md. Không gửi lại mutation chưa rõ kết quả.
- Khi được yêu cầu phát hành, cần Windows build/native acceptance cho updater, khôi phục hàng đợi, restored/maximized/taskbar/DPI. Không dùng Windows binary cũ làm bằng chứng cho mã mới; giữ tag/binary 1.5.13 bất biến.

[Biên nhận](2026-10-24-update-gtin-ozon-verify.json) · [Chi tiết](../fixes/2026-10-24-update-gtin-ozon-workspace.md) · [Bộ nhớ](../PROJECT_MEMORY.md).
