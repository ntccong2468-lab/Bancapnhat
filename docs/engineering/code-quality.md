# Chất lượng mã

- Tìm nguyên nhân trước khi sửa và giữ thay đổi nhỏ, dễ xem xét. Dùng test tái hiện lỗi và các trường hợp biên khi cần; không tạo test chỉ phản chiếu từng dòng triển khai.
- Giữ ranh giới giữa UI, nghiệp vụ, lưu trữ và adapter sàn. Hành vi tương tự giữa WB và Ozon nên dùng thành phần chung khi hợp lý, nhưng không bỏ qua hợp đồng và trạng thái riêng của từng sàn.
- Dữ liệu/ID liên quan đến seller phải được kiểm tra phạm vi và danh tính; không ghép sản phẩm hoặc GTIN chỉ theo tên gần giống. Các trạng thái chờ, lỗi, chưa xác định và đã xác nhận cần phân biệt rõ.
- Đóng tài nguyên, giới hạn hàng đợi và timeout hợp lý; xử lý hủy, khôi phục và đồng thời mà không làm mất dữ liệu hoặc gửi lặp.
- Không sửa schema không có migration và test tương thích dữ liệu cũ. Không thay đổi dependency hoặc cấu hình production hàng loạt chỉ để qua build.
- Trước khi bàn giao, đọc diff, chạy `git diff --check` cùng kiểm thử và build phù hợp; báo các giới hạn, bước chưa chạy và kết quả không tái hiện được. Tham khảo `docs/engineering/testing.md` và `docs/engineering/security.md`.
