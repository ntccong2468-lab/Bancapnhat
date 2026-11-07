# Rà soát báo cáo GTIN, PDF Ozon và ứng dụng — chuẩn bị 1.6.6

Người dùng yêu cầu kiểm báo cáo, đủ nhãn KIZ kết hợp ở các cửa hàng Ozon, Code Doctor/soi mã toàn ứng dụng, TASTE + IMPECCABLE cho giao diện, sau đó gộp và phát hành Latest. Nguồn chức năng **`2e5b50a`** kế thừa đầy đủ hotfix Ozon `86b1dcc`, WB `a95201d`, nhận feed `a43b7e1` và bản đã phát hành 1.6.5. Nhánh phát hành `release/vn-code-1.6.6`; SHA đóng gói cuối phải lấy từ biên nhận Windows, không suy từ HEAD tài liệu.

Mốc app ban đầu **`bd3175d61cb1bb133999e8a6e5b011902d50c7cb`** đã được bổ sung sửa nhánh in đơn lẻ trong lượt soi cuối. [Windows build `42407000935`](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42407000935) đã hủy trước đóng gói; không dùng làm bằng chứng bản cuối. Lượt Windows nguồn mới phải giữ full suite, kể cả ba ca 10.000. [Nguồn và các mốc kế thừa](2026-11-07-vncode-166-source-inclusion.json), [kết quả Linux từng lớp](report-pdf-app-20261107/verification.json), [PDF ba shop](report-pdf-app-20261107/pdf.json), [scan đã triage](report-pdf-app-20261107/source-scan.json), [JAR local](report-pdf-app-20261107/local-package.json), [ảnh native](assets/report-pdf-app-20261107/README.md). Biên nhận mỗi lượt giữ đúng SHA; tài liệu/tooling sau SHA đóng gói không thay binary đang build.

Nguồn cuối **`ac8ba1c939e22d0318877a3a2ba7242359cce78e`**, [Windows `42408546769`](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42408546769) và [security `42408547869`](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42408547869), khởi chạy 19:55 UTC. Các tệp giao diện giống byte lúc capture; chỉ thêm guard service, hai test và ghi chú sau mốc đầu.

## Lỗi có bằng chứng và sửa đổi

| Mức | Chẩn đoán trước sửa | Thay đổi và kiểm chứng |
| --- | --- | --- |
| P1 | 552 thẻ chưa ghép GTIN bị cộng vào Bị chặn: đúng bộ số ảnh cho ra 825 thay vì 273 | `GtinRunStatusSnapshot`, `SyncRunRepository` phân biệt thiếu GTIN, cập nhật cần xử lý và sàn đã xác nhận. Hai nhánh WB/Ozon đều tái hiện, sau sửa các nhóm cộng đúng 1.982. |
| P1 | Sàn đã xác nhận barcode nhưng lỗi kích hoạt liên kết cục bộ vẫn làm tăng số cập nhật bị chặn | Ưu tiên bằng chứng marketplaceVerified trong thống kê; lỗi cục bộ còn nguyên ở báo cáo chi tiết, không sửa dữ liệu để che lỗi. |
| P1 | Spinner chờ đọc lại GTIN làm danh sách không tự cập nhật dù worker gửi đã xong | Chỉ Task ghi còn hoạt động mới giữ danh sách; observer đọc trạng thái không khóa làm mới. Regression trước sửa thất bại. |
| P1 | PDF giữ snapshot barcode sản phẩm từ trước bước chuẩn bị, nên có KIZ nhưng vẫn bỏ nhãn kết hợp khi dữ liệu sản phẩm đến sau | Đọc lại snapshot sau chuẩn bị và bổ sung metadata thiếu qua `v3/product/info/list` theo SKU, tối đa 1.000 SKU/lần. Giữ đúng shop/SKU/product/offer; đọc lại owner sau HTTP. Không ghi vào cursor Catalog hay bảng bằng chứng GTIN. Baseline 4 ca có 2 thất bại; sau sửa đủ nhãn ở cả hai đường xuất PDF. |
| P1 | Đơn không gắn KIZ xuất được vận đơn nhưng báo cáo không đánh dấu carrier-only | Cả xuất theo task và đường tương thích đều ghi rõ đơn chưa có nhãn KIZ kết hợp. Hai regression tái hiện trước sửa; không giả số nhãn hoặc chặn việc lấy vận đơn gốc. |
| P1 | Nhánh in riêng một đơn không cần KIZ đọc `context.products` khi không có context in theo lô | Soi cuối phát hiện và tái hiện hai ca NullPointer, cả có/thiếu metadata. Chỉ làm mới metadata nhãn kết hợp khi thật sự có binding KIZ; giữ carrier/picking/print receipt và quy tắc yêu cầu KIZ. |
| P2 | Giao diện tài chính có nhãn viết thường không nhất quán; thanh tải cập nhật dùng gradient cũ đang có hiệu lực | Thống nhất Phải thanh toán; thanh cập nhật dùng màu phẳng. Giữ giao diện đã duyệt, cả hai nền và mọi handler. |

## Báo cáo theo ảnh

Phạm vi 1.982 biến thể gồm **1.141 đã xác nhận trên sàn + 16 chờ cập nhật/xác nhận + 273 cần xử lý + 552 chưa ghép GTIN + 0 chưa kiểm chứng**. Mẫu số là toàn phạm vi; không cộng lại 552 vào nhóm 273. Đây là ca hồi quy tái tạo bộ số từ ảnh, không phải một lượt đọc mới toàn shop seller.

Thông báo nền và hộp báo cáo dùng cùng cách tính. Lỗi, yêu cầu chưa rõ kết quả, owner, danh tính biến thể và bằng chứng đọc lại vẫn được giữ. Không coi nhận HTTP 200/feed_id là đã công bố thẻ hoặc cập nhật barcode thành công.

## PDF và giới hạn chính xác

- PDF seller đã gửi có **22 trang: 17 vận đơn và 5 trang KIZ kết hợp**. Chưa có DB/nhật ký export đi kèm để quy toàn bộ 12 nhãn thiếu về một nguyên nhân duy nhất.
- Regression ở **ba shop ID 1/17/301 độc lập**, mỗi shop 3 đơn × 2 sản phẩm: mỗi PDF có **3 vận đơn + 6 KIZ = 9 trang**. Quét lại đủ 6 DataMatrix và barcode Ozon ở 203 dpi, đối chiếu từng mã đã dành cho đúng đơn. Một lượt đọc metadata theo SKU, một task vận đơn cho cả lô. Kho seller nhập không cần dựng GTIN mapping.
- 1.001 sản phẩm thiếu metadata dùng **2 HTTP request**, không gọi từng SKU. Kiểm owner đổi giữa HTTP, sai offer/SKU, response trùng/thiếu, SKU cũ sai định dạng, giữ metadata tốt của sản phẩm khác và giữ màu/size đã biết. Các ca này dùng MockWebServer, không phải API seller thật.
- Giữ nguyên vận đơn Ozon, bỏ barcode sản phẩm đứng riêng, sắp theo quy tắc WB và giữ từng nhãn kết hợp sát vận đơn đúng chủ. Thiếu mã phù hợp, định danh trang chưa rõ hoặc metadata không xác minh được phải hiện lý do; không tạo KIZ giả, không lấy nhầm đơn/kho hoặc giả trạng thái chấp nhận trên Ozon.
- Benchmark của lượt hồi quy có 100 đơn/200 trang, hai lượt xuất/2 tải batch/0 tải nhãn riêng. Benchmark 1.000 đơn có ghi rõ không KIZ, không ảnh, API localhost; không dùng số này làm thời gian 1.000 nhãn kết hợp hoặc đo sàn thật.

## Code Doctor và phạm vi kiểm tra toàn ứng dụng

- Baseline bộ đếm: **4/4 thất bại**; snapshot PDF cũ: **2/4 thất bại**; readback giữ danh sách: **1 ca lỗi assertion**; báo carrier-only: **2/2 thất bại**. Hai lượt biên dịch fixture thiếu khai báo/import đã được sửa trước baseline hợp lệ, không tính chúng thành lỗi ứng dụng.
- Hồi quy chức năng rộng tại `2e5b50a`: **3.974 ca/527 lớp, 0 failure/error, 2 OS skip**, 14 phút 47 giây. Ba phương thức stress 10.000 GTIN được tách khỏi lượt Linux bằng tham số, không vô hiệu trong source; lượt Windows phát hành sẽ giữ full suite. Các file runtime không thay đổi trong lượt chạy.
- Lệnh: `PATH=/workspace/toolchains/xvfb-current/root/usr/bin:$PATH xvfb-run -a /workspace/toolchains/mvn-vncode -B '-Dtest=*Test,*Tests,Test*,*TestCase,!GtinBulkPipelineTest#*TenThousand*' verify`, với các cờ capture dữ liệu tổng hợp. Kết quả từng lớp, lệnh capture và danh sách stress/OS skip được lưu trong biên nhận kiểm chứng.
- Sau tăng version 1.6.6 và sửa đúng một paint của thanh cập nhật: **40 ca UI/version đạt, 0 skip**. Có 155/155 test công cụ và 67/67 test portal, kiểm cú pháp portal đạt. Portal không được triển khai.
- Sau guard in riêng đơn không cần KIZ: **150 ca/5 lớp Ozon đạt**, 0 failure/error/skip; gồm hai ca trực tiếp carrier-only, 100 đơn/200 trang kết hợp, 1.000 carrier theo batch, metadata, workflow exemplar, label service và picking. Baseline hợp lệ là hai NullPointer; fixture ban đầu thiếu cấu hình miễn KIZ rồi dùng PDF hai loại trang đã được chỉnh đúng hợp đồng một vận đơn, không tính lỗi fixture là lỗi app.
- Phạm vi các nhóm bao gồm WB/Ozon FBS/FBO/shipments, nhận đơn/nhãn/picking/history, mua và nguồn KIZ/mapping, năm thao tác sản phẩm, Znack/feed/ký/GTIN/readback/giá, GS1, tài chính, cửa hàng, điều hướng, license/updater, lưu trữ/migration và wrapper. Kiểm thử dùng service fixture/SQLite/HTTP cục bộ; không suy thành đã ghi thật mọi chức năng hoặc đã nhận hết đơn của mọi shop thật.

## TASTE và IMPECCABLE

Giữ Native Dispatch Workbench đã duyệt trong `DESIGN.md`; không rút kiểu ngẫu nhiên hoặc đổi hệ thiết kế. Câu hỏi chính của màn đơn/sản phẩm là việc còn cần làm, nên giữ danh sách lớn, scope cửa hàng và hành động ngay trên hàng.

- Đã render **16 trang FXML + 10 trang Java**, 4 ngôn ngữ, 2 theme, 2 kích thước nội dung. Kiểm **896 + 984** vị trí nút cố định, không thấy tràn. Kiểm danh sách WB FBS, sản phẩm, hai màn đăng ký và Ozon FBS; đối chiếu nhiều ảnh native đã render, không dùng HTML preview để chứng nhận app.
- Có **84 ảnh tổng hợp** sau sửa, gồm thông báo bộ số ảnh ở vùng làm việc 600/940 px. Kiểm tương phản và bố cục là JavaFX; chưa chứng nhận Narrator, DPI hoặc taskbar trên máy seller. Hai skip Linux là junction NTFS và entropy Windows, cần kiểm Windows.
- Đánh giá có giới hạn cho native: accessibility 3/4, performance 3/4, theming 3/4, thích nghi cửa sổ 3/4, nhất quán triển khai 3/4 = **15/20**; không phải chứng nhận WCAG hoặc kiểm hết thiết bị thật.
- TASTE source lint ban đầu có 10 gradient FAIL và 10 cảnh báo màu ở `theme.css`: 9 gradient/màu cũ bị stylesheet workspace nạp sau ghi đè. Một gradient thanh cập nhật còn hoạt động được sửa thành màu phẳng. Không tắt rule hoặc thêm ignore để đổi kết quả scanner.
- IMPECCABLE source detector có 52 finding: 4 cảnh báo Inter, 1 font mono cho mã/log và 47 advisory kích thước/radius. Đối chiếu với font bundled và ngoại lệ component trong thiết kế đã duyệt; không đổi font/radio/tab chỉ để làm detector dành cho web xanh. Java/FXML không phải DOM/CDP: render/bounds native là bằng chứng riêng.
- Skill báo `PRODUCT.md` JavaFX không thuộc enum nền tảng web/iOS/Android và sidecar thiết kế cũ hơn `DESIGN.md`. Không khai sai nền tảng hoặc tự sửa drift ngoài yêu cầu; có thể chạy `impeccable document` để làm mới sidecar khi được yêu cầu.

## Soi nguồn và công bố

OpenQodex 0.16.0/Gitleaks 8.21.2 quét lại đúng Git tree nguồn cuối **2.917 tệp**, không profile seller/Git history; chỉ chọn scanner Gitleaks, không AI reviewer. Có 10 candidate secret đã đối chiếu với các file không đổi và triage của 1.6.5: public URL/key, dữ liệu giả và marker kiểm tra; không xác nhận auth secret thật. Công cụ còn nêu 19 advisory suppression import của skill Python không đổi; đó không phải Ruff đã chạy. Không gọi exit 0 là toàn bộ scanner/CodeQL đạt.

Rà soát CI lại trên đúng nguồn cuối 1.6.6, [run `42408547869`](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42408547869): lint/audit và CodeQL JavaScript đạt; Java xuất SARIF nhưng gate giữ thất bại vì tám finding cũ, gồm một high tại SHA-1 thumbprint định danh chứng thư CryptoPro. Đối chiếu đủ tám rule/file/line và byte nguồn với binary-source 1.6.5, không có finding mới. Không thay query/ngưỡng/gate hoặc đổi SHA-1 store ID thành thuật toán khác để né scanner. [Chi tiết và hash SARIF](2026-11-07-vncode-166-security.json); các medium/giới hạn vẫn giữ trong đánh giá trước. Lượt trước guard `42407363087` có cùng kết quả, không dùng thay lượt nguồn cuối.

Người dùng đã yêu cầu phát hành 1.6.6 lên Latest sau khi hoàn tất. **Đang chuẩn bị build Windows đầy đủ/nâng cấp từ 1.6.5**; chưa đổi Latest tại mốc ghi này. Không ghi đè release 1.6.5 immutable, không prune bản cũ. Chưa có lượt ghi API seller mới, mua KIZ, ký/công bố Catalog, kích hoạt license hoặc Gmail trong lượt này.
