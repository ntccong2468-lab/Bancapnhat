# VNCode 1.4.13

Bản sửa đồng bộ sản phẩm, Znack/GTIN và kho KIZ seller nhập, kế tiếp 1.4.12.

Đã phát hành [VNCode 1.4.13 Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.13)
theo yêu cầu người dùng. Cả bảy mục đã được duyệt và triển khai; mục 3 được làm xong
trước mục 7 theo thứ tự người dùng yêu cầu. Kiểm chứng API/Windows thật
được tách riêng với kiểm thử cục bộ, không dùng kết quả giả lập để xác nhận
mua hoặc gán mã thật.

Windows build `38962251398` đã qua toàn bộ kiểm thử, launcher, DPAPI,
DPI/bố cục và nâng cấp giữ dữ liệu đến 1.4.12. Đường tải EXE công khai đã
được tải lại, đối chiếu hash và đọc bằng parser updater của JAR Windows.
[Bằng chứng Windows/phát hành](2026-10-16-vncode-1413-windows-release-checks.json).
`complete` và `licenseIntegration.productionReady` vẫn là `false` trong
trạng thái kỹ thuật vì nghiệm thu API seller/license production chưa hoàn
tất; việc Published/Latest được ghi riêng theo yêu cầu phát hành trực tiếp.

| Mục | Phần đã triển khai | Báo cáo |
| --- | --- | --- |
| 1 | Đọc nguồn, tạo/đăng ký thẻ, duyệt/ký/xuất bản và đối chiếu GTIN WB/Ozon | [Mục 1](../validation/2026-10-16-approved-item-1-znack-gtin.md) |
| 2 | Nguồn kho seller/mua theo shipment WB, nhập theo danh mục | [Mục 2](../validation/2026-10-16-item-2-wb-shipment-kiz.md) |
| 3 | Nguồn kho/mua và danh mục Ozon, ảnh chính, báo cáo in FBS | [Mục 3](../validation/2026-10-16-item-3-ozon-fbs.md) |
| 4 | Danh sách đăng ký Znack, trạng thái/thao tác rõ và nút đồng bộ GTIN có đối chiếu | [Mục 4](../validation/2026-10-16-item-4-znack-registration.md) |
| 5 | GS1 dùng cấu hình Znack/CryptoPro, thông tin từng nguồn và báo cáo đồng bộ | [Mục 5](../validation/2026-10-16-item-5-gs1.md) |
| 6 | Sao chép kế thừa dữ liệu gốc, giá biến thể, xem trước và báo cáo | [Mục 6](../validation/2026-10-16-item-6-listing-copy.md) |
| 7 | Khung giao diện chung, menu gọn, cập nhật dòng FBS và đo tải | [Mục 7](../validation/2026-10-16-item-7-workspace-ui.md) |

Mục 1 được duyệt và bổ sung:

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

Mục 3 bổ sung cho Ozon:

- Tab Kho KIZ / Znack với nguồn seller/mua; giữ cùng lựa chọn từ kiểm tra trước in tới giữ mã và xuất PDF.
- Category/type ID gốc phân biệt nhóm trùng tên; tải tệp và số còn/giữ/đã dùng theo nhóm.
- Ảnh chính hỗ trợ các dạng phản hồi của Ozon, không lấy ảnh tùy tiện khi SKU mơ hồ; kiểm tra offer của đơn.
- Báo cáo từng posting phân biệt giữ mã cục bộ, PDF hoàn tất và kết quả từ sàn. Giữ hợp đồng in cục bộ hiện tại, không bật lại endpoint exemplar legacy.

Mục 7 thống nhất toàn ứng dụng:

- Sidebar tự động hoặc chủ động thu gọn; header chuyển các thao tác phụ vào menu và giữ cửa hàng/đồng bộ/tác vụ/cập nhật dễ tiếp cận.
- Cùng kiểu chữ, khoảng cách, bảng, thẻ, nút, tab và trạng thái ở các route; dialog vừa cửa sổ chủ, các bảng dài cuộn riêng.
- Bảng FBS và sản phẩm giữ dòng không đổi, lựa chọn/bộ lọc/cuộn; dữ liệu sản phẩm và ảnh vẫn cập nhật đúng khi metadata đổi hoặc ảnh về muộn.
- Giữ runtime đồng bộ nền, hàng đợi và quota hiện có. Trạng thái phân biệt đang chạy, hoàn tất một phần, dữ liệu cũ và lỗi; không gọi polling là push tức thời.
- Tên bước/tác vụ và thời gian báo cáo dễ đọc; timestamp báo cáo theo Moscow.

Kiểm thử cuối trên mã nguồn `040172d`: `mvn verify` và đóng gói JAR qua,
1.533 bài thành công, 2 bài dành riêng cho Windows được skip, không có
failure/error. Hợp đồng phát hành 60/60, Portal 60/60 và kiểm tra cú pháp
qua. Ma trận giao diện không có control bị cắt; phép đo tổng hợp 10 shop
× 1.000 đơn có p95 từ ghi cục bộ đến hiển thị 89 ms. Số này không bao gồm
mạng/quota và không phải kết quả đo Windows seller.
[Bằng chứng kiểm thử cuối, commit và giới hạn](../validation/2026-10-16-items-3-7-final-checks.json).

Các thay đổi đồng bộ và kho KIZ dùng chung từ đầu bản 1.4.13:

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
