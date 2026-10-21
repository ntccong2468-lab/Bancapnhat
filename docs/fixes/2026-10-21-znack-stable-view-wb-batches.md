# Znack: giữ danh sách và đối soát GTIN WB theo lô

Trạng thái: mã nguồn sau PR #9, chưa đóng gói, chưa thay Latest.

- Tự đồng bộ WB/Ozon cập nhật nền phần đã tải, giữ bộ lọc/lựa chọn và số trang đã cuộn. Không thay lại danh sách khi dữ liệu không đổi. Trong lúc tác vụ GTIN còn chạy, thông báo đồng bộ không tải lại bảng.
- Khi cần xác minh thẻ, dòng trạng thái nêu lý do cụ thể (thiếu bằng chứng xuất bản, đổi cấu hình tài khoản, thiếu màu/size). Không bỏ kiểm tra chủ sở hữu, trạng thái xuất bản hoặc đúng biến thể chỉ vì đã có dãy số GTIN.
- WB dùng `POST /content/v2/cards/update`, tối đa 3.000 nmID/10 MB, gộp các size cùng thẻ; nghỉ 15 giây giữa lô và giữ nhịp này khi chuyển nhóm kế hoạch trong cùng lượt chạy. Bảo toàn barcode, các trường thẻ và giấy tờ cũ. Kiểm tra API trước ghi vẫn thực hiện ngoài giao diện.
- Lưu trạng thái từng dòng trước/sau yêu cầu; không gửi lại khi chưa biết kết quả. Sau gửi, đọc lại sàn và danh sách lỗi để đếm thành công. HTTP 200 riêng lẻ không được tính đã cập nhật.
- Hiện bảng ngắn gồm số đã cập nhật, chưa cập nhật, chưa có GTIN và thiếu dữ liệu xác minh. Lý do gộp theo nhóm; nút Báo cáo mở đầy đủ từng dòng. Khi sàn đã xác nhận GTIN, giao diện không giữ trạng thái chờ chỉ vì bước ghi nhận nội bộ chưa hoàn tất.
- Giữ lý do WB trả về từ `errorText` hoặc danh sách lỗi. Nội dung được che API key/token, giới hạn độ dài và lưu trong bảng chẩn đoán bổ sung có khóa ngoại; không đổi bảng dữ liệu/mã hàng cũ. Kết quả đã đọc lại thành công không bị lỗi ghi nhận nội bộ làm giảm số đã cập nhật.

Hợp đồng và nguồn WB: [tài liệu đối chiếu](../research/2026-10-21-wb-gtin-batch-contract.md).

## Kiểm chứng

- Lượt hồi quy liên quan: 147 kiểm thử đạt, gồm API adapter, hàng đợi, giữ lựa chọn/danh sách 404 thẻ sau tự đồng bộ, hộp kết quả và báo cáo lý do WB đã che thông tin nhạy cảm.
- Sau chỉnh bảo toàn lý do có sẵn trong báo cáo: 25 kiểm thử trọng tâm đạt; đây là tập con, không cộng trùng vào 147.
- Kiểm thử quy mô riêng với 10.000 GTIN WB: 4 lần ghi, tối đa 3.000 thẻ/yêu cầu, 214 lần đọc, bảo toàn dữ liệu và đọc lại xác nhận 10.000 mã. Khoảng nghỉ được kiểm tra qua transport giả lập, không lấy thời gian thực của kiểm thử làm cam kết tốc độ production.
- Sau sửa kết thúc trạng thái chờ khi đã có bằng chứng WB: 16 kiểm thử snapshot/giao diện đạt; là tập kiểm tra giao nhau, không cộng dồn.
- Lượt Ozon 10.000 mã ngoài phạm vi ghi WB đã dừng; không tính là kiểm thử đạt. Lỗi thiếu DISPLAY của môi trường đã được xử lý bằng Xvfb trước các lượt hồi quy giao diện đạt.

Kiểm thử dùng API giả lập với planner/adapter/hàng đợi/SQLite thật của ứng dụng. Không ghi GTIN lên shop production trong đợt này, không mua KIZ, không tạo bộ cài và không phát hành. Chưa thể khẳng định kết quả ghi trên tài khoản seller chỉ từ các kiểm thử này.
