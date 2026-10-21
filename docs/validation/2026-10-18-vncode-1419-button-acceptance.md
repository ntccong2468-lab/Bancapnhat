# Nghiệm thu GTIN bằng nút trong ứng dụng 1.4.19

**Trạng thái: chưa nghiệm thu được thao tác ghi thật bằng nút.**

Lúc 11:27:57 ngày 18/10/2026 (Moscow), đã chạy JAR production hiện tại qua
`Launcher → MainApplication`, mở cửa sổ JavaFX thật và dùng JavaFX Robot
bấm nút điều hướng Đăng ký Znack. Không cài license fixture, thay dịch vụ,
giả trạng thái chứng thư hoặc gọi trực tiếp hàm cập nhật thay cho nút.

Phiên này chạy Linux, dùng hồ sơ cloud mới riêng biệt, chưa liên kết cửa hàng.
Nút `updateWbButton` hiển thị nhưng bị khóa. License production có trạng thái
`UNVERIFIED`, mã `LICENSE_UNAVAILABLE`, không có quyền `SELLER_API`/`GTIN`.
Windows DPAPI không có trên máy này. Các binding API WB/Catalog đã có trong
môi trường; chúng không cung cấp phiên Windows đã kích hoạt và cấu hình Znack
đã xác thực cho ứng dụng. Vì vậy không bấm được nút cập nhật, không có tác vụ
GTIN qua nút và không có yêu cầu ghi seller mới trong lượt này.

Đây là thiếu môi trường nghiệm thu, chưa phải bằng chứng thao tác trên phiên
Windows hợp lệ bị lỗi. Kết quả ghi thật qua API ở lượt trước vẫn là phép thử
riêng: [báo cáo WB](2026-10-18-vncode-1419-wb-live-gtin.md).

## Điều kiện hoàn tất lượt nghiệm thu còn thiếu

Cần phiên VNCode 1.4.19 trên Windows với license đang hoạt động, cửa hàng WB
đã liên kết và cấu hình Znack đã kiểm tra chữ ký/đồng bộ nguồn thành công.
Không sao chép file DPAPI sang máy hay tài khoản Windows khác. Không gửi
license, khóa API hoặc khóa riêng vào chat.

Trên phiên đó, phép thử cần thực hiện đầy đủ:

1. Chọn đúng một biến thể có GTIN đã xuất bản trên Catalog nhưng WB chưa có;
   đối chiếu article, màu, size Nga và nhóm TN VED. Ghi nhận barcode trước sửa.
2. Bấm **Cập nhật GTIN lên WB** trên màn hình Đăng ký Znack; kiểm tra trạng thái
   viên thuốc ở giữa phía dưới, tiến độ kết thúc và kết quả riêng của dòng.
3. Đọc mới từ WB, kiểm tra GTIN có trên đúng `nmID/chrtID`, barcode cũ và các
   biến thể khác được giữ nguyên. Đóng/mở lại màn hình rồi đọc mới lần nữa.
4. Chỉ đánh dấu đạt khi có cả sự kiện bấm nút, tác vụ hoàn tất và readback WB
   xác nhận. Không tự bấm lại khi kết quả gửi chưa rõ.

GTIN `04650738830910` trên `443045288/627025126` đã được thêm ở lượt API trước;
nó chỉ phù hợp kiểm tra trạng thái đã cập nhật, không chứng minh một lần thêm
GTIN mới qua nút.

[Kết quả máy đọc được](evidence/2026-10-18-vncode-1419-button-attempt.json) ·
[Ảnh cửa sổ thực tế](evidence/2026-10-18-vncode-1419-button-blocked.png).

Không phát hành Latest hoặc ghi nhận nghiệm thu desktop thành công trong lượt này.
