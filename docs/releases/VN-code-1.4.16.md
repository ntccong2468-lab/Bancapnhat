# VNCode 1.4.16

Bản nâng cấp từ 1.4.15, tập trung sửa thao tác hàng loạt, đồng bộ GTIN chạy nền và kho KIZ trong shipment FBS.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.16/VN-code-1.4.16-Windows-x64.exe). Bộ cài có sẵn Java; cài đè để giữ dữ liệu hiện có.

- **Tin tức:** tiêu đề và nội dung nằm trong khung, có cuộn riêng. Tin chính thức của WB được tự dịch sang tiếng Việt và lưu bộ nhớ đệm; giữ nguyên ngày tháng, GTIN và đường dẫn. Phần dịch chạy riêng, không chiếm worker đồng bộ sàn. Khi dịch vụ dịch không phản hồi, vẫn giữ bản gốc và ghi rõ trạng thái.
- **Sản phẩm:** thay trang sửa có bảng xem trước bằng hộp sửa hàng loạt cho thương hiệu, chất liệu/tỷ lệ, giới tính, giấy tờ và TN VED. Bấm **Áp dụng** một lần cho tập sản phẩm đã chọn; app tự đọc mới, kiểm tra toàn bộ tập, lưu và đọc lại kết quả. Giấy tờ có sẵn được lấy từ toàn bộ cửa hàng, giữ thông tin loại và ngày hiệu lực. Thương hiệu shop được xếp trước; TN VED có nhóm gợi ý và lọc theo sản phẩm.
- **GTIN lên WB/Ozon:** chạy nền từ màn đăng ký Znack; không mở bảng xem trước. Thông báo dạng viên thuốc nằm dưới giữa bảng, hiển thị tiến độ và số thành công/chờ đọc lại/cần xử lý. Giữ kiểm tra thẻ đã xuất bản, đúng biến thể và SKU Ozon; không lặp lại lần ghi chưa rõ kết quả.
- **Danh mục đăng ký Znack:** tự chọn trong danh sách Catalog cho phép theo loại sản phẩm trên sàn, áp dụng cả WB và Ozon. Nếu còn nhiều danh mục phù hợp, báo cần kiểm tra loại hàng thay vì mở hộp chọn từng thẻ hoặc chọn đại.
- **Kho KIZ seller nhập:** cập nhật ngay số mã còn trong danh mục sau tải tệp; sửa việc FBS làm mới cùng shipment khiến kết quả nhập bị bỏ qua. Số còn tự giảm khi giữ/gán mã, không hiện cột mã đã dùng. Nhập lại tệp không tạo mã trùng hay khôi phục mã đã dùng. Kho nhập vẫn lấy theo danh mục; luồng mua KIZ giữ nguyên và đối chiếu đúng GTIN.
- **Liquid Glass:** mức 0% là lớp kính trong nhất, tăng dần đến kính phủ đặc ở 100%. Nền nút và viền phản chiếu theo vật liệu kính; chữ giữ màu tương phản đã đo trên cả nền sáng/tối. Cài đặt được lưu khi mở lại app. Hiệu ứng kính áp dụng trong giao diện VNCode.
- **Sao chép bài đăng:** chọn sản phẩm nguồn và shop đích rồi bấm **Đăng bài**, không còn bước bản xem trước/xác nhận riêng. Giữ dữ liệu gốc và tùy chỉnh nếu cần; có thanh tiến độ, trạng thái từng tác vụ và đọc lại sau gửi. Dữ liệu thiếu được báo đúng trường, không bắt nhập lại các giá trị gốc đã có.

Chặn callback tải cửa hàng mở lại cảnh báo sau khi màn hình đã đóng. Giữ các sửa lỗi SQLite và ưu tiên đồng bộ FBS liên tục của 1.4.15. Bộ cài Windows được kiểm tra nâng cấp giữ dữ liệu từ 1.4.15 cùng các bản nền trước.

Đối chiếu mã công khai WCode 1.0.27, nhánh công khai mới nhất `8f7c3d1`, WBCode Latest 1.3.9 và ảnh giao diện do chủ ứng dụng cung cấp. Không có mã nguồn nội bộ đầy đủ của WBCode 1.3.9. Kiểm thử tự động không thay thế việc ký CryptoPro, mua/gán KIZ và tạo thẻ thật trên tài khoản doanh nghiệp; đợt sửa giao diện này không tự thực hiện các thao tác sản xuất đó.
