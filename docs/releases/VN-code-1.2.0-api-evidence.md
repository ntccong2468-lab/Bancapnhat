# Bằng chứng API cho bản cập nhật VN code 1.2.0

Ngày đối chiếu: 2026-10-08 UTC. Đây là tài liệu triển khai bản cập nhật cùng ứng dụng VN code, không phải một ứng dụng mới. Không thay đổi repository nguồn Vncode, UpgradeCode hoặc thư mục dữ liệu.

## Nguồn và mức xác minh

| Phần | Nguồn | Kết quả |
| --- | --- | --- |
| Thay đổi WCode | [Release 1.2.0](https://github.com/rupphi/relatest-wcode/releases/tag/v1.2.0) | Mô tả công khai; không cung cấp mã nguồn hoặc hợp đồng API |
| WB FBS | [OpenAPI tiếng Nga chính thức](https://dev.wildberries.ru/api/swagger/yaml/ru/03-orders-fbs.yaml?region=ru), [tiếng Anh](https://dev.wildberries.ru/api/swagger/yaml/en/03-orders-fbs.yaml?region=ru) | HTTP 200; lấy trực tiếp từ dev.wildberries.ru |
| Ozon | [URL Swagger gốc](https://docs.ozon.ru/api/seller/swagger.json), [bản sao](https://github.com/dev-ik/seller-sdk/blob/main/docs/ozon/swagger.json), [metadata](https://github.com/dev-ik/seller-sdk/blob/main/docs/ozon/swagger.meta.json) | URL gốc lặp chuyển hướng trong môi trường này. Bản sao ghi ngày 2026-08-14; SHA-256 khớp metadata. Không coi bản sao là tài liệu trực tiếp mới nhất |
| Ozon, đối chiếu | [SDK rFBS](https://github.com/easycb/easycb-go/blob/master/ozon/rfbs_posting.go), [tài liệu giao rFBS](https://github.com/DragonSigh/ozon-seller-api-docs/blob/master/rfbs-delivery.md) | Đối chiếu phạm vi API; không phải nguồn chính thức hiện hành |
| Честный ЗНАК | [True API chính thức](https://docs.crpt.ru/gismt/True_API/) mục 10.7 | HTTP 200; đọc trực tiếp mô tả `/nk/categories` |

SHA-256 của bản đã đọc:

- WB OpenAPI RU: `dc3a4b37c62038b3eedd1ff423700b935fd2bd6651ec00a8de69dca6161812e0`.
- Ozon Swagger bản sao: `56e0ff0c64710918107ab83b574d209762fc559dbc9c80198ae31708b3881830`.

## WB: đã xác minh hợp đồng giao hàng mới

1. Đọc chi tiết supply bằng `GET /api/v3/supplies/{supplyId}`. Có `cargoType`, `shippingDt`, `shippingPointId`, `shippingType`.
2. Lấy điểm nhận bằng `GET /api/marketplace/v3/fbs/shipping-points?city=...&cargoType=...`. `city` phải dùng Cyrillic; `cargoType` là 1, 2 hoặc 3. Chỉ áp dụng cho người bán ở Nga.
3. Lưu thông số bằng `PATCH /api/marketplace/v3/fbs/supplies/shipping-method`.
4. Chỉ khi từng supply được xác nhận thành công mới gọi `PATCH /api/v3/supplies/{supplyId}/deliver`.

```json
{"data":[{"supplyId":"WB-GI-100","shippingType":"selfShipping","shippingDt":"2026-10-09","shippingPointId":100}]}
```

`shippingType` chỉ có `selfShipping` hoặc `transportCompany`; ngày dùng `YYYY-MM-DD`. Mỗi request hỗ trợ tối đa 100 supply; VN code nên gửi một supply cho thao tác trên màn hình chi tiết.

HTTP 200 chưa bảo đảm thành công. Phải tìm đúng supply trong `results`, yêu cầu `success: true` và không có `error`. Sau khi supply hoặc hộp đã được quét, thao tác sửa có thể trả 409. Không tự động lặp PATCH khi timeout; đọc lại chi tiết để đối soát trước khi cho người dùng thử lại.

Endpoint điểm nhận không công bố cursor hoặc limit. Vì vậy tìm kiếm không phân biệt hoa/thường và ё/е, cùng phân trang 20 dòng của dropdown, phải thực hiện trên danh sách trả về ở máy người dùng. Không thêm tham số phân trang giả vào API.

`crossBorderType: 0` nghĩa là nội địa, không chứng minh shop thuộc Nga. Không dùng trường này một mình để tự suy ra quốc gia.

Hợp đồng đã có lớp kiểm tra `WbShippingContract` và ba kiểm thử. Đây là nền tảng cho biểu mẫu giao hàng; chưa có kết nối biểu mẫu và lưu lựa chọn theo shop, nên không đánh dấu chức năng hoàn tất.

## Ozon: cần giữ đúng phạm vi vận chuyển

- `POST /v4/posting/fbs/ship`: đóng gói, chuyển sang `awaiting_deliver`. HTTP 200 cần được đối soát bằng `/v3/posting/fbs/get`; `ship_failed` không phải thành công.
- `POST /v2/posting/fbs/awaiting-delivery`: chuyển **đơn đang tranh chấp** sang chờ giao. Không phải API giao tất cả đơn đóng gói.
- `POST /v2/fbs/posting/delivering`: đúng đường dẫn tìm được, thuộc nhóm **DeliveryrFBS**, dành cho dịch vụ vận chuyển bên thứ ba. Request là `posting_number` dạng mảng. Kết quả từng đơn có `result` và `error`; đổi trạng thái bất đồng bộ, phải đọc trạng thái trước và đối soát sau.
- Chưa có bằng chứng rằng `/v2/fbs/posting/delivering` được phép dùng cho mọi đơn FBS do Ozon vận chuyển. Không gọi endpoint này cho FBS thông thường chỉ để làm giao diện giống mô tả WCode.

VN code hiện chặn yêu cầu `non_standard_fbs` ở quy trình đóng gói. Nếu hỗ trợ rFBS cần mở rộng mô hình điều kiện cho phép, nhật ký đối soát và kiểm thử; không bỏ chặn chung. Chức năng chọn nhiều đơn/nhắc in nhãn sẽ chỉ bật thao tác ghi khi phạm vi được xác minh.

## TN VED cấp 1: đã phân biệt hai khái niệm, chưa xác minh quy tắc WCode

True API chính thức mô tả:

- `tnved`: mã **cấp cuối**, 4–10 chữ số.
- `cat_level`: cấp trong **cây danh mục**, đi kèm `cat_id`, `cat_parent_id`.

Đây là hai trường khác nhau. Không có bằng chứng trong mô tả release để đồng nhất “TN VED cấp 1” với `cat_level == 1`, hai chữ số đầu hoặc bốn chữ số đầu. Quy tắc tự cấp GTIN mới phải chờ định nghĩa được xác minh; cấp nhầm có thể tiêu hao hạn mức GS1 và làm mất liên kết nhận diện sản phẩm.

Quy trình VN code hiện có cấp lại GTIN sau xác nhận, lưu checkpoint và khóa theo SKU. Bản cập nhật cần giữ lịch sử GTIN cũ, trạng thái ký/feed, quyền sở hữu shop và đối soát thao tác cấp mã; không tự đổi GTIN chỉ dựa trên giả định về tiền tố.

## Trạng thái phát hành

Tài liệu API WB đã được tìm thấy; lỗi truy cập trang HTML WB trước đó không còn là trở ngại cho việc đọc hợp đồng. Phần Ozon và quy tắc TN VED vẫn cần xác minh phạm vi/ý nghĩa. `VN-code-1.2.0-status.json` tiếp tục chặn phát hành đầy đủ khi còn chức năng chưa triển khai. Không có thao tác ghi vào shop thật trong quá trình nghiên cứu hoặc kiểm thử.
