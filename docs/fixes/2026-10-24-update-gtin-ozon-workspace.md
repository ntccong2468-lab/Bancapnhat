# Cập nhật, GTIN, nhận đơn Ozon và vùng hiển thị chung

Nền mã nguồn: VN code 1.5.13, commit phát hành `7e88dc0e5e4e491025874f560c3ac069b6718644` và bàn giao `b4e3a8d`. Nhánh sửa: `fix/update-gtin-ozon-fbs-layout`.

## Phạm vi giao diện

Áp dụng cho toàn bộ workspace, theo yêu cầu bổ sung của seller; không chỉ trang Znack. Cả 20 route đi qua `WorkspaceViewport`: trang chủ, license, thiết kế tem, hướng dẫn, tin tức, tài chính, supply, đóng hàng WB, FBO, đơn FBO, Ozon, liên kết KIZ, cấu hình Znack, đăng ký Znack, lịch sử in, GS1, TN VED, sản phẩm, sao chép và sửa sản phẩm.

Bảng/danh sách nhường chiều cao cho bộ lọc, nút và báo cáo cuối trang. Khi nội dung tối thiểu vẫn cao hơn cửa sổ, viewport cho cuộn tới cuối. Giữ danh tính view được cache để chuyển trang, tải dữ liệu, dừng media và xóa số lượng FBO vẫn theo đúng vòng đời cũ. Sidebar có vùng cuộn co giãn để không đẩy Cài đặt/license xuống ngoài cửa sổ. Không đổi màu hoặc bố cục nghiệp vụ.

`WorkspaceWindowBounds` dùng visual bounds của màn hình đang chứa app, kể cả màn hình có tọa độ âm hoặc taskbar bên trái/dưới. Cửa sổ thường được giới hạn trong vùng làm việc; maximize do hệ điều hành xử lý. Listener danh sách màn hình được gỡ khi ẩn và gắn lại khi hiện. Các hộp thoại tiếp tục dùng `DialogLayout` chung.

## Cài cập nhật trong lúc đang làm việc

Bỏ bước chặn tải bộ cài chỉ vì còn tác vụ nền. Sau tải/xác minh, installer tạm ngừng nhận công việc mới, chờ thao tác đang ghi kết thúc hoặc tới checkpoint đã lưu, rồi tự chụp snapshot và chạy bộ cài. Có pha riêng “đang lưu tác vụ đang chạy”; không báo 100% đồng nghĩa đã cài xong. Hủy/lỗi trước khi chạy installer sẽ mở lại hàng đợi và giao diện.

`UpdateHandoff` theo dõi executor chung, seller writes, sync, tài chính, license, đăng ký WB/Ozon và gán KIZ. Continuation của worker đang chạy được phép thoát khỏi phần việc của nó để tránh deadlock khi parent chờ child. Batch đăng ký và GTIN nhường tại ranh giới item/chunk đã lưu.

Trước handoff, hàng đợi KIZ WB lưu tham chiếu stock, order, fingerprint tài khoản và hash; không sao chép raw KIZ hoặc credential vào bảng resume. Sau khởi động/khôi phục license, app nạp đúng stock đã chọn, bỏ qua order có checkpoint CONFIRMED và không cấp mã khác. KIZ bị sàn từ chối, stock bị mất hoặc tài khoản đổi không được tự khôi phục. Schema bảng resume được thêm riêng, không xóa/sửa dữ liệu cũ.

Giới hạn có chủ đích: HTTP đang ghi phải có kết quả/biên nhận trước khi cài. Lô legacy thiếu tham chiếu stock không được tạm dừng rồi làm mất mã: worker đang chạy phải hoàn tất; nếu còn lô chưa chạy mà không thể lưu an toàn, app dừng handoff và giữ tác vụ. Không ép đóng giữa một mutation chưa xác định kết quả.

## GTIN WB/Ozon

Dùng ledger `gtin_sync_verified_mappings` sẵn có làm lịch sử xác nhận bền vững. Lượt mới giữ kết quả SUCCEEDED cho đúng tài khoản, nguồn Catalog, product/variant và GTIN đã xác nhận. Nếu lần đọc sàn mới hơn cho thấy GTIN không còn trên biến thể, chỉ biến thể đó được đưa lại vào delta; lịch sử cũ không bị xóa.

Đọc Catalog đã lưu theo scope trước, không quét lại toàn bộ Catalog chỉ để làm lại các mã đã cập nhật. GTIN chưa xong vẫn phải có proof hiện hành: các mã thiếu proof được đọc chọn lọc bằng `NationalCatalogGtinSource.forSelection`, sau đó mới đi qua kiểm tra owner/publication/variant và hàng đợi ghi thật cũ. Không dùng lịch sử để cấp quyền ghi mới hoặc bỏ qua giấy tờ.

Báo cáo cộng các kết quả cũ đã xác nhận với kết quả delta mới; không tính thẻ đã xong vào số “bị chặn”. Restart hoặc lỗi ở chunk sau không làm mất phần đã xác nhận.

Lập snapshot cho lô lớn dùng một kết nối/prepared statement để kiểm tra từng owner hiện hành, thay vì khởi tạo kết nối lại cho từng thẻ. Join ledger WB giữ tra cứu theo khóa `nm_id` có index và vẫn đối chiếu nguyên văn ID; không nới phạm vi ghép thẻ.

## Nhận đơn Ozon

Thay đường bỏ qua KIZ khi nhận đơn bằng:

1. Đọc yêu cầu thật của posting. Xử lý cả `products_requiring_mandatory_mark` và `optional.products_with_possible_mandatory_mark`; nhóm optional tôn trọng SKU seller đã miễn KIZ. Nếu nhiều đơn vị cần KIZ, gọi `/v1/posting/fbs/split` để tách **trước khi lắp ráp**.
2. Đọc lại native posting IDs, order, warehouse và tổng số lượng; không đoán hậu tố đơn con.
3. Dùng đúng stock đã giữ hoặc giữ stock theo chính sách kho hiện có; gọi create-or-get để lấy exemplar ID thật và ghép theo SKU.
4. Validate → set toàn bộ KIZ của đơn con → đọc status. Chỉ khi đúng code/exemplar có `passed`, không có mã lỗi và `ship_available` mới gọi `/v4/posting/fbs/ship`.
5. Đọc lại trạng thái lắp ráp. Timeout giữ biên nhận để đối soát, không lặp set/split/ship chưa xác định kết quả.

`product_id` của ship/split là Ozon SKU, không phải seller catalog ID. Kho do seller nhập theo danh mục vẫn dùng chính sách hiện có; kết quả chấp nhận do Ozon trả về.

Nếu Ozon vẫn xử lý sau vòng đọc ngắn, app báo rõ KIZ đã gửi, chưa nhận đơn. Thử tiếp sẽ đọc cùng yêu cầu đã gửi; không đổi mã hoặc gửi lại mù. Chưa thêm dịch vụ chạy ngoài app cho quy trình nhận đơn.

## PDF FBS Ozon 1.000 đơn

Dùng `/v3/posting/fbs/package-label/create` với `posting_numbers` tối đa 1.000, đọc `/v2/posting/fbs/package-label/get`. Task được lưu trước/sau ranh giới gửi; batch không biết task ID sẽ không tự tạo lại. Task v2 cũ vẫn đọc tiếp bằng giao thức cũ.

Mỗi shared task chỉ tải PDF một lần. Đọc mã posting trong nội dung trang để ghép; không coi thứ tự trang là thứ tự request. Khi không chứng minh được trang thuộc đơn nào, dừng xuất. In lại một đơn từ shared task không được in cả 1.000 nhãn.

Ghép trực tiếp nhãn vận chuyển và nhãn kết hợp barcode sản phẩm + KIZ vào một PdfDocument đích; bỏ bước tạo, mở lại và sao chép bundle trung gian cho từng đơn. Picking list vẫn xuất một lần. Chỉ publish cả kết quả và consume KIZ sau khi toàn bộ batch thành công; lỗi/hủy vẫn dọn staging. Không thêm lại nhãn sản phẩm thứ ba.

Thời gian Ozon tạo nhãn và độ trễ mạng thuộc upstream. Benchmark giả lập không được dùng làm cam kết tốc độ API production.

## Nguồn giao thức

- Tài liệu chính thức: https://docs.ozon.ru/api/seller/
- Đường swagger trực tiếp ở phiên này gặp vòng HTTP 307; không coi là đã tải trực tiếp thành công.
- Dùng bản sao schema công khai: https://raw.githubusercontent.com/dev-ik/seller-sdk/main/docs/ozon/swagger.json ; metadata của nguồn ghi lấy từ swagger Ozon ngày 19.10.2026. SHA-256 bản đối chiếu: `4c5756ae6b68177eae367786656f1b4ebdc00f2ca76c0311cf312a011ad1f9e4`.
- Schema đối chiếu: `v1PostingFbsSplitRequest`, `v1ProductFbsSplit`, `posting.v3.PostingFbsPackageLabelCreateRequest`, `posting.v2.PostingFbsPackageLabelGetResponse`, cùng exemplar create/validate/set/status và ship v4. Tên component create nhãn cần đọc theo `$ref` trong snapshot; giới hạn 1..1000 nằm ở `posting_numbers`.
- Snapshot và metadata nguồn giữ ngoài repo trong `/tmp/vncode-ozon-swagger-20261024.json` và file meta cùng tên. Không chứa credential seller.

## Xác minh và phát hành

`clean verify` cuối đạt **2.956 ca: 2.954 đạt, 2 bỏ qua theo hệ điều hành, không thất bại/lỗi**, thời gian 17 phút 34 giây. Node đạt 117/117. Kiểm thử nghiệp vụ dùng SQLite tạm và HTTP giả lập, có pending/rejected, đảo thứ tự 1.000 trang nhãn, resume, đổi tài khoản và child sai danh tính. Hai bộ layout có 896 và 987 phép kiểm tra nút cố định, không phát hiện bị cắt. [Biên nhận](../validation/2026-10-24-update-gtin-ozon-verify.json).

Không gọi mutation production trên WB/Ozon/Catalog, không mua KIZ thật. Chưa tạo hoặc công bố bộ cài Windows cho phần sửa này; Latest 1.5.13 là bản đã phát hành trước phiên, không tự được thay bằng mã nguồn vừa sửa.

## File KIZ seller gửi

`zl.txt` có SHA-256 `56a9868354cc142696c6dae36a3bea488abe077f01f9ed2f5baa2de5950e345e`, 200 dòng không rỗng/khác nhau, thuộc hai GTIN. Mỗi dòng là CIS ngắn 31 ký tự, không phải chuỗi DataMatrix đầy đủ có phần crypto. Không kết luận mã hỏng chỉ từ độ dài này.

Lượt đọc Ozon production tìm được 19 đơn chờ đóng gói; barcode sản phẩm OZN chưa chứng minh được đơn nào thuộc hai GTIN trong file. Tra cứu Catalog bằng kết nối môi trường trả HTTP 403, chưa xác định nguyên nhân. Không dùng kết quả này để kết luận thẻ không tồn tại hoặc credential sai. Chỉ đọc đơn/chi tiết/sản phẩm, chưa gán mã, tách hoặc nhận đơn.

Phép thử nhập/gán toàn bộ file chạy riêng ngoài repository, bằng mã app/SQLite tạm và HTTP giả lập: app đọc/nhập đủ 200 mã, 0 mã lỗi cấu trúc, giữ nguyên 200 mã trong payload thử. Đây không phải bằng chứng Ozon chấp nhận.

Sau hồi quy, gọi API production `/v5/fbs/posting/product/exemplar/validate` riêng từng mã, đúng một exemplar mỗi request: **200/200 trả HTTP 200 nhưng đều `valid=false` ở mark; 0 mã đạt**. Cả 200 có `MANDATORY_MARK_HAS_INCORRECT_CRYPTOTAIL`; trong đó 7 còn có `MANDATORY_MARK_IN_INCORRECT_STATUS`. Hai nhóm lỗi chồng nhau, không cộng thành 207 mã. Mẫu đối chứng sai độ dài trả `MANDATORY_MARK_HAS_INVALID_LENGTH`.

File chỉ chứa CIS 31 ký tự, chưa có phần crypto cần thiết cho lần kiểm tra này. Seller cần xuất lại chuỗi KIZ đầy đủ có phần crypto từ nguồn cấp mã; không tự tạo hoặc nối đuôi giả. API chưa cho biết nguyên nhân cụ thể đằng sau trạng thái không hợp lệ của 7 mã, không suy ra chúng đã bán/đã dùng.

Phản hồi thật có thể ghi `product.valid=true` trong khi `exemplar.valid=false` và `mark.valid=false`; phải đọc cấp mark/exemplar, không coi HTTP 200 hoặc product-level true là chấp nhận. Thử batch 50 exemplar không có chi tiết từng mã nên không dùng làm bằng chứng; kết luận cuối dựa trên 200 request riêng. Phép thử lại bằng shape lỗi thật và app/HTTP giả lập chặn đủ 200 mã trước `set`, số lần `set` bằng 0.

Validation trước gán trả lỗi mã, chưa chứng minh KN3 xanh sau gán. Không gán, tiêu thụ kho, tách hoặc nhận đơn thật; không cần thử gán một lô đã bị validation từ chối. Không lưu raw KIZ, payload, dữ liệu đơn hay credential trong tài liệu/commit.
