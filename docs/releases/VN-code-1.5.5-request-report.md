# Báo cáo VNCode 1.5.5

## Phạm vi sửa

Bản nền là 1.5.4. Bản này đưa toàn bộ sửa nguồn sau 1.5.4 vào bộ cài mới: đăng ký WB/Ozon kiểm tra đủ dữ liệu và xác nhận đúng thẻ Catalog; mẫu tự điền từ cấu trúc 24 thẻ đã ký xanh; mặc định nước sản xuất hiển thị «Россия»; bộ lọc Đang xử lý và báo cáo tiến trình; ký tối đa 10 thẻ/lô; phân loại lỗi CryptoPro/CRPT; Đồng bộ & map GTIN Ozon; tự ẩn viên thuốc sửa lỗi; khôi phục cửa sổ đang giữ dữ liệu và bảo vệ database; bỏ nhãn sản phẩm Ozon trùng khi đã đủ nhãn kết hợp.

Giữ dữ liệu seller, schema 6, phạm vi TN VED/giấy tờ, ưu tiên FBS, kho KIZ, Liquid Glass và các sửa updater/mua KIZ đã có trong 1.5.4. Mẫu xanh không cung cấp danh tính, giấy tờ hoặc GTIN cho sản phẩm khác; không chứng minh thẻ mới đã được duyệt.

## Kiểm thử bộ cài phát hành

Linux clean verify mới: 2309 kiểm thử, 2307 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua theo nền tảng. 103 kiểm tra công cụ phát hành đạt. Windows build 39496207125 đang chạy từ source cc73b32ae50ed21a25196653291df2996ecef1bf; chưa ghi nhận checksum hoặc ca nâng cấp thành công cho tới khi đọc đủ bằng chứng. Phải kiểm tra nâng cấp từ đúng EXE 1.5.4 đã phát hành bằng /passive /norestart và giữ dữ liệu.

## Chưa hoàn tất

Chưa nghiệm thu tạo/sửa/ký thẻ Catalog production, ghi GTIN lên WB/Ozon bằng nút trên Windows seller, mua KIZ trả phí thật, chữ ký CryptoPro doanh nghiệp hoặc license production trong phiên bản này. Chưa đo thời gian nâng cấp trên máy seller và chưa chứng minh thông báo đã tới desktop seller. Các cờ nghiệm thu tương ứng vẫn false; giữ kiểm tra license, chữ ký, TLS và toàn vẹn dữ liệu.

Phát hành theo yêu cầu của chủ dự án: «áp dụng cho bản cập nhật mới và đưa lên latest». Quyền kiểm thử không giới hạn chỉ áp dụng môi trường dữ liệu cô lập; không thay đổi cơ chế license của bộ cài seller.
