# Mục 1 — kết quả kiểm tra API thật ngày 16/10/2026

## Kết luận

Đã tái hiện và sửa lỗi nguồn National Catalog trả danh sách trống vì thiếu
`from_date` và `to_date`. Cùng API key, yêu cầu cũ trả tổng 0; yêu cầu bổ sung
khoảng thời gian trả đủ **1.961 thẻ qua 2 trang**. Sau sửa, lớp
`ZnackApiClient` thật của VNCode đọc lại đúng số lượng này từ dịch vụ thật.

Mã sửa: `370142d4cf419c8f2f3c53b20f2bef704b538882`, nhánh
`update/vncode-approved-upgrades`. Chưa phát hành bộ cài hoặc thay Latest.

| Bước kiểm tra | Bằng chứng thực tế | Trạng thái |
| --- | --- | --- |
| Kết nối WB/Ozon | API của cả hai sàn trả HTTP 200 bằng thông tin đã cấp vào môi trường | Qua |
| Đọc danh mục Catalog | 1.961 thẻ: trạng thái danh sách gồm 1.873 published, 88 draft; không trùng GTIN và mọi GTIN trong danh sách qua checksum | Qua sau sửa |
| Đọc chi tiết thẻ | Truy vấn trực tiếp 20 GTIN của mẫu WB: 20 thẻ published, signed và trade-unit; parser thật của VNCode đọc được article, màu, size và thông tin còn lại | Qua cho 20 thẻ đã chọn |
| Đối chiếu WB | Đọc lại đúng 20 biến thể bộ quần áo, qua `WbGtinProductReader.fromCard` và `GtinCatalogIndex`: 20/20 có GTIN trùng thẻ Catalog đã xuất bản | Đã xác minh GTIN hiện có |
| Đối chiếu Ozon | Đọc lại 20 sản phẩm bộ bằng `OzonApiClient` và `OzonGtinProductReader`, giữ native product ID/offer ID/SKU: 0/20 có GTIN Catalog trong barcode, hiện chỉ có barcode dạng OZN | Chưa đồng bộ GTIN trong mẫu này |
| Dữ liệu trước đăng ký | Đọc nhóm TN VED 6103 được 33 danh mục; đọc được 12 GTIN nháp đã có | Các API chỉ đọc hoạt động |
| Cấp GTIN/tạo thẻ mới | Không gọi endpoint cấp số mới hoặc gửi hồ sơ | Chưa kiểm thử thật |
| Ký CryptoPro | Không có CryptoPro/chứng thư thật của seller trong môi trường Linux này | Chưa kiểm thử thật |
| Gửi GTIN mới và đọc lại | Chưa có tác vụ ghi được xác nhận cho một sản phẩm và GTIN cụ thể | Chưa kiểm thử thật |

Việc WB đã có GTIN trước lượt kiểm tra không chứng minh bản mã mới đã gửi
GTIN thành công. Kết quả Ozon chỉ áp dụng cho 20 sản phẩm mẫu trong nhóm bộ,
không kết luận tất cả sản phẩm của tài khoản đều chưa có GTIN. Đã đọc toàn
bộ danh sách Catalog, nhưng chưa đọc chi tiết toàn bộ 1.961 thẻ.

## Thay đổi và kiểm thử

- Dùng khoảng từ `2000-01-01 00:00:00` đến thời điểm bắt đầu đọc theo giờ
  Moskva. Cố định mốc cuối trong một client để phân trang không đổi phạm vi
  giữa chừng. Cả kiểm tra kết nối và đọc sản phẩm dùng cùng hàm tạo URL.
- Thêm kiểm thử mô phỏng đúng hành vi dịch vụ: bỏ khoảng thời gian vẫn nhận
  HTTP 200 và tổng 0; có khoảng thời gian mới nhận danh sách. Kiểm tra mốc
  thời gian giữ nguyên qua connection check và hai trang.
- Hồi quy toàn bộ trên source `370142d…`: **1.426 bài, 1.424 qua,
  0 failure/error, 2 skip**, `BUILD SUCCESS`, hoàn tất
  `2026-10-16T12:48:10Z`. Hai skip thuộc `Gs1PrefixTemplateStoreTest` và
  `WindowsSecretProtectorTest` theo điều kiện nền tảng. Đã khôi phục Xvfb
  sau khi Publish môi trường để chạy cả kiểm tra JavaFX.
- 77 kiểm thử tập trung đã qua, 0 failure/error/skip. Công cụ API Node qua
  **7/7 bài**.
- Node trong cloud cần `--use-env-proxy`; lượt gọi đầu không dùng proxy bị
  lỗi kết nối. Gọi qua proxy chuẩn, giữ xác minh TLS, WB/Ozon đều trả 200.
  Đây là cấu hình chạy công cụ cloud, không phải bằng chứng app Windows lỗi.

Lệnh công cụ chỉ đọc trong môi trường cloud này (credential lấy từ Secrets):

```bash
node --use-env-proxy tools/check-marketplace-gtin-readonly.mjs \
  --category sets --output /tmp/vncode-live-sets.json
```

Lượt Java trực tiếp dùng `ZnackApiClient`, `CatalogProductDataParser`,
`GtinCatalogIndex`, `WbGtinProductReader` và `OzonGtinProductReader` thật để
đọc/đối chiếu; không chạy qua phần xác thực license, chứng thư và giao diện
Windows. Lượt này không thay thế kiểm tra từ ứng dụng đã cài trên máy seller.

## Giới hạn và bước còn lại

Để nghiệm thu đầu cuối, cần chọn một cặp sản phẩm/biến thể và GTIN đã xuất
bản, xem trước và xác nhận gửi rồi đọc lại kết quả. Ozon phải đối chiếu thêm
SKU/tên/thuộc tính để có đúng thẻ; không lấy một GTIN bất kỳ để thử ghi.
Tạo thẻ mới và ký cần thực hiện trên máy Windows có CryptoPro/chứng thư của
seller. Chưa nghiệm thu trên máy seller thứ hai hoặc xác nhận production license.

Không mua/gán KIZ, không phát sinh GTIN mới, không gửi/ký hồ sơ, không ghi
barcode trên sàn và không phát hành Release trong lượt này. API key không
được lưu vào mã nguồn hoặc báo cáo. Dữ liệu phản hồi chi tiết giữ ở file
riêng trong `/tmp`, không đưa lên GitHub; báo cáo công khai chỉ chứa tổng hợp.

[Bằng chứng tổng hợp JSON](2026-10-16-item-1-live-api-checks.json).
