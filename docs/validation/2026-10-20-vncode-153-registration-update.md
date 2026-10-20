# VNCode 1.5.3: đăng ký tuần tự và bàn giao cập nhật

Bản nền là Published 1.5.2, source `6fe8ef21871657a38151897ddc13c3b1b5028fd5`. Các thay đổi dưới đây không thay mẫu đăng ký mặc định, phạm vi TN VED của giấy tờ, giấy tờ của seller, dữ liệu sản phẩm đã đồng bộ hay cơ chế KIZ/FBS.

## Nguyên nhân và thay đổi

- Bộ lọc trước đây coi trạng thái cục bộ hoặc GTIN là bằng chứng có thẻ Catalog. Phân loại mới giữ các checkpoint chắc chắn chưa gửi trong **Chưa tạo**, chỉ nhận feed đã được Catalog chấp nhận hoặc quan sát Catalog thật vào **Đang xử lý**. Các yêu cầu có kết quả chưa xác định được tách để đối soát, tránh gửi trùng/cấp GTIN lần nữa.
- Vòng đăng ký cũ phụ thuộc vào controller, bộ lọc và thời gian sống trang. Lựa chọn nay được lưu đầy đủ trong SQLite trước khi gửi; worker xử lý lần lượt từng thẻ, lưu GTIN/payload trước POST, rồi lưu `feed_id` trước khi tăng tiến trình. Duyệt/ký là công đoạn tiếp theo của monitor hiện có, không chặn gửi thẻ kế tiếp.
- Cả WB và Ozon có báo cáo toàn bộ lựa chọn và tiến trình bền vững. Chỉ `feed_id` đã lưu mới tăng số đã gửi; không coi cấp GTIN, xếp hàng hoặc HTTP 200 thiếu danh tính là thành công.
- Trang WB/Ozon tiếp tục cho tìm kiếm, đổi bộ lọc và xem thẻ đang chờ duyệt. Nhãn tiến trình đặt dưới hàng nút đăng ký, viên thuốc xác định article/size vừa gửi và tự tắt khi hoàn tất. Ô thao tác ngoài cùng bên phải của thẻ chờ duyệt hiển thị **Đang chờ duyệt**.
- Khi mất mạng, trạng thái chắc chắn chưa gửi được lưu và thử tiếp; các yêu cầu có kết quả chưa xác định phải đối soát. Mở lại app sẽ phục hồi hàng đợi. Đóng cửa sổ khi có hàng đợi có thể thu xuống khay hệ thống với Mở lại/Thoát; không thể gửi API khi không có mạng hoặc tiến trình đã tắt.
- Updater giữ một tác vụ/hộp tiến trình trong lúc tải, xác minh, sao lưu và bàn giao. 100% chỉ xuất hiện khi đã sẵn sàng cài. Bỏ lần kiểm tra toàn vẹn SQLite trùng, dùng lại rollback đã xác minh chỉ khi checksum snapshot cùng toàn bộ DB/WAL còn khớp; dữ liệu đổi sẽ sao lưu lại. Khóa dữ liệu được giữ đến hết sao lưu. MSI dùng chế độ passive của Windows Installer, vẫn giữ kiểm tra checksum/kích thước/TLS và phục hồi đầy đủ. Cập nhật bị chặn khi còn công việc ghi/đăng ký đang chạy.

## Bằng chứng kiểm thử

- HTTP integration dùng `ZnackApiClient` thật qua máy chủ localhost: ba payload đã lưu, POST `/v3/feed` tuần tự, checkpoint trước gửi, danh tính feed của thẻ trước và bộ đếm sau phản hồi. Trường hợp HTTP 200 thiếu `feed_id` không tăng thành công, không tự gửi lại.
- Kiểm thử service: lưu cả lô trước POST; mất mạng sau thẻ đầu; tạo service mới và tiếp tục hai thẻ còn lại không bấm Đăng ký lần nữa; crash trước cấp GTIN và sau lưu payload; chặn đổi chủ sở hữu; tránh gửi lặp khi mất phản hồi.
- Kiểm thử nút JavaFX: lọc Chưa tạo, Chọn tất cả, một lần Đăng ký chuyển đầy đủ lựa chọn sang worker; đổi bộ lọc trong khi worker đang chạy không làm mất lựa chọn đã gửi. Đường dispatcher được thay bằng fixture trong test này; đây không phải thao tác production.
- Kiểm thử toàn bộ lựa chọn hơn 1.000 biến thể giữ số dòng JavaFX hữu hạn; bộ lọc phân biệt checkpoint chưa gửi, feed đã nhận và kết quả chưa xác định.
- Kiểm thử updater: cùng hộp tiến trình qua tải/sao lưu; hủy trước bàn giao; lỗi bàn giao giữ app mở; không báo 100% trong lúc sao lưu; rollback/WAL và schema cũ giữ nguyên. Hành vi thực tế của khay/cài đặt được ghi riêng ở kết quả Windows.

Kết quả hồi quy đầy đủ và bộ cài Windows sẽ được ghi vào `docs/releases/VN-code-1.5.3-status.json` sau khi chạy xong, không sử dụng lại kết quả 1.5.2.

## Đọc API production và giới hạn

Lúc 09:35 ngày 20.10.2026 (Europe/Moscow), đọc Catalog thật trả HTTP 200: 1.277 thẻ, gồm 1.241 published, 34 lỗi, 2 nháp. Đọc chi tiết một thẻ lỗi cũng trả HTTP 200. API danh sách/chi tiết không trả nguyên văn lỗi kiểm duyệt hoặc `feed_id`; endpoint trạng thái cần feed ID được lưu trong app của seller.

Lượt này chưa gửi mới/cấp GTIN/ký bằng tài khoản production. Đường đăng ký production vẫn xác minh license, chủ sở hữu và CryptoPro; không tắt các kiểm tra đó để làm test xanh. Kiểm thử HTTP localhost không chứng minh một thẻ mới đã xuất hiện trên trang Честный знак của seller. Chưa đo thời gian cập nhật trên máy seller. Lượt chuyển giao từ bản đang cài 1.5.2 vẫn chạy mã updater cũ; bản mới chỉ sửa được mã sau khi được cài.

## Quyền kiểm thử do chủ dự án cấp

Chủ dự án cho phép phiên kiểm thử có quyền license không giới hạn, áp dụng cả WB và Ozon. Bộ kiểm thử dùng authority ký tạm `LicenseDomainFixture`/`LicenseTestBootstrap` đã có, cấp mọi quyền ứng dụng trong dữ liệu cô lập; không đưa khóa authority kiểm thử vào bộ cài. 71 ca cuối trên source 9dfcae6 đã đạt, gồm cả WB, Ozon, nút đăng ký, HTTP transport và guard monitor khôi phục. Điều này gỡ phần chặn quyền license trong phiên kiểm thử, không cung cấp chứng thư/khóa CryptoPro hoặc biến chữ ký fixture thành chữ ký production. Cờ nghiệm thu license production vẫn được tách riêng.
