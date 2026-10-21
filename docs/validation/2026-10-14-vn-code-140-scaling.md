# VNCode 1.4.0: Znack, Ozon/KIZ và nhiều máy khách

Nhánh `update/vn-code-1.4.0`. Đây là kiểm thử cục bộ trên cloud Linux, không có
thao tác ghi seller, gửi email hay chạy máy in thật.

## Thay đổi đã viết

- Cấu hình Znack có trang thông tin chủ tài khoản/INN/chứng thư/số dư theo nhóm
  và hợp đồng; refresh trong worker, không tự yêu cầu PIN khi mở màn hình.
- Đăng ký WB có phân trang/chọn mọi kết quả lọc, ký thẻ đã duyệt, sync Catalog,
  giấy tờ theo sản phẩm và tạo thẻ mới giữ GTIN cũ. Ozon có repository/workflow/UI
  riêng với review thuộc tính bắt buộc, loại giá trị, giấy tờ và TN VED.
- GTIN ADD WB/Ozon dùng hợp đồng API chính thức, đọc mới đúng biến thể, giữ
  barcode cũ, chốt scope/chứng cứ trước gửi và đọc lại sau gửi. Không tự replay;
  không cho nguồn sandbox cấp quyền ghi sàn production.
- Hàng đợi WB luân phiên 10 cửa hàng; bulk inventory, checkpoint bền vững,
  hủy/đối soát và kiểm tra license/credential sau các lần chờ.
- Ozon bulk-read/reserve, đọc trước hai đơn, in 1.000 đơn theo bộ lọc, ghép PDF
  tuần tự, progress có giới hạn và callback đúng shop. Chặn các job cùng dùng
  một tệp đầu ra; mở PDF và đọc lịch sử in ngoài FX.
- Nhiều người dùng chạy máy riêng, chung Portal 0.3.1; client giãn renew,
  backend cache khóa ký/checkpoint thu hồi/index/cleanup có giới hạn.

## Số đo cục bộ

Cùng fixture: 10 cửa hàng Ozon, 10.000 sản phẩm và 1.000 posting mỗi cửa hàng;
heap 768 MiB. Bài đo kiểm tra readiness khi thiếu mapping, kiểm tra đủ mọi
posting nhưng không reserve KIZ, không HTTP và không in.

| Chỉ tiêu | Trước | Sau, lượt đầu | Sau, build cuối |
|---|---:|---:|---:|
| 1.000 posting/cửa hàng, khoảng đo | 12,534–16,437 giây | 0,051–0,246 giây | 0,030–0,078 giây |
| Trung vị 10 cửa hàng | 14,555 giây | 0,091 giây | 0,049 giây |
| Tổng thời gian kiểm tra 10.000 posting | 147,532 giây | 1,113 giây | 0,491 giây |

File gốc: [baseline](evidence/2026-10-14-ozon-readiness-baseline.json),
[bulk-read lượt đầu](evidence/2026-10-14-ozon-readiness-upgraded.json),
[build cuối](evidence/2026-10-14-ozon-readiness-final.json).
Số này không phải tốc độ gán KIZ qua mạng hoặc tổng thời gian in.

## Kiểm thử

Các bộ targeted đã đạt, gồm 10 cửa hàng × 1.000 đơn, 10.000 reservation đúng
shop/mỗi mã một đơn, 40 SELECT cho đọc 10.000 posting thay cho N+1, rollback khi
thiếu kho hoặc sai ownership, 1.000 đơn/2.000 trang PDF, fresh requirements,
giữ mapping cũ/candidate mới, hết quyền giữa lúc chờ, timeout/readback và FX
heartbeat khi worker bị chặn. Maven targeted chạy với `-DargLine=-Xmx768m`.

`node --test tools/*.test.mjs`: 42/42 đạt.
Website `npm run check`, `npm test`: 28/28 đạt; có 100/1.000 khách độc lập,
rotation khóa và thu hồi trong lúc ký. D1 adapter/SQLite RAM là fixture.

`clean verify`: **1.175 kiểm thử, 1.174 đạt, 0 failure, 0 error, 1 bỏ qua**;
203 report XML. Kiểm thử bỏ qua là
`WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows`.
Cả 52 kiểm thử FXML và 3 kiểm thử Ozon chọn/hủy toàn bộ lô đều đạt trong lượt full.

JAR `target/VNcode-1.4.0.jar` khởi động trên Java 25/Xorg với dữ liệu tạm riêng,
cửa sổ `VN code v1.4.0` hiển thị; database `integrity_check=ok`, không lỗi khóa
ngoại. Đã dừng process smoke sau kiểm tra. Trusted keys vẫn `{}`, JAR không chứa
fixture cấp quyền. Đây là khởi động Linux, chưa phải bản cài Windows đã nghiệm thu.
SHA-256 JAR:
`075823c392c2c5485c7bc163730b80124b2988d448e6388ac6bf9d16afa21e31`.
[Bằng chứng tổng hợp](evidence/2026-10-14-vncode-140-verification.json),
[smoke runtime](evidence/2026-10-14-vncode-140-runtime.json).

Hai vấn đề tích hợp đã sửa trước lượt full thành công: thiếu kho hiển thị lại
GTIN/số cần/số có; bộ kiểm thử hủy Ozon chỉ nhận modal thuộc cửa sổ của nó và
truyền lỗi callback về test thread. Kiểm thử tin tức chờ acknowledgement nền và
kiểm tra dữ liệu đã lưu, không giả định ghi đĩa đã xong ngay trên FX.

## Phần cần nghiệm thu thật

Windows/CryptoPro/DPAPI, email, máy in, tài khoản seller và website production
chưa được nghiệm thu. Hạn mức API vẫn áp dụng: thêm barcode Ozon 20 request/phút,
WB content update 10 request/phút. Công việc lớn có thể phải chờ mạng nhưng FX
không xử lý SQL/network của lô đơn.

Không tự suy quy tắc pháp lý TN VED hoặc loại size giày; các giá trị chưa rõ cần
người vận hành kiểm tra. Chưa công bố nguồn tất cả nhóm Catalog bên ngoài các
thẻ VNCode biết, sửa gender/TN VED pháp lý hàng loạt hoặc thay/xóa barcode sàn.
