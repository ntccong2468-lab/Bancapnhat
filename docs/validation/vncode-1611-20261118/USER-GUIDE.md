# Cách dùng các bổ sung GS1, Sản phẩm và Ozon

Hướng dẫn cho VN Code 1.6.11, nguồn `cf3d8d69`, đã phát hành Latest ngày 18.11.2026 lúc 15:52:03 MSK. Updater 1.6.10 đã nhận đúng bản mới; xem phiên bản trong app để biết bản đang cài. [Báo cáo phát hành và giới hạn nghiệm thu](FINAL-REPORT.md).

## Kiểm tra GS1 đúng cửa hàng

Chọn cửa hàng trên thanh đầu rồi mở **GS1 RUS**. Nếu chưa có chứng thư đã kiểm tra, vào **Cấu hình Znack**, chọn chữ ký của đúng doanh nghiệp và bấm **Kiểm tra chữ ký**. Máy Windows cần CryptoPro, chứng thư còn hiệu lực và quyền dùng khóa riêng.

Trong GS1, bấm **Kiểm tra lại**. Khung **Tài khoản GS1 & Честный знак** hiển thị sáu mục: Chữ ký số, Đăng nhập Честный знак, Thoả thuận với đơn vị vận hành, Số dư tài khoản, Thành viên GS1 và Hạn mức GTIN. Xem lý do từng mục; “Chưa xác minh” chưa có nghĩa “không hợp lệ”. Nếu một nguồn lỗi, app có thể giữ dữ liệu tốt gần nhất kèm cảnh báo/thời điểm.

Muốn xem lỗi nguồn chi tiết, bấm chuột phải tiêu đề **Thành viên GS1 RUS**, chọn **Báo cáo đồng bộ GS1**.

## Gia nhập hoặc đăng ký lại

1. Bấm **Gia nhập GS1** khi app cho phép. Hồ sơ bị loại/từ chối có thể mở lại khi Catalog cho sửa; hồ sơ đã gửi hoặc chưa rõ kết quả phải kiểm tra trạng thái trước.
2. Đi sáu bước: **Doanh nghiệp → Ngân hàng → Địa chỉ → Hoạt động → Người liên hệ → Xem lại hồ sơ**. Dùng **Tiếp tục/Quay lại**; sửa trường được chỉ rõ khi có lỗi. Địa chỉ nên tra và chọn gợi ý; có nút sao chép địa chỉ pháp lý và thêm/xóa địa chỉ sản xuất.
3. **Lưu nháp lên NK** lưu hồ sơ vào Catalog. Khi đã kiểm đầy đủ, chọn **Ký XML và nộp**, đọc phần xem lại/XML và xác nhận ký. Chỉ gửi thông tin có thật; app không tự xác nhận thay seller.

Hồ sơ từ chối giữ lý do để sửa; đã gửi chưa có nghĩa GS1 đã duyệt thành viên.

## Xem và ký thỏa thuận

Trong mục thỏa thuận, bấm **Xem và ký thỏa thuận → chọn tài liệu → Mở PDF**. Đọc bằng **Trang trước/Trang sau**, kiểm doanh nghiệp rồi tích đồng ý và bấm **Ký và gửi chữ ký**. App ký đúng PDF đã duyệt và đọc lại trạng thái.

Nếu mất phản hồi hoặc đang chờ đối tác, dùng **Kiểm tra lại trạng thái**, không ký lặp. Nếu đã hủy ký trước khi gửi, bấm **Mở PDF** lần nữa: chỉ khi app xác minh yêu cầu vẫn là nháp chưa gửi mới được đồng ý và ký lại.

## Xin tiền tố và gửi thư

Bấm **Xin tiền tố GS1 bổ sung**, chọn 999 hoặc 99.999 mã và kiểm thông tin doanh nghiệp, người ký, GCP. App kiểm lại hạn thành viên và lượng mã trên các tiền tố trước khi cho gửi.

Có mẫu 01.04.01 mặc định. **Chọn mẫu Word** dùng mẫu riêng và được ưu tiên. Dùng **Xem đơn đã điền**, sau đó **Ký đơn** hoặc **Đính kèm đơn đã ký (ảnh / PDF)**. Kiểm PDF, chữ ký và thông tin thư; tích xác nhận rồi bấm **Xem lại thư**.

VN Code gửi qua hộp thư do bạn cấu hình. Khi đã có yêu cầu nháp, trong **Thư từ GS1 RUS → Tài khoản thư**, nhập SMTP TLS, IMAP TLS, cổng, tài khoản, người gửi và thông tin đăng nhập theo nhà cung cấp mail. Lưu trên Windows. Mở lại nháp, kiểm người nhận/tệp rồi bấm **Gửi thư** và xác nhận. Dùng **Nhận thư mới** để đọc phản hồi. Gửi xong chưa có nghĩa GS1 đã cấp tiền tố; kiểm lại Catalog.

Trong lỗi đăng ký thẻ, **Kiểm tra thành viên GS1** xử lý nhánh mã 166; **Kiểm tra GS1 và xin tiền tố** xử lý nhánh hết mã. App đọc mới rồi mở tác vụ phù hợp, không tự gửi/ký.

## Sản phẩm và Ozon

Ở **Sản phẩm → Danh mục**, tích nhiều mục, tối đa 100; bỏ lọc để xem lại toàn bộ. Năm nút **Sửa thương hiệu, Sửa chất liệu, Sửa giới tính, Sửa TN VED, Sửa giấy tờ** vẫn dùng sau khi chọn sản phẩm; xem trước khi áp dụng.

Chọn shop Ozon → **Đơn hàng FBS → Đơn mới** hoặc **Đang giao hàng → In KIZ** khi cần PDF KIZ riêng. App xét toàn lô đang lọc, báo đơn thiếu và hỏi xác nhận trước lưu PDF. Đây là mã đã gán, không tự mua/gán thêm, tải vận đơn hoặc chuyển trạng thái giao hàng. Đối chiếu mã đơn/STT; cảnh báo giờ nhận thiếu/trùng có thể khiến thứ tự khác Ozon. Luồng in vận đơn và nhãn kết hợp thông thường vẫn riêng.

Khi nâng cấp từ 1.6.10, app tạo snapshot trước chuyển database schema 6→7; giữ hồ sơ và mã đã dùng. Không mở database đã nâng cấp bằng app cũ; khôi phục cần snapshot tương thích.

[Ảnh GS1 mẫu](../wbcode-guides-gs1-20261118/screenshots/gs1-agreement-dark.png) · [Ảnh Ozon mẫu](ozon-fbs-sample.png) · [Báo cáo đối chiếu](../wbcode-guides-gs1-20261118/REPORT.md). Ảnh dùng dữ liệu tổng hợp; không chứng minh đã ký, gửi mail hay giao dịch seller thật trong môi trường kiểm thử.
