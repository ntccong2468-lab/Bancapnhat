# Đối chiếu yêu cầu VNCode 1.5.0

| Yêu cầu | Trạng thái |
| --- | --- |
| Tải 100% nhưng giữ tiến trình lâu | Đã tách trạng thái xác minh/sao lưu/mở bộ cài; không hiển thị 100% cho giai đoạn vẫn còn xử lý. Giao diện không bị khóa luồng FX khi thao tác đĩa. |
| Mở ứng dụng chờ vài phút | Cửa sổ xuất hiện trước khi phục hồi; chuẩn bị dữ liệu chạy nền và chỉ chạy một lần. Bỏ lượt quét toàn bộ bản sao lưu khi không có bản nào được phép xóa. Launcher thật trên Linux/Xvfb với profile trống: cửa sổ đầu khoảng 3,93 giây, màn hình chính khoảng 5,28 giây; chưa coi đây là số đo máy seller Windows. |
| Nút và điều hướng theo ảnh Liquid Glass mới | Đã viết lớp giao diện chung; giữ kích thước thanh sửa sản phẩm và khả năng cuộn của bảng tin. |
| Trong như giọt nước, chữ rõ | Nền kính tối thiểu alpha 0,005; chữ alpha 1; không giảm opacity của Node/Window. Kiểm tra cả hai theme và hai đầu thanh trượt. |
| Đưa các sửa GTIN/CryptoPro và KIZ vừa làm vào 1.5.0 | Có trong cùng mã nguồn ứng viên; không dùng lại bộ cài 1.4.19. |
| Đăng ký lần lượt từng thẻ | WB đợi worker lưu checkpoint rồi mới chuẩn bị thẻ tiếp; Ozon giữ chuỗi chờ hoàn tất từng thẻ. Chờ duyệt/cần ký là trạng thái riêng, không phải thành công. |
| Trạng thái thẻ có sẵn trên Catalog | Bổ sung nhận diện lỗi/kiểm duyệt/nháp theo dữ liệu thật cho WB/Ozon; sửa báo cáo từng thẻ và bộ lọc/tổng hợp WB. Phân loại bằng mã ứng dụng khớp 1.277/1.277 phản hồi thật: 1.237 xuất bản, 34 lỗi, 4 kiểm duyệt, 2 nháp. |
| Cập nhật GTIN lần lượt và báo kết quả thật | Giữ kiểm tra thẻ xuất bản, article/màu/size/TN VED, thêm kiểm tra SKU/offer Ozon; đọc lại sàn mới tính thành công. Không tự gửi lại lần ghi chưa rõ kết quả. |
| Đồng bộ FBS liên tục, được ưu tiên | Không đổi nhịp FBS; ghi GTIN dùng hàng đợi seller hiện có và nhường suất HTTP riêng cho FBS. |
| Dữ liệu thật | Đọc toàn bộ danh mục WB/Ozon/Catalog qua Secret của môi trường, không dùng dữ liệu fixture cho các yêu cầu thật. |
| Đăng ký/ký thẻ và sửa sàn qua nút ứng dụng thật | **Chưa hoàn tất:** môi trường Linux không có CryptoPro/chứng thư riêng của seller, license production hay phiên seller Windows để nghiệm thu. Không dùng chứng thư hoặc license giả để thay thế. |
| Lọc KIZ đỏ bị WB/Ozon từ chối ngay lúc nhập | **Chưa triển khai xác minh hàng loạt trên sàn.** Báo cáo tệp chỉ xác minh cấu trúc và ghi rõ chưa kiểm tra chấp nhận trên sàn; lỗi mạng/quyền không được gán thành “mã hỏng”. |

Chi tiết kiểm thử và bộ cài được cập nhật trong [trạng thái phát hành](VN-code-1.5.0-status.json). Đã xuất bản 1.5.0 lên Latest, lưu làm bản nền cho các cập nhật sau. Bộ kiểm thử Windows: 1.790 đạt, 2 trường hợp bỏ qua theo nền tảng; 288 bố cục và 15 đường nâng cấp đã qua. Đã tải toàn bộ bộ cài công khai, xác minh SHA-256 và kiểm tra nguồn cập nhật bằng mã ứng dụng 1.4.19/1.5.0 thật; chưa xác nhận thông báo trên máy seller.
