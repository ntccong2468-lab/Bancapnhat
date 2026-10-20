# VNCode 1.4.14

Bản nâng cấp từ 1.4.13, bổ sung giao diện Liquid Glass, ghép SKU Ozon với thẻ Znack và hoàn thiện đồng bộ, kho KIZ cùng thao tác sửa sản phẩm.

- Giao diện Liquid Glass theo mẫu đã duyệt: nền kính xanh nhạt, chuyển sắc xanh mềm, viền sáng mảnh, panel bo góc và nút dạng viên thuốc. Áp dụng toàn app, giữ nội dung/chức năng VNCode. Bản nâng cấp mở với giao diện sáng theo mẫu; sau đó vẫn giữ lựa chọn sáng/tối của seller.
- **Cài đặt → Liquid Glass** có thanh độ trong suốt 0–100, xem trước tức thì, Lưu/Hủy; giữ cài đặt khi mở lại. Chỉ độ trong của nền thay đổi, chữ và nút giữ độ hiển thị đầy đủ.
- Tự bỏ khoảng trắng Unicode đầu/cuối article khi gửi WB và tạo article sao chép WB/Ozon; giữ khoảng trắng giữa các từ và dấu phân cách mã. Kiểm tra trùng article trước sửa thẻ WB.
- Ozon ghép thêm qua SKU seller/offer_id và hậu tố size: kiểm tra model, màu, size nhà sản xuất/size Nga thực sự có từ API. Hỗ trợ ví dụ `152DASH-52` với article `152 nau dashoa` khi màu nâu và size 52 khớp; giữ các nhánh mẫu hàng khác nhau. Nhiều thẻ phù hợp sẽ báo mơ hồ.
- Quy tắc ghép được dùng trong đồng bộ và màn đăng ký Znack; lưu bằng chứng và kiểm tra dữ liệu mới đọc trước xác nhận/chạy tác vụ.
- Kho GTIN trong shipment FBS cập nhật theo khóa để giữ vị trí cuộn khi tự làm mới; dòng tự co theo chiều rộng thực của khung. Trang chào mừng chỉ đổi bố cục khi qua ngưỡng kích thước, tránh dựng lại cây giao diện ở mỗi thay đổi chiều rộng.

- Sửa lỗi SQLite `SYNC_STORAGE` / `database is locked` khi nhiều tác vụ cùng ghi dữ liệu. Giữ nguyên nguyên nhân lỗi đồng bộ để báo cáo; các giao dịch chỉ ghi dữ liệu cục bộ được chờ và thử lại có giới hạn.
- Ưu tiên đồng bộ FBS liên tục: giữ nhóm xử lý riêng và một lượt kết nối sàn dành cho đơn hàng khi tác vụ nền đang đọc dữ liệu. Shipment đang mở được làm mới ưu tiên trong lúc quét các shipment khác; giữ đúng vị trí quét nền.
- Đồng bộ sản phẩm WB/Ozon kiểm tra đủ các trang, detail và thuộc tính, chặn cursor lặp; không báo hoàn tất hoặc thay sản phẩm bằng dòng rỗng khi API trả thiếu. Ozon kiểm tra đúng credentials của lượt đọc trước khi lưu.
- Sửa đường Làm mới thủ công của WB: phản hồi thiếu trạng thái, danh sách đơn hoặc thành phần shipment không được xóa liên kết / ghi số lượng về 0. Lịch sử chỉ ghi mốc hoàn tất sau trang cuối hợp lệ.
- Ozon kiểm tra đúng posting và quantity trước khi cập nhật đơn, gán mã hoặc tiếp tục đóng đơn. Phản hồi thuộc đơn khác bị chặn trước thao tác ghi.
- FBO tải tiếp sau 1.000 dòng WB, tiếp tục lịch sử Ozon còn dở qua checkpoint; bundle thiếu dữ liệu hoặc cursor lặp giữ nguyên dữ liệu đã có. Thời gian chờ API dài không giữ worker nền.
- GS1 và báo cáo Znack giữ đúng lỗi nguồn khi việc ghi nhật ký cũng gặp lỗi; lượt đọc dùng chung được kết thúc rõ ràng. Tác vụ GTIN đã xác nhận tiếp tục được xử lý khi có báo cáo tạm dừng cũ.
- Trạng thái đơn WB được lưu nguyên trang trong một giao dịch; giấy tờ và nhãn Catalog lưu theo nhóm có giới hạn. Lỗi giữa nhóm giữ nguyên dữ liệu cũ của cả nhóm, giảm số lần commit và tranh chấp SQLite khi FBS chạy cùng Znack.
- Sửa lỗi WB trả thuộc tính theo thứ tự khác nhau khiến VNCode tưởng sản phẩm đã đổi và chặn lưu. Vẫn kiểm tra các thay đổi thực sự của sản phẩm và thứ tự ảnh.
- Sửa cả dấu vết bản xem trước GTIN WB khi API đảo thứ tự thuộc tính. Article được tự bỏ khoảng trắng đầu/cuối và kiểm tra trùng trước khi gửi; lỗi thẻ mới trả sau ACK được ghi nhận dứt điểm theo đúng sản phẩm, không chờ mãi hoặc gửi lặp.
- Màn Sản phẩm WB hiển thị một dòng cho mỗi bài đăng, giữ đầy đủ các size khi chọn sửa/sao chép. Bổ sung bộ lọc lỗi có số lượng và thanh sửa hàng loạt; bố cục rộng tách giấy tờ/chất liệu khỏi giới tính/TN VED.
- Thêm gợi ý thương hiệu, giới tính và TN VED từ sàn; Ozon nhận diện đúng trường `ТН ВЭД коды ЕАЭС`, tìm giá trị đã chọn trong các từ điển lớn.
- Ozon nhận mã TN VED từ nhãn từ điển có kèm mô tả; giữ riêng size Nga và size nhà sản xuất. Đối chiếu GTIN dùng đúng hệ size của thẻ Catalog và giá trị sàn thực sự trả về, không đoán quy đổi size. Cache cũ được đọc lại để bổ sung trường mới, giữ nguyên sản phẩm và barcode.
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

Lượt API thật mới đọc đủ 388 bài/1.982 biến thể WB, 175 sản phẩm Ozon và 1.276 thẻ Catalog đang được API liệt kê. Quy tắc SKU tìm được 10 cặp Ozon duy nhất; đã gắn một GTIN và đọc lại thành công trên `152NAU-2XL`, màu nâu, size Nga 52, giữ barcode cũ. Đã xác nhận sửa khoảng trắng cuối article trên một bài WB, giữ các trường được phép ghi và barcode của 5 size. Ghi GTIN WB mới chưa nghiệm thu: mẫu cũ bị Catalog trả 403, mẫu bộ mới có khác TN VED cần xử lý trong bản xem trước đồng bộ. Lượt kiểm tra trước đã tạo được một thẻ Znack nháp, chưa ký/xuất bản. Xem [báo cáo bổ sung](../validation/2026-10-17-liquid-glass-ozon-sku.md) và [báo cáo trước](../validation/2026-10-17-vncode-1414-live-acceptance.md) để phân biệt các lượt kiểm tra.

Bộ cài Windows chưa có chữ ký Authenticode.

Lượt rà soát đồng bộ đọc đủ 244 đơn FBS Ozon qua 3 trang và kiểm tra bằng parser của VNCode. API đơn/shipment/FBO WB của token môi trường trả 401, đang chờ token có quyền phù hợp; chưa xem đây là nghiệm thu đơn WB thật. Không mua mã, gán KIZ hay đóng đơn thật trong lượt rà soát. Xem [báo cáo đồng bộ](../validation/2026-10-17-sync-release-audit.md) để biết kết quả kiểm thử và phần còn cần kiểm tra.
