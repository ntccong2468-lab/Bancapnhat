# Cập nhật GTIN WB theo lô

Nguồn chính thức đọc ngày 21/10/2026: https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml — HTTP 200. SHA256 tệp YAML: `55adcfc425dd6a2867fe445034944950d413c063438708a9e48a4989b267c416`.

Endpoint ghi: `POST https://content-api.wildberries.ru/content/v2/cards/update`.

- Tối đa 3.000 **thẻ nmID** và 10 MB mỗi yêu cầu. Số GTIN không đồng nghĩa số thẻ: nhiều size của cùng nmID được gộp trong cùng đối tượng thẻ.
- Hạn mức công bố: 10 yêu cầu/phút, khoảng cách 6 giây, burst 5. Khoảng nghỉ 15 giây do seller yêu cầu thấp hơn hạn mức; hạn mức hoặc Retry-After dùng chung vẫn phải được tôn trọng.
- Chỉ thêm SKU/barcode cho size; không sửa hoặc xóa mã cũ.
- Cập nhật là ghi đè thẻ và giấy tờ: phải gửi lại các trường cần giữ, không gửi chỉ riêng GTIN.
- HTTP 200 không chứng minh cập nhật thành công. Cần đọc lại thẻ và danh sách lỗi `POST /content/v2/cards/error/list`; WB nêu đồng bộ các dịch vụ có thể mất tới 30 phút.

Ứng dụng giữ lựa chọn và dữ liệu hiển thị trong lúc gửi, ghi trạng thái từng dòng vào SQLite, nghỉ 15 giây giữa các lô WB và đọc lại để đếm kết quả. Các kiểm tra nguồn/chủ sở hữu/size trước khi gửi vẫn thực hiện ở nền; việc không tải lại giao diện không có nghĩa bỏ kiểm tra API để bảo toàn dữ liệu.

GTIN có dạng số không tự chứng minh quyền dùng mã: vẫn cần thẻ Catalog thuộc đúng tài khoản, đã xuất bản và khớp đúng biến thể. Khi thiếu bằng chứng, app nêu lý do, không tự đánh dấu đã xác minh hoặc ghi đại một GTIN vào sản phẩm.

Báo cáo nghiệm thu của thay đổi này tách kiểm thử giả lập khỏi dữ liệu production. Việc tải tài liệu API không phải bằng chứng đã ghi GTIN thật lên shop.
