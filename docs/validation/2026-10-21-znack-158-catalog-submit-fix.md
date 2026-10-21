# Sửa luồng gửi Catalog trên nền VNCode 1.5.8

Nền: tag `v1.5.8`, commit `06408f3a35fddd2952b64374f75e5761d0b43502`.
Nhánh: `fix/znack-catalog-submit-158-20261021`. Chưa tạo bộ cài hoặc phát hành Latest.

## Lỗi tái hiện được

API `/v3/attributes?cat_id=30696&attr_type=m` trả thuộc tính kích thước
`35`, với loại giá trị cho phép gồm `РОССИЯ`, `МЕЖДУНАРОДНЫЙ`, `---`.
Bộ chuẩn bị có thể chọn `---` cho size số chưa xác định hệ kích thước. Nhưng
`fillTemplate` đổi loại này thành chuỗi rỗng. Bộ kiểm tra trước gửi của 1.5.8
sau đó từ chối chính bản nháp vừa chuẩn bị, báo
`ZNACK_INVALID_ATTRIBUTE_VALUE: Размер одежды / изделия [35]`, trước POST.

Đã giữ nguyên giá trị loại theo schema và vẫn kiểm tra schema hiện hành trước
cấp GTIN/gửi thẻ. Không suy đoán size số là hệ Nga. Test mới thất bại trên mã
cũ rồi đạt sau sửa. Đây là một nguyên nhân đã chứng minh; ảnh báo cáo tổng hợp
1 thẻ lỗi của seller chưa đủ xác định trường lỗi của chính thẻ đó.

## Giấy tờ ở nút Cấu hình giấy tờ

Theo yêu cầu mới, giấy tờ có số/ngày được lưu trong cấu hình cửa hàng được ưu
tiên cho đăng ký WB và Ozon. Khi chưa cấu hình, vẫn có thể lấy giấy tờ sản phẩm.
Loại mặc định `CONFORMITY_DECLARATION` đơn lẻ không được coi là đã cấu hình.
Giấy tờ cấu hình sai/thiếu/hết hạn không được âm thầm thay bằng giấy tờ khác.
Giữ nguyên kiểm tra registry và cảnh báo phạm vi TN VED; không đổi TN VED của
sản phẩm để ép khớp giấy tờ. Không ghi cứng giấy tờ một seller cho seller khác.
Không đổi giấy tờ trên bài đăng chỉ vì lưu cấu hình đăng ký Catalog.

API registry đọc thật xác nhận giấy tờ seller từng cung cấp,
`ЕАЭС N RU Д-RU.РА04.В.55922/26`, ngày `2026-05-28`, trạng thái
`Действует`, `status_group=1`; phạm vi trả về:
`6103, 6104, 6105, 6106, 6110, 6112`.
Đây là kết quả tra cứu giấy tờ, không phải bằng chứng tạo thẻ.

## Báo cáo

- Giữ mã lỗi allowlist trước dấu `:` khi lỗi kèm tên trường; không đổi thành
  `SYNC_INTERNAL`, không lưu phần chi tiết vào cột mã lỗi.
- Đưa cột lý do lên sau Article; không phải cuộn ngang qua toàn bộ cột mới thấy.
- Hiện tối đa ba lý do cụ thể kèm article/size ngay phần tổng hợp, kể cả lỗi nằm
  ngoài trang/bộ lọc hiện tại. Toàn bộ các dòng vẫn có trong bảng và CSV.
- Giữ kiểm tra tài khoản và che dữ liệu nhạy cảm của báo cáo.

## Bằng chứng và giới hạn

Đã đọc API production bằng Secret của môi trường, không in giá trị Secret.
Catalog trả HTTP 200 cho danh sách thẻ, từ điển nước, schema thuộc tính,
GTIN nháp (`exist=1`, không cấp mã) và tra cứu giấy tờ (`rd-info`, chỉ đọc).
Danh sách hiện có 1.277 thẻ; truy vấn cập nhật từ 20/10 không có dòng.
Không gửi POST tạo thẻ thật, cấp GTIN mới, ký thẻ hay ghi WB/Ozon trong lượt này.
Không có CryptoPro/chứng thư Windows của seller để nghiệm thu ký production.

Tài liệu chính thức: https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/

Bằng chứng riêng của lượt làm việc nằm ngoài Git tại
`/workspace/reports/znack-158-catalog-fix-20261021/`:
`evidence.json`, `red-size-type.log`, `red-configured-document.log`,
`focused-green.log`, `registration-regression.log`,
`registration-regression-final.log`. Không đưa phản hồi có dữ liệu doanh nghiệp
hoặc thông tin xác thực vào repository.

## Kết quả kiểm thử cuối

- Bộ hồi quy đăng ký WB/Ozon, V3, báo cáo và giao diện: **742 ca, 0 lỗi,
  0 thất bại, 0 bỏ qua**, 1 phút 52 giây, `BUILD SUCCESS`.
- Sau khi thêm ca nghiệm thu lý do lỗi hiển thị ngay trong phần tổng hợp:
  **29 ca, 0 lỗi, 0 thất bại, 0 bỏ qua**, `BUILD SUCCESS`. Không cộng hai lượt
  thành số ca độc lập vì có ca được chạy lại.
- Có kiểm thử HTTP qua máy chủ cục bộ: gửi từng thẻ, lưu feed ID, chỉ đếm sau
  khi đọc được đúng thẻ; HTTP 200 không có feed ID, thẻ chưa xuất hiện, rate
  limit và khởi động lại không làm POST lặp.
- Có kiểm thử báo cáo trên 10.001 dòng, nguyên nhân ngoài trang/bộ lọc vẫn
  hiện, CSV không làm lộ thông tin nhạy cảm; kiểm thử giao diện chạy JavaFX/Xvfb.
- `git diff --check`: đạt. Không chạy lại toàn bộ chức năng ngoài phạm vi đăng ký.

Lượt hồi quy đầu phát hiện việc loại giấy tờ mặc định nhưng số/ngày trống bị coi
là seller đã cấu hình. Đã sửa điều kiện phát hiện cấu hình, thêm ca hồi quy và chạy
lại đủ bộ đăng ký nêu trên. Giữ log lượt lỗi để đối chiếu, không bỏ kiểm thử lỗi.
