# Znack: bộ lọc, đăng ký hàng loạt và giấy tờ

Phạm vi: mã nguồn sau bộ cài 1.5.10, chưa đóng gói/phát hành theo yêu cầu dừng tải của chủ dự án.

## Nguyên nhân đã tái hiện

- Bộ lọc Đang xử lý dùng cờ chặn thao tác ghi để loại cả thẻ đã có feed được Catalog nhận nhưng chưa chứng minh đủ size/màu. Thẻ bị mất khỏi bộ lọc dù biên nhận vẫn còn trong dữ liệu.
- Chọn tất cả qua nhiều trang gắn cờ “sửa Catalog” cho PRE_SUBMIT_ERROR có GTIN nhưng chưa gửi, khiến nút Đăng ký đi sai luồng. Chọn từng dòng và chọn hàng loạt trước đây tính cờ khác nhau.
- Bộ lọc Lỗi gộp thẻ đang gửi/cấp GTIN/cần đối chiếu và thẻ thực sự lỗi. Báo cáo sửa còn lấy cả lỗi chuẩn bị của thẻ chưa tạo.
- Bước kiểm tra trước đăng ký không lưu báo cáo lô khi bị chặn; giấy tờ dùng chung một bài chỉ được kiểm tra một lần nên cần ánh xạ lỗi sang tất cả size đã chọn trong báo cáo.
- Nút sửa giấy tờ trong bảng thiếu dữ liệu mở sửa giấy tờ trên sàn, trong khi đăng ký ưu tiên giấy tờ ở Cấu hình giấy tờ. Thay số/ngày/loại giấy tờ còn giữ ngày hết hạn của giấy tờ trước.

## Hành vi sau sửa

- Chưa tạo → chọn tất cả → đăng ký dùng hàng đợi gửi hiện có; thẻ chưa gửi không bị chuyển sang sửa một thẻ Catalog chưa tồn tại. Trạng thái kết quả ghi chưa rõ vẫn cần đọc lại, không tự xin GTIN mới hoặc gửi lại một POST chưa rõ kết quả.
- Thẻ có feed được nhận giữ trong Đang xử lý dù còn cảnh báo đối chiếu thuộc tính; cảnh báo vẫn chặn cập nhật GTIN chưa đủ bằng chứng.
- Bộ lọc Lỗi chỉ lấy thẻ bị từ chối/lỗi thực, không lấy bản nháp, đang gửi, đang duyệt hay yêu cầu đang cần đối chiếu. WB/Ozon có bộ lọc riêng cho yêu cầu cần kiểm tra GTIN/feed.
- Giữ số thẻ xác nhận tồn tại riêng với số yêu cầu đã được nhận và số đang chờ đọc lại. Biên nhận được ghi vào báo cáo ngay khi POST trả feed, trước khi lượt đọc trạng thái hoàn tất; không đổi biên nhận thành báo thành công tạo thẻ.
- Báo cáo kiểm tra dữ liệu trước đăng ký lưu đầy đủ mọi size đã chọn, nêu trường/lý do và ghi rõ chưa gửi Catalog. Báo cáo này xuất hiện trong mục Báo cáo đăng ký.
- Dùng cùng hộp Cấu hình giấy tờ ở WB/Ozon và bảng thiếu dữ liệu. Khi đổi giấy tờ không kế thừa hạn cũ; khi lưu lại đúng giấy tờ cũ vẫn giữ hạn. Registry vẫn xác minh hiệu lực/phạm vi, không tự đổi TN VED hoặc bỏ qua lỗi thật.
- Chỉnh sửa thẻ lỗi giữ GTIN/good_id hiện hữu, chuẩn bị đề xuất theo dữ liệu sản phẩm và mẫu mặc định, xác minh lại rồi gửi bằng luồng có nhật ký. Không đưa thẻ chưa tạo hoặc thẻ Catalog đã chuyển sang trạng thái khác vào báo cáo sửa lỗi.

## Giới hạn nghiệm thu

Kiểm thử dùng dữ liệu tổng hợp, API giả lập và HTTP server kiểm thử; chưa có dữ liệu sáu thẻ thực trên máy seller để xác minh chúng đã xuất hiện trên Catalog production. Không tạo/ký thẻ production, không mua KIZ và không thay bản Latest trong đợt sửa này. Không xóa dữ liệu đã đồng bộ.

## Kiểm chứng

241 kiểm thử thuộc 14 lớp riêng biệt đạt, không có lỗi/bỏ qua: 117 kiểm thử worker/biên nhận/sửa thẻ và 124 kiểm thử HTTP/giao diện/preflight/giấy tờ. Không cộng trùng các lượt chạy hồi quy trước đó. Bao gồm chọn tất cả 304 thẻ chưa gửi qua nhiều trang, bảo toàn thẻ đang chờ khi thiếu thuộc tính, báo cáo đủ sáu size bị chặn giấy tờ, cập nhật bộ đếm trước khi trả kết quả đọc lại và sửa cùng GTIN/good_id cho WB/Ozon. Xem tệp JSON cùng tên để biết từng lớp kiểm thử.
