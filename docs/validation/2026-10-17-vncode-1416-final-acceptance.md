# Nghiệm thu VNCode 1.4.16

Mã ứng dụng: `f41386017ec6516a2322579dd2ee22306378d6e4`. Nâng cấp từ 1.4.15. Hồ sơ chi tiết và bảy yêu cầu nằm trong [JSON nghiệm thu](2026-10-17-vncode-1416-final-acceptance.json).

- Linux `clean verify`: 1.684 kiểm thử, 1.682 đạt, 0 thất bại, 0 lỗi, 2 ca phụ thuộc hệ điều hành được bỏ qua theo thiết kế. Hoàn tất 18:12:17 UTC ngày 17/10/2026, thời gian 5 phút 32 giây.
- 52 kiểm thử trọng tâm đã đạt trước lượt hồi quy toàn bộ; lượt toàn bộ có thêm kiểm thử ngoài phạm vi sửa mới. Bộ đóng gói Node 60/60; Portal 60/60, mã Portal không thay đổi.
- Tệp riêng `quan.txt` được đọc và nhập trong DB fixture qua luồng UI: đủ 600 mã duy nhất, hiển thị 600 mã ở danh mục; không đưa mã thật vào repo. Kiểm tra reserve/consume và nhập lại xác nhận không khôi phục mã đã dùng.
- FBS tổng hợp 10 shop × 1.000 đơn, 100.000 sản phẩm, 20 mẫu: commit-to-pulse p95 66,57 ms, FX dispatch p95 0,73 ms; 0 lần đứng giao diện quá 500 ms. Đây là số liệu Linux với dữ liệu tổng hợp, không phải máy seller cấu hình thấp.
- Catalog thật đọc đủ 1.276 thẻ qua hai trang và 19 danh mục đang hoạt động cho nhóm TN VED mẫu. Probe Java chọn đúng bộ thể thao `30733`, quần `30683`, váy `30685`, áo khoác thể thao `30735`; fixture chỉ chứa danh mục công khai, không chứa dữ liệu thẻ của seller.
- API Ozon đọc được 175 sản phẩm. API sản phẩm WB trả 200 trong miền đã cấu hình. Hai miền FBS/tin tức WB trả 401 khi Secret chỉ được chuyển tới miền Content API; cần sửa phạm vi miền của Secret hiện có rồi Publish môi trường. Công cụ báo xung đột yêu cầu Secret, không lưu thay đổi và không tạo Secret trùng. Xem [báo cáo đọc API](2026-10-17-vncode-1416-live-read.json).

Bộ cài Windows đang được kiểm chứng trong [run 39056670575](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39056670575), gồm toàn bộ kiểm thử Java, launcher, DPAPI, DPI, bố cục và nâng cấp giữ dữ liệu từ 1.4.15 cùng các bản nền cũ. Chưa dùng trạng thái đang chạy này làm bằng chứng bộ cài đã đạt.

Không thực hiện kích hoạt license sản xuất, cấp/ký thẻ thật bằng CryptoPro của doanh nghiệp, mua KIZ hoặc gán mã vào đơn thật trong lượt nghiệm thu này. Giao thức license và khóa tin cậy không thay đổi. Các phép ghi giả lập và số liệu tổng hợp được ghi riêng với các phép đọc API thật.
