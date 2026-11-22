# VN Code 1.6.14

- Giao diện đen phẳng, chữ và nút dễ đọc hơn. Các màn FBS, FBO, Sản phẩm, Znack và KIZ tận dụng tốt hơn chiều rộng cửa sổ. Chuyển trang nhẹ hơn và tôn trọng tùy chọn giảm chuyển động.
- Phiên license vừa kích hoạt được giữ khi có lượt kiểm tra đang chờ. Nút Hủy dừng lượt tải bản cập nhật cả khi đang xếp hàng lẫn khi đang chờ dữ liệu từ máy chủ.
- Luồng FBS Wildberries xác minh cấu hình cửa hàng trước khi tạo lô. App giữ xác nhận đã giao của Wildberries và đồng bộ lại trạng thái nếu lần đọc tiếp theo bị lỗi.
- Phân trang, chọn và chỉnh sửa sản phẩm dùng kết quả của bộ lọc hiện hành. Luồng sao chép bài phân biệt phản hồi từ chối, giới hạn tốc độ và trường hợp cần đối soát.
- CryptoPro đọc đúng múi giờ ghi trên chứng thư. Znack trả lại chỗ trong hàng đợi sau khi hủy và khôi phục thao tác khi lượt sửa thẻ quá hạn.
- Thiết kế mẫu in giữ đúng mẫu đang sửa. Làm mới tin tức vẫn hoàn thành khi đổi thiết lập dịch. Báo cáo phạt Ozon và thao tác chọn mã TN VED hiển thị đúng trạng thái.
