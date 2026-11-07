# GTIN Ozon — ghi API thật và đối chiếu toàn bộ danh mục

**07.11.2026, MSK (UTC+03:00)**. Runtime **`cae86ef71149a9be505b060360a7a9db8e07c5b4`**, JAR 1.6.5 SHA-256 `f1f8fa1334e64587608735176247b09d3c8f628107787cdfb46f551cd41de84b`. Theo yêu cầu mới: nghiệm thu thật, sau đó người dùng bỏ phần mua KIZ, chọn một mẫu hàng và cung cấp khóa Catalog khác để đối chiếu tất cả. **Không phát hành Latest.** [Biên nhận đã loại dữ liệu seller](ozon-live-gtin-20261107/live-api.json).

## Kết quả ghi thật

Đã chọn đúng một biến thể của mẫu người dùng chỉ định: màu nâu, size nhà sản xuất **L**, size Nga/Catalog **48**; tên và article hai bên khác nhau. API Catalog mới trả đúng một thẻ đã published, trade-unit cho lựa chọn này. Không dùng thẻ/GTIN của bộ dữ liệu cũ để gửi.

- Gọi thật **`POST /v1/barcode/add`** đúng SKU số và GTIN đã đối chiếu; **một request mutation**, response `errors=[]`.
- Sau gửi, cả **`v3/product/info/list`** và **`v4/product/info/attributes`** đều trả GTIN mới trên đúng native product/offer/SKU. Barcode cũ giữ nguyên: **1 → 2**; danh tính vật lý theo helper `OzonReviewedVariantProof` không đổi.
- Lượt đọc mới/kiểm tra/gửi/đọc lại hoàn tất trong **10.433 ms**, 17:06:32–17:06:42 MSK. Đây là thời gian harness API trong môi trường kiểm, không phải benchmark thao tác trên Windows.
- JVM mới đọc lại lúc 17:10 MSK xác nhận cùng GTIN và revision danh tính đã lưu, **0 request ghi thêm**. Journal trước HTTP được giữ, không gửi lặp để lấy lại bằng chứng.
- `OzonGtinAdapter.read/conflicts` production được gọi tiếp qua API thật: đọc thấy GTIN và không thấy native product khác giữ mã ở cả phân vùng **ALL** và **ARCHIVED**. Trước POST đã kiểm 500 bài hiện hành; 33 bài lưu trữ và lần kiểm adapter đủ hai phân vùng được đọc bổ sung sau đó, không mô tả là đã kiểm 533 bài trước POST.

`ListingHttpClient` trả JSON sau HTTP thành công nhưng không trả đối tượng status/header cho harness. Giá trị `200` trong báo cáo private ban đầu là suy từ đường thành công SDK; biên nhận công khai giữ **HTTP success / không giữ raw status**, `errors=[]` và nội dung readback, không dùng mã HTTP làm bằng chứng hoàn tất.

## Đối chiếu đủ hai nguồn

Khóa Catalog do người dùng vừa cung cấp trả **2.023 thẻ**, đọc đủ **3 trang danh sách + 81 batch chi tiết**, tối đa 25 GTIN/batch, **0 lỗi thiếu chi tiết**. Bộ 1.380 thẻ từ credential môi trường trước đó có **0 GTIN trùng** với bộ mới; đây là hai tập dữ liệu khác nhau, không phải bằng chứng app Windows của seller lưu sai key hoặc hai doanh nghiệp chắc chắn khác nhau. Không đưa approval/cache từ tập cũ sang tập mới.

| Nguồn | Kết quả |
| --- | ---: |
| Ozon hiện hành, đọc đủ info/attributes | 500 |
| Ozon lưu trữ, đọc đủ info/attributes | 33 |
| Tổng native product ID khác nhau | **533** |
| Catalog published | 1.926 |
| Catalog errors | 95 |
| Catalog draft | 2 |
| Tổng thẻ Catalog có chi tiết | **2.023** |

Trong **500 bài hiện hành**:

| Kết quả đối chiếu dữ liệu | Số bài |
| --- | ---: |
| GTIN đã khớp trong barcode từ trước | 1 |
| GTIN vừa cập nhật thật, đã readback | 1 |
| Các size còn lại của mẫu người dùng chọn có cặp màu/size tương ứng, chưa gửi | 4 |
| Thiếu màu hoặc size tường minh | 36 |
| Cần chọn mẫu Catalog tương ứng | 458 |

33 bài lưu trữ được giữ nguyên, không gửi GTIN; không thấy barcode thuộc bộ Catalog mới hoặc GTIN vừa gửi trên các bài này. **Cần chọn mẫu/chưa thấy GTIN trong barcode không đồng nghĩa Catalog chưa có thẻ.** Không tự gán các bài còn lại chỉ bằng tên gần giống, cùng màu/size hoặc cùng TN VED.

Bảng Excel riêng của seller có bốn sheet: 500 bài hiện hành, tổng hợp, 33 bài lưu trữ và đủ 2.023 thẻ Catalog; kèm CSV. Dữ liệu thật nằm ngoài Git trong gói riêng `VN-code-1.6.5-live-gtin-audit`, không có API key. Biên nhận công khai chỉ giữ số liệu, cờ kiểm chứng và hash; không chứa GTIN, SKU, good_id, article hoặc khóa thật.

## Phương pháp và giới hạn

- Không mock HTTP, không chứng thư/license giả, không kích hoạt license, mua KIZ, cấp GTIN, gửi feed/ký Catalog, ghi WB hoặc chỉnh các biến thể còn lại.
- Chạy production `ZnackApiClient`, `CatalogProductDataParser`, `OzonProductSnapshotReader`, matcher màu/size, helper revision, `ListingHttpClient` và các phương thức đọc/xung đột của `OzonGtinAdapter`. Khóa chỉ nằm trong file riêng quyền hạn chế, không nhúng vào JAR/source ZIP.
- **Lần POST do harness live gọi lớp transport production. Chưa gọi `OzonExistingCardLinkService.approveBatch`, `OzonGtinAdapter.submit`/licensed queue hoặc bấm UI app Windows bằng hồ sơ/chứng thư thật.** Không tạo scope/chứng thư giả để vượt gate. Vì vậy đây là nghiệm thu khả năng ghép dữ liệu và ghi/readback API, không phải nghiệm thu đầy đủ đường nút bấm/PKI trong app.
- Một thử nghiệm đọc qua `NationalCatalogGtinSource` trong profile trống bị `LicenseDeniedException` trước HTTP; sau đó dùng client/parser ở mức API độc lập như phạm vi kiểm này, không tắt kiểm tra trong source ứng dụng. Không diễn giải lỗi profile kiểm thành lỗi license của seller.
- Runtime không thay đổi trong lượt này. 476/476 ca và build của đúng source/JAR đã có ở [biên nhận build](ozon-reviewed-20261107/verification.json); không tính đó là 476 giao dịch thật hoặc ca mới chạy lại. Đã kiểm tính toàn vẹn workbook/checksum và kiểm không có credential trong tệp xuất.

Lệnh thực tế có dạng dưới; `PRIVATE_API_DIR` chứa inputs/credential/raw ngoài Git. Helper là harness riêng, không đóng vào JAR và không chạy tự động trong CI:

```sh
javac -cp 'target/VNcode-1.6.5.jar:target/lib/*' -d PRIVATE_API_DIR/classes PRIVATE_API_DIR/ReadCurrentCatalog.java
java PROXY_AND_CA_OPTIONS -cp 'PRIVATE_API_DIR/classes:target/VNcode-1.6.5.jar:target/lib/*' com.vncode.app.integration.znack.registration.ReadCurrentCatalog PRIVATE_API_DIR
java PROXY_AND_CA_OPTIONS -cp 'PRIVATE_API_DIR/classes:target/VNcode-1.6.5.jar:target/lib/*' com.vncode.app.integration.znack.registration.LiveOzonGtinAcceptance PRIVATE_API_DIR preview
java PROXY_AND_CA_OPTIONS -cp 'PRIVATE_API_DIR/classes:target/VNcode-1.6.5.jar:target/lib/*' com.vncode.app.integration.znack.registration.LiveOzonGtinAcceptance PRIVATE_API_DIR apply
java PROXY_AND_CA_OPTIONS -cp 'PRIVATE_API_DIR/classes:target/VNcode-1.6.5.jar:target/lib/*' com.vncode.app.integration.znack.registration.LiveOzonGtinAcceptance PRIVATE_API_DIR verify
java PROXY_AND_CA_OPTIONS -cp 'PRIVATE_API_DIR/classes:target/VNcode-1.6.5.jar:target/lib/*' LiveAdapterReadback PRIVATE_API_DIR
```

Phạm vi source và quyết định lưu tiếp vào candidate **1.6.5**; Latest vẫn **1.6.4**. Không thêm bộ cài Windows hoặc tự phát hành từ kết quả một giao dịch này.
