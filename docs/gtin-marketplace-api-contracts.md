# Trạng thái hợp đồng API cập nhật GTIN

Ngày kiểm tra: 06/10/2026. Các thao tác đọc sử dụng API đã có trong VN code.

| Sàn | Tài liệu chính thức đã yêu cầu | Kết quả từ môi trường | Mutation mới |
| --- | --- | --- | --- |
| WB | https://dev.wildberries.ru/openapi/work-with-products | HTTP 403 | Chặn thêm/thay cho tới khi xác minh schema và giới hạn |
| Ozon | https://docs.ozon.ru/api/seller/ | HTTP 403 | Chặn thêm/thay cho tới khi xác minh schema và giới hạn |

Hai miền đã được lưu vào bản nháp network allowlist, chưa được áp dụng
cho runtime. Không dùng endpoint phỏng đoán và không gửi mutation thật.
Chưa xác minh quyền token, schema cập nhật, quy tắc xóa/thay barcode và
kết quả xử lý bất đồng bộ. Đây là blocker cho thao tác mới trên sàn;
không chứng minh bản thân API không hỗ trợ thao tác đó.

Đối chiếu, nguồn GTIN, sửa ánh xạ nội bộ, history và cơ chế hàng đợi có
thể kiểm thử độc lập. Mutation/reconciliation dùng adapter fixture trong
kiểm thử; không coi kết quả fixture là xác nhận trên tài khoản seller.
