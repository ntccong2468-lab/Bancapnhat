# VNCode 1.4.14

Bản nâng cấp từ 1.4.13, tập trung sửa đồng bộ Znack, thao tác sửa sản phẩm và các màn hình đã đối chiếu với WBCode 1.3.9 cùng ảnh seller cung cấp.

- Sửa lỗi SQLite `SYNC_STORAGE` / `database is locked` khi nhiều tác vụ cùng ghi dữ liệu. Giữ nguyên nguyên nhân lỗi đồng bộ để báo cáo; các giao dịch chỉ ghi dữ liệu cục bộ được chờ và thử lại có giới hạn.
- Sửa lỗi WB trả thuộc tính theo thứ tự khác nhau khiến VNCode tưởng sản phẩm đã đổi và chặn lưu. Vẫn kiểm tra các thay đổi thực sự của sản phẩm và thứ tự ảnh.
- Màn Sản phẩm WB hiển thị một dòng cho mỗi bài đăng, giữ đầy đủ các size khi chọn sửa/sao chép. Bổ sung bộ lọc lỗi có số lượng và thanh sửa hàng loạt; bố cục rộng tách giấy tờ/chất liệu khỏi giới tính/TN VED.
- Thêm gợi ý thương hiệu, giới tính và TN VED từ sàn; Ozon nhận diện đúng trường `ТН ВЭД коды ЕАЭС`, tìm giá trị đã chọn trong các từ điển lớn.
- Sửa sản phẩm có xem trước giá trị cũ/mới, xác nhận và nút **Lưu thay đổi lên sàn**. Sau gửi tự đọc lại có giới hạn; kết quả chưa xác nhận hiển thị đang chờ, có Đối soát. Thay đổi giá trị khi đang đọc bản xem trước sẽ yêu cầu xem trước lại.
- Sửa danh sách WB còn hiện cache cũ sau khi đã lưu và đọc lại thành công; mở sửa giấy tờ mới xóa hết thông tin của lần nhập trước.
- Thêm tra cứu 526 mã TN VED thời trang theo danh mục, giới tính, chất liệu và kiểu dệt. Có chọn mã chung hoặc chọn riêng từng sản phẩm trước khi xem trước; gợi ý chưa đủ dữ liệu/không duy nhất được ghi rõ để seller kiểm tra. Bộ tra cứu tham khảo không thay thế danh mục có phiên bản do seller nhập.
- Báo cáo tác vụ giữ dòng đang chọn khi tự làm mới, phân trang 300 dòng, hiển thị chi tiết SKU/biến thể/GTIN và mã lỗi. Trạng thái chờ seller xác nhận được phân biệt với chờ sàn xử lý; trạng thái đơn WB hiển thị riêng với KIZ cục bộ.

API thật đã đọc sản phẩm, thuộc tính và gợi ý của WB/Ozon. Phép thử gửi lại giá trị hiện có trên một sản phẩm WB đã được đọc lại khớp; Ozon đã nhận yêu cầu thử nhưng chưa xác nhận hoàn tất trong cửa sổ kiểm tra. Đây không phải nghiệm thu tạo GTIN mới, ký CryptoPro hoặc mua/gán KIZ thật. Việc kiểm tra Catalog mới cần Secret Catalog trong môi trường; các ảnh GS1/Znack được dùng làm chuẩn đối chiếu giao diện.

Bộ cài Windows chưa có chữ ký Authenticode.
