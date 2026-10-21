# VNCode 1.5.9

- Sửa lỗi loại kích thước hợp lệ bị đổi thành chuỗi rỗng, khiến đăng ký thẻ bị chặn trước khi gửi lên Catalog.
- Đăng ký WB/Ozon ưu tiên giấy tờ seller lưu ở “Cấu hình giấy tờ”; kiểm tra giấy tờ trước khi gửi. Khi chưa cấu hình, vẫn dùng giấy tờ sản phẩm phù hợp.
- Báo cáo đăng ký hiển thị lý do lỗi cụ thể kèm article/size ngay phần tổng hợp; đưa cột lý do lên vị trí dễ thấy.
- Giữ mã lỗi cụ thể khi lỗi kèm tên trường cần sửa.

Giữ nguyên dữ liệu và định danh nâng cấp ứng dụng. Theo yêu cầu chủ dự án, bản này kiểm tra mã/bộ cài hiện tại, không chạy ma trận kiểm thử các phiên bản cũ. Kiểm thử mô phỏng và tra cứu API không thay thế nghiệm thu tạo/ký thẻ thật trên Catalog; bản này chưa xác nhận thao tác đó trên máy seller.
