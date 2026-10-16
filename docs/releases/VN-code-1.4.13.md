# VNCode 1.4.13

Bản nâng cấp từ VNCode 1.4.12 dành cho Windows x64.

- **Đồng bộ Znack và GTIN:** dùng chung nguồn National Catalog theo đúng tài khoản; sửa trường hợp đồng bộ trả danh sách trống và khôi phục cấu hình Catalog trên máy mới. Luồng đăng ký có tạo thẻ, theo dõi duyệt, ký và xác nhận xuất bản. Đồng bộ GTIN có xem trước, xác nhận và đọc lại WB/Ozon; Ozon kiểm tra thêm product/offer/SKU, tên và biến thể.
- **Kho KIZ trong FBS WB/Ozon:** chọn “KIZ do seller nhập” hoặc “Mua KIZ”. Tải tệp riêng theo danh mục, xem số còn/đang giữ/đã dùng. Kho nhập cấp theo danh mục, không kiểm tra Znack hoặc ép trùng GTIN sản phẩm. Chọn cả hai nguồn thì ưu tiên kho nhập và chỉ mua phần thiếu; giữ luồng mua hiện tại và kiểm tra đúng GTIN cho phần mua.
- **FBS Ozon:** bố cục cùng cách dùng với WB, bổ sung tab Kho KIZ / Znack và sửa lấy ảnh chính. Báo cáo từng đơn phân biệt giữ mã cục bộ, tạo PDF và xác nhận từ sàn; không báo đã gán thành công trên sàn chỉ từ kết quả cục bộ.
- **Đăng ký Znack:** chỉ mở danh sách sau khi đồng bộ Znack và sản phẩm sàn thành công; hiển thị trạng thái, thao tác, lý do lỗi và kết quả từng thẻ. Thêm thao tác đồng bộ GTIN lên WB/Ozon có đối chiếu.
- **GS1:** dùng thông tin Cấu hình Znack/CryptoPro, đồng bộ thành viên, tiền tố, số dư và báo cáo từng nguồn ngay trong app. Thư GS1 dùng hộp thư seller cấu hình.
- **Sao chép bài đăng:** kế thừa dữ liệu sản phẩm gốc và giá từng biến thể; có bản xem trước và các mục chỉnh sửa tùy chọn. Trường API không hỗ trợ hoặc dữ liệu còn thiếu được báo rõ.
- **Giao diện và tốc độ:** thu gọn thanh điều hướng/header theo cửa sổ; thống nhất khoảng cách, bảng, nút và hộp thoại. Bảng FBS chỉ cập nhật dòng thay đổi, giữ lựa chọn và vị trí cuộn; sửa ảnh tải muộn. Đồng bộ tiếp tục chạy nền theo hạn mức API, có trạng thái và báo cáo tác vụ cụ thể.

Tải **VN-code-1.4.13-Windows-x64.exe** trong Assets. Đóng VNCode trước khi cài; bộ cài đã kèm Java. Kho KIZ được lưu riêng trên từng máy.

Kiểm thử Java/giao diện trên Linux và Windows: mỗi lượt 1.533 bài qua, 2 bài không áp dụng cho nền tảng được skip; không có failure/error. Thông tin kiểm chứng Windows và nâng cấp nằm trong các tệp build-info/native-smoke/upgrade-stable-1412-smoke kèm bản phát hành. Bộ cài chưa có chữ ký Authenticode. Kiểm thử mua/gán KIZ qua API thật và nghiệm thu license production nhiều máy/khác mạng chưa hoàn tất cho bản này.

[Báo cáo chi tiết bảy mục nâng cấp](https://github.com/ntccong2468-lab/Bancapnhat/blob/update/vncode-approved-upgrades/docs/validation/2026-10-16-vncode-1413-upgrade-summary.md) · [Bằng chứng kiểm thử cục bộ](https://github.com/ntccong2468-lab/Bancapnhat/blob/update/vncode-approved-upgrades/docs/validation/2026-10-16-items-3-7-final-checks.json)
