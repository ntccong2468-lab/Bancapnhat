# VNCode 1.5.11

- Đồng bộ GTIN lên WB: kiểm tra lô 10.000 với giới hạn toàn thẻ, chia yêu cầu phù hợp, giữ barcode cũ và xác nhận từng biến thể bằng đọc lại. Báo cáo phân biệt đã gửi, đang chờ, đã xác nhận và cần xử lý; HTTP nhận yêu cầu không được tính là đã cập nhật.
- KIZ WB/Ozon: đơn chưa có GTIN trên sàn có thể dùng GTIN hợp lệ của đúng sản phẩm/biến thể đã gửi Catalog và đang chờ duyệt. Định danh này được ưu tiên trước quy tắc ghép chung; giữ phạm vi shop, chứng thư và môi trường. Có đủ mã sẵn dùng đúng GTIN thì tiếp tục gán; khi thiếu, dùng lại luồng mua và mã yêu cầu bền vững hiện có.
- Mã mới nhận nhưng chưa đủ điều kiện lưu thông vẫn được đối soát theo trạng thái thật. Không tạo GTIN, giấy tờ hoặc trạng thái lưu thông giả, không mua lại khi kết quả yêu cầu cũ chưa rõ.
- Gom sửa đăng ký trước đó: Chưa tạo dành cho chưa tạo/chưa xác nhận GTIN; Đang xử lý hiện ngay lúc gửi; Lỗi chỉ là Catalog từ chối. Chỉnh sửa mở bảng đề xuất tự điền, seller xem và ấn Áp dụng; sửa cùng thẻ giữ GTIN.
- Bản nền đầy đủ: bộ cài EXE/MSI, bộ chạy Windows kèm Java, mã nguồn toàn repository, Git bundle, checksum và báo cáo kiểm thử được đóng gói cùng một commit. Giữ định danh nâng cấp và dữ liệu schema 6; không thay bố cục màn hình chính.

Shop người dùng yêu cầu đối chiếu: **Mpp fashion**; số liệu thực tế trên máy seller chưa được cung cấp/đọc trong phiên cloud. Các số kiểm thử 10.000 là mô phỏng qua pipeline thật với dịch vụ sàn giả lập, không xác nhận shop đã đồng bộ 10.000 GTIN thật. Không thực hiện mua KIZ có phí, ký CryptoPro hay ghi Catalog/WB/Ozon production trong đợt kiểm tra này. Bằng chứng chạy nằm trong gói verification và build-info; chỉ công bố các ca đã thực sự chạy.

Bộ cài Windows chưa có chữ ký Authenticode. Đóng ứng dụng trước khi nâng cấp; dữ liệu cá nhân và thông tin tài khoản không nằm trong gói mã nguồn/bản nền.
