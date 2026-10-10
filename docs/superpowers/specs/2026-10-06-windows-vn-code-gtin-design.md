# Thiết kế VN code Windows và đồng bộ GTIN

## 1. Mục tiêu đã được người dùng duyệt

Ứng dụng desktop Windows x64 giữ toàn bộ chức năng hiện có của ứng dụng tham chiếu,
cải thiện giao diện và cấu trúc mã, bổ sung thêm GTIN thiếu và sửa/thay GTIN
gán sai trên Wildberries và Ozon khi API của sàn cho phép. Người dùng đã
chọn JavaFX + SQLite và duyệt sơ đồ nghiệp vụ. Đây là bản đặc tả để duyệt
trước kế hoạch triển khai; chưa phải báo cáo chức năng đã được xây dựng.

## 2. Phạm vi bảo toàn chức năng

| Nhóm | Chức năng phải giữ |
| --- | --- |
| Shop | Nhiều shop, phân biệt WB/Ozon, kiểm tra kết nối, cấu hình theo shop |
| WB | Catalog, đồng bộ đơn/supply, chi tiết đơn, đóng gói, giao supply, sticker, FBO/FBW đang có |
| Ozon | Catalog, FBS Standard, posting nhiều item, exemplar/KIZ, ship, nhãn, chức năng FBO hiện có |
| Tài chính | Dashboard, lịch đồng bộ, chỉ số, quảng cáo và báo cáo hiện có |
| Znack | GTIN, mapping, kho KIZ, mua/tải mã, pipeline tiếp tục sau khởi động, đưa vào lưu thông |
| Đăng ký thẻ | Luồng Национальный каталог và ánh xạ thuộc tính WB hiện có |
| Chữ ký | CryptoPro, chọn chứng thư, kiểm tra chữ ký và phiên ký |
| In/xuất | Thiết kế template, barcode/DataMatrix, PDF, Excel, nhãn chính thức, lịch sử |
| Hệ thống | Cấp phép, cập nhật, ngôn ngữ RU/EN/VI/ZH, theme, phục hồi dữ liệu |

Giữ phạm vi được hỗ trợ trong mã hiện tại; không coi yêu cầu bảo toàn là
cam kết thêm rFBS, multibox hoặc loại nghiệp vụ chưa có. Kiểm kê màn hình,
command và kiểm thử trước khi thay thế từng phần để tránh bỏ sót chức năng.
Không bỏ hoặc vượt qua kiểm tra cấp phép hiện có.

## 3. Kiến trúc và giao diện

Giữ adapter WB, Ozon, Znack và National Catalog riêng biệt. UI gọi service
nghiệp vụ, service dùng repository và adapter; UI không trực tiếp gửi API.
Tách điều hướng, trạng thái shop, điều phối tác vụ, cập nhật và cấp phép
khỏi HomeController theo từng phần có kiểm thử hồi quy.

Thanh điều hướng chứa Dashboard, Sản phẩm, Đơn/Đóng gói, FBO, GTIN,
Đồng bộ GTIN lên sàn, KIZ/Znack, Tài chính, Mẫu in, Lịch sử và Cài đặt.
Chọn shop luôn hiển thị tên và sàn. Giữ các màn hình nghiệp vụ hiện có
trong khi thay dần khung giao diện; không dựng màn hình giả thay chức năng.
Tác vụ chạy nền có tiến độ, lỗi có thể xử lý và kết quả theo từng mục.

## 4. Module GTIN mới

### Nguồn và danh tính

Đọc GTIN đăng ký từ National Catalog hoặc bộ dữ liệu đã đồng bộ có nguồn,
trạng thái và thời điểm rõ ràng. Phân biệt GTIN của đơn vị hàng với KIZ và
mã bao bì. Giữ GTIN dạng chuỗi, bảo toàn số 0 đầu, kiểm tra độ dài và check
digit theo chuẩn được hỗ trợ. Chỉ đề xuất tự động từ thẻ được phép sử dụng.

Mỗi ánh xạ thuộc một shop và marketplace. WB dùng nmID/chrtID; Ozon dùng
product_id/offer_id và định danh biến thể có trong API. Không dùng tên sản
phẩm hoặc SKU giữa hai sàn như định danh tương đương.

### Đối chiếu và thao tác

Đối chiếu article, màu, size và cấp đơn vị hàng. Thiếu thuộc tính, nhiều
ứng viên hoặc GTIN xuất hiện ở sản phẩm khác phải được báo rõ. Không suy
diễn đổi size hoặc màu nếu chưa có quy tắc người dùng xác nhận.

Hỗ trợ ba thao tác riêng:

1. Sửa ánh xạ trong app: chỉ đổi lựa chọn GTIN nội bộ, không gửi lên sàn.
2. Thêm GTIN thiếu: bổ sung GTIN đúng, giữ barcode và thuộc tính hiện có.
3. Thay GTIN sai: người dùng chọn chính xác mã cũ và mã mới; chỉ gửi khi
   API cho phép xóa/thay mã trong trường hợp cụ thể.

Không tự kết luận mọi barcode khác GTIN mục tiêu đều là mã sai. Có thể
có barcode hợp lệ khác. Mã cũ phải được người dùng chọn và xác nhận.

### Khả năng API và giới hạn

Adapter cung cấp khả năng thêm/thay theo sàn và trường hợp sản phẩm,
kèm lý do khi không hỗ trợ. Trước triển khai mutation, đối chiếu tài liệu
API chính thức hiện hành: endpoint, schema, quyền token, giới hạn,
cơ chế bất đồng bộ và quy tắc barcode. Ghi lại nguồn và ngày xác minh.

Mã đã xem chưa chứng minh khả năng thay/xóa barcode trên WB hoặc Ozon.
Nếu không xác minh được, chức năng thay trên sàn đó bị chặn với hướng
dẫn xử lý thủ công; sửa ánh xạ nội bộ vẫn hoạt động. Không giả lập thành
công, không dùng endpoint chưa xác minh. Thêm mã đúng mà mã sai còn tồn
tại không được báo là hoàn tất thao tác thay.

## 5. Xem trước, hàng đợi và xác minh

Bảng hiển thị shop, sàn, định danh sản phẩm/size, article, màu, size,
barcode hiện tại, GTIN đề xuất, nguồn, thao tác và trạng thái.
Xem trước hiển thị đầy đủ mã cũ → mã mới và thuộc tính được giữ.
Cho chọn hàng loạt các dòng đủ điều kiện trong bộ lọc hiện tại; các dòng
bị chặn không được đưa vào hàng đợi. Chỉ xác nhận xem trước mới tạo tác vụ.

Lưu snapshot trước cập nhật và dấu nhận dạng dữ liệu liên quan. Đọc lại
ngay trước khi gửi; nếu định danh hoặc barcode thay đổi thì yêu cầu xem
trước lại. Không ghi đè bằng snapshot đã cũ hoặc payload thiếu thuộc tính.

Trạng thái tác vụ: QUEUED, VALIDATING, SENDING, AWAITING_VERIFICATION,
SUCCEEDED, FAILED, RECONCILE_REQUIRED, CANCELLED. Lưu SENDING trước khi
gửi. Sau khởi động, tác vụ đang SENDING chuyển sang đối soát, không gửi
lại tự động. Một tác vụ có khóa chống lặp theo shop/sản phẩm/thao tác/
mã cũ/mã mới; chỉ một mutation trên cùng sản phẩm được chạy tại một lúc.

HTTP thành công chỉ xác nhận nhận yêu cầu. Dùng trạng thái xử lý và đọc
lại để xác nhận GTIN ở đúng sản phẩm. Khi thay, phải xác nhận mã mới có
và mã cũ được bỏ theo mục tiêu đã duyệt. Giới hạn thời gian poll dẫn tới
chờ đối soát, không kết luận thất bại chắc chắn rồi tự gửi lại.
Tạm dừng chỉ dừng gửi tác vụ mới; hủy không đảo ngược thao tác đã gửi.

## 6. Dữ liệu và bảo mật

Migration additive tăng schema theo cơ chế hiện có; tạo và verify snapshot
trước migration. Không đổi số phiên bản schema ở nhiều nơi không thống
nhất. Kiểm tra integrity_check và foreign_key_check. Binary cũ từ chối
schema mới; app giữ khóa thư mục dữ liệu suốt vòng đời.

Bổ sung bảng nguồn GTIN, ánh xạ biến thể, job/item cập nhật và audit,
có shop_id/marketplace, định danh nguồn, timestamps và trạng thái.
Snapshot barcode không chứa token, raw KIZ hoặc thông tin cá nhân.
Lịch sử phân biệt sửa nội bộ và sửa trên sàn, cho xuất CSV/Excel đã lọc.

Giữ cơ chế lưu credential hiện có, không xuất token trong UI/log/history.
Mọi repository/API operation kiểm tra ownership và marketplace. Không
tự động gửi live mutation trong quá trình phát triển hoặc kiểm thử.

## 7. Đóng gói Windows

Tạo EXE/MSI và portable với runtime đi kèm; giữ dữ liệu ngoài thư mục
binary theo AppPaths hiện có. Giữ kiểm tra cập nhật có chữ ký và xác thực
gói cài. Bộ cài Windows phải được build và smoke-test trên Windows.
Không tuyên bố chữ ký Authenticode hoạt động khi chưa có cấu hình tương ứng.
CryptoPro và chứng thư của seller là phụ thuộc riêng cho nghiệp vụ ký.

## 8. Kiểm thử và tiêu chí hoàn thành

- Chạy baseline Node contract tests và Maven clean verify trước thay đổi;
  phân biệt lỗi nền có sẵn với lỗi phát sinh.
- Kiểm thử GTIN số 0 đầu, checksum, đơn vị hàng, matching mơ hồ, nhiều
  barcode hợp lệ, GTIN ở sản phẩm khác và isolation giữa shop/sàn.
- Mock API kiểm tra payload bảo toàn, thao tác không hỗ trợ, dữ liệu cũ,
  quyền truy cập, rate limit, async errors, timeout và đối soát.
- Kiểm thử restart trong mỗi trạng thái có thể đã gửi; không gửi lặp.
- Migration từ database phiên bản nền bằng dữ liệu tạm, snapshot và
  kiểm tra tính toàn vẹn; không chạy test trên database thật của seller.
- UI đổi phải qua FxmlSmokeTest; bản dịch mới đồng bộ bốn ngôn ngữ.
- Chạy toàn bộ Maven verify và Node tests sau tích hợp, giữ các module
  hiện có và kiểm tra installer/portable trên Windows.
- Kiểm thử live chỉ sau khi người dùng duyệt đúng tài khoản, sản phẩm
  và thay đổi cụ thể; kết quả mock không được coi là xác nhận live.

Hoàn thành khi các chức năng nền vẫn truy cập và hoạt động, module GTIN
được kiểm thử, các thao tác sàn không hỗ trợ bị báo đúng, và có artifact
Windows đã kiểm tra. Báo riêng các bước chưa chạy do thiếu Windows,
credential seller, CryptoPro hoặc khả năng API chưa xác minh.

## 9. Ranh giới triển khai

Triển khai tăng dần: baseline và kiểm kê → nền GTIN/migration → nguồn
và matching → adapter đã xác minh → hàng đợi/đối soát → UI/bản dịch →
đóng gói và hồi quy. Đây là thứ tự thiết kế, chưa phải kế hoạch công việc
chi tiết. Giữ nguyên các nghiệp vụ chưa được thay thế trong mỗi giai đoạn.

Không đưa yêu cầu chưa xác minh của API thành cam kết khả thi. Khả năng
thay GTIN được cung cấp có điều kiện như người dùng đã duyệt trong sơ đồ.
