# Nghiệm thu nút sửa sản phẩm qua API thật — 26.10.2026

Người dùng yêu cầu kiểm tra 5 nút có thực sự sửa được và trả lời «ok» cho hai bài thử đã trình bày. Vì chưa đưa giá trị kinh doanh mới, đã thông báo trước rằng lượt thử gửi lại đúng thông tin hiện có. Chỉ dùng một sản phẩm WB và một sản phẩm Ozon đã chọn.

## Kết quả thực

| Thao tác | WB | Ozon |
| --- | --- | --- |
| Thương hiệu | Gửi và đọc lại xác nhận | Chờ tên thương hiệu: bài đang trống thương hiệu |
| Chất liệu | Gửi và đọc lại xác nhận | Yêu cầu cũ được đối soát xác nhận, không gửi lại |
| Giới tính | Gửi và đọc lại xác nhận | Gửi và đọc lại xác nhận |
| TN VED | Gửi và đọc lại xác nhận | Gửi và đọc lại xác nhận đúng mã và dictionary ID |
| Giấy tờ | Gửi lại metadata giấy tờ hiện có và xác nhận | Gắn giấy tờ đã có theo SKU và đọc lại đúng certificate_id |

**9 lệnh ghi API thật**; WB 5/5, Ozon 4/5. Dùng nút Apply của `ProductBulkEditPane`, service, reader, adapter và `ListingHttpClient` thật qua HTTPS. TN VED được điền mã hiện có tương đương đầu ra của picker; không dùng bot bấm Windows của seller. Cửa sổ JavaFX và dữ liệu app được tách riêng, dùng giấy phép kiểm thử ký riêng theo quyền thử không license chủ dự án đã cấp trước đó. Không thay license production, không đóng gói test authority.

Thử gửi lại dữ liệu đang có không chứng minh mọi trường đã đổi sang một giá trị kinh doanh khác. Thành phần vật liệu được chuẩn hóa dấu phân cách/mảng; mã TN VED và ID từ điển giữ nguyên. Brand, giới tính, số/ID giấy tờ hiện có được giữ. Kiểm tra HTTPS độc lập sau lượt thử xác nhận mã bài, SKU, barcode, size, tiêu đề, mô tả, kích thước, KIZ marking và tập giấy tờ cần giữ. Không tạo sản phẩm, upload giấy tờ mới, ký Catalog hoặc mua KIZ.

## Lỗi thực phát hiện và sửa

1. WB tiến `updatedAt` sau lượt ghi trước, dù nội dung thẻ không đổi; kiểm tra revision chặn lần sửa tiếp theo. Chỉ loại mốc này khỏi hash nội dung, giữ raw payload, thời điểm đọc, toàn bộ trường kinh doanh và guard chủ sở hữu. Thay đổi thực/nội dung ảnh vẫn đổi revision.
2. Ozon đọc thuộc tính văn bản trả `dictionary_value_id=0`; phép so nguyên JSON làm chất liệu đã imported vẫn chờ mãi và khóa các thao tác sau. Chuẩn hóa ID 0 thành văn bản khi đối soát, tiếp tục kiểm tra giá trị thật; với ID từ điển dương, so đúng ID trong cùng thuộc tính. Không đổi payload import để làm phép so dễ hơn.
3. Ozon TN VED trả nhãn `mã - mô tả`; tra giá trị trước đó đòi chuỗi khớp hoàn toàn với mã. Giờ so đúng mã 10 chữ số, lấy ID đã được endpoint tìm kiếm xác nhận, chặn ánh xạ mơ hồ. Đọc lại dùng đúng ID, không bị mô tả/localization làm sai.

Chất liệu Ozon của lượt đầu có task đã imported; dùng đúng receipt cũ đối soát và chuyển RECONCILED, **0 lệnh gửi lại**. Sau đó mới thử tiếp giới tính, TN VED và giấy tờ. Trước đó một lỗi serializer của bộ ghi biên nhận xảy ra trước mọi job/mutation; đã sửa và xác nhận 0 job trước khi chạy lại.

## Kiểm chứng và bàn giao

- Mã ứng dụng `fa3aa3a22c78f8780605fa1d3fb48787a465449e`, hash cây `5275e80f1ff74d0c5ee02fcb9a22989ea379b0229de6e7d5c28b3cb4b1823a17`, nhánh `fix/product-card-bulk-edit-identity`. Kế thừa toàn bộ sửa giao diện và card identity trước đó.
- 4 ca tái hiện đúng lỗi hợp đồng trước sửa; 82 ca mục tiêu đạt. Hồi quy cuối **389/389 đạt**, 48 lớp, không lỗi/thất bại/bỏ qua, 2 phút 21 giây. Các lượt 82/239 trước là tập con/nguồn trước, không cộng lặp.
- [Biên nhận công khai đã bỏ dữ liệu seller](../validation/2026-10-26-product-edit-live-api-verify.json). Payload, mã thật, task, dữ liệu kết nối và DB ở riêng `/tmp/vncode-product-editor-live-check-20261026`; không commit chúng. Ba thao tác WB đầu gửi/xác nhận trên nguồn 3fd8618; các thao tác sửa lại và đối soát bổ sung dùng nguồn mới, biên nhận ghi nguồn theo từng dòng.
- Thương hiệu Ozon chưa được thử ghi vì không có giá trị hiện có hoặc giá trị mới được chỉ định. Câu hỏi tên thương hiệu còn chờ; khi có câu trả lời phải dùng đúng sản phẩm/cửa hàng và kiểm tra từ điển rồi mới gửi.
- Chưa full suite/native Windows hoặc tạo bộ cài/thay Latest cho các sửa này. Latest/POM vẫn 1.5.17; lần phát hành sau tăng version từ nguồn mới, giữ tag/binary cũ bất biến.
