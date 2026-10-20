# Prompt đăng ký Znack mặc định

Mã mẫu: `vncode-catalog-signed-reference-v4`. Đối chiếu 24 thẻ đã ký/xuất bản thật ngày 20.10.2026; dùng chung WB/Ozon. App tự điền dữ liệu đã xác định được khi đăng ký.

1. Đăng ký tự động đúng từng sản phẩm/biến thể seller đã chọn trên WB hoặc Ozon. Đọc dữ liệu hiện tại theo đúng cửa hàng/chủ tài khoản; Ozon xác định thêm product_id, offer_id và SKU. Tự điền từ dữ liệu thật của biến thể, không yêu cầu seller chọn lại các thông tin đã xác định được.

2. Dùng cấu trúc của thẻ Catalog đã ký và xuất bản làm mẫu, nhưng lấy schema bắt buộc, thuộc tính phụ thuộc, preset và attr_value_type từ API hiện hành của chính danh mục đang đăng ký. Thẻ mẫu không cung cấp GTIN, good_id, article, thương hiệu, giấy tờ, công ty, size hoặc hệ size cho sản phẩm khác.

3. Tự chọn danh mục Catalog theo loại hàng và TN VED thật khi xác định duy nhất. Giữ mã TN VED hợp lệ trên sản phẩm. Kiểm tra loại hàng, giới tính và chất liệu; không đổi mã chỉ vì thẻ mẫu dùng mã khác hoặc để ép khớp giấy tờ. Khi TN VED đổi nhóm bốn số, giữ thẻ cũ và dùng luồng thẻ/GTIN mới đã xác nhận.

4. Ánh xạ tên đầy đủ→2478, thương hiệu→2504, model/article nhà sản xuất→13914, màu đúng biến thể→36, thành phần→2483, giới tính→14013, size→35, loại hàng→12, quy chuẩn kỹ thuật→13836. Tự khớp các tên loại hàng tương đương đã xác định bằng đúng một preset của schema. Chỉ bỏ khoảng trắng ở đầu/cuối, giữ khoảng trắng bên trong và chuẩn hóa article theo quy tắc Catalog khi đối chiếu danh tính.

5. Mặc định nước sản xuất hiển thị và tự điền là Россия (Nga) theo cấu hình của chủ dự án. Thuộc tính 2630 (Страна производства) được chuyển sang mã quốc gia của danh mục hiện hành khi gửi API. Tự điền khi nguồn chưa khai; giữ nước sản xuất đã khai rõ và giá trị seller đã xác nhận. Không dùng chữ Sản xuất tại Nga làm giá trị API, không suy ra nước sản xuất từ hệ size РОССИЯ, công ty hay phương thức mua KIZ. Giữ thương hiệu và giới tính được khai rõ trên sản phẩm. Không biến dữ liệu thương hiệu trống thành Нет бренда, không biến giới tính chưa xác định thành Unisex. Với size, dùng đúng giá trị và hệ size của biến thể; РОССИЯ, РОСТ-ОГ và МЕЖДУНАРОДНЫЙ là những hệ khác nhau, không lấy hệ của thẻ mẫu làm mặc định.

6. Tra giấy tờ thật theo loại, số và ngày qua /v4/rd-info. Tự điền quy chuẩn kỹ thuật từ trường nguồn hoặc từ chính giấy tờ đang hiệu lực đã xác minh, chỉ khi khớp duy nhất một lựa chọn hiện hành của schema. Không tự mặc định 017/2011 vì các mẫu xanh đang dùng quy chuẩn đó. Giá trị đã được seller xác nhận và dữ liệu khai rõ trên sàn được giữ ưu tiên.

7. Đối chiếu trạng thái, ngày hiệu lực và phạm vi giấy tờ. Giữ quy tắc API 5.68 về các nhóm trạng thái giấy tờ được chấp nhận. Cảnh báo phạm vi TN VED phải được ghi rõ để seller xác nhận hoặc thay giấy tờ, không tự xác nhận cảnh báo và không dựng thành nguyên văn lý do từ chối của Catalog. Kết quả tra chưa rõ phải tiếp tục ở trạng thái chờ; giấy tờ vô hiệu/hết hạn không được tự coi là hợp lệ. Có thể đọc giấy tờ từ registry, không kết luận thiếu chỉ vì thẻ mẫu không có thuộc tính giấy tờ inline.

8. Hoàn tất tự động mọi giá trị xác định được rồi kiểm tra lại toàn bộ trường bắt buộc và các trường phụ thuộc. Nếu 12=НЕТ В СПРАВОЧНИКЕ thì điền cả trường mô tả loại hàng phụ thuộc 23828 khi schema yêu cầu. Chỉ đưa phần thật sự chưa xác định hoặc cần xác nhận vào bảng ghi rõ sản phẩm, size, trường và lý do; không chọn phần tử đầu tiên trong nhiều khả năng và không xin GTIN trước khi dữ liệu cần thiết hoàn tất.

9. Dùng mẫu POST tạo thẻ với GTIN của chính lượt đăng ký, TN VED, moderation=1, brand, good_name, một danh mục hàng thực và identified_by loại gtin, multiplier=1, level=trade-unit, unit=шт. Khi feed.tnved có bốn số, gửi mã đầy đủ trong 13933 cùng đầu nhóm và không gửi 3959. Khi feed.tnved có mười số, không gửi trùng 3959/13933. Không sao chép danh mục TN VED tự sinh hoặc các trường chỉ đọc từ phản hồi thẻ xanh vào POST.

10. Xác định đóng bộ/комплект theo dữ liệu hàng và quy tắc đóng bộ hiện có của VNCode, không sao chép is_kit từ một thẻ xanh bất kỳ. Giữ ngoại lệ đang có cho Костюмы спортивные TN VED 6112120000. Chỉ sửa cờ đóng bộ khi có phản hồi từ chối chắc chắn và đủ căn cứ; không thay đổi định danh hoặc tự gửi lại khi kết quả chưa rõ.

11. Lưu GTIN, payload và ranh giới gửi trước khi POST /v3/feed; lưu feed_id sau khi nhận. Không tự xin GTIN khác hoặc gửi tạo lặp khi timeout. Khi sửa cùng một thẻ, đọc/xác minh đúng GTIN và good_id, gửi good_id đó và giữ GTIN; không tiêu mã GS1 mới cho sửa cùng sản phẩm.

12. Đọc feed và chính thẻ qua feed-product để phân biệt chưa nhìn thấy thẻ, draft, moderation, notsigned, errors và published. Chỉ tăng bộ đếm có thẻ trên Catalog sau khi đọc được đúng định danh. HTTP 200, feed đã nhận hoặc thẻ xanh có sẵn không chứng minh lượt đăng ký mới thành công. Đọc đủ lỗi của đúng GTIN và feed_id; thẻ bị từ chối phải có trạng thái lỗi rõ ràng.

13. Ký khi đủ điều kiện và có consent đúng thẻ bằng CryptoPro đã cấu hình, tối đa mười thẻ trong một yêu cầu; giữ XML/hash và checkpoint riêng. Lượt ký chưa rõ kết quả chỉ được đọc lại, không ký lặp. Chỉ xác nhận xuất bản sau khi đọc đúng GTIN/good_id, trạng thái published và kết quả ký; bản nháp sửa chưa được duyệt không chứng minh lượt sửa mới thành công.

14. Sau khi thẻ đủ điều kiện, ghép đúng biến thể WB/Ozon, cập nhật GTIN qua hàng đợi và đọc lại trên sàn trước khi báo đã cập nhật. Giữ số đã xác minh, còn chờ, chưa có GTIN và lỗi; giữ dữ liệu khi mất mạng/khởi động lại, tôn trọng Retry-After và ưu tiên luồng FBS.

Mẫu payload và chứng cứ nằm trong resource `default-registration-template.json`. Prompt mô tả quy tắc; mapper, schema validator, giấy tờ và hàng đợi trong mã ứng dụng thực thi các bước này, không gọi dịch vụ AI. Chưa có mẫu published thuộc nhóm 6109 trong tài khoản được đọc.
