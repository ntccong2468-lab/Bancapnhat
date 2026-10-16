# VNCode 1.4.14

Bản nâng cấp từ 1.4.13, tập trung sửa đồng bộ Znack, thao tác sửa sản phẩm và các màn hình đã đối chiếu với WBCode 1.3.9 cùng ảnh seller cung cấp.

- Sửa lỗi SQLite `SYNC_STORAGE` / `database is locked` khi nhiều tác vụ cùng ghi dữ liệu. Giữ nguyên nguyên nhân lỗi đồng bộ để báo cáo; các giao dịch chỉ ghi dữ liệu cục bộ được chờ và thử lại có giới hạn.
- Sửa lỗi WB trả thuộc tính theo thứ tự khác nhau khiến VNCode tưởng sản phẩm đã đổi và chặn lưu. Vẫn kiểm tra các thay đổi thực sự của sản phẩm và thứ tự ảnh.
- Màn Sản phẩm WB hiển thị một dòng cho mỗi bài đăng, giữ đầy đủ các size khi chọn sửa/sao chép. Bổ sung bộ lọc lỗi có số lượng và thanh sửa hàng loạt; bố cục rộng tách giấy tờ/chất liệu khỏi giới tính/TN VED.
- Thêm gợi ý thương hiệu, giới tính và TN VED từ sàn; Ozon nhận diện đúng trường `ТН ВЭД коды ЕАЭС`, tìm giá trị đã chọn trong các từ điển lớn.
- Sửa sản phẩm có xem trước giá trị cũ/mới, xác nhận và nút **Lưu thay đổi lên sàn**. Sau gửi tự đọc lại có giới hạn; kết quả chưa xác nhận hiển thị đang chờ, có Đối soát. Thay đổi giá trị khi đang đọc bản xem trước sẽ yêu cầu xem trước lại.
- Sửa lỗi lưu bản xem trước có mã TN VED riêng từng sản phẩm (lỗi đọc dữ liệu tác vụ từ JSON); giữ đúng giá trị cho từng bài và mở lại tác vụ không gửi lặp.
- Sửa danh sách WB còn hiện cache cũ sau khi đã lưu và đọc lại thành công; mở sửa giấy tờ mới xóa hết thông tin của lần nhập trước.
- Thêm tra cứu 526 mã TN VED thời trang theo danh mục, giới tính, chất liệu và kiểu dệt. Có chọn mã chung hoặc chọn riêng từng sản phẩm trước khi xem trước; gợi ý chưa đủ dữ liệu/không duy nhất được ghi rõ để seller kiểm tra. Bộ tra cứu tham khảo không thay thế danh mục có phiên bản do seller nhập.
- Báo cáo tác vụ giữ dòng đang chọn khi tự làm mới, phân trang 300 dòng, hiển thị chi tiết SKU/biến thể/GTIN và mã lỗi. Trạng thái chờ seller xác nhận được phân biệt với chờ sàn xử lý; trạng thái đơn WB hiển thị riêng với KIZ cục bộ.

- Sửa nhập kho KIZ báo toàn mã trùng nhưng tồn danh mục bằng 0: nhập lại mã chưa phân loại vào danh mục đã chọn; cho chuyển mã còn trống từ danh mục khác bằng lựa chọn riêng. Báo rõ mã có sẵn, đang giữ, đã dùng, đã lưu trữ, khác chủ hoặc khác nội dung. Kho seller trên WB/Ozon dùng theo danh mục, không cần kiểm tra Znack hay trùng GTIN sản phẩm.
- Đổi 4 số đầu TN VED có thể đăng ký thẻ mới bằng nút Đăng ký hoặc tập chọn đã xác nhận. Thẻ cũ được giữ trong lịch sử; mỗi thẻ làm lại cần một GTIN mới. Giữ thẻ đang hoạt động tới khi thẻ thay thế được xác nhận.
- Đăng ký lại thẻ Ozon đang chờ sẽ tiếp tục theo dõi feed; không tự xin mã/gửi thẻ lần nữa. Lưu và hiển thị lý do từ chối. Khôi phục việc theo dõi thẻ WB bị báo Registration interrupted sau khi mở lại.
- Thêm lựa chọn tự ký sau duyệt cho từng thẻ đăng ký. Lựa chọn đã xác nhận được giữ khi mở lại app; chữ ký chưa rõ kết quả được đọc lại, không gửi lặp.
- Chuẩn hóa article theo ký tự Catalog thực sự bỏ; giữ dấu phân cách mã và kiểm tra biến thể, báo rõ nếu nhiều kết quả trùng khớp.
- Danh sách Znack WB/Ozon tải thêm khi cuộn; giữ giao diện ảo hóa để không nạp hàng nghìn dòng giao diện một lúc.
- Thương hiệu WB hiện thương hiệu của shop trước, tải thêm từ điển ở nền và cho cuộn toàn bộ kết quả. Nút dùng mã TN VED gợi ý theo từng sản phẩm nằm ngay bên trái Áp dụng; hộp duyệt Ozon được co gọn và xuống dòng.
- Kiểm thử lại GS1 ký trực tiếp trên app, điền đơn 01.04.01, tạo PDF và đính kèm ảnh đơn đã ký JPG/PNG có xử lý hướng EXIF. Nét ký không lưu thành hồ sơ chữ ký; tệp đã ký vẫn có bước xem và xác nhận gửi.

API thật đã đọc sản phẩm, thuộc tính và gợi ý của WB/Ozon. Phép thử gửi lại giá trị hiện có trên một sản phẩm WB đã được đọc lại khớp; Ozon đã nhận yêu cầu thử nhưng chưa xác nhận hoàn tất trong cửa sổ kiểm tra. Đây không phải nghiệm thu tạo GTIN mới, ký CryptoPro hoặc mua/gán KIZ thật. Việc kiểm tra Catalog mới cần Secret Catalog trong môi trường; các ảnh GS1/Znack được dùng làm chuẩn đối chiếu giao diện.

Bộ cài Windows chưa có chữ ký Authenticode.
