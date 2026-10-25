# Sửa nhận diện GTIN mua KIZ và xuất PDF cho WB/Ozon

Người dùng báo luồng mua KIZ vẫn không xác định được GTIN, in nhãn Ozon không xuất PDF và yêu cầu sửa cả WB. Nhánh `fix/kiz-gtin-ozon-pdf`, nền `d7d04b448dbf0c31f2686d629377feb62ee76a05`. Mã ứng dụng chốt `0f74d385e339e4579f3eb033a6ac42cac6390eb9`, hash cây nguồn `375715b9f339b34c2784756ebe43b99fd4bca51590e961f23d423f40b2ee1f35`.

Nhánh kế thừa sửa khung danh sách Sản phẩm/Znack/FBS Ozon theo FBS WB. Cả hai nhóm sửa đều **chưa có bộ cài mới**; không thay Latest v1.5.16 hoặc dữ liệu seller.

## Lỗi tái hiện được và thay đổi

1. **Ozon mất GTIN sau khi thẻ được xuất bản.** Bộ lập kế hoạch mua/in đọc danh tính đăng ký chờ xử lý, bỏ qua trạng thái `PUBLISHED`. Thẻ đã xuất bản đúng chủ tài khoản, đúng SKU/size nhưng chưa ghi barcode về Ozon có thể báo thiếu GTIN trước khi đến bước kiểm tra mua. Cho bộ lập kế hoạch dùng cùng nguồn chứng minh danh tính mua KIZ, có cả thẻ đã xuất bản và bằng chứng Catalog hiện hành. Bài thử trước sửa: WB đạt, Ozon trả GTIN rỗng; sau sửa cả hai đạt. Size khác không được dùng chung GTIN.
2. **WB bỏ sót GTIN đã nhập trên sàn.** Luồng FBS chủ yếu đọc mapping trong app hoặc quy tắc danh mục. Bổ sung đọc hàng loạt barcode/GTIN của đúng size từ dữ liệu WB đã đồng bộ, đối chiếu article, màu, size và Catalog đúng chủ tài khoản. Hỗ trợ barcode cũ của đơn cùng size có thêm GTIN mới trên WB; không cần tạo lại thẻ hoặc thêm quy tắc danh mục. Chỉ tra các đơn còn cần KIZ mua, sau khi loại đơn đã gán KIZ và cấp mã từ kho seller tải lên.
3. **PDF Ozon dùng API cũ và chờ quá ngắn ở đường in riêng từng đơn.** Yêu cầu mới dùng `/v3/posting/fbs/package-label/create` với `posting_numbers`, đọc `/v2/posting/fbs/package-label/get` với `task_id` số nguyên. Tác vụ riêng lưu tiền tố `v3s:`, tác vụ chung giữ `v3:`; receipt cũ vẫn đọc được. Tăng từ 12 lần chờ 500 ms lên tối đa 120 lần đọc, nghỉ một giây giữa lần đọc chưa xong, chưa tính thời gian HTTP/giới hạn yêu cầu. Hết lượt vẫn giữ receipt để lần in sau tiếp tục đúng tác vụ.
4. **Tác vụ nhãn bị Ozon xác nhận thất bại không phục hồi được.** Cho lần in tiếp theo tạo tác vụ riêng khi Ozon đã trả lỗi kết thúc. Giữ receipt gốc và trạng thái trước khi gửi; mất kết quả gửi không được tạo lặp. Lỗi ghi file cục bộ tiếp tục tải từ tác vụ cũ, không tạo tác vụ mới. Luồng In tất cả cũng phân loại các tác vụ này đúng cách.

Giữ kiểm tra cửa hàng/chủ sở hữu, giấy tờ, danh tính hàng, license, trạng thái lưu thông KIZ và chống gửi lặp chưa rõ kết quả. Không lấy GTIN bất kỳ chỉ vì kho có mã, không tự thay TN VED/size. Mua KIZ vẫn phải có xác nhận và bằng chứng đúng hàng như luồng hiện có.

## Kiểm chứng

Lệnh hồi quy cuối:

```bash
DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -B \
  '-Dtest=*Kiz*Test,Ozon*Test,OrderExportWorkflowKizPlanningTest,FboVerifiedGtin*Test,RegisteredGtin*Test,ZnackModuleTest,FriendlyErrorCodeTest,WorkspaceListAreaTest' test
```

**779/779 ca đạt, không lỗi/thất bại/bỏ qua**, 4 phút 53 giây. 92 ca mục tiêu và một ca xuất PDF WB chạy trước đó là tập con, không cộng thêm vào tổng cuối. Nhóm hồi quy gồm mua/đặt chỗ/giải phóng kho, trạng thái KN3, nhận đơn, đăng ký/Catalog/GTIN, PDF và diện tích bảng.

- WB: dùng SQLite và mã kho mua tổng hợp có trạng thái `IN_CIRCULATION`; gọi luồng xuất thật trong mã ứng dụng, mở được cả PDF nhãn và PDF danh sách bằng iText, xác nhận một mã đã tiêu thụ và có lịch sử in. Không có đăng ký VNCode/quy tắc danh mục trong fixture; đổi size phải chặn trước đặt chỗ/mua.
- Ozon: tạo/đọc/tải PDF qua HTTP localhost; kiểm chứng API v3/v2, tác vụ chậm qua ngưỡng chờ cũ, phục hồi lỗi kết thúc, lỗi ghi file giữ receipt và mất receipt không gửi lại. Kiểm tra 1.000 nhãn đảo thứ tự trang vẫn ghép đúng đơn, chỉ một create/get/download khi kết quả sẵn sàng; in lại một đơn không xuất toàn bộ 1.000 trang.
- Lượt tái hiện đầu có 4 ca: một đạt, một lỗi GTIN Ozon, hai lỗi hợp đồng API giả lập. Lượt mục tiêu đầu 58 ca còn một lỗi bọc thông báo mất receipt; đã sửa giữ nguyên nguyên nhân và chặn gửi lặp. Không dùng các lượt thất bại này làm bằng chứng hoàn tất.

### API Ozon thật: chỉ đọc

Kết nối bằng credential môi trường trả HTTP 200. Truy vấn tối đa 100 đơn trong cửa sổ ngày giới hạn cho 100 đơn `delivering`; truy vấn riêng trạng thái `awaiting_deliver` trả **0 đơn**. Vì không có đơn phù hợp trong truy vấn này, chưa xác nhận tạo/xuất PDF cho đơn seller thật. Không tạo tác vụ nhãn, thay trạng thái đơn, mua hoặc tiêu KIZ production.

API hiện chấp nhận cả `task_id: 1` và `task_id: "1"` ở v1/v2 get: đều trả 404 vì không có tác vụ. Giá trị không phải số trả 400. Vì vậy, **không kết luận chuỗi số là nguyên nhân lỗi production**; gửi số nguyên là tuân theo schema và bài thử hợp đồng nghiêm ngặt.

Schema tham khảo là snapshot OpenAPI chính thức do người dùng cung cấp, lấy ngày 19.10.2026 từ `https://docs.ozon.ru/api/seller/swagger.json`, SHA-256 `4c5756ae6b68177eae367786656f1b4ebdc00f2ca76c0311cf312a011ad1f9e4`. Tài liệu ghi v2 create dự kiến tắt ngày 02.11.2026 và khuyến nghị v3 create/v2 get. Lần thử tải mới trong lượt sửa gặp vòng chuyển hướng HTTP 307; không gọi snapshot này là bản vừa tải mới nhất.

Chỉ lưu số liệu đọc đã loại dữ liệu nhạy cảm trong [biên nhận](../validation/2026-10-25-kiz-gtin-ozon-pdf-verify.json). Không commit credential, mã KIZ, dữ liệu khách hàng hoặc đơn thật.

## Bàn giao

- Đã commit mã và lưu hồi quy trên nhánh riêng. Kế thừa bản sửa giao diện trước đó, không ghi đè các thay đổi đó.
- Chưa chạy full Java suite, native Windows/cài nâng cấp hoặc tạo bộ cài trong lượt này. Bằng chứng 779 ca là hồi quy chọn theo phạm vi, không phải toàn bộ hơn 3.000 ca của dự án.
- Latest được quan sát trước đó là v1.5.16, release `423202074`, bất biến. Lượt này không thay release/tài sản; POM vẫn 1.5.16 vì chưa đóng gói. Khi được yêu cầu phát hành, tăng phiên bản từ nhánh này, chạy các kiểm tra phát hành và giữ tag/binary v1.5.16 nguyên vẹn.
