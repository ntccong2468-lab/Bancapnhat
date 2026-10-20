# Phạm vi bản ứng viên VNCode 1.5.3

## Thay đổi chuẩn bị kiểm chứng

- Znack WB/Ozon lưu lô đăng ký, gửi lần lượt từng thẻ và ghi tiến độ sau khi Catalog nhận feed. Bộ lọc Chưa tạo không coi dữ liệu mới xếp hàng là thẻ đã gửi; Đang xử lý giữ các thẻ chờ duyệt và trạng thái đọc lại từ Catalog.
- Trang đăng ký tiếp tục phản hồi khi chạy lô; báo cáo giữ đầy đủ lựa chọn. Gửi chưa rõ kết quả phải đối chiếu, không tự cấp thêm GTIN hoặc gửi trùng.
- Hàng đợi tiếp tục khi có mạng hoặc mở lại app. Đóng cửa sổ lúc đang chạy có thể giữ app trong khay hệ thống với nút Mở lại/Thoát; nếu không tạo được biểu tượng khay, cửa sổ vẫn hiện và báo lý do.
- Giảm phần kiểm tra/sao lưu lặp ở khởi động và bàn giao bộ cài, giữ kiểm tra toàn vẹn và dữ liệu SQLite/WAL. Không thay mẫu đăng ký, quy tắc phạm vi giấy tờ/TN VED hoặc các luồng dữ liệu và KIZ của 1.5.2.
- Kịch bản Windows đã bổ sung nâng cấp từ bộ cài Published 1.5.2 với URL và SHA-256 cố định; kết quả chạy trên Windows chưa có tại thời điểm tạo báo cáo này.

## Đọc Catalog thật

Lượt đọc chỉ đọc lúc 09:35 ngày 20/10/2026, giờ Moscow, nhận HTTP 200 và 1.277 thẻ: 1.241 đã xuất bản, 34 lỗi, 2 nháp. Không thực hiện POST tạo thẻ mới trong lượt kiểm tra này. Số lượng đọc được không chứng minh nút Đăng ký đã tạo thành công một thẻ mới.

## Kiểm tra metadata

`release-version.mjs` xác nhận hai khai báo phiên bản trong `pom.xml` cùng là 1.5.3. Ba nhóm kiểm thử Node về phiên bản, điểm khởi chạy và hợp đồng bộ cài/nâng cấp chạy 27 kiểm thử, không có lỗi; đây không phải toàn bộ kiểm thử Java hoặc bộ cài Windows.

## Chưa hoàn tất

Chưa có bộ cài ứng viên Windows và kết quả kiểm tra nâng cấp 1.5.2 cho mã nguồn 1.5.3 cuối cùng. Hồi quy toàn bộ cần ghi kết quả thực sau khi hoàn tất. Chưa nghiệm thu thao tác đăng ký/ký thẻ mới trên tài khoản production, chữ ký CryptoPro doanh nghiệp, ghi GTIN thật qua desktop có license production hoặc hành vi khay hệ thống trên Windows.

Đây là bản ứng viên để kiểm tra. Chưa tạo tag hoặc GitHub Release 1.5.3, chưa thay Latest 1.5.2 và chưa bổ sung ngoại lệ vượt cổng phát hành. Tắt hẳn tiến trình, mất điện hoặc không có mạng không thể tiếp tục gọi API; công việc còn lại được lưu để tiếp tục khi app và kết nối hoạt động lại.
