# VNCode 1.5.7

- Đăng ký Znack WB/Ozon: thẻ nháp hiện có được kiểm tra và gửi duyệt bằng đúng GTIN/ID cũ sau xác nhận; giữ checkpoint, không gửi lặp khi chưa rõ kết quả.
- Tách trạng thái đăng ký, kiểm duyệt và GTIN trên sàn; hiển thị riêng Size RU và thời điểm đối soát.
- Giữ các đơn mua KIZ đã gửi khi nâng cấp; phản hồi thiếu không dẫn đến mua lại. Sửa khóa yêu cầu mua KIZ FBO.
- Khôi phục đúng nhãn/KIZ khi xuất file lỗi và chặn xuất trùng đồng thời.
- Đối soát từng mã CIS; sửa nhận phản hồi thành công có mã 0 trong xác minh kho KIZ.
- Sửa hạn mức đọc lỗi và kiểm tra phân trang WB; cải thiện hủy/timeout CryptoPro và lý do lỗi Catalog.
- Sửa tốc độ đọc đầu ra CryptoPro trên Windows để chữ ký lớn không bị báo hết thời gian do chờ giữa mỗi lần đọc.

Đã kiểm thử tự động trên Linux; bộ cài phải vượt kiểm thử Windows và nâng cấp giữ dữ liệu trước khi công bố. Các kiểm tra ký CryptoPro/chứng thư, license và ghi API production thật chưa được nghiệm thu trong bản này. Việc phát hành theo yêu cầu chủ dự án không thay thế các nghiệm thu đó.
