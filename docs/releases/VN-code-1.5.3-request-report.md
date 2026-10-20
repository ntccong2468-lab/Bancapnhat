# Báo cáo VNCode 1.5.3

## Thay đổi đã thực hiện

- Znack WB/Ozon lưu lô đăng ký, gửi lần lượt từng thẻ và ghi tiến độ sau khi Catalog nhận feed. Bộ lọc Chưa tạo không coi dữ liệu mới xếp hàng là thẻ đã gửi; Đang xử lý giữ các thẻ chờ duyệt và trạng thái đọc lại từ Catalog.
- Trang đăng ký tiếp tục phản hồi khi chạy lô; báo cáo giữ đầy đủ lựa chọn. Gửi chưa rõ kết quả phải đối chiếu, không tự cấp thêm GTIN hoặc gửi trùng.
- Hàng đợi tiếp tục khi có mạng hoặc mở lại app. Đóng cửa sổ lúc đang chạy có thể giữ app trong khay hệ thống với nút Mở lại/Thoát; nếu không tạo được biểu tượng khay, cửa sổ vẫn hiện và báo lý do.
- Giảm phần kiểm tra/sao lưu lặp ở khởi động và bàn giao bộ cài, giữ kiểm tra toàn vẹn và dữ liệu SQLite/WAL. Giữ mẫu đăng ký, quy tắc phạm vi giấy tờ/TN VED và dữ liệu KIZ của 1.5.2. Nhánh FBS của WB giữ nguyên; nhánh Ozon bổ sung đối soát đóng gói đang chờ.
- Ozon FBO chuyển ba nhãn riêng của mỗi sản phẩm cần KIZ thành một nhãn ghép 58×40 mm theo mẫu đã duyệt. Lịch sử giữ cả hai mã và định danh đã xác minh để in lại khi không còn Catalog hoặc quyền in mới, không lấy thêm KIZ. Ozon FBS dùng cùng mẫu nhãn ghép và giữ nguyên các trang vận chuyển chính thức.
- Ozon Nhận tất cả gửi mỗi đơn vị hàng trong một package, kể cả nhiều đơn vị cùng SKU, để tạo shipment con thật. Lưu kế hoạch trước khi gửi; chỉ xác nhận sau khi đọc lại đúng mã shipment, đơn nguồn, kho, SKU, số lượng và trạng thái. Đồng bộ nền tiếp tục đọc lại các kết quả đang chờ theo ngân sách đọc riêng, vẫn duy trì cập nhật trạng thái FBS thông thường. KIZ đã gán được bảo vệ trước khi tách.
- Đối soát Ozon nâng cấu trúc checkpoint cũ theo cách bổ sung cột, giữ nguyên receipt đã gửi. Tổng số lượng shipment con chưa khớp được đọc mới lại ở lượt sau, tránh giữ mãi dữ liệu cache cũ sau khi Ozon đã sửa; không gửi lại POST đóng gói.
- Kịch bản Windows bổ sung nâng cấp từ bộ cài Published 1.5.2 với URL và SHA-256 cố định. Bước phát hành bắt buộc xác minh kết quả Java và mọi bằng chứng bộ cài/nâng cấp của đúng source và lượt build được khóa trong biên bản phát hành; không dùng lại build của phạm vi trước khi thêm nhãn Ozon.

## Đọc Catalog thật

Lượt đọc chỉ đọc lúc 09:35 ngày 20/10/2026, giờ Moscow, nhận HTTP 200 và 1.277 thẻ: 1.241 đã xuất bản, 34 lỗi, 2 nháp. Không thực hiện POST tạo thẻ mới trong lượt kiểm tra này. Số lượng đọc được không chứng minh nút Đăng ký đã tạo thành công một thẻ mới.

## Kiểm tra metadata

`release-version.mjs` xác nhận hai khai báo phiên bản trong `pom.xml` cùng là 1.5.3. Lượt trước khi thêm FBO/tách shipment, source 58d091a, đạt 2.007 kiểm thử Linux với 2 bỏ qua theo nền tảng và 85 kiểm thử Node. Phần FBO trên source 1524b56 đạt 98 kiểm thử trọng tâm. Phạm vi cuối, gồm hai sửa lỗi checkpoint và cache đối soát, đạt 105 kiểm thử trọng tâm, không lỗi hoặc bỏ qua. Kết quả hồi quy sạch toàn bộ và Windows mới được ghi riêng trong JSON/bằng chứng của đúng source cuối; không dùng lượt Windows của phạm vi cũ làm nghiệm thu cuối.

So sánh mã nguồn xác nhận bốn đầu vào mẫu đăng ký/giấy tờ/TN VED vẫn trùng byte với 1.5.2. Toàn bộ `FbsSyncService.java` có thay đổi trong nhánh Ozon; đoạn phương thức đồng bộ WB vẫn trùng byte với bản nền.

Lượt đọc Ozon thật lúc 11:02 ngày 20/10/2026, giờ Moscow, có năm đơn chờ đóng gói và đều một đơn vị. Không có đơn nhiều đơn vị trong mẫu, không thực hiện POST đóng gói/tách shipment production. Kiểm thử mô phỏng không được ghi nhận là tách shipment thật trên tài khoản seller.

## Chưa hoàn tất

Chưa nghiệm thu thao tác đăng ký/ký thẻ mới trên tài khoản production, chữ ký CryptoPro doanh nghiệp, ghi GTIN thật qua desktop có license production, tách shipment nhiều đơn vị thật trên Ozon hoặc hành vi khay hệ thống trên Windows. Phát hành yêu cầu kiểm tra Windows mới, gồm bàn giao EXE thụ động khi nâng cấp từ 1.5.2, với bằng chứng bộ cài đi kèm.

Phát hành được ràng buộc riêng cho 1.5.3 và đúng source/lượt Windows cuối cùng trong biên bản; chỉ thay Latest sau khi toàn bộ kiểm tra tự động bắt buộc đạt. Publisher 39385466871 đã hủy khi chủ dự án bổ sung mẫu nhãn Ozon, chưa thay Latest. Cổng nghiệm thu production thông thường và kiểm tra license/chữ ký của app giữ nguyên; các phần chưa nghiệm thu ở trên vẫn có cờ false trong báo cáo.

Chủ dự án cấp quyền license không giới hạn cho phiên kiểm thử WB/Ozon. Các kiểm thử chạy bằng authority ký tạm, đủ mọi quyền và không được đóng gói trong app. Quyền kiểm thử không thay thế chứng thư/khóa CryptoPro thực của seller.

Tắt hẳn tiến trình, mất điện hoặc không có mạng không thể tiếp tục gọi API; công việc còn lại được lưu để tiếp tục khi app và kết nối hoạt động lại.
