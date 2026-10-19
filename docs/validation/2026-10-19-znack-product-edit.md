# Znack và sửa sản phẩm — VNCode 1.5.1

## Dữ liệu thật đã đọc

Ngày 19.10.2026, dùng API Catalog và WB được cấp trong môi trường, không đưa khóa vào mã nguồn hay báo cáo.

- Catalog: đọc đủ **1.277 thẻ** qua hai trang; 1.240 thẻ published, 34 thẻ draft/errors, hai draft/draft và một draft/notsigned. Đọc chi tiết cả 34 thẻ lỗi và ba thẻ published có chữ ký.
- Cả 34 thẻ lỗi dùng một tờ khai. Tra số/ngày qua **POST `/v4/rd-info`** xác nhận tờ khai đang hiệu lực, nhưng `product_tnved` chỉ có **6101, 6201, 6202, 6211**. Các thẻ lỗi thuộc **6110 (24), 6106 (7), 6103 (3)**. Có hiệu lực không có nghĩa là bao phủ mọi sản phẩm của shop.
- Bảy thẻ loại ФУТБОЛКА ghi giới tính МУЖСКОЙ nhưng dùng 6106909000; biểu thuế đang đóng gói trong app mô tả mã này cho áo blouse nữ/bé gái. Cần sửa phân loại theo sản phẩm thật, không đổi mã chỉ để khớp tờ khai.
- Các giá trị thuộc tính của nhóm lỗi không vi phạm danh sách preset của schema danh mục 31326 đang được API trả về. Không tự kết luận danh mục này không hợp lệ chỉ vì là danh mục cha.
- WB: đọc được sản phẩm đầy đủ và danh mục thuộc tính (HTTP 200); mẫu có năm biến thể, 18 đặc tính và một giấy tờ. Đọc danh sách lỗi cập nhật (HTTP 200): có tám thông báo trực tiếp về khoảng trắng ở đầu/cuối article; các bài trong cùng nhóm còn nhận thông báo lỗi nhóm.
- Một thẻ published có cả `published` và `draft` trong `good_detailed_status`: phải giữ phân biệt bản xuất bản với bản nháp chỉnh sửa, không hạ trạng thái chỉ vì có draft.

Nguồn giao thức: [National Catalog API v5.68, ngày 28.09.2026](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/). `/v3/feed-product` xác nhận trạng thái lỗi nhưng không trả nguyên văn lý do kiểm duyệt. `/v3/feed-status?verbose=true` cần `feed_id` của chính lượt gửi; phiên này không có cơ sở dữ liệu Windows của seller để lấy các feed cũ. Vì vậy phạm vi giấy tờ và mâu thuẫn TN VED là lỗi dữ liệu đã xác minh, không được mô tả là đã đọc nguyên văn toàn bộ quyết định của kiểm duyệt viên.

## Các sửa trong app

- Mẫu mặc định chung WB/Ozon được nâng thành **`vncode-catalog-approved-v2`**. Giữ dữ liệu đúng sản phẩm, biến thể, schema; không lấy GTIN, giấy tờ, giới tính hay hệ size của thẻ mẫu để áp vào bài khác.
- Tra giấy tờ và kiểm tra hiệu lực, thời hạn, phạm vi TN VED trước cấp GTIN, khi chuẩn bị lựa chọn và trước gửi feed. Kết quả đang chờ tra cứu không bị coi là giấy tờ vô hiệu. Lưu tạm kết quả đọc tối đa hai phút; kết quả chờ được tra lại.
- Không cho gửi mâu thuẫn giới tính rõ ràng với biểu thuế đã biết. Bảng kiểm tra ban đầu đưa TN VED về luồng sửa trên sàn; không tự sửa mã để hợp thức hóa giấy tờ.
- Giấy tờ sai phạm vi được đưa về bảng cần sửa có lý do, thao tác sửa giấy tờ và nút **Áp dụng**. Xác minh lại bằng dữ liệu sàn và registry trước khi tiếp tục. Ozon chỉ cho chọn giấy tờ đã qua kiểm tra.
- Đọc đúng `error_details.items[].errors` dạng object hoặc array, `commonError`, tên/ID thuộc tính và lỗi của đúng GTIN. Nhiều lỗi thuộc tính của cùng một thẻ không bị nhầm thành lỗi định danh. Có tổng lỗi mà chưa đọc được nội dung thì không báo thành công.
- Lỗi chắc chắn từ Catalog được ghi trạng thái lỗi kể cả sau khi đã bắt đầu ký. Giữ checkpoint chữ ký, không tự ký/gửi lại khi kết quả còn chưa rõ.
- Đối chiếu thêm bộ cài chính thức **Wbcode 1.3.10**: đọc thẻ thật cả khi feed còn Received/Processing/Moderated; báo riêng nháp, kiểm duyệt, chờ ký, lỗi hoặc chờ xác nhận xuất bản. Thẻ đã ký/published không bị yêu cầu ký thêm chỉ vì feed chậm. [Bảng đối chiếu](../research/2026-10-19-wbcode-1310-registration.md).
- Báo cáo từng dòng WB/Ozon có article, GTIN, `feed_id`, trạng thái và lý do; dùng hộp chi tiết có nút sao chép. WB mở bằng ô trạng thái; Ozon có menu chuột phải. Không buộc phải mở một báo cáo đồng bộ nguồn trước.
- Sửa hàng loạt năm trường sản phẩm dùng ảnh chụp dữ liệu không đọc giá. Giá và quyền API giá không còn làm chậm việc sửa thương hiệu, giới tính, TN VED, chất liệu, giấy tờ, hoặc làm thay đổi fingerprint của một sửa không liên quan đến giá.
- Giai đoạn kiểm tra lại toàn bộ lô có tiến độ riêng trước bước gửi. Phản hồi từ chối rõ ràng (HTTP 4xx hoặc WB `error=true`) được lưu lỗi với lý do, không chuyển sang chờ đối soát vô hạn. Timeout/5xx còn chưa rõ kết quả vẫn chỉ đọc lại, không tự gửi lặp.
- Khi bài cũ còn tồn tại nhưng dữ liệu mới chưa khớp, đọc lỗi WB theo đúng article và thời điểm gửi. Giữ thời điểm qua SQLite và khôi phục từ nhật ký gửi cho tác vụ cũ. Bỏ qua lỗi lịch sử trước lượt gửi và lỗi có thời gian bất thường trong tương lai.
- Giữ nội dung/biến thể/barcode/giấy tờ chưa chọn sửa. Giữ quy tắc loại khoảng trắng đầu/cuối article và kiểm tra trùng article sau khi loại khoảng trắng. Chỉ báo đã lưu sau khi đọc lại giá trị từ sàn.
- Giữ hộp **Áp dụng / Hủy**, chạy nền, đóng hộp khi chuyển tác vụ vào nền và viên thuốc giữa phía dưới danh sách Sản phẩm.

## Kiểm thử và giới hạn

Kiểm thử dùng SQLite thật, JavaFX thật trên Xvfb/Linux và API mô phỏng. Các thử nghiệm đi qua bộ đọc dữ liệu, adapter payload, nhật ký ghi và đọc lại; đủ năm loại sửa sản phẩm. Có kiểm thử từ chối ngay, từ chối bất đồng bộ khi bài cũ còn tồn tại, bỏ qua lỗi cũ, khôi phục tác vụ bản trước, không gửi lại khi mất phản hồi, không tiêu thụ GTIN khi giấy tờ không phù hợp, báo lỗi sau khi ký và báo cáo sao chép.

**668 kiểm thử hồi quy đạt, 0 lỗi**; sau bổ sung ghép danh mục áo hoodie/sweatshirt, **5 kiểm thử danh mục đạt, 0 lỗi**. Lượt hồi quy đầu có một fixture giao diện chưa khai báo API đọc lỗi mới; đã bổ sung phản hồi API rỗng đúng giao thức và chạy lại đủ phạm vi nêu trên, giữ nguyên kiểm tra trạng thái chưa xác nhận.

Sau đối chiếu Wbcode 1.3.10: **236 kiểm thử đăng ký/đồng bộ đạt**. Kiểm tra tiếp việc thẻ đã ký nhưng feed còn chậm và giữ trạng thái chờ xuất bản: **117 kiểm thử đạt, 0 lỗi**.

Số lượng và checksum build được lưu tại [bằng chứng kiểm thử](2026-10-19-znack-product-edit-evidence.json).

**Không có thao tác ghi thật trong phiên kiểm tra này.** Chưa xác nhận tạo/ký một thẻ mới hoặc lưu sửa sản phẩm thật bằng nút trên Windows. Môi trường Linux không có CryptoPro/chứng thư Windows của seller và phiên license production để nghiệm thu đầy đủ. 34 thẻ nêu trên cần giấy tờ thực sự bao phủ sản phẩm; sửa mã không làm tờ khai sai phạm vi trở thành hợp lệ. Các kiểm thử mô phỏng không được trình bày là sàn hoặc Catalog đã chấp nhận giao dịch thật.

Các sửa được lưu cho **1.5.1 đang phát triển**; không thay tag, bộ cài hoặc Latest 1.5.0.
