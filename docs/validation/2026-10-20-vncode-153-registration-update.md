# VNCode 1.5.3: đăng ký tuần tự và bàn giao cập nhật

Bản nền là Published 1.5.2, source `6fe8ef21871657a38151897ddc13c3b1b5028fd5`. Mẫu đăng ký mặc định, phạm vi TN VED của giấy tờ, giấy tờ của seller, dữ liệu sản phẩm/KIZ đã đồng bộ và nhánh FBS của WB được giữ nguyên. Nhánh FBS Ozon bổ sung đối soát shipment đang chờ sau thao tác đóng gói từng đơn vị.

## Nguyên nhân và thay đổi

- Bộ lọc trước đây coi trạng thái cục bộ hoặc GTIN là bằng chứng có thẻ Catalog. Phân loại mới giữ các checkpoint chắc chắn chưa gửi trong **Chưa tạo**, chỉ nhận feed đã được Catalog chấp nhận hoặc quan sát Catalog thật vào **Đang xử lý**. Các yêu cầu có kết quả chưa xác định được tách để đối soát, tránh gửi trùng/cấp GTIN lần nữa.
- Vòng đăng ký cũ phụ thuộc vào controller, bộ lọc và thời gian sống trang. Lựa chọn nay được lưu đầy đủ trong SQLite trước khi gửi; worker xử lý lần lượt từng thẻ, lưu GTIN/payload trước POST, rồi lưu `feed_id` trước khi tăng tiến trình. Duyệt/ký là công đoạn tiếp theo của monitor hiện có, không chặn gửi thẻ kế tiếp.
- Cả WB và Ozon có báo cáo toàn bộ lựa chọn và tiến trình bền vững. Chỉ `feed_id` đã lưu mới tăng số đã gửi; không coi cấp GTIN, xếp hàng hoặc HTTP 200 thiếu danh tính là thành công.
- Trang WB/Ozon tiếp tục cho tìm kiếm, đổi bộ lọc và xem thẻ đang chờ duyệt. Nhãn tiến trình đặt dưới hàng nút đăng ký, viên thuốc xác định article/size vừa gửi và tự tắt khi hoàn tất. Ô thao tác ngoài cùng bên phải của thẻ chờ duyệt hiển thị **Đang chờ duyệt**.
- Khi mất mạng, trạng thái chắc chắn chưa gửi được lưu và thử tiếp; các yêu cầu có kết quả chưa xác định phải đối soát. Mở lại app sẽ phục hồi hàng đợi. Đóng cửa sổ khi có hàng đợi có thể thu xuống khay hệ thống với Mở lại/Thoát; không thể gửi API khi không có mạng hoặc tiến trình đã tắt.
- Updater giữ một tác vụ/hộp tiến trình trong lúc tải, xác minh, sao lưu và bàn giao. 100% chỉ xuất hiện khi đã sẵn sàng cài. Bỏ lần kiểm tra toàn vẹn SQLite trùng, dùng lại rollback đã xác minh chỉ khi checksum snapshot cùng toàn bộ DB/WAL còn khớp; dữ liệu đổi sẽ sao lưu lại. Khóa dữ liệu được giữ đến hết sao lưu. EXE chuyển tiếp `/passive /norestart` của jpackage; bước Windows từ 1.5.2 phải xác minh đúng EXE phát hành, MSI nhúng và dữ liệu giữ nguyên. Vẫn giữ kiểm tra checksum/kích thước/TLS và phục hồi đầy đủ. Cập nhật bị chặn khi còn công việc ghi/đăng ký đang chạy.
- Ozon Nhận tất cả đóng gói mỗi đơn vị trong một package qua `/v4/posting/fbs/ship`, lưu kế hoạch và số shipment Ozon trả về. Xác nhận cần đọc lại các shipment con đúng đơn/kho/SKU, mỗi shipment một đơn vị, đủ số lượng và đúng trạng thái. Kết quả không rõ được lưu để đối soát, không tự lặp POST. Đồng bộ Ozon nền đọc tối đa ba GET cho hàng đợi mỗi lượt và vẫn chạy luồng trạng thái thông thường.
- Receipt của bản ứng viên cũ được bổ sung hai cột tiến trình còn thiếu trước khi worker nạp. Khi tập shipment con đã cache chưa bảo toàn số lượng, worker xóa phần đọc lại chưa khớp để đọc dữ liệu mới ở lượt sau; giữ nguyên danh tính shipment/receipt và không tạo thêm lệnh đóng gói.

## Sửa bổ sung sau lượt Windows trước

- Đề xuất Ozon hiện giải các trường bắt buộc và phụ thuộc theo schema Catalog. Article dùng Type «Артикул» khi có đúng một lựa chọn phù hợp. Size Nga có dữ liệu nguồn rõ ràng dùng Type Nga; giá trị chữ XS–XL dùng hệ quốc tế khi schema có đúng một lựa chọn tương ứng. Không suy ra Nga/EU/US từ một size số không có hệ. Tên alias của màu, giới tính, chất liệu, danh mục và quy chuẩn được nhận từ các trường sản phẩm đã đọc; preset phải khớp hợp lệ và không tự chọn giá trị mơ hồ.
- `PRE_SUBMIT_ERROR` Ozon chỉ được dựng lại với GTIN cũ khi checkpoint chứng minh chưa gửi, không có feed/good ID, trạng thái ký hay payload chữ ký. Payload cũ lỗi được thay bằng payload đã kiểm lại theo dữ liệu/schema/giấy tờ mới. Không cấp thêm GTIN, không đi vòng qua guard chủ sở hữu, và không áp dụng đường này cho yêu cầu đã gửi nhưng chưa rõ kết quả.
- WB giữ article gốc của đúng preimage cùng article chuẩn hóa đã gửi để tìm lỗi sàn. Chỉ dùng lỗi đủ mới và article gắn duy nhất với một sản phẩm trong lô; không lấy lỗi của article khác hoặc lỗi cũ làm kết quả cho thẻ hiện tại.
- Đối soát GTIN ghi bền bằng chứng `marketplaceVerified` trước khi kích hoạt ứng viên cục bộ. Nếu kích hoạt lỗi, thẻ có lỗi `GTIN_LOCAL_ACTIVATION_FAILED` riêng và giữ bằng chứng đã đọc từ sàn; những thẻ khác vẫn được xử lý. Bằng chứng này xuất phát từ API đọc lại, không được suy ra từ HTTP 200 của lệnh ghi.
- Viên thuốc WB/Ozon quan sát báo cáo SQLite theo chu kỳ, không gửi lại API ghi. Số đã xác minh trên sàn được giữ ngay cả khi còn lỗi kích hoạt cục bộ. Bộ quan sát ngừng khi mất trang hoặc ngữ cảnh shop/kết nối/dữ liệu thay đổi; thông báo cuối tự tắt, thông báo của trang cũ không được ghi đè thông báo mới.

Các sửa trên đã đóng phạm vi mã và đang chạy nhóm kiểm thử trọng tâm tổng hợp. Chưa ghi số đạt hoặc danh tính build Windows mới khi chưa có kết quả thực tế.

## Bảo toàn đầu vào bản nền

Bốn tệp mẫu đăng ký, lớp template, giấy tờ và quy tắc tariff vẫn trùng byte với 1.5.2. `FbsSyncService.java` đã thay đổi trong phương thức Ozon để thêm đọc lại đóng gói; phương thức WB từ dấu khai báo `private SyncCoordinator.Page wb(` đến trước `private static SyncCoordinator.Page next(` vẫn trùng byte với bản nền. Hash và phạm vi kiểm tra được lưu riêng trong `2026-10-20-vncode-153-local-checks.json`; không mô tả cả tệp FBS là chưa thay đổi.

## Bằng chứng kiểm thử

- HTTP integration dùng `ZnackApiClient` thật qua máy chủ localhost: ba payload đã lưu, POST `/v3/feed` tuần tự, checkpoint trước gửi, danh tính feed của thẻ trước và bộ đếm sau phản hồi. Trường hợp HTTP 200 thiếu `feed_id` không tăng thành công, không tự gửi lại.
- Kiểm thử service: lưu cả lô trước POST; mất mạng sau thẻ đầu; tạo service mới và tiếp tục hai thẻ còn lại không bấm Đăng ký lần nữa; crash trước cấp GTIN và sau lưu payload; chặn đổi chủ sở hữu; tránh gửi lặp khi mất phản hồi.
- Kiểm thử nút JavaFX: lọc Chưa tạo, Chọn tất cả, một lần Đăng ký chuyển đầy đủ lựa chọn sang worker; đổi bộ lọc trong khi worker đang chạy không làm mất lựa chọn đã gửi. Đường dispatcher được thay bằng fixture trong test này; đây không phải thao tác production.
- Kiểm thử toàn bộ lựa chọn hơn 1.000 biến thể giữ số dòng JavaFX hữu hạn; bộ lọc phân biệt checkpoint chưa gửi, feed đã nhận và kết quả chưa xác định.
- Kiểm thử updater: cùng hộp tiến trình qua tải/sao lưu; hủy trước bàn giao; lỗi bàn giao giữ app mở; không báo 100% trong lúc sao lưu; rollback/WAL và schema cũ giữ nguyên. Hành vi thực tế của khay/cài đặt được ghi riêng ở kết quả Windows.

Kết quả hồi quy đầy đủ và bộ cài Windows sẽ được ghi vào `docs/releases/VN-code-1.5.3-status.json` sau khi chạy xong, không sử dụng lại kết quả 1.5.2.

Source a684fcd đã chạy đầy đủ Linux: 2.045 kiểm thử, không thất bại hoặc lỗi, 2 bỏ qua theo nền tảng, 335 bộ kiểm thử. Windows run 39393525107 dừng ở nhóm focused đầu với 83 kiểm thử/1 thất bại `OzonBulkUiTest` dòng 161; chưa chạy full Java và chưa có bộ cài. Sửa fixture đã chuẩn bị để kiểm lại, chưa được coi là đã đạt trên Windows. Các kết quả này thuộc phạm vi trước những sửa Znack/GTIN bổ sung ở trên; source/run mới phải được khóa theo lần đóng mã cuối và kiểm lại đầy đủ.

## Đọc API production và giới hạn

Lúc 09:35 ngày 20.10.2026 (Europe/Moscow), đọc Catalog thật trả HTTP 200: 1.277 thẻ, gồm 1.241 published, 34 lỗi, 2 nháp. Đọc chi tiết một thẻ lỗi cũng trả HTTP 200. API danh sách/chi tiết không trả nguyên văn lỗi kiểm duyệt hoặc `feed_id`; endpoint trạng thái cần feed ID được lưu trong app của seller.

Đọc lại lúc `2026-10-20T08:30:45Z` (11:30 Moscow) lấy đủ hai trang và chi tiết cả 34 thẻ lỗi: nhóm TN VED 6110 có 24 thẻ, 6106 có 7 và 6103 có 3. Các trường nguyên nhân kiểm duyệt và feed ID đều không có dữ liệu; không có bằng chứng để gán cùng một nguyên nhân cho tất cả thẻ lỗi. [Bản tóm tắt chỉ đọc](2026-10-20-vncode-153-catalog-readonly-recheck.json) chỉ giữ tổng số, cấu trúc trường và HTTP; không có mã thẻ, GTIN hay khóa.

Đối chiếu lúc `2026-10-20T08:44:39Z` (11:44 Moscow) đọc đủ bốn trang WB (100/100/100/88), gồm 388 thẻ, 1.982 biến thể và 3.777 barcode. Chỉ một biến thể có barcode thuộc tập 1.241 GTIN Catalog published đang đọc; 1.981 biến thể không khớp tập này. Không có GTIN published dùng chung nhiều biến thể hoặc nhiều nhãn size trong mẫu đọc. [Bản độ phủ đã làm sạch](2026-10-20-vncode-153-wb-catalog-coverage.json) không chứa các mã gốc. Phép đối chiếu chỉ xác nhận thành viên trong tập mã của kết nối Catalog hiện tại; không xác nhận item/màu/size, sở hữu hay phạm vi giấy tờ, không chứng minh đã ghi GTIN bằng bản ứng viên, và không suy ra sản phẩm chưa đăng ký ở nơi khác hoặc tài khoản đang sai.

Lượt này chưa gửi mới/cấp GTIN/ký bằng tài khoản production. Đường đăng ký production vẫn xác minh license, chủ sở hữu và CryptoPro; không tắt các kiểm tra đó để làm test xanh. Kiểm thử HTTP localhost không chứng minh một thẻ mới đã xuất hiện trên trang Честный знак của seller. Chưa đo thời gian cập nhật trên máy seller. Lượt chuyển giao từ bản đang cài 1.5.2 vẫn chạy mã updater cũ; bản mới chỉ sửa được mã sau khi được cài.

## Quyền kiểm thử do chủ dự án cấp

Chủ dự án cho phép phiên kiểm thử có quyền license không giới hạn, áp dụng cả WB và Ozon. Bộ kiểm thử dùng authority ký tạm `LicenseDomainFixture`/`LicenseTestBootstrap` đã có, cấp mọi quyền ứng dụng trong dữ liệu cô lập; không đưa khóa authority kiểm thử vào bộ cài. 71 ca cuối trên source 9dfcae6 đã đạt, gồm cả WB, Ozon, nút đăng ký, HTTP transport và guard monitor khôi phục. Điều này gỡ phần chặn quyền license trong phiên kiểm thử, không cung cấp chứng thư/khóa CryptoPro hoặc biến chữ ký fixture thành chữ ký production. Cờ nghiệm thu license production vẫn được tách riêng.
