# Triển khai VNCode 1.4.12

Ngày làm việc: 15/10/2026, theo múi giờ Moscow. Nền nguồn rà soát ban đầu: `2d4eeacee9195c005226dcd5fd8696f79013f60f`; bản Published tại thời điểm bắt đầu: 1.4.11.

## Phạm vi đã viết mã

- `SyncRuntime`/`SyncCoordinator`: một lịch nền của ứng dụng, hai worker FBS và một worker nền. Giới hạn tổng request marketplace đang dùng vẫn là hai; không nâng thành bốn như con số gợi ý trong thiết kế ban đầu. Cooldown giải phóng worker đọc FBS; yêu cầu làm mới không bỏ qua cooldown.
- `FbsSyncService`: phân trang Ozon, trạng thái chi tiết của đơn rời hàng đợi, lô trạng thái WB tối đa 1.000 ID, phân trang lịch sử, danh sách và nội dung shipment WB. Dùng checkpoint đã lưu; dữ liệu chỉ được ghi khi còn đúng chủ cửa hàng. Polling có trễ khi chạm giới hạn API/mạng; không có webhook thời gian thực của sàn.
- `ZnackAutoSyncService`: source Catalog → đối chiếu biến thể → kế hoạch Catalog/GTIN bền vững → gửi/đọc lại → báo cáo. Kiểm tra product/offer/SKU Ozon; liên kết native phải giữ đúng scope. Luồng tự động hiện chỉ chọn trường GTIN. Luồng xem trước đã có của Catalog tiếp tục xử lý TN VED/giấy tờ/chất liệu/thuộc tính với xác nhận riêng.
- `Gs1AutomaticSyncService`: đọc trực tiếp qua xác thực chứng thư từ Cấu hình Znack; không cần trình duyệt. Có cache riêng theo chủ/chứng thư, phiên riêng Catalog/ELK và một lượt xác thực lại khi phiên bị từ chối. Lưu trạng thái đơn GS1 đã gửi để giao diện không đề nghị gửi lặp chỉ vì vừa đọc cache.
- `SyncRunRepository` và báo cáo: ID tác vụ, dòng sản phẩm, tiến độ, mã lỗi an toàn, timeline, CSV/JSON có phân trang. Giao diện bỏ công cụ GTIN riêng và mở đối chiếu trong luồng Znack.
- Cơ sở dữ liệu bổ sung bảng, không xóa journal cũ; khóa chờ SQLite không giữ monitor của các lệnh chọn cửa hàng/làm mới. `SyncDataScope` chặn phản hồi cũ mở database khác khi môi trường dữ liệu thay đổi.
- Kho KIZ bổ sung nhóm seller tự tạo và liên kết sản phẩm; WB, Ozon và FBO lấy mã nhập FIFO trong nhóm mà không đối chiếu GTIN sản phẩm. Mã vẫn phải đúng chủ/INN và đã lưu thông. Giữ reservation/journal; phần mua thiếu có kiểm tra đúng native product, barcode/SKU và GTIN Catalog trước khi tạo mua có phí.
- Sao chép bài đăng tự kế thừa dữ liệu nguồn, ánh xạ dictionary tương ứng, size/RU size, article và giá API trả về. Batch không bắt mở lại từng bài để nhập dữ liệu đã có; không lấy nội dung chỉnh tay của bài đầu áp vào bài khác. Không tạo Catalog proof từ barcode sao chép.
- FBO giữ hợp đồng phân trang/cursor đang dùng của dịch vụ hiện có và nhận lịch nền chung. Tải chi tiết mặt hàng FBO vẫn theo lựa chọn của người dùng. Catalog nguồn và một số lượt lên kế hoạch vẫn có thể đọc nhiều trang trong một lượt nền; chưa chuyển toàn bộ thành một request mỗi lượt như mục tiêu dài hạn.

## Bằng chứng kiểm thử

- Lượt tập trung trước hoàn thiện: **100 bài, không lỗi/không bỏ qua**, gồm GS1 phiên HTTP, điều phối 10 cửa hàng, SQLite bận, pipeline WB/Ozon, lô 1.205 trạng thái WB, nội dung shipment 105 đơn và 54 bài FXML. Đây không phải thử tải seller 10 cửa hàng thật.
- Lượt Java đầy đủ sau kho KIZ và sao chép: **1.386 bài, 1.384 qua, 2 bỏ qua theo nền tảng, không lỗi**. Sau guard cuối kiểm tra lại INN của reservation nhập kho và hoàn thiện từ ngữ giao diện, **59 bài tập trung qua**, gồm allocator, mua đúng biến thể, WB/Ozon/FBO, reader giá và giao diện thực. Lượt xác thực/GS1/CryptoPro trước đó có 69 bài qua. Windows sau đó đã chạy lại trên nguồn ứng dụng cuối, như kết quả bộ cài bên dưới.
- Hợp đồng công cụ phát hành: **53/53 qua** trên thay đổi hiện tại. Bổ sung yêu cầu kiểm tra nâng cấp bộ cài từ Published 1.4.10 và 1.4.11.
- Hai lỗi timeout mock đã được xử lý ở các lượt phát triển: transport kiểm thử hợp đồng 500 ms và fixture đọc 1.001 chi tiết có read timeout 200 ms. Chỉ tăng timeout của fixture lên mức phù hợp; không đổi timeout production, không thêm retry mutation. Lượt hồi quy cuối đã qua, vẫn đọc đủ 1.001 đơn khi chúng rời trạng thái ban đầu.

## Chưa được chứng minh

Chưa có phiên seller để thử API production, CryptoPro/PIN thật, đơn GS1, GTIN, gán KIZ hoặc mua mã thật. Không gửi email, không mua mã và không dùng dữ liệu KIZ thật trong kiểm thử này. Bằng chứng WBCode là release 1.3.8 và UI/mã WCode công khai; không tuyên bố đã chạy WBCode mới nhất với tài khoản seller.

Windows workflow đã kiểm tra launcher, DPAPI, bố cục/DPI, dữ liệu 10 cửa hàng và nâng cấp từ các bộ cài cũ trên đúng nguồn ứng dụng. Kết quả Linux JavaFX được bổ sung bằng kiểm tra Windows thực. Bản mới chưa được gắn Published/Latest; điều kiện nghiệm thu license thật vẫn được giữ trong công cụ phát hành.

## Đối chiếu và sơ đồ bổ sung

Đã đọc lại trực tiếp bảy tệp WCode 1.0.27 theo commit cố định; manifest checksum đi cùng [bảng đối chiếu](../research/2026-10-15-vncode-1412-category-copy.md). WBCode 1.3.9 xuất hiện trong lúc làm việc và đã bổ sung ghi chú công khai; chưa có source nội bộ hay nghiệm thu seller để khẳng định tương đương mọi hành vi. [Sơ đồ kho KIZ và sao chép](../architecture/2026-10-15-category-kiz-and-listing-copy.md) mô tả hành vi mới.

Đã mở giao diện JavaFX với dữ liệu tổng hợp cho kho và màn sao chép đã điền dữ liệu, ngoài các bài kiểm thử giao diện. Không dùng file KIZ thật của seller. Hợp đồng đọc giá WB/Ozon được tải lại từ tài liệu chính thức, lưu URL/checksum trong `docs/research/vncode-1412-listing-price-contracts.json`.

Bổ sung sau lượt hồi quy: reader Ozon lấy size Nga từ thuộc tính có tên tương ứng, mapping chuyển size/size Nga sang cấu trúc biến thể của sàn đích thay vì bắt nhập lại như thuộc tính card. Giá nguồn ngoại tệ không được tự hiểu là RUB khi gửi giá WB. **48 bài sao chép/reader/giao diện qua** sau bổ sung này; Windows đã chạy lại toàn bộ trên nguồn ứng dụng cuối như kết quả bên dưới.

## Bộ cài Windows đã kiểm tra

Workflow [38776235230](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38776235230) hoàn tất thành công trên đúng nguồn ứng dụng `fdce2e185d17afaf2a72643700b8ddf233f1d525`.

- Java/FXML: **1.388 bài, 1.386 qua, 2 bỏ qua theo nền tảng, không lỗi**; 53 kiểm thử công cụ phát hành qua.
- Native launcher, bảo vệ DPAPI/GS1, dữ liệu 10 cửa hàng ở DPI 100/125/150, 272 bố cục, cài mới/gỡ cài đặt: qua.
- Nâng cấp giữ dữ liệu từ 1.1.34, 1.2.0, 1.2.1, 1.2.2, 1.3.0, 1.4.0, 1.4.6, **1.4.10 và 1.4.11**: qua.
- Đã tải lại artifact, kiểm tra định dạng Windows PE và checksum: `VN-code-1.4.12-Windows-x64.exe`, **145.091.584 byte**, SHA-256 `66b34a542bca66b812a5c7f8685596196f0de0d9e6d0fc3d2b8d67178d15b8a1`.

Bằng chứng máy đọc được: `VN-code-1.4.12-Windows-build.json`. Bộ cài chưa có chữ ký Authenticode. Các kiểm tra trên dùng dữ liệu tổng hợp; không thay thế kích hoạt license thật hoặc gán/mua KIZ thật. Nguồn ứng dụng/kiểm thử/build không đổi sau build; commit ghi nhận kết quả chỉ cập nhật tài liệu, theo `validatePublicationSource`.

Draft Releases đã được tạo bởi workflow [38780513750](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38780513750), ID `414102966`, gồm 20 tệp; EXE trên GitHub có cùng byte và SHA-256 đã kiểm tra. Draft cần đăng nhập tài khoản có quyền repo để tải; chưa phát hành Published/Latest. Điều kiện còn thiếu để xuất bản là hồ sơ nghiệm thu license production của đúng nguồn/bộ cài, không phải yêu cầu chạy lại kiểm thử đơn vị.
