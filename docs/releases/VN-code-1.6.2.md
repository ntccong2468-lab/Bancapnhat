# VN code 1.6.2

Bản cập nhật kế thừa toàn bộ 1.6.1, sửa nhãn Ozon và số vận đơn trong phiếu nhặt hàng. Giữ định danh cài đặt, dữ liệu, cấu hình và luồng nhận đơn theo lô.

## In Ozon và phiếu nhặt hàng

- PDF vận đơn giữ nguyên nội dung và kích thước nhãn do Ozon trả về. Bỏ trang barcode sản phẩm lớn đi kèm khi xác định được chính xác; giữ nhãn kết hợp KIZ/barcode hiện có. Không thêm ô mã tự tạo vào nhãn vận đơn.
- Tiếp tục lấy nhãn theo tác vụ lô tối đa 1.000 đơn. Nhận diện và lọc trang ngay trong PDF đã tải; không đổi thành tải từng đơn để loại barcode sản phẩm. Tăng tốc nhận diện trang barcode dạng vector, có phương án đọc ảnh khi cần.
- Phiếu nhặt hàng lấy đúng số in trên nhãn vận đơn của từng đơn, đối chiếu thêm trường `scanit` từ API Ozon. Hiển thị đầy đủ số và bốn ký tự cuối nền đen chữ trắng trên phiếu nhặt hàng. Không suy số vận đơn từ article hoặc thứ tự trang.
- Lưu số nhãn theo cửa hàng và chủ kết nối để lần đọc sau dùng lại đúng dữ liệu. Nếu chưa xác định được số nhãn, ghi rõ phần thiếu; vẫn giữ nhãn vận đơn Ozon đã cấp.

## Báo cáo tài chính

- Chuyển mục Lý do phạt khỏi Tổng quan sang **Công cụ → Báo cáo tài chính → Lý do phạt**.
- Giữ bộ lọc cửa hàng, khoảng ngày, phân trang và trạng thái nguồn dữ liệu. Dừng tác vụ màn hình khi rời tab hoặc đóng cửa sổ. Nguồn phạt WB được hỗ trợ; Ozon vẫn báo chưa hỗ trợ khi chưa có nguồn API được xác minh.

## Kiểm chứng và phạm vi

Trước đóng gói, 302 ca kiểm thử liên quan đạt. Đã đọc API Ozon và đối chiếu số `scanit` với một PDF thật đã lưu; lượt xác minh này không nhận đơn, tạo tác vụ nhãn mới hoặc sử dụng KIZ. Lô 100/1.000 dùng dữ liệu mô phỏng, không phải phép đo tốc độ trên cửa hàng thật. Kiểm thử Windows đầy đủ, cài mới và nâng cấp từ 1.6.1 phải đạt trước khi công bố; kết quả của bộ cài nằm trong `build-info.json` và gói verification đính kèm release.

Bộ cài giữ trạng thái chưa ký Authenticode như bản trước. Các phần thử nghiệm migration credential vẫn nằm ngoài runtime. Xem báo cáo thay đổi tại `docs/validation/2026-11-04-ozon-labels-finance/README.md`.
