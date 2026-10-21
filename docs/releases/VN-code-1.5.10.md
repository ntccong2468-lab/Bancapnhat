# VNCode 1.5.10

- WB/Ozon: khi bấm in lại trong lúc có lượt mua KIZ đang dở, app tiếp tục đối soát lượt đó bằng mã yêu cầu cũ thay vì chỉ báo chờ; không tạo lượt mua trùng.
- Ưu tiên KIZ sẵn dùng đúng GTIN trước khi xét bổ sung kho. Thông báo chờ liệt kê GTIN, số cần, số sẵn dùng và số còn thiếu để seller không phải tìm GTIN thủ công.
- Hiển thị chờ KIZ bằng thông báo đầy đủ có thể cuộn, thay hộp lỗi hệ thống bị cắt chữ. Phân biệt đang bổ sung, đang tiếp tục lượt mua và cần kiểm tra/lưu thông.
- Giữ trạng thái chờ KIZ trong báo cáo Ozon, không ghi nhận chờ kho là một lần in thất bại; bảo toàn mã lỗi KIZ trong báo cáo đồng bộ.

Đã kiểm thử mô phỏng 177 KIZ sẵn dùng đúng GTIN cho cả WB/Ozon, bao gồm có lượt mua cũ đang chờ. Chưa có dữ liệu kho trên máy seller nên chưa xác định nguyên nhân riêng của số tồn 177 mã được phản ánh. Không mua KIZ thật hoặc ghi lên sàn trong đợt kiểm thử này. Giữ nguyên dữ liệu và định danh nâng cấp; chỉ kiểm tra mã/bộ cài hiện tại, không chạy ma trận phiên bản cũ.
