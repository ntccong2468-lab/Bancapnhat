# Znack: sửa số giấy tờ lẫn Latin/Cyrillic và lỗi preflight

Nguồn: **`0a00fbd837d5e92eeb0018e480fb1891436107e9`**, nền `d66d2ca`, nhánh `fix/kiz-znack-wb-gtin-audit-20261108`. Giữ toàn bộ sửa KIZ/Znack/GTIN và giao diện đã có trên nhánh. [Biên nhận, lệnh và danh sách 28 lớp](registration-permit-script-20261108/verification.json). **Chưa phát hành.**

## Chẩn đoán có bằng chứng

Log production 1.6.6 cho thấy `POST /v4/rd-info` trả HTTP 400, `result.errors[].code=14`. Số giấy tờ có Latin `P` (U+0050) ở vị trí 16 và Latin `B` (U+0042) ở vị trí 21, trong các phần phải dùng Cyrillic `Р`/`В`. Hai bảng chữ có hình dạng gần giống. Đây là bước tra giấy tờ trước cấp GTIN/gửi feed; không phải phản hồi từ thao tác tạo thẻ.

Đọc API thật bằng khóa môi trường, không thay dữ liệu seller:

| Trường hợp | Kết quả |
| --- | --- |
| Số gốc | HTTP 400, code 14, không có giấy tờ |
| Chỉ sửa hai ký tự trong phần registry | HTTP 200, một tuyên bố, `status_group=1`, không lỗi nghiệp vụ |
| Đúng JAR cuối → Settings → chọn giấy tờ → request → ZnackApiClient thật | Nhận đúng giấy tờ còn hiệu lực, số/ngày/loại khớp, không lỗi chặn |

Tổng ba yêu cầu đều là tra cứu registry. Raw có thông tin doanh nghiệp nằm riêng ngoài Git, quyền thư mục 0700/tệp 0600; receipt chỉ lưu aggregate/hash. Không ghi số giấy tờ thật, khóa hoặc hồ sơ seller vào fixture/bộ nhớ. Tra giấy tờ đạt không chứng minh một thẻ sản phẩm đã được gửi hoặc công bố.

## Phần đã sửa

- `PermitDocumentNumber`: chuẩn hóa hữu hạn ký tự trông giống nhau trong hai phần chữ của số EAEU/RU có cấu trúc đầy đủ. Giữ `N`, mã quốc gia, số thứ tự và năm; dạng không nhận diện không được suy đoán. Ngày cấp không thuộc phép đổi ký tự này.
- `ZnackModels.Settings` và `ZnackRegistrationDocuments`: cấu hình mới/cũ được đọc nhất quán; giấy tờ lấy từ sản phẩm được chuẩn hóa khi chuẩn bị đăng ký. Đổi cách gõ cùng số giấy tờ không làm mất ngày hết hạn. Không sửa snapshot nguồn hoặc byte của payload/journal đã duyệt.
- `RegistrationPermitValidator`/`ZnackNationalCatalogService`: chỉ chuyển HTTP 400 code 14 có số/ngày và, nếu hiện diện, loại giấy tờ khớp thành lỗi riêng của giấy tờ. HTTP 200 chứa cùng lỗi cũng không được chấp nhận. Không cache từ chối thành proof; lần sau được đọc lại. HTTP 401/403/429/5xx, JSON lỗi hoặc lỗi không xác định vẫn giữ ngoại lệ gốc.
- `RegistrationDiagnostics` và bốn bản dịch: hiển thị rõ số giấy tờ không được registry xác nhận, giữ chi tiết đã lọc từ Catalog; không gộp thành lỗi đọc schema chung. Các thẻ dùng giấy tờ khác vẫn được kiểm tra trong cùng lượt preflight.
- Tương thích sửa giấy tờ: so sánh hữu hạn cùng số registry khi tìm reference Ozon và merge giấy tờ WB. Giữ kiểm tra cửa hàng, loại, ngày, nguồn và ID. Ozon trả lại đúng document thô đã sở hữu để adapter đối chiếu API; WB giữ ID/metadata của attachment khi sửa, tránh tạo bản trùng do bảng chữ.

Review độc lập tìm và đã sửa hai hồi quy: nội dung lỗi server chứa token cảnh báo TN VED không được làm code 14 thành advisory; chuẩn hóa Settings không được làm mất reference Ozon/ID giấy tờ WB. Không còn finding vật chất trong diff đã review; đây không phải chứng nhận toàn ứng dụng.

## Kiểm chứng

- Tái hiện trước sửa trên baseline: 27 ca, 4 failure/1 error đúng các hành vi chưa xử lý; đã sửa fixture Gson trước lượt này.
- Tái hiện finding advisory: 4 ca, 1 failure trước guard; thêm kiểm tra bước `validateDocuments` cuối vẫn từ chối.
- **360/360 ca, 28 lớp, 0 failure/error/skip; Maven package PASS**, Linux/Xvfb, JDK 25.0.4.1. Lệnh đầy đủ và selector trong receipt; gồm sửa sản phẩm WB/Ozon, lô 1.000 sản phẩm mô phỏng, giấy tờ/mua KIZ, preflight, payload, readback và dialog JavaFX. Các ca HTTP/SQLite dùng fixture không thay cho ba yêu cầu thật ở trên.
- JAR cuối **`47928b63f780a53e67b7e61624d2e1e8d55c304917b22580420805c74bc3bb90`**; 1.978 class khớp byte output. Manifest 19 tệp nguồn/test không đổi giữa lúc build và chốt commit. JAR vẫn dùng version candidate 1.6.6; thay target candidate cũ theo đường dẫn, không thay binary Latest.
- Probe JAR ban đầu dùng nhầm ngày ISO ở biên cấu hình UI; đổi harness về ngày `dd.MM.yyyy` đúng ảnh người dùng rồi tra cứu thật đạt, không sửa source sau build. Không dùng lần probe bị chặn cục bộ làm bằng chứng API.

Chưa kiểm Windows/bộ cài, chưa cấp GTIN/gửi feed/ký/ghi sàn hoặc kích hoạt license trong lượt này. Không đổi schema/dependency/POM, không tag/publisher/asset hoặc cập nhật Latest. Khi tiếp tục phát hành, giữ các sửa chưa phát hành từ các bàn giao trước và dùng phiên bản mới theo quy trình phát hành; không ghi đè release immutable cùng version.

Kinh nghiệm: kiểm tra code point khi giấy tờ trông đúng bằng mắt; chỉ đổi phần registry có cấu trúc xác định. Lỗi HTTP của tra registry có thể là dữ liệu nghiệp vụ của một giấy tờ, nhưng không được hạ lỗi transport/auth thành giấy tờ hợp lệ. Chuẩn hóa dữ liệu dùng chung phải kiểm cả reference và merge trên từng sàn.
