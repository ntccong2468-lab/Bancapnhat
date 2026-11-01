# Bảo mật

- Giữ token API, credentials, email, thông tin license, chứng thư và mã KIZ ngoài mã nguồn, log, báo cáo và fixture. Sử dụng dữ liệu tổng hợp trong kiểm thử; khi chia sẻ lỗi phải loại bỏ thông tin nhạy cảm.
- Bảo toàn kiểm tra quyền, phạm vi cửa hàng, xác minh nguồn/chủ sở hữu, mã ký và checksum cập nhật. Không tắt TLS hoặc xác minh chữ ký để vượt qua lỗi kết nối.
- Với các endpoint và truy vấn mới, kiểm tra xác thực, phân quyền, đầu vào và phạm vi tenant; dùng prepared statements cho giá trị SQL. Kiểm tra output UI theo công nghệ thực tế thay vì giả định JavaFX là trình duyệt.
- Xử lý các thao tác có tác động ngoài hệ thống theo phạm vi người dùng cho phép. Không thử ghi, mua KIZ, ký hoặc nhận đơn thật trong các bài thử tự động mặc định.
- Rà lỗ hổng dependency và source bằng công cụ có sẵn hoặc được cấu hình cho dự án; ghi nguồn dữ liệu, thời gian, mức ảnh hưởng và các giới hạn của quét. Không công bố dữ liệu nhạy cảm trong SARIF hoặc log CI.
- Không tự sửa cấu hình secrets/quyền GitHub, tắt branch protection hay xóa release để khắc phục kiểm thử. `prune` trong `tools/baseline-release.mjs` có thể xóa các release cũ.

Các kết quả kiểm thử mô phỏng không phải chứng nhận bảo mật hoặc xác nhận Catalog/sàn đã chấp nhận giao dịch.
