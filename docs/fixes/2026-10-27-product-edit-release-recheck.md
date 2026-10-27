# Kiểm tra lại trước phát hành 1.5.18 — 27.10.2026

Chủ dự án yêu cầu kiểm tra lại sửa đổi ngày 26.10, xác minh ghi thật qua API rồi đóng gói/phát hành. Nguồn ứng dụng được chốt tại `02559cdcba3962510f22a3d2deadf79770cd8775`, kế thừa toàn bộ `fa3aa3a`, `3fd8618`, `b20c8d2`; chỉ tăng phiên bản từ 1.5.17 lên 1.5.18. Hash cây nguồn/test/POM: `07f15623fc5f9dc266497b7e1bbcdfa5206d812f77c020820dcd5e5764865d31`.

## Ghi và đọc lại dữ liệu thật

| Trường | WB | Ozon |
| --- | --- | --- |
| Thương hiệu | 1 lệnh ghi, RECONCILED | 1 lệnh ghi, RECONCILED |
| Chất liệu | 1 lệnh ghi, RECONCILED | 1 lệnh ghi, RECONCILED |
| Giới tính | 1 lệnh ghi, RECONCILED | 1 lệnh ghi, RECONCILED |
| TN VED | 1 lệnh ghi, RECONCILED | 1 lệnh ghi, RECONCILED |
| Giấy tờ có sẵn | 1 lệnh ghi, RECONCILED | 1 lệnh bind, RECONCILED |

Tổng **10 lệnh ghi HTTPS thật**, trên đúng hai bài đã được chọn ở lượt trước; gửi lại giá trị hiện có. Lần đọc mới Ozon đã có thương hiệu nên không còn thiếu dữ liệu để thử trường này. Không tự chọn thương hiệu mới, mã TN VED khác hoặc thông tin kinh doanh mới.

Dùng `ProductBulkEditService.preview/apply/reconcile`, reader, adapter và `ListingHttpClient` thật, không thay phản hồi mạng bằng fixture. Các handler JavaFX được kiểm tra trong hồi quy; lần nghiệm thu nút Apply ghi thật ngày 26.10 vẫn được giữ riêng. Đây không phải thao tác điều khiển cửa sổ Windows của seller.

Mỗi lệnh được lưu receipt và đọc lại; kết quả chỉ ghi nhận khi RECONCILED. Nút sửa giấy tờ dùng lại giấy tờ đã có, không thử tải giấy tờ mới. Giữ mã bài, SKU, size, barcode, tên/mô tả, ảnh, kích thước và tập giấy tờ/số/ngày hiện có. Đọc HTTPS độc lập sau đó trả 200 cho WB, Ozon info và Ozon attributes, đối chiếu lại các trường được bảo toàn.

Bộ thử ban đầu giới hạn 9 lệnh vì dự kiến Ozon còn trống thương hiệu. Khi thương hiệu đã có, giới hạn cục bộ chặn bind giấy tờ cuối trước HTTP; lần đó chỉ đọc lại giấy tờ đang có, không được tính là ghi. Đã tiếp tục riêng đúng một bind giấy tờ, tổng cuối 10 lệnh; không gửi lại các trường khác hoặc yêu cầu chưa rõ kết quả.

App-data riêng và giấy phép kiểm thử ký riêng theo quyền kiểm thử chủ dự án đã cấp; không đổi license production, không đưa fixture vào bộ cài. Không ghi Catalog, tạo sản phẩm, mua/tiêu KIZ hoặc sửa đơn hàng. Payload, task và DB có kết nối thật giữ riêng trong `/tmp/vncode-1518-live-recheck`, không đưa vào Git hoặc artifact. Không chạy lại các marker one-shot; đọc receipt trước khi tiếp tục.

[Biên nhận đã loại dữ liệu seller](../validation/2026-10-27-product-edit-live-recheck.json). Kết quả build/full-suite/native Windows và phát hành được ghi riêng trong bộ biên nhận 1.5.18; không suy ra chúng từ lượt API này.
