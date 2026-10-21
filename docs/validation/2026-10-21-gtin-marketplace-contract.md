# Hợp đồng cập nhật GTIN lên WB/Ozon — giai đoạn F

Đối chiếu ngày **21.10.2026 UTC** bằng tài liệu OpenAPI chính thức đang được công bố. Phạm vi là barcode của thẻ sản phẩm trên sàn, đọc lại xác nhận và phân biệt với metadata đơn FBS. Không gọi API ghi seller, không ký, không xin GTIN, không mua KIZ, không build hoặc phát hành trong lượt đối chiếu này.

## Nguồn đã đọc

| Nguồn chính thức | Kết quả đọc ngày 21.10.2026 | Phiên bản trong tài liệu | SHA-256 toàn bộ nội dung |
| --- | --- | --- | --- |
| [WB Item Management YAML](https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml) | HTTP 200; 387.069 byte | `info.version: items` | `55adcfc425dd6a2867fe445034944950d413c063438708a9e48a4989b267c416` |
| [WB FBS Orders YAML](https://dev.wildberries.ru/api/swagger/yaml/en/03-orders-fbs.yaml) | HTTP 200; 263.360 byte | `info.version: order` | `187fa8858350860c122b7fe1635b915eae87f4d2cb6ad4c7b8c5d7525f3f5004` |
| [Ozon Seller Swagger](https://docs.ozon.ru/api/seller/swagger.json) | HTTP 200 lúc 00:08:19 UTC; 4.234.584 byte | `info.version: 2.1` | `4c5756ae6b68177eae367786656f1b4ebdc00f2ca76c0311cf312a011ad1f9e4` |

Ozon ban đầu chuyển hướng HTTP 307. Phiên HTTP công khai giữ cookie thông thường đọc được JSON chính thức; không dùng khóa seller. Hash Ozon trùng với bản đã lưu trong [đối chiếu 19.10.2026](2026-10-19-gtin-bulk.md). Các nhãn phiên bản trên không chứa ngày phát hành schema; ngày quan sát và hash xác định chính xác bản tài liệu đã đọc. Metadata cục bộ nằm tại `/tmp/vncode-phase-f-contracts/sources.json` và `ozon-cookie-session-source.json`; không chứa credential seller.

## WB: barcode thẻ sản phẩm và GTIN đơn FBS là các trường khác nhau

| Thao tác | Hợp đồng chính thức | Hệ quả cho VNCode |
| --- | --- | --- |
| `POST https://content-api.wildberries.ru/content/v2/cards/update` | Thẻ và toàn bộ `documents` bị ghi đè. SKU của size chỉ được **thêm**, không được sửa/xóa SKU cũ. | Cần đọc mới đúng shop/nmID/chrtID, giữ nguyên các size, barcode và trường được phép ghi; thêm GTIN đúng size. Không dùng phép ghi đè để thay barcode. |
| `sizes[].skus` | Mảng barcode của size. `chrtID` bắt buộc cho size đã có; không gửi `chrtID` khi tạo size mới. | Phân biệt nmID của thẻ với chrtID của size. Không suy ra size từ tiêu đề hoặc vị trí dòng. |
| `gtin` ở cấp thẻ Content | “Additional GTIN”: chỉ định khi GTIN đó đã nằm trong `skus` của một thẻ; một GTIN không thể nằm trong `skus` của nhiều thẻ/size. | Không tự sao chép một GTIN vào `skus` của nhiều thẻ. Trường bổ sung này không thay cho kiểm tra barcode đúng biến thể hoặc chứng minh quyền sử dụng GTIN. Bản YAML tiếng Anh ghi giới hạn seller Nga nhưng câu bị cắt tại “Only for sellers from the Russian”; không suy rộng phạm vi quốc gia từ câu này. |
| `kizMarked` | Seller xác nhận mã bắt buộc đã được áp dụng lên hàng; `needKiz` trong kết quả đọc cho biết yêu cầu đánh dấu. | Giữ xác nhận hiện có; không tự chuyển thành `true` chỉ vì có GTIN hoặc thẻ Catalog đã ký. |
| `PUT https://marketplace-api.wildberries.ru/api/v3/orders/{orderId}/meta/gtin` | GTIN ở đây là mã định danh hàng Belarus của **đơn FBS**, đúng 13 ký tự, một mã/đơn. Đơn phải ở `confirm` và metadata của đơn phải cho phép trường `gtin`. | Không gọi API này để cập nhật barcode của thẻ sản phẩm hay thay trường Additional GTIN trong Content. |
| `PUT .../meta/sgtin` | Mã đánh dấu Chestny ZNAK của đơn FBS; đọc lại qua `POST /api/v3/orders/meta`. | SGTIN/KIZ của từng đơn cũng không phải barcode GTIN trên thẻ sản phẩm. |

Schema ghi Content có `nmID`, `vendorCode`, `kizMarked`, `brand`, `title`, `gtin`, `description`, `dimensions`, `documents`, `characteristics`, `sizes`. Ba trường bắt buộc được khai báo là `nmID`, `vendorCode`, `sizes`. Các trường của size gồm `chrtID`, `techSize`, `wbSize`, `price`, `skus`; `price` ở API này chỉ dùng khi thêm size. Ảnh, video, tag và giá của size hiện có không được cập nhật bằng API này.

`dimensions` ghi gồm `length`, `width`, `height`, `weightBrutto`. Schema đọc có thêm `isValid`, là kết quả đánh giá; không gửi ngược trường này. Schema không tuyên bố bốn số đo hay brand/title/description luôn bắt buộc trong phản hồi. Nếu ảnh chụp thẻ thiếu trường cần giữ lại, việc từ chối ghi để bảo toàn dữ liệu là guard cục bộ của ứng dụng; không được bịa giá trị mặc định hoặc diễn đạt thành yêu cầu bắt buộc của WB. Tài liệu còn nêu `isValid=false` có thể xuất hiện khi thiếu số đo hoặc số đo bằng không.

Giới hạn Content update: **3.000 nmID/yêu cầu**, tối đa **10 MB**; **10 yêu cầu/phút/tài khoản**, khoảng nạp **6 giây**, burst **5**. Dùng kích thước UTF-8 thực và giữ nhóm size của cùng nmID trong một thẻ. Giới hạn FBS thêm metadata riêng là 1.000 yêu cầu/phút, khoảng 60 ms, burst 20; phản hồi 4xx tính như 10 yêu cầu. Không dùng hạn mức FBS để tăng tốc Content.

HTTP 200 của `cards/update` chỉ chứng minh phản hồi tiếp nhận. WB yêu cầu kiểm tra `POST /content/v2/cards/error/list` nếu thẻ chưa được cập nhật; đồng bộ dịch vụ có thể mất tới **30 phút**. Kiểm duyệt giấy tờ có thể mất tới **3 ngày**, khác với việc barcode đã hiện trong thẻ.

Đọc thẻ bằng `POST /content/v2/get/cards/list`: tối đa **100 thẻ/trang**, `sort.ascending=true`, tiếp tục bằng cặp cursor `updatedAt,nmID`. Tài liệu chỉ dẫn dừng khi `cursor.total < limit`; đây là số của trang, không phải tổng toàn shop. So sánh nghiêm ngặt số dòng thực với `cursor.total` là kiểm tra tính toàn vẹn của ứng dụng, không phải bảo đảm bổ sung được schema tuyên bố. Thẻ trong thùng rác phải đọc bằng endpoint thùng rác riêng. API đọc danh sách có hạn mức 100 yêu cầu/phút, 600 ms, burst 5; danh sách lỗi có hạn mức riêng 10/phút, 6 giây, burst 5 và phân trang `updatedAt,batchUUID` đến `next=false`.

## Ozon: thêm barcode theo SKU, xác nhận theo đầy đủ định danh sản phẩm

`POST /v1/barcode/add` nhận:

```json
{"barcodes":[{"barcode":"00000000000017","sku":99887766}]}
```

`sku` là SKU Ozon kiểu int64, khác `product_id` và `offer_id`; phải đọc từ đúng sản phẩm. Barcode là chuỗi, không đổi sang số làm mất số 0 ở đầu. Giới hạn **100 cặp/yêu cầu**, **100 barcode/sản phẩm**, **20 yêu cầu/phút/tài khoản seller**. Hạn mức theo tài khoản, không phải 20 lượt cho mỗi API key hay mỗi cửa hàng nội bộ dùng chung Client-Id.

HTTP 200 trả `errors[]`, mỗi lỗi gồm `code`, `error`, `barcode`, `sku`. Vì vậy HTTP 200 có thể chứa lỗi một phần; phải đối chiếu đúng từng cặp, giữ nguyên code và thông điệp upstream trong báo cáo. Không gán thành công cho cả lô từ mã HTTP, cũng không gán thất bại cho các thành viên khác chỉ vì một cặp bị từ chối. Schema không tuyên bố trường `errors` là bắt buộc; phản hồi thiếu cấu trúc cần thiết không đủ làm bằng chứng đã cập nhật.

`POST /v3/product/info/list` nhận tối đa **1.000 định danh tổng cộng** qua `product_id`, `offer_id` hoặc `sku`. Kết quả `items[]` có `id` (product_id), `offer_id`, `sku`, `barcodes` (“tất cả barcode của sản phẩm”) và `updated_at`. Đọc lại cần khớp đúng định danh và có GTIN mới, đồng thời giữ các barcode cũ. `/v4/product/info/attributes` cung cấp thuộc tính để xác minh biến thể; không nhập lại sản phẩm chỉ để thêm barcode.

`POST /v1/barcode/delete` là thao tác riêng; Ozon không cho gỡ khi kho còn tồn với barcode đó hoặc chưa đủ sáu tháng sau khi bán hết. Quy trình ADD hiện có không được tự chuyển sang delete/import khi gặp lỗi.

`POST /v3/product/list` dùng cursor `last_id`, `limit` 1–1.000. `result.total_items` là tổng int64; `result.total` int32 đã deprecated và tài liệu thông báo gỡ ngày **23.11.2026**. Không chứng minh đã đọc hết chỉ bằng trang ngắn nếu tổng khai báo còn thiếu hoặc cursor/định danh bị lặp.

Điểm cần giữ đúng khi nói “toàn cửa hàng”: `visibility` mặc định `ALL` nhưng mô tả rõ **ALL loại trừ sản phẩm đã lưu trữ**. `ARCHIVED` là giá trị riêng đã được công bố. Chỉ quét ALL không đủ chứng minh không có GTIN trùng ở archive. Khi lọc rõ bằng `offer_id` hoặc `product_id`, tài liệu nói trả các định danh đã yêu cầu bất kể visibility; chỉ dùng một nhóm định danh tối đa 1.000 trong một yêu cầu.

## Phân biệt sự thật API và guard của ứng dụng

- **Đã xác minh từ tài liệu:** các đường dẫn, trường định danh, ADD-only của WB, toàn bộ overwrite, hạn mức, lỗi từng cặp Ozon, cursor và phạm vi archive nêu trên.
- **Điều kiện ứng dụng phải chứng minh bằng đọc lại:** GTIN đúng shop/sản phẩm/size, toàn bộ barcode cũ còn nguyên, payload không thay đổi trong lúc chờ hạn mức, trạng thái Catalog mới và quyền chủ thể ký. Các điều kiện này không tự có chỉ vì endpoint trả HTTP 200.
- **Kết quả chưa rõ:** timeout, lỗi mạng, 5xx, phản hồi hỏng/thiếu, mất kết nối sau khi đã gửi hoặc chưa đọc được thẻ không được biến thành “GTIN không tồn tại” hay thất bại chắc chắn. Giữ checkpoint và đối chiếu; không tự phát lại phép ghi có kết quả chưa xác định. Hạn mức 429 cần lưu thời điểm thử lại theo header/hợp đồng hiện có, không lặp dồn.
- **Chưa nghiệm thu ở lượt này:** không có ghi GTIN production, không đo thời gian cập nhật 10.000 GTIN thật, không chứng minh backend sàn xử lý ngay, không có nghiệm thu CryptoPro/license hoặc bộ cài Windows cho mã C–F. Chứng cứ API đọc/kiểm thử mô phỏng cũ không thay thế các nghiệm thu này.

## Công cụ sẵn có cho G; chưa thực thi trong lượt này

Linux có Maven wrapper dự án và wrapper môi trường `/workspace/toolchains/mvn-vncode` (JDK 25, kho Maven cục bộ, trust store môi trường). Luồng full verify đã được dùng trong dự án:

```sh
DISPLAY=:99 XDG_CACHE_HOME=/tmp/vncode-ui-cache /workspace/toolchains/mvn-vncode -o clean verify
node --test tools/*.test.mjs
```

Đây là lệnh để người điều phối dùng sau khi nguồn F đóng băng; tài liệu này không ghi chúng là đã chạy. Xvfb/display, dependency đã cache và kết quả Surefire thực phải được kiểm tra tại lúc chạy.

Windows có [build.bat](../../build.bat) (`app-image`, `exe`, `msi`) và [build-java.yml](../../.github/workflows/build-java.yml): JDK25, Node22, full `mvnw.cmd -B clean verify`, `build.bat exe`, các probe native/GS1/workspace/DPI/layout/side-by-side/upgrade. `VNCODE_REUSE_VERIFIED_BUILD=true` chỉ bỏ lượt Maven trùng trong cùng job sau khi đã xác minh đúng nguồn; không phải giấy phép dùng lại kiểm thử từ bản phát hành cũ.

Các script native thực nằm trong `tools/windows-native-smoke.ps1`, `windows-gs1-smoke.ps1`, `windows-seller-workspace-smoke.ps1`, `windows-ui-layout-smoke.ps1`, `windows-side-by-side-smoke.ps1`, `windows-upgrade-smoke.ps1`. Workflow test Znack riêng tạo app/data profile riêng, không thay thế full regression. Các workflow publisher/hotfix có thể thay release/Latest; không chạy để làm kiểm thử. **Bộ cài Windows và phát hành vẫn đang giữ**, không dispatch, gắn tag, tải artifact hay đổi Latest trong giai đoạn F này.
