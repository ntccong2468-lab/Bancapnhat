# VNCode 1.4.14 — mã hoàn tất và kiểm tra API thật

Mã ứng dụng: `ece7aa48a98caad78cc58da14923bd19c8b75c93`, nhánh
`update/vncode-approved-upgrades`. Bản này nâng cấp từ 1.4.13. Chưa thay Latest.

## Kết quả theo yêu cầu kiểm tra

| Hạng mục | Kết quả thực tế | Đánh giá |
| --- | --- | --- |
| Toàn bộ danh mục WB | Đọc 4 trang: 388 bài đăng, 1.982 biến thể; không trùng khóa sản phẩm/biến thể | Qua phần đọc API |
| Toàn bộ danh mục Ozon | Đọc 2 trang: 175 sản phẩm; lấy đủ 175 bản thông tin và 175 bộ thuộc tính, cùng định nghĩa của 5 loại hàng | Qua phần đọc API |
| Toàn bộ Znack trước thử đăng ký | 1.961 thẻ qua 2 trang danh sách và 79 lô chi tiết; mọi thẻ đều đọc được, không thiếu/trùng GTIN | Qua |
| Tạo một thẻ Znack thật | Cấp đúng 1 GTIN mới, gửi đúng 1 feed bằng dữ liệu của một biến thể bộ quần áo WB; đọc lại được đúng thẻ nháp, đang moderation, chưa ký | Đã tạo nháp; chưa nghiệm thu ký/xuất bản |
| Đồng bộ Znack sau đăng ký | Đọc lại đủ **1.962/1.962** thẻ và toàn bộ chi tiết: 1.873 published, 89 draft; gồm thẻ vừa tạo | Qua phần đọc toàn bộ |
| Lưu/phân trang dữ liệu thật | Repository thật lưu 388 bài/1.982 biến thể WB, 175 sản phẩm Ozon và 1.962 thẻ Catalog cho từng shop trong DB cô lập; đọc lại đủ số lượng, giữ cả hai hệ size | Qua hợp đồng lưu/đọc; không phải phiên app trên máy seller |
| Ghi một GTIN còn thiếu lên WB | Đã đối chiếu article, màu, hệ size, TN VED, thương hiệu và thẻ signed/published; gửi một yêu cầu, nhận ACK. Đọc lại chưa có GTIN. API lỗi WB chỉ rõ article có khoảng trắng cuối | **Không qua**; đang chờ duyệt bỏ khoảng trắng cuối trên đúng một mẫu để thử lại |
| Ghi GTIN lên Ozon | 175 sản phẩm không có GTIN Catalog trong barcode và chưa có cặp article/màu/size khớp duy nhất theo quy tắc hiện tại | **Chưa thử ghi**; cần xác nhận liên kết sản phẩm cụ thể, không gán GTIN tùy ý |
| CryptoPro, license production, máy seller thứ hai | Môi trường cloud không có chứng thư/khóa riêng trên Windows của seller | Chưa nghiệm thu đầu cuối |

Đọc hết Catalog không có nghĩa mọi sản phẩm sàn đã được gán GTIN. Trong dữ liệu
WB có 1.757 biến thể đang chứa barcode trùng một GTIN đã xuất bản; đó là dữ liệu
có sẵn trước thử nghiệm, không phải 1.757 lượt ghi mới thành công. Sau sửa hệ
size, chỉ mục tìm được 25 biến thể WB còn thiếu barcode có một ứng viên published,
gồm 20 biến thể bộ thuộc 4 bài có khoảng trắng cuối article. Các cặp mơ hồ và các
khác biệt màu/size thực sự vẫn cần xử lý dữ liệu.

Lượt ghi WB thử trên `bo polo den `, biến thể 2XL/size Nga 52. WB trả lỗi:
«В Артикуле продавца … содержится пробел. Пожалуйста, удалите пробел.»
Article chưa được tự đổi. Không gửi lại yêu cầu sau ACK. Cần duyệt thay đổi
`bo polo den ` → `bo polo den` để thử lại đúng mẫu đó. Bộ ghi của WBCode 1.3.9
cũng sao chép nguyên `vendorCode`; đối chiếu bản đó không tự giải quyết dữ liệu
article không hợp lệ này.

Thẻ Znack mới dùng article `bo polo be nike chu`, size 3XL, dữ liệu lấy từ
biến thể WB được chọn. Native `ZnackWbAttributeMapper` và
`ZnackNationalCatalogService.buildPayload` của VNCode tạo hồ sơ; đủ 11 thuộc tính
bắt buộc theo schema thật. Chỉ có một yêu cầu cấp số và một POST tạo feed.
Giấy tờ lấy từ thuộc tính của đúng sản phẩm WB, không thay bằng giấy của sản phẩm
khác; phép thử này chưa xác nhận độc lập tính hợp lệ pháp lý của giấy tờ.

## Lỗi phát hiện bằng dữ liệu thật và mã đã sửa

- Ozon trả TN VED dạng `6103420001 - …`; bước chuẩn bị trước đây chỉ nhận chuỗi
  toàn số nên bỏ sót. Nay tách mã từ đúng dạng nhãn từ điển, không nối mọi chữ số
  trong mô tả, và hiển thị/đếm lỗi TN VED nhất quán.
- Cả 175 sản phẩm Ozon có size Nga khác size nhà sản xuất. Nay lưu riêng hai
  trường từ API, đọc lại đúng hệ size; nâng cấp DB giữ nguyên sản phẩm/barcode
  và buộc đọc đầy đủ để điền hai trường mới, không đoán hệ size của cache cũ.
- Catalog lưu size có `attr_value_type`. Nay giữ thông tin này, dùng `wbSize`
  hoặc size Nga Ozon khi thẻ ghi `РОССИЯ`; không tự quy đổi 3XL thành 54. Thẻ
  quốc tế vẫn đối chiếu size nhà sản xuất.
- Chỉ mục đồng bộ nền nay dùng cùng quy tắc chuẩn hóa article với bước đối chiếu
  và màn đăng ký; giữ dấu phân cách mã, không chọn tùy ý khi có nhiều thẻ phù hợp.
- Đọc mới thẻ WB nay giữ màu của biến thể để bước kiểm tra GTIN không so với màu
  trống. Bộ ghi GTIN sắp thuộc tính theo ID trước khi tính dấu vết; ba lần đọc API
  trước sửa cho ba dấu vết, sau sửa cho một dấu vết ổn định. Thứ tự giá trị và ảnh
  vẫn được giữ.
- Article WB có khoảng trắng đầu/cuối được báo trước khi gửi GTIN, có hướng dẫn
  xử lý. Lỗi thẻ WB mới trả sau ACK được đối chiếu đúng article/thời điểm, lưu
  thành lỗi dứt điểm thay vì chờ mãi; lỗi cũ hoặc của article khác không được dùng
  để kết luận yêu cầu hiện tại thất bại. Không tự sửa article và không gửi lặp.

## Những phần công việc trước đã hoàn tất trong bản mã này

| Phần | Mã và kiểm tra đã có |
| --- | --- |
| SQLite/Znack | Giao dịch ghi cục bộ có giới hạn chờ/thử lại, lấy quyền ghi trước khi đọc trong giao dịch; lỗi nguồn được giữ, lỗi báo cáo chỉ là suppressed |
| Sản phẩm và TN VED | Gợi ý từ API, chọn/sửa hàng loạt, xem trước cũ/mới, xác nhận và Lưu lên sàn, đọc lại; sửa JSON khóa theo sản phẩm, một dòng/bài WB giữ đủ size; tra cứu 526 mã |
| Kho KIZ | Sửa nhập lại file đã có trong journal nhưng mã chưa được phân danh mục; chỉ chuyển mã còn trống khi có lựa chọn riêng; không đụng mã giữ/đã dùng/khác chủ |
| File KIZ seller cung cấp | File mẫu có 600 CIS ngắn hợp lệ, không trùng/lỗi dòng; hồi quy riêng tái hiện 200 mã chưa phân loại và đưa đúng 200 mã vào tồn danh mục, không tạo bản sao |
| Đăng ký Znack | Đổi nhóm TN VED giữ thẻ cũ và cấp mã mới; tiếp tục feed đang chờ/thẻ bị gián đoạn; lý do từ chối, đồng ý tự ký theo từng thẻ và khôi phục sau mở lại; không ký/gửi lặp khi chưa rõ kết quả |
| Danh sách/giao diện | Znack tải thêm khi cuộn; thương hiệu shop hiện trước và danh sách WB tải dần; nút gợi ý TN VED cạnh Áp dụng; hộp Ozon và báo cáo tác vụ co theo cửa sổ |
| GS1 | Kiểm thử lại điền Word 01.04.01, nét ký, tạo PDF, JPG/PNG và hướng EXIF. Mẫu Word phải được nhập một lần; chưa gửi thư hoặc ký đơn thật trong lần kiểm tra API này |

Đối chiếu dựa trên WCode công khai 1.0.27, hợp đồng/FXML và quan sát bộ cài
WBCode công khai 1.3.9 cùng ảnh người dùng. Không có toàn bộ mã nguồn nội bộ
WBCode mới nhất; không tuyên bố đã sao chép nguồn đó hoặc đã xem mọi màn hình
WBCode sau đăng nhập có license.

## Kiểm thử và phát hành

- Hồi quy toàn bộ source `ece7aa4`: **1,607 bài, 0 failure/error, 2 skip**
  theo nền tảng, `BUILD SUCCESS`; hoàn tất `2026-10-16T23:38:54Z`.
- 70 kiểm thử tập trung FXML/GTIN sau sửa fixture qua; 60/60 kiểm tra công cụ
  phát hành qua.
- Kiểm tra Windows source `ece7aa4`:
  [run 38982243667](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38982243667).
  Không dùng bộ cài của source cũ làm bằng chứng cho source này. Lượt Windows
  trước đã bị kiểm tra cùng tồn tại chặn vì thư mục dữ liệu mặc định bị tạo
  trong CI. Đã cô lập thư mục mặc định của từng bước Maven và probe GS1;
  đóng fixture giao diện trước khi đợi tác vụ nền dừng; giữ nguyên quy tắc
  từ chối đụng dữ liệu đã tồn tại. Cần lượt Windows mới
  xác nhận thay đổi này.

API thật dùng tài khoản đã được cho phép. Thử giao thức đăng ký/ghi không chạy
qua phiên Windows đã cài và không chứng minh bước xác thực chứng thư/license
production. DB kiểm tra riêng không giả lập chứng thư để cấp quyền: đã xác nhận
nguồn chưa gắn chứng thư bị chặn khi yêu cầu quyền ghi. Khóa API, phản hồi riêng,
GTIN/feed ID của thử nghiệm và nhật ký ngăn gửi lặp nằm trong tệp riêng; báo cáo
công khai chỉ giữ tổng hợp. Chưa mua/gán KIZ thật, chưa ký CryptoPro, chưa phát
hành Latest 1.4.14.

[Bằng chứng tổng hợp JSON](2026-10-17-vncode-1414-live-acceptance.json).
