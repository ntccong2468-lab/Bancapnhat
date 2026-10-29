# Sửa lỗi nhận đơn Ozon: action null — 29.10.2026

Bản 1.5.19 có thể dừng khi đơn đã có job KIZ cục bộ nhưng chưa có lịch sử `accept_kiz`: repository trả `null`, luồng nhận gọi `action.isBlank()`. Đã tái hiện trong môi trường giả lập với một request đọc đơn, không có request gán KIZ hoặc nhận đơn trước lỗi.

Sửa tại `OzonAcceptanceKizService` bằng cách chuyển lịch sử `null` thành chuỗi rỗng. Giữ nguyên nhánh đối soát nếu có dấu đã gửi trước đó; không đổi hợp đồng nullable của repository, không giải phóng hay tạo lại KIZ khi chưa rõ kết quả.

Hồi quy liên quan 64/64; thêm một ca HTTP localhost gồm 101 job có sẵn, 101/101 đơn được gán 101 KIZ khác nhau và nhận, các mã đã sử dụng được ghi nhận đúng. Ba ca riêng kiểm tra job CREATED không có action, KIZ đã giữ được dùng lại, và mất lịch sử sau lần gửi phải đọc lại thay vì gửi KIZ trùng. Tổng 65 ca duy nhất, không cộng các ca con đã bao gồm trong lượt 64. Thời gian 38,934 giây là cho lô giả lập, không phải thời gian Ozon thật.

API Ozon thật chỉ đọc trả 286 đơn qua ba trang (100/100/86), gồm 16 chờ nhận và 270 đang giao. Đọc chi tiết 16/16 thành công; Ozon báo có thể chuyển tiếp, mỗi đơn một đơn vị và có KIZ tùy chọn. Phản hồi chi tiết không chứa mã KIZ, không chứng minh kho seller trống. Kết nối hiện tại trả 404 với đơn trong ảnh, chưa xác định nó có thuộc cửa hàng này. Không có dữ liệu kho KIZ hoặc cấu hình miễn trừ của app seller trong môi trường; không gửi lệnh nhận hoặc KIZ thật, do đó chưa xác nhận nhận thật 16 đơn.

Chưa build hoặc phát hành bản mới; Latest vẫn 1.5.19. Khi phát hành cần tăng phiên bản và kiểm tra cài đặt/nâng cấp. [Biên nhận](../validation/2026-10-29-ozon-null-action-verify.json).

Người dùng yêu cầu nhận thật. Đã đọc exemplar/status của 16/16 đơn: HTTP 200, trạng thái ship_available, không thấy mark khác rỗng. Không thể suy ra chính sách miễn trừ hoặc mã KIZ của app từ phản hồi này. Tệp zl.txt trước đó đã bị Ozon từ chối 200/200 do thiếu crypto nên không dùng lại. Cần kho KIZ đầy đủ của đúng cửa hàng hoặc thông tin miễn trừ trước khi nhận thật; chưa thực hiện ghi đơn/KIZ.
