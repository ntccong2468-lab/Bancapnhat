# VN code 1.3.0 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.3.0-preview.1/VN-code-1.3.0-preview.1-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.3.0-preview.1)

## Thay đổi trong phiên bản này

- Thêm GS1 RUS: trạng thái thành viên, hạn dùng, GCP/GLN và hạn mức GTIN theo INN.
- Hồ sơ National Catalog có từ điển/FIAS; lưu nháp, duyệt đúng XML rồi ký bằng CryptoPro.
- SMTP/IMAP TLS bằng hộp thư riêng, mật khẩu Windows DPAPI; hội thoại và lịch sử chưa đọc.
- Hóa đơn đối chiếu riêng, chứng từ có checkpoint; không tự gửi lại khi mất phản hồi.
- CMS/XML xếp hàng theo chứng thư; logo VN trắng nền đen; TN VED không có menu riêng.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `VN-code-1.3.0-preview.1-Windows-x64.exe`. Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 648 kiểm thử Java/JavaFX và 33 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38028666240); mã bộ cài: `7858f9c3502980c6e1d3141bbeef719c85493d04`.
- SHA-256: `7b27b1ceb5d3de6c840e5cbfda8ead52898918b253b603ceaace4ef0d1e88c42`; kích thước: 142088704 byte.

## Giới hạn

- GS1: chưa nghiệm thu đăng nhập/nộp hồ sơ với doanh nghiệp thật, chấp nhận XML bằng chứng thư CryptoPro thật và gửi/đọc SMTP/IMAP thật. Hợp đồng API được đối chiếu từ frontend chính thức, chưa có cam kết phiên bản API.
- GS1: chưa có hợp đồng xác thực người gửi cho các nhà cung cấp IMAP; thư được ghi rõ chưa xác minh. Hóa đơn và bản sao thư Đã gửi dựa trên đối chiếu thủ công có lưu dấu kiểm tra.
- Products WB/Ozon: chỉnh sửa hàng loạt nhãn hiệu/chất liệu/TN VED/giấy tờ và đọc lại trạng thái tài liệu còn thuộc phần cập nhật tiếp theo.
- FBO: GTIN đăng ký đúng biến thể, danh sách thiếu GTIN và các cải tiến mua KIZ chưa hoàn tất trong đợt GS1 này.
- TN VED: liên kết tra cứu trong mọi tác vụ cần, quy tắc đánh dấu và gợi ý có nguồn còn chưa hoàn tất; giữ lõi danh mục/import/search hiện có.
- Trình thiết kế mẫu nhúng, thông tin số dư CZ trên header và những thay đổi còn lại của bản bố cục đã duyệt chưa hoàn tất; cập nhật GTIN thật WB/Ozon vẫn bị khóa chờ hợp đồng API đã xác minh.
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Bancapnhat/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.

## Sử dụng GS1

1. Trong Cấu hình Znack, chọn và kiểm tra chứng thư CryptoPro của doanh nghiệp. Trang GS1 dùng INN/chứng thư đã xác minh của shop đang chọn.
2. Chọn GS1 RUS → Kiểm tra thành viên để đọc trạng thái, ngày hết hạn, GCP/GLN và hạn mức. Dữ liệu lưu được ghi rõ thời điểm; hạn mức có giá trị không tự chứng minh đang là thành viên.
3. Hồ sơ gia nhập cho sửa các bước doanh nghiệp, ngân hàng, địa chỉ FIAS, thông tin công ty và người liên hệ. Xác nhận lưu nháp trước khi lấy XML. Sau đó kiểm tra toàn bộ tài liệu và tab XML; chỉ nút Ký và gửi mới dùng chứng thư để ký đúng XML đang xem. Hủy/lỗi ký trước khi POST giữ bản nháp cho thử lại. Timeout sau khi POST không tự gửi lại. Nút Kiểm tra trạng thái hồ sơ chỉ đọc lại kết quả; đã gửi không đồng nghĩa được GS1 chấp thuận.
4. Cấu hình hộp thư SMTP/IMAP TLS của bạn. Mật khẩu được DPAPI mã hóa cho tài khoản Windows hiện tại. Lỗi kết nối/xác thực trước khi gửi giữ bản nháp để sửa cấu hình và thử lại. Nếu chưa cấu hình, có thể xuất thư .eml để xem/gửi bằng ứng dụng thư riêng; xuất file không ghi nhận thư đã gửi.
5. Soạn yêu cầu gia hạn, thêm GTIN hoặc trợ giúp lỗi National Catalog; kiểm tra địa chỉ nhận, nội dung và tệp trước khi gửi. Làm mới thư tìm theo mã yêu cầu trên toàn hộp thư, không giới hạn ở 250 thư mới nhất.
6. Thư đến luôn ghi nguồn chưa được xác minh: From và Authentication-Results do người gửi tự ghi không được coi là bằng chứng xác thực. Đối chiếu hóa đơn trên tài khoản GS1 chính thức; nhập INN, số hóa đơn, số tiền và xác nhận đã kiểm tra người nhận. Hóa đơn cũ chỉ đánh dấu bằng From không tự mở khóa gửi chứng từ.
7. Gửi chứng từ một lần cho yêu cầu đã có hóa đơn đối chiếu. Khi mất phản hồi SMTP, không tạo/gửi chứng từ khác để vượt trạng thái chưa rõ. Đối soát bằng bản .eml thực sự nằm trong hộp Đã gửi; app kiểm tra ID, người nhận, nội dung và tệp, ghi nhận xác nhận thủ công, không gọi SMTP. Bản nháp xuất ra không phải bằng chứng gửi thành công.
