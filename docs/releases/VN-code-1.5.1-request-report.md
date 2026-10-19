# Báo cáo yêu cầu VNCode 1.5.1

Đã viết luồng báo cáo và sửa thẻ Znack lỗi: chọn tất cả, xem lỗi, Chỉnh sửa/Hủy, đề xuất giá trị trước/sau, bổ sung trường/giấy tờ và Áp dụng. Bảng đóng trước khi chạy nền; viên thuốc dưới giữa bảng theo dõi từng thẻ. Giữ GTIN của thẻ sửa, xác minh lại dữ liệu và giấy tờ, không báo đăng ký thành công chỉ từ receipt.

Giữ toàn bộ thay đổi đã lưu cho 1.5.1: GTIN theo lô WB/Ozon; KN3 và thay KIZ đỏ trong shipment; sửa năm trường sản phẩm theo lô và đọc lại sàn; kiểm tra TN VED/giấy tờ trong mẫu đăng ký; logo VN Liquid Glass nền xanh chữ trắng. Giữ nền 1.5.0, schema 6 và ưu tiên FBS.

178 ca hồi quy liên quan và 73 ca công cụ release đã qua trên Linux; bộ cài và kiểm thử Windows sẽ được kiểm chứng bằng CI nguồn cố định trước khi xuất bản.

**Chưa hoàn tất nghiệm thu production:** chưa tạo/sửa/ký thẻ thật bằng CryptoPro doanh nghiệp; chưa xác nhận license production hoặc lượt ghi WB/Ozon thật từ bản desktop này. Kiểm thử tự động dùng API giả lập, không thay thế xác nhận của Catalog hoặc sàn. Chủ repo yêu cầu phát hành Latest; việc phát hành không đổi các mục chưa nghiệm thu thành đã qua.
