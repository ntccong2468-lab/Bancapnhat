# Báo cáo VNCode 1.5.3

## Thay đổi đã thực hiện

- Znack WB/Ozon lưu lô đăng ký, gửi lần lượt từng thẻ và ghi tiến độ sau khi Catalog nhận feed. Bộ lọc Chưa tạo không coi dữ liệu mới xếp hàng là thẻ đã gửi; Đang xử lý giữ các thẻ chờ duyệt và trạng thái đọc lại từ Catalog.
- Trang đăng ký tiếp tục phản hồi khi chạy lô; báo cáo giữ đầy đủ lựa chọn. Gửi chưa rõ kết quả phải đối chiếu, không tự cấp thêm GTIN hoặc gửi trùng.
- Hàng đợi tiếp tục khi có mạng hoặc mở lại app. Đóng cửa sổ lúc đang chạy có thể giữ app trong khay hệ thống với nút Mở lại/Thoát; nếu không tạo được biểu tượng khay, cửa sổ vẫn hiện và báo lý do.
- Giảm phần kiểm tra/sao lưu lặp ở khởi động và bàn giao bộ cài, giữ kiểm tra toàn vẹn và dữ liệu SQLite/WAL. Không thay mẫu đăng ký, quy tắc phạm vi giấy tờ/TN VED hoặc các luồng dữ liệu và KIZ của 1.5.2.
- Kịch bản Windows bổ sung nâng cấp từ bộ cài Published 1.5.2 với URL và SHA-256 cố định. Bước phát hành bắt buộc xác minh kết quả Java và mọi bằng chứng bộ cài/nâng cấp của đúng source 9dfcae6 và run 39383179796; không dùng lại build cũ.

## Đọc Catalog thật

Lượt đọc chỉ đọc lúc 09:35 ngày 20/10/2026, giờ Moscow, nhận HTTP 200 và 1.277 thẻ: 1.241 đã xuất bản, 34 lỗi, 2 nháp. Không thực hiện POST tạo thẻ mới trong lượt kiểm tra này. Số lượng đọc được không chứng minh nút Đăng ký đã tạo thành công một thẻ mới.

## Kiểm tra metadata

`release-version.mjs` xác nhận hai khai báo phiên bản trong `pom.xml` cùng là 1.5.3. Toàn bộ 81 kiểm thử Node đã đạt. Hồi quy Linux/Xvfb trên source a65b2b4 có 1.991 ca, không lỗi, 2 bỏ qua theo nền tảng. Guard khôi phục Ozon cuối cùng đã qua 71 kiểm thử trọng tâm của source 9dfcae6 và nằm trong lượt Java Windows thực thi mới. Không dùng lượt Windows đã hủy để tính nghiệm thu.

## Chưa hoàn tất

Chưa nghiệm thu thao tác đăng ký/ký thẻ mới trên tài khoản production, chữ ký CryptoPro doanh nghiệp, ghi GTIN thật qua desktop có license production hoặc hành vi khay hệ thống trên Windows.

Phát hành được ràng buộc riêng cho 1.5.3, source 9dfcae6 và lượt Windows 39383179796; chỉ thay Latest sau khi toàn bộ kiểm tra tự động bắt buộc đạt. Cổng nghiệm thu production thông thường và kiểm tra license/chữ ký của app giữ nguyên; các phần chưa nghiệm thu ở trên vẫn có cờ false trong báo cáo.

Chủ dự án cấp quyền license không giới hạn cho phiên kiểm thử WB/Ozon. Các kiểm thử chạy bằng authority ký tạm, đủ mọi quyền và không được đóng gói trong app. Quyền kiểm thử không thay thế chứng thư/khóa CryptoPro thực của seller.

Tắt hẳn tiến trình, mất điện hoặc không có mạng không thể tiếp tục gọi API; công việc còn lại được lưu để tiếp tục khi app và kết nối hoạt động lại.
