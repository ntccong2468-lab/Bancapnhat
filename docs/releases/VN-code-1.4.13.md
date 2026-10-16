# VNCode 1.4.13

Bản sửa đồng bộ sản phẩm, Znack/GTIN và kho KIZ seller nhập, kế tiếp 1.4.12.

Chưa phát hành. Phần mục 1 được người dùng duyệt và bổ sung:

- Luồng đầy đủ từ tạo/đăng ký thẻ, theo dõi duyệt, ký CryptoPro đến đọc lại đúng thẻ. Chữ ký được nhận chưa đủ để báo xuất bản.
- Nút đồng bộ GTIN ở đăng ký Znack mở đối chiếu và xem trước của đúng cửa hàng WB/Ozon. Seller xác nhận trước khi ghi; đọc Znack nền không tự phê duyệt ghi GTIN.
- Theo dõi thẻ chờ xử lý bằng worker Znack hiện có, không giữ worker ngủ nhiều phút. Chỉ tiếp tục tác vụ ghi đã được xác nhận và đối soát khi kết quả chưa rõ.
- Ozon kiểm tra lại SKU/tên/biến thể; tên sản phẩm cũng nằm trong fingerprint trước gửi và khi đọc lại.
- Sửa yêu cầu danh sách Catalog thiếu khoảng thời gian khiến API trả 0 thẻ dù tài khoản có thẻ. Đã xác minh bằng client VNCode với API thật: đọc đủ 1.961 thẻ qua hai trang; mẫu WB 20/20 có GTIN khớp thẻ đã xuất bản, mẫu Ozon chưa có GTIN trong barcode. Chưa thực hiện ghi GTIN mới hoặc ký CryptoPro thật.

[Báo cáo mục 1 và giới hạn nghiệm thu thật](../validation/2026-10-16-approved-item-1-znack-gtin.md).
[Báo cáo kiểm tra API thật sau sửa](../validation/2026-10-16-item-1-live-api-report.md).

Mục 2 đã được duyệt và triển khai cho shipment FBS của WB:

- Thêm hai nguồn trong «Kho KIZ / Znack»: KIZ do seller nhập và Mua KIZ; lưu lựa chọn riêng theo shipment.
- Hiển thị danh mục đã đồng bộ, nút tải tệp từng dòng và số lượng còn/đang giữ/đã dùng. Nhập mã theo danh mục không cần kiểm tra Znack hoặc trùng GTIN của sản phẩm.
- Chỉ kho nhập thì thiếu mã sẽ báo đơn/danh mục và không mua. Chọn cả hai thì kho nhập trước, chỉ chuyển phần thiếu sang pipeline mua hiện tại; nguồn mua loại toàn bộ mã nhập khỏi tồn và giữ kiểm tra GTIN.
- Giữ nguyên pipeline đặt mua, ký, tải mã, đưa vào lưu thông và đối soát. Danh sách thẻ GTIN của kho mua chỉ dựng các dòng đang nhìn thấy.
- Nhập lại tệp không chuyển mã sang nhóm khác; danh mục trống mới phát hiện không buộc hàng không đánh dấu phải có KIZ. Nguồn của tác vụ được chốt trước khi chạy, đổi cửa hàng không nhận kết quả cũ.

[Báo cáo mục 2 và giới hạn kiểm thử](../validation/2026-10-16-item-2-wb-shipment-kiz.md).

- UI và tác vụ nền cùng nhận kết quả của một lượt đọc Catalog đang chạy, tránh việc màn hình bị bỏ lại danh sách trống khi đồng bộ lúc khởi động.
- Dùng chung nguồn Catalog cho các màn hình; đọc theo tài khoản đã xác thực và phân trang, không chỉ danh sách nhóm `lp` hoặc cache đăng ký local.
- Máy mới có thể lấy API key Catalog qua chứng thư CryptoPro đã cấu hình; bản sao lưu chứa key DPAPI của Windows khác sẽ xác thực lại và lưu key riêng trên máy hiện tại. Không cần đăng nhập trình duyệt; mỗi máy vẫn cần chứng thư/khóa riêng hoạt động.
- Thẻ đã xuất bản không bị ẩn khỏi nguồn GTIN chỉ vì thiếu metadata giấy tờ. Kiểm tra giấy tờ cho mua/đưa mã vào lưu thông vẫn được giữ đúng bước.
- Ghép GTIN qua chỉ mục theo đúng biến thể; kiểm tra product ID, offer ID và SKU Ozon trước khi ghi, rồi đọc lại để xác nhận. Tác vụ GTIN riêng không đợi API giá hoặc từ điển thuộc tính không liên quan.
- Tách hàng đợi Znack/GS1 khỏi tác vụ nền khác, hiển thị bước/số lượng và xử lý timeout. Lô Catalog lỗi không bị gọi lại cho từng GTIN; dòng ghi/đọc lại lỗi nhiều lần chuyển sang Cần xử lý với lý do cụ thể.
- Kho KIZ nhập theo danh mục dùng ngay, không kiểm tra Znack/INN/chứng thư và không yêu cầu GTIN mã nhập trùng GTIN sản phẩm. Bỏ nút kiểm tra Znack trong kho; giữ FIFO, chống trùng, reservation và lịch sử đã dùng trên WB/Ozon/FBO.
- Khi kho category thiếu, chỉ lượng mua bù mới cần đúng GTIN đã xác minh của sản phẩm. Nhập file không tự tạo chứng cứ Catalog hoặc đánh dấu giả đã đưa mã vào lưu thông.
- Báo cáo tác vụ hiển thị từng GTIN/thẻ và sản phẩm, trạng thái/loại lỗi, kể cả nguồn đồng bộ mới.

Kho KIZ vẫn riêng từng máy; tránh cấp phát cùng tập mã trên hai kho độc lập. Các thay đổi được đối chiếu ghi chú WBCode Latest 1.3.9 và mã WCode công khai; không có mã nguồn nội bộ 1.3.9 để xác nhận tương đương toàn bộ. Nghiệm thu trên tài khoản seller/máy thứ hai thực tế và nghiệm thu license production đúng phiên bản vẫn chưa được thực hiện.
