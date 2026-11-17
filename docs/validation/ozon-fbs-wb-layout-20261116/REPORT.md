# Đăng ký Znack Ozon và giao diện FBS theo mẫu WB — 16.11.2026

Nguồn cuối iteration3 đã qua kiểm thử và đóng gói kiểm chứng. Các biên nhận chính bên dưới thuộc đúng nguồn cuối; bằng chứng iteration1 được giữ riêng làm lịch sử, không cộng vào kết quả mới.

Yêu cầu: chụp đăng ký Znack Ozon trước, sau đó chỉ đổi giao diện đóng đơn FBS Ozon theo FBS WB; giữ các phần và chức năng khác. Thực hiện trên nhánh `feature/ozon-znack-order-kiz-20261116`, nền `a7c4d008ae450a82b6cfbfbb79e5532eca6bfa1e` cùng toàn bộ chỉnh sửa chưa phát hành có sẵn. Không tăng phiên bản, commit, push hoặc phát hành.

## Ảnh đăng ký Znack trước khi sửa

Đã chụp và đưa người dùng xem cả hai ảnh này trước khi sửa mã giao diện FBS:

| Nền tối | Nền sáng |
| --- | --- |
| [Mở ảnh](images/znack-registration-ozon-before-dark-1600x1000.png) | [Mở ảnh](images/znack-registration-ozon-before-light-1600x1000.png) |

Đây là ảnh chụp thành phần JavaFX thật của VN Code, dùng 24 sản phẩm mẫu trong hồ sơ thử. Header, sidebar và theme dùng mã production; shell tái tạo bố cục Home. Không phải ảnh dữ liệu cửa hàng thật hoặc máy Windows của seller, và không phải một lượt chạy HomeController/Launcher. Màn hình đăng ký Znack không bị nhiệm vụ này sửa.

[Biên nhận trước sửa](before-capture-summary.json) ghi 26 PNG được giữ ở thư mục công việc ngoài repo. Sáu ảnh chính được lưu cùng báo cáo.

## Phần đã sửa

Ozon trước đây dùng preset bảng chi tiết shipment WB (`supply-orders`, `ozon-posting-table`), trong khi màn hình cần đối chiếu là FBS `PackingController` và `packing-view.fxml`. Đã dùng đúng màn hình FBS WB đang có làm mẫu.

- Thanh tiêu đề, nút đồng bộ, thứ tự tab → bộ lọc → bảng → thao tác theo bố cục WB; bỏ khoảng trống do dòng trạng thái rỗng.
- Năm cột chính; hàng một sản phẩm cao 70 px, ảnh 36×48 px. Tại cửa sổ 1200×720, chiều rộng cột thực tế hai bảng đều là `[46, 228, 127, 594, 159]`.
- Giữ các tab và trạng thái riêng của Ozon. Cột thao tác in/trạng thái vẫn có ở các tab cần chúng.
- Giữ STT, số posting/đơn gốc, barcode Ozon, GTIN, SKU, article, màu, size, số lượng, hạn xử lý, thông tin KIZ/KN3 trong dòng chi tiết, tooltip, nội dung hỗ trợ tiếp cận hoặc bảng chi tiết. Không bỏ dữ liệu để làm bảng gọn.
- Chuyển bốn tùy chọn sắp xếp đang có vào menu “Sắp xếp”; giữ nguyên node, mặc định, listener và thiết lập đã lưu. Không thêm bộ lọc danh mục chỉ lọc trang hiện tại vì nó sẽ sai với chọn tất cả và phân trang của Ozon.
- Giữ ô tìm kiếm dùng chung và nội dung tìm kiếm khi đổi tab; giữ phân trang, chọn tất cả theo bộ lọc và các nút tác vụ.

Hai màn có cùng bố cục và các kích thước chung đã kiểm chứng. Tên trạng thái, số tab và thao tác đặc thù Ozon được giữ theo yêu cầu bảo toàn chức năng; không thay chúng thành nghiệp vụ WB.

Đã xác định và sửa độ lệch dọc 2 px: WB giữ spinner ẩn trong bố cục, còn Ozon để spinner không tham gia layout. Ozon nay dùng cùng ProgressIndicator trực tiếp với pref/max 18 và giữ managed, chỉ visibility theo busy; không chèn padding 2 px hoặc gắn min-height 36 đo từ Linux. Đồng thời sửa ảnh từ căn giữa sang căn trái như WB. [Kiểm thử native cuối](focused-ui-result-final.json) đo **0 px** chênh Y/height của root/header/title/refresh/tab/filter/table ở cả tối/sáng và rảnh/đang tải; ảnh cũng trùng vị trí tương đối `[10, 7]`, kích thước 36×48. [Phân tích và sửa](implementation-final.md), [hình học](absolute-geometry-final.json).

## Ảnh FBS sau sửa

| Nền tối | Nền sáng |
| --- | --- |
| [Mở ảnh](images/final/ozon-final-dark-1600x1000.png) | [Mở ảnh](images/final/ozon-final-light-1600x1000.png) |

Ảnh sau sửa được chụp từ JAR mới SHA-256 `b3ea7e8e20eeda53c38754f38509e3364267d458e297216a18966c4f8eab315b`, không dùng JAR trước sửa. [Biên nhận ảnh sau](final-capture-summary.json) ghi phạm vi và nguồn ảnh. Các ảnh này vẫn là JavaFX với dữ liệu mẫu, không chứng minh API nhận/in đơn thật.

Đối chiếu trực tiếp: [FBS WB cùng JAR](images/final/packing-final-dark-1600x1000.png), [Ozon trước sửa](images/ozon-before-dark-1600x1000.png), [Ozon sau sửa ở cửa sổ nhỏ](images/final/ozon-final-light-1200x720.png). Bộ ảnh sau có 24 PNG: hai nền, bốn trạng thái đơn và kho KIZ, menu sắp xếp, các màn chính ở hai kích thước.

Khung bảng WB/Ozon trên ảnh cuối trùng `x=241, y=259, width=1330, height=688` ở cả hai nền. [Biên nhận hình học cùng JAR](same-jar-table-geometry-final.json) cũng ghi việc JavaFX phân phối lại vài pixel cột sau các chuỗi resize khác nhau; phép đối chiếu độ rộng chuẩn trong 23 kiểm thử dùng cùng điều kiện viewport mới. [91 tệp chứng cứ trước](previous-evidence-preservation-final.json) giữ nguyên hash; không thay ảnh cũ bằng ảnh mới.

## Kiểm chứng

| Kiểm tra đã chạy | Kết quả | Bằng chứng |
| --- | --- | --- |
| Kiểm thử trước sửa để xác định khác biệt | 9 ca: 7 lỗi hình học, 2 ca bảo toàn đạt; không lỗi môi trường | [Kết quả tập trung](focused-ui-result.json) |
| Giao diện WB/Ozon, dữ liệu, thao tác, bàn phím/theme | 23/23 đạt, gồm hình học tuyệt đối và vị trí ảnh | [Kết quả tập trung](focused-ui-result-final.json) |
| `clean verify` riêng trên mã cuối, 64 lớp liên quan | 394 ca, 0 failure/error/skip; exit 0 | [JUnit tổng hợp](combined-result-final.json), [lựa chọn kiểm thử](test-selection-final.json) |
| JAR so từng entry với class/resource mới biên dịch | Đạt; CRC, đường dẫn, manifest, 74 thư viện; không lẫn fixture | [JAR](jar-integrity-final.json) |
| Chạy thật `Launcher.main` từ JAR mới trên Linux/Xvfb | Mở workspace 6,561 giây; đóng bình thường, nhả khóa dữ liệu; SQLite integrity OK/schema 6/0 lỗi FK | [Launcher](launcher-result-final.json) |
| Review bảo toàn độc lập | 140 vùng khớp byte và một vùng chỉ đổi setter managed của spinner; 323 tệp production và 77 `fx:id`/handler cũ giữ nguyên | [Review](independent-review-final.json) |
| Kiểm kê nguồn cuối và kiểm tra tài liệu | Không mất tệp baseline, không thay ngoài phạm vi; diff check và ECC validator | [Bàn giao](handoff-integrity.json) |

394 ca có bao gồm 23 ca tập trung; không cộng hai số. Lượt 393/64 và 22 ca iteration1 là bằng chứng trước khi sửa nốt header/ảnh, không thay thế cổng cuối này. Các lượt 1.576/1.954 và kiểm thử Windows trong các nhiệm vụ trước là bằng chứng lịch sử, không dùng làm kết quả của lần sửa này. Kiểm thử PDF có fixture lô 100/1.000, không phải đo tốc độ API Ozon thật.

JAR 7.810.846 byte, 2.080 entry production, 2.039 class Java 25. Lượt Launcher dùng hồ sơ tổng hợp không cửa hàng, license thử và chặn mạng ra ngoài; không kích hoạt license hoặc gọi seller API. Mọi 1.442 đầu vào build và chín hash nguồn/test đã chốt giữ nguyên qua kiểm thử, đóng gói và chụp ảnh. [Biên nhận kiểm chứng cuối](FINAL-RECEIPT-final.json).

## Phạm vi và bảo toàn

Sao lưu đủ **3.807 tệp** trước sửa; ZIP được kiểm CRC và hash từng tệp, SHA-256 `31273667b102af4a16e01106fd90e147314c0e3c1b66a247c0a776509c018d7c`. Bản sao ở `/workspace/ozon-fbs-wb-layout-20261116/`. [Biên nhận](baseline-backup.json).

Chín tệp nguồn/test trong [hash đã chốt](source-freeze-final.json):

- Hai tệp giao diện Ozon: `OzonDashboardController.java`, `ozon-dashboard-view.fxml`.
- Bốn tệp ngôn ngữ, mỗi tệp chỉ thêm khóa `ozon.fbs.sort_options`; giá trị cũ giữ nguyên.
- Hai kiểm thử giao diện hiện có cập nhật kỳ vọng bố cục; thêm `OzonWbPackingParityTest` với đối chiếu native trực tiếp.

Thêm tài liệu và cập nhật ba tệp bộ nhớ dự án để AI tiếp tục đúng trạng thái. Không sửa WB, CSS chung, kiến trúc, schema, dependency, API, CI, license, phiên bản hoặc các màn khác. Các luồng nhận đơn/in theo lô, chọn trên nhiều trang, KIZ/mua/tiếp tục ký, GTIN và mở đăng ký Znack giữ nguyên vùng mã nghiệp vụ đã đối chiếu. [Phạm vi triển khai](implementation-final.md), [review](independent-review-final.md).

Đã dùng Taste và Impeccable theo thiết kế native được duyệt. Trình nạp ngữ cảnh Impeccable tự động chưa chạy; đã thông báo và dùng cách đọc trực tiếp PRODUCT/DESIGN/tài liệu skill. Không cài engine, hook hoặc dependency mới.

## Giới hạn và việc tiếp theo

Kiểm tra ảnh bổ sung phát hiện hai giới hạn được giữ rõ:

- Đăng ký Znack ở 1200×720 có thanh công cụ xuống dòng chạm vùng bảng và nút ngoài cùng bị cắt trong viewport. Đây là màn nguồn không sửa trong nhiệm vụ này; ảnh 1600×1000 đọc được. [Ảnh cửa sổ nhỏ](images/final/znack-registration-ozon-final-light-1200x720.png).
- Ảnh Robot của menu tối có mép nền trắng trên Xvfb không có compositing. Kiểm tra cùng điều kiện cho thấy menu danh mục WB hiện có cũng như vậy; JavaFX báo không hỗ trợ `TRANSPARENT_WINDOW`. Không kết luận đây là lỗi mới của Ozon; cần xem trên Windows để xác nhận cách vẽ cuối. [Đối chiếu môi trường popup](popup-diagnostic.json).

Chưa nghiệm thu trên Windows/CryptoPro/DPAPI, cửa hàng thật, mua KIZ có phí hoặc API ghi nhận đơn/in/đăng ký. Không chạy lại toàn bộ bộ kiểm thử app hoặc CodeQL trong lần sửa giao diện này. Những bằng chứng này xác nhận phạm vi đã kiểm, không bảo đảm mọi lỗi tương lai đều bị loại bỏ.

Mã nguồn vẫn 1.6.10; nhiệm vụ này chưa tạo bộ cài hoặc cập nhật Latest. Khi có yêu cầu phát hành, dùng toàn bộ nguồn đã lưu, chạy cổng Windows/đóng gói phù hợp và đối chiếu lại trạng thái release trước khi xuất bản.
