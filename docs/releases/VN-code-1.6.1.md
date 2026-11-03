# VN code 1.6.1

Bản gộp các chỉnh sửa sau 1.6.0: giao diện toàn ứng dụng, sửa thông tin sản phẩm và Catalog, FBS/KIZ và xuất PDF Ozon. Giữ định danh cài đặt và thư mục dữ liệu để nâng cấp từ 1.6.0.

## Giao diện toàn ứng dụng

- Nền tối và sáng dùng chung hệ thống màu, chữ, input, nút, tab, bảng và hộp thoại. Sidebar gọn hơn; các danh sách sản phẩm và đơn hàng chiếm vùng làm việc và tăng kích thước theo cửa sổ.
- Thanh tiến trình sửa sản phẩm, đăng ký Znack và in Ozon có vùng riêng dưới danh sách, giữ dữ liệu/lựa chọn và không phủ hàng đang xem. Các nút cố định và nội dung dài được kiểm tra ở cửa sổ nhỏ, bốn ngôn ngữ.
- Giữ năm nút sửa thông tin sản phẩm; toolbar chọn hàng và các hộp thoại Áp dụng/Hủy vừa cửa sổ. Thêm tên truy cập cho ô tìm kiếm, bộ lọc, nút biểu tượng và liên kết nhãn với trường nhập.
- Sửa cột giá Ozon bị cắt ở cửa sổ hẹp: những cột có chiều rộng cố định vẫn tham gia đúng phép tính co bảng. Giữ cuộn ngang khi tổng chiều rộng tối thiểu không đủ.
- GS1, năm tab cấu hình Znack/Kho KIZ, Tổng quan/tài chính, FBO, shipment, lịch sử in, mẫu in, GTIN, TN VED, sao chép, hướng dẫn/tin tức, license và khởi động đều nhận bộ control mới. Giữ các thao tác, lựa chọn theme và tùy chỉnh nền sáng hiện có.

## Sản phẩm và đăng ký Catalog

- Sửa hoàn tất công việc chỉnh sửa sản phẩm và bước đối soát; xử lý riêng sản phẩm thiếu từ điển thay vì chặn toàn bộ tập chọn.
- Phiên bản dữ liệu Ozon không còn đổi chỉ vì trạng thái kiểm duyệt hoặc thứ tự thuộc tính đơn; thay đổi nội dung thật vẫn yêu cầu kiểm tra lại. Preview cũ chưa gửi có thể cần lập lại; không tự phát lại yêu cầu đã gửi.
- Dùng đúng hồ sơ đã xem, TN VED của biến thể và giấy tờ phù hợp; so sánh nước bằng từ điển đã xác minh khi Catalog trả tên thay mã ISO. Lưu receipt, chờ đọc lại đúng thẻ trước khi xác nhận; không đánh dấu thành công chỉ từ HTTP 200.
- Nghiệm thu API trước phát hành: 10/10 lượt gửi lại giá trị hiện có của năm trường WB/Ozon được đọc lại xác nhận; một GTIN/feed mới được đọc thấy đúng thẻ Catalog ở trạng thái draft/moderation. Chưa ký/công bố thẻ thử hoặc gửi GTIN mới lên sàn; không suy kết quả này thành nghiệm thu mọi danh mục.

## FBS, KIZ và PDF Ozon

- Giữ xử lý từng kết quả nhận đơn, đối soát yêu cầu chưa rõ và tiếp tục những đơn API cho phép. Hiển thị riêng đơn chưa xử lý được; không báo đã nhận hoặc đã in cho đơn sàn từ chối.
- Đọc dữ liệu nguồn theo nhóm tối đa 100 mã đơn và phân trang thay vì luôn đọc từng đơn. Yêu cầu nhãn theo tác vụ lô tối đa 1.000 đơn; giữ nguyên PDF Ozon khi membership, số lượng và trạng thái tác vụ đã đối soát, kể cả trang nhãn sản phẩm đi kèm.
- Thiếu KIZ hoặc mapping không loại nhãn vận đơn mà Ozon đã cấp. Báo rõ phần chưa kèm KIZ; chỉ thêm KIZ đã xác minh binding và không trừ kho phần chưa in. KIZ được thêm thành phần riêng, không ghép bằng thứ tự trang không được chứng minh.
- Lần in lại tiếp tục receipt/tác vụ đã lưu; kết quả gửi chưa rõ được đối soát trước, không tự tạo yêu cầu lặp. Bỏ giới hạn chia lô lỗi quá nhỏ và chỉ chia khi đã biết yêu cầu không tạo tác vụ.
- Cache thumbnail trên đĩa riêng với ảnh đầy đủ để tăng tốc phiếu nhặt hàng và in lại. Phiếu nhặt hàng Ozon có thông tin mã vận đơn theo dữ liệu sàn.
- Kho KIZ seller tải lên dùng chính sách danh mục đã chọn, kho mua tiếp tục cần GTIN đúng biến thể/thẻ đã xác minh. Đề xuất mua thiếu và các bước có phí giữ xác nhận hiện có.
- Đo thật trên một đơn/2 trang: 6,111 giây cho tác vụ mới; in lại cùng task có cache 4,989 giây. Lô 100/1.000 đã kiểm tra bằng HTTP/PDF giả lập; chưa có phép đo thật cho lô lớn. Tốc độ phụ thuộc mạng, số trang, ảnh, KIZ và thời gian Ozon tạo nhãn.

## Tổng quan và độ ổn định

- Tổng quan có giao dịch phạt WB/Ozon, số tiền, đơn/SKU và mô tả do sàn cung cấp; mô tả sàn có thể chưa nêu đầy đủ nguyên nhân chi tiết. Giữ phạm vi cửa hàng và trạng thái dữ liệu đã xác minh.
- Kế thừa các sửa audit cho cô lập lỗi PDF/KIZ, dữ liệu, updater và quy trình kiểm thử. Kiểm tra và sử dụng đồng hồ license trong cùng khóa, giữ toàn bộ điều kiện xác minh chữ ký, hết hạn và thu hồi.
- Thử nghiệm migration mã hóa credential vẫn nằm ngoài runtime; bản này không áp dụng thử nghiệm đó vào dữ liệu seller.

Ảnh và báo cáo: `docs/validation/2026-11-03-ui-ux-pro-max/README.md`. Bộ cài được phát hành sau kiểm thử Windows, khởi động/cài mới/nâng cấp và đối chiếu checksum. Các test tự động dùng dữ liệu tạm, không kích hoạt license thật hoặc tự mua KIZ.
