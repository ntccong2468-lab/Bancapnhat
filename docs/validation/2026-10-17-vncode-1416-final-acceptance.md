# Nghiệm thu VNCode 1.4.16

Mã ứng dụng cuối: `536567835f6a044626d00be273b2217bb1c4fc7c`. Nâng cấp từ 1.4.15. Hồ sơ từng yêu cầu và kết quả nằm trong [JSON nghiệm thu](2026-10-17-vncode-1416-final-acceptance.json).

- Linux trước sửa callback màn hình đóng (nguồn `a7e236e`) chạy sạch toàn bộ: **1.686 kiểm thử, 1.684 đạt, 0 thất bại, 0 lỗi, 2 ca phụ thuộc hệ điều hành được bỏ qua theo thiết kế**. Hoàn tất 18:46:29 UTC ngày 17/10/2026, thời gian 5 phút 31 giây. SHA-256 log: `c0107d278b97e76474ed80d245ff5e444df893c2831418d65caa66d873debfeb`.
- Bộ đóng gói Node 60/60. Portal 60/60 và hai Worker đóng gói thử thành công trong job cùng nguồn; mã Portal không thay đổi.
- Mở trực tiếp bốn hộp thoại thương hiệu, chất liệu, giới tính và giấy tờ trên fixture. Hai nút Áp dụng/Hủy cùng một hàng; không còn hướng dẫn xem trước cũ. Kiểm thử bấm Áp dụng trong hộp thoại và xác nhận cập nhật hai sản phẩm; danh sách giấy tờ lấy toàn bộ shop, loại trừ shop khác và giữ loại chứng nhận gốc.
- Tệp riêng `quan.txt` được đọc và nhập trong DB fixture qua luồng UI: đủ 600 mã duy nhất, hiển thị 600 mã ở danh mục; không đưa mã thật vào repo. Reserve/consume và nhập lại xác nhận số còn giảm, không khôi phục mã đã dùng.
- FBS tổng hợp 10 shop × 1.000 đơn, 100.000 sản phẩm, 20 mẫu: commit-to-pulse p95 70,56 ms, FX dispatch p95 2,17 ms; 0 lần đứng giao diện quá 500 ms. Đây là số liệu Linux với dữ liệu tổng hợp, không phải máy seller cấu hình thấp.
- Catalog thật đọc đủ 1.276 thẻ qua hai trang và 19 danh mục đang hoạt động cho nhóm TN VED mẫu. Probe Java chọn đúng bộ thể thao `30733`, quần `30683`, váy `30685`, áo khoác thể thao `30735`; fixture chỉ chứa danh mục công khai, không chứa dữ liệu thẻ của seller.
- API Ozon đọc được 175 sản phẩm. API sản phẩm WB trả 200 trong miền đã cấu hình. Hai miền FBS/tin tức WB trả 401 khi Secret chỉ được chuyển tới miền Content API; cần sửa phạm vi miền của Secret hiện có rồi Publish môi trường. Công cụ báo xung đột yêu cầu Secret, không lưu thay đổi và không tạo Secret trùng. Xem [báo cáo đọc API](2026-10-17-vncode-1416-live-read.json).

Bộ cài Windows đang được kiểm chứng trong [run 39061440469](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39061440469), gồm toàn bộ Java/JavaFX, launcher, DPAPI, DPI, bố cục và nâng cấp giữ dữ liệu từ 1.4.15 cùng các bản nền cũ. Chưa dùng trạng thái đang chạy này làm bằng chứng bộ cài đã đạt.

Không thực hiện kích hoạt license sản xuất, cấp/ký thẻ thật bằng CryptoPro của doanh nghiệp, mua KIZ hoặc gán mã vào đơn thật trong lượt nghiệm thu này. Giao thức license và khóa tin cậy không thay đổi. Các phép ghi giả lập và số liệu tổng hợp được ghi riêng với các phép đọc API thật.

Lượt Windows trước có 1 lỗi kiểm thử do đọc danh sách trước callback FX. Đã thay bằng chờ kết quả hiển thị thực, giữ nguyên kiểm tra chống dữ liệu cửa hàng cũ; đồng thời chặn callback khởi tạo cửa hàng sau khi Home bị dispose. Cả 55 kiểm thử FXML đã đạt trên Linux sau sửa. Đang chạy sạch toàn bộ 1.687 kiểm thử trên mã nguồn mới.
