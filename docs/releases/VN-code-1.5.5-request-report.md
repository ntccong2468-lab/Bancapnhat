# Báo cáo VNCode 1.5.5

## Phạm vi sửa

Bản nền là 1.5.4. Bản này đưa toàn bộ sửa nguồn sau 1.5.4 vào bộ cài mới: đăng ký WB/Ozon kiểm tra đủ dữ liệu và xác nhận đúng thẻ Catalog; mẫu tự điền từ cấu trúc 24 thẻ đã ký xanh; mặc định nước sản xuất hiển thị «Россия»; bộ lọc Đang xử lý và báo cáo tiến trình; ký tối đa 10 thẻ/lô; phân loại lỗi CryptoPro/CRPT; Đồng bộ & map GTIN Ozon; tự ẩn viên thuốc sửa lỗi; khôi phục cửa sổ đang giữ dữ liệu và bảo vệ database; bỏ nhãn sản phẩm Ozon trùng khi đã đủ nhãn kết hợp.

Giữ dữ liệu seller, schema 6, phạm vi TN VED/giấy tờ, ưu tiên FBS, kho KIZ, Liquid Glass và các sửa updater/mua KIZ đã có trong 1.5.4. Mẫu xanh không cung cấp danh tính, giấy tờ hoặc GTIN cho sản phẩm khác; không chứng minh thẻ mới đã được duyệt.

## Kiểm thử bộ cài phát hành

Source: `cc73b32ae50ed21a25196653291df2996ecef1bf`. Bản nền: [VNCode 1.5.4](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.4); giữ schema 6 và dữ liệu seller.

Linux `clean verify`: 2.309 kiểm thử, 2.307 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua theo nền tảng. Windows Java mới: 2.309 kiểm thử, 2.307 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua theo nền tảng. [Windows build 39496207125](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39496207125) chạy từ đúng source, artifact 12301419643; không dùng lại kết quả Java của bản trước.

Đủ 25 bằng chứng native và 20 ca nâng cấp giữ dữ liệu, gồm 288 bố cục, launcher, workspace, DPAPI và bộ cài 1.5.4 đúng checksum. Bàn giao `/passive /norestart` được kiểm tra bằng chính EXE phát hành và MSI nhúng; dữ liệu và đăng ký ứng dụng được giữ.

Bộ cài: `VN-code-1.5.5-Windows-x64.exe`, 147.044.352 byte. SHA-256: `e966b8d36101f8d0c62b5340e7804ff574fb1453e89d713dbcd604b7446b4b13`. Checksum đối chiếu đúng các byte đã kiểm tra. Updater phát hiện bản mới không chứng minh thông báo đã xuất hiện trên desktop seller.

## Chưa hoàn tất

Chưa nghiệm thu tạo/sửa thẻ Catalog production, chữ ký CryptoPro doanh nghiệp, ghi GTIN qua desktop seller, giao dịch mua KIZ trả phí thật hoặc license production trong phiên bản này. Mọi cờ nghiệm thu production tương ứng vẫn false; kiểm thử mô phỏng không thay thế giao dịch thật. Không bảo đảm sản phẩm khác được duyệt chỉ vì mẫu Catalog đã xanh.

Phát hành theo yêu cầu trực tiếp của chủ dự án với các giới hạn đã công bố. Kiểm tra license, chữ ký, TLS và toàn vẹn của app được giữ.
