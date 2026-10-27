# Tăng tốc PDF WB/Ozon và kiểm tra nhận đơn Ozon — 27.10.2026

Chủ dự án bổ sung yêu cầu khi bản sửa sản phẩm đang được kiểm tra để phát hành. Nguồn cuối `9c026d8892519bb991a7ec49684daaff2fa23be5`, hash cây `aef72d30f8e04a3c84a6abeb6c444bca13331e9958de36f8cd0b6ffc9b02b0e7`, phiên bản 1.5.18. Giữ toàn bộ sửa sản phẩm và kết quả 10 lệnh API thật; các tệp service/reader/adapter/giao diện sửa sản phẩm không thay đổi so với lượt thật `02559cd`.

## Thay đổi

- WB trước đây tạo PNG QR trung tâm 1.000 px và QR nhỏ cho mỗi đơn. Nay mã hóa một lần ở độ phân giải module, tạo một form vector dùng chung cho đủ năm vị trí; giữ UTF-8, mức sửa lỗi H, khoảng trắng, kích thước và hai lựa chọn thứ tự trang. Không giảm độ rõ của mã để tăng tốc.
- Font nhãn được tạo và đăng ký qua cache riêng từng `PdfDocument` của iText, dùng tài nguyên Arial đi kèm. Nhãn Ozon và nhãn KIZ dùng chung một font/subset trong cùng PDF; không chia sẻ đối tượng font giữa các PDF hoặc giữ font bằng cache toàn cục có thể rò bộ nhớ.
- Khi PDF Ozon chung đã được kiểm tra danh tính và một đơn có đúng một trang, ghép trang đó trực tiếp sang PDF đích. Bỏ bước ghi/mở lại một PDF riêng cho mỗi đơn. Các trang đảo thứ tự vẫn được lấy theo danh tính đơn; trang không xác định được đơn hoặc đơn nhiều trang giữ đường tải riêng/lọc nhãn sản phẩm hiện có.
- Giữ giấy phép, owner, reservation KIZ, đối soát yêu cầu mới, lịch sử in và việc chỉ đánh dấu in sau khi xuất thành công. Không tăng số request mạng hoặc tự tạo lại tác vụ chưa biết kết quả.

## Đo với dữ liệu mô phỏng

Cùng máy/JDK, heap, fixture và 10 đơn làm nóng; sau đó dựng 1.000 nhãn, không tính thời gian sàn tạo/tải vận đơn, mua hoặc giữ KIZ. WB tạo cả nhãn mẫu và sticker; Ozon đo bộ dựng nhãn kết hợp KIZ/barcode.

| Bộ dựng PDF | Trước | Sau | Nhanh hơn | Kích thước trước → sau |
| --- | --- | --- | --- | --- |
| WB, 1.000 đơn | 48,02 giây | 14,18 giây | 3,39 lần | 6.629.355 → 3.217.644 byte |
| Ozon, 1.000 nhãn | 5,10 giây | 1,86 giây | 2,75 lần | 31.452.982 → 1.769.534 byte |

Đây là đo phần dựng PDF trên fixture, không phải cam kết tốc độ tải API/in máy seller. Đường ghép Ozon trực tiếp còn loại bỏ I/O PDF từng đơn; ca 1.000 đơn xác nhận đường này không gọi hàm ghi/tải file riêng.

## Nhận đơn Ozon

Đã rà soát `OzonUnitShipService`, `OzonAcceptanceKizService`, `OzonShipService` và luồng nút giao diện. Giữ quy trình: đọc yêu cầu mới → tách đơn nhiều đơn vị trước khi gán mã → giữ KIZ đúng sản phẩm → validate/set → đọc trạng thái đúng product/exemplar/fingerprint → chỉ ship khi sàn đã chấp nhận. Mã chờ/đỏ chặn nhận; khi chờ chuyển thành đạt, tiếp tục receipt hiện có và không set/ship lặp. Giao diện đưa đơn nhận xong vào Đơn mới; in thành công chuyển Đang giao hàng, lỗi/hủy giữ đơn chưa in.

API thật v4 list/unfulfilled và v3 get chi tiết đơn đọc được, trả HTTP 200; dữ liệu có trường optional KIZ đúng dạng đang xử lý. Lượt đọc chỉ lấy trang đầu 100 đơn ở mỗi nguồn, không nhận hoặc gán KIZ cho đơn khách thật và không kết luận toàn cửa hàng hết đơn mới. Một probe ban đầu dùng v3 cũ trả 404; mã app đang dùng v4, không phải lỗi endpoint trong app.

## Kiểm chứng và phát hành

**115/115 ca liên quan đạt** trước khi chạy full suite nguồn cuối. Quét đủ năm QR WB ở 203/300 dpi và hai thứ tự trang; quét KIZ GS1 + barcode Ozon; font dùng lại trong lô và PDF thứ hai mở sau PDF thứ nhất. Kiểm tra PDF chung 1.000 trang đảo thứ tự, xuất 1.000 đơn không file trung gian, đường dự phòng không rõ danh tính, lỗi/hủy, hồi phục lịch sử in và nhận đơn KIZ chờ/đỏ/tách đơn.

[Biên nhận và số đo](../validation/2026-10-27-pdf-speed-ozon-acceptance-verify.json). Full suite nguồn trước đã đạt nhưng được giữ riêng trong `2026-10-27-vncode-1518-before-pdf-baseline.json`; Windows run `40556964700` bị hủy theo yêu cầu bổ sung, không dùng artifact đó phát hành. Build cuối chốt `40561909841`; full suite/native/bộ cài/Latest ghi trong bộ biên nhận phát hành 1.5.18, không suy ra từ 115 ca này.
