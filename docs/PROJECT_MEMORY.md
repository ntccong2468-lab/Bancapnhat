# Bộ nhớ dự án VNCode

## Trạng thái phiên 22.10.2026

- Bản công khai tại thời điểm bắt đầu: `v1.5.11`, commit `2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`.
- Luồng đăng ký/đồng bộ được hoàn tất trước giao diện: nhánh `fix/znack-workflow-parity`, PR #12, commit cuối `017412f`. Không có bộ cài riêng cho bước này.
- Giao diện nền đen và tab Kho KIZ: commit `b27d799`, nhánh `ui/wbcode-dark-reference`, đã push. 115 ca/11 lớp kiểm thử UI đạt; ảnh view thật với dữ liệu tổng hợp nằm trong `docs/fixes/images/2026-10-22-dark-reference`.
- Chủ dự án đã yêu cầu kiểm tra lại mua KIZ, GTIN và đăng ký Znack, sửa lỗi nếu có, sau đó build và đưa bản mới lên Latest. Bản dự kiến: **1.5.12**. Chưa được coi là phát hành chỉ vì đã commit hoặc có artifact; phải kiểm tra Windows, upload, xác minh tài sản và API Latest.
- Chi tiết kết quả cuối và biên nhận phát hành được bổ sung trong bàn giao của phiên này.

## Quyết định cần giữ

1. Cấu hình Znack có đúng 5 tab chính: Thông tin, Lịch sử mua KIZ, Đã xóa, Cài đặt, Kho KIZ. Tab Kho KIZ dùng kho và luồng nhập file hiện có, không tạo kho song song.
2. Giao diện tham chiếu mới là nền đen, nút xám, thao tác chính tím, logo VN đen/chữ trắng. Không phục hồi nền xanh khi nâng cấp. Lựa chọn giao diện được áp dụng một lần khi nâng cấp, sau đó giữ lựa chọn rõ ràng của người dùng.
3. Đăng ký phải lấy TN VED từ đúng sản phẩm/biến thể trên sàn. Giấy tờ chỉ được dùng khi có phạm vi phù hợp; không thay TN VED để ép khớp giấy tờ, không gán danh tính thẻ của sản phẩm khác.
4. Gửi feed là một mốc riêng với việc đã đọc thấy thẻ trên Catalog và việc đã xuất bản. Báo cáo Lỗi chỉ nhận từ chối thực sự của Catalog; cần kiểm tra ghép thẻ không được lẫn vào nhóm này.
5. Giữ hàng đợi, GTIN, feed ID, phạm vi shop/chứng thư và kết quả chưa rõ qua khởi động lại. Đối soát trước khi gửi lại; không xin GTIN/mua mã mới chỉ vì timeout. Không thể thực thi HTTP khi tiến trình đã đóng hoàn toàn hoặc không có mạng.
6. GTIN WB: tối đa 3.000 thẻ/yêu cầu và 10 MB, nghỉ 15 giây giữa các lô, giữ barcode gốc, xác minh lại từng size. Ozon dùng endpoint barcode riêng, tối đa 100 cặp/yêu cầu. Luôn tôn trọng giới hạn/Retry-After phía dịch vụ.
7. KIZ: kiểm tra kho sẵn dùng và yêu cầu còn chờ trước khi mua phần thiếu. Mã yêu cầu mua là UUID hợp lệ được giữ trong pipeline. Kho seller tải lên và kho mua phải tiếp tục dùng đúng luồng/định danh đã có.
8. Giữ Hỗ trợ/Hướng dẫn hiện có, không thêm backend hội thoại mới. Không thay kiến trúc ứng dụng ngoài phạm vi đã được giao.
9. Phát hành bản mới không tự động xóa các Release cũ. Không chạy chế độ `prune` chỉ vì chủ dự án yêu cầu đưa bản mới lên Latest.
10. Không lưu API key, license, mật khẩu, mã KIZ thật hoặc dữ liệu tài khoản seller trong bộ nhớ/bàn giao. Không tắt license/chữ ký production để làm kiểm thử xanh.

## Kinh nghiệm kiểm tra và phát hành

- Codex Web dùng JavaFX trên Xvfb với `DISPLAY=:99`; cần truyền biến này cho từng lệnh Maven. Không suy ra lỗi ứng dụng từ việc runner chưa có DISPLAY.
- Font Inter biến thiên không bảo đảm chữ đậm ổn định trên JavaFX. Bản Inter Bold tĩnh cùng giấy phép OFL và nguồn/hash được kèm trong app.
- CSS phải bao phủ cả trường hợp view là scene root và nằm trong workspace; nếu chỉ dùng selector hậu duệ thì preview và app có thể khác nhau. Màu glass cũ không được ghi đè giao diện đen.
- Khi ẩn dòng phản hồi lúc rảnh, vẫn phải hiện lại khi có lỗi/tiến trình. Không giấu lỗi cấu hình để giống ảnh.
- Kiểm tra YAML phải phát hiện khóa lặp: hai workflow cũ có `name`, `options` hoặc `run` bị trùng, khiến GitHub từ chối trước khi thực thi.
- Lô giả lập 10.000 chạy qua planner, adapter và SQLite thật của app nhưng không phải ghi production. Giữ rõ các cờ `liveCatalogWriteVerified`, `liveMarketplaceWriteVerified`, `paidKizPurchaseVerified` trong bằng chứng.
- Bản build và tag phải trỏ cùng SHA. Build Windows dùng `publish-vncode-baseline.yml`, chạy toàn bộ Maven verify trước `jpackage`, kèm EXE/MSI/runtime/source/Git bundle/checksum/verification. Đừng chạy hai Maven mutation/compile đồng thời trong cùng workspace.
- Đóng gói `app-image` sẽ dọn `out`; phải giữ bộ cài và chứng cứ trước khi tạo runtime. Chỉ đổi Latest sau khi kiểm chứng tài sản cuối.

Nguồn chi tiết: [luồng đăng ký](fixes/2026-10-22-znack-workflow-parity.md), [giao diện](fixes/2026-10-22-dark-reference-ui.md), [bản nền](releases/BASELINE.md), [ghi chú 1.5.12](releases/VN-code-1.5.12.md).
