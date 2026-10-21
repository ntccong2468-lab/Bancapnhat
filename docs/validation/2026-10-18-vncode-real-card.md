# Kiểm tra tạo một thẻ Catalog thật — 18/10/2026

Theo yêu cầu đăng ký thật một thẻ, đã chọn một biến thể bộ thể thao trên WB,
đọc mới sản phẩm, giấy tờ và schema danh mục Catalog trước khi cấp GTIN.
Không dùng thông tin của thẻ mẫu để thay giấy tờ của sản phẩm được chọn.

## Phạm vi và kết quả

- Dùng mapper, bộ chọn danh mục và template thật từ JAR VNCode 1.4.17.
  Những hàm dựng dữ liệu này chạy cục bộ; yêu cầu HTTPS thật sử dụng Secret
  Catalog đã được cấu hình cho đúng host chính thức.
- Chỉ cấp **một GTIN**, tạo **một thẻ**. Đã đọc lại HTTP 200, xác minh GTIN,
  `good_id` dương và đủ 10 thuộc tính gửi đi của chính biến thể: article,
  màu, size/hệ size, tên, thương hiệu, chất liệu, giới tính, quy chuẩn và giấy tờ.
- Trạng thái đọc lại: **`good_status=draft`,
  `good_detailed_status=[moderation]`, `good_signed=false`**.
  Thẻ đã tồn tại trên Catalog và đang chờ duyệt, chưa được xác nhận xuất bản.
- Không ghi GTIN vào WB/Ozon trong phép thử này.

## Hai phản hồi từ chối đã xử lý

1. Catalog trả mã **31**: nhóm TN VED `6112110000` không cho `is_kit`.
   Dùng chính `CatalogPackingPolicy` của JAR để bỏ cờ sau phản hồi
   `Rejected` chắc chắn.
2. Catalog trả mã **55**: không tải được ảnh từ CDN WB. Bỏ ảnh tùy chọn sau
   phản hồi `Rejected` chắc chắn. Nội dung sản phẩm và giấy tờ giữ nguyên.

Có tổng cộng ba lần gửi feed cho **cùng một GTIN**: lần đầu bị hai lỗi,
lần tiếp theo chỉ còn lỗi ảnh, lần cuối tạo được thẻ và chuyển kiểm duyệt.
Không gửi lại yêu cầu còn đang xử lý; không cấp GTIN thứ hai.
Đây là phép thử API có dùng chính sách sửa của ứng dụng, không phải bằng chứng
đã chạy trọn nút Đăng ký trên Windows hoặc nghiệm thu license production.

## Phần còn cần thiết bị ký

Môi trường hiện không có CryptoPro, công cụ ký hoặc chứng thư riêng của seller.
Sau khi Catalog duyệt, cần ký bằng chứng thư doanh nghiệp trên Windows rồi
đọc lại `published`. Chưa gửi chữ ký và không dùng license fixture cho bất kỳ
yêu cầu API thật nào. Bằng chứng tạo thẻ này không thay thế nghiệm thu chữ ký,
ghi GTIN trên sàn hoặc license production và không tự chuyển Latest.

Biên nhận cấp GTIN/feed cùng phản hồi đầy đủ được giữ riêng ngoài Git tại
`/tmp/vncode-one-real-card/`; GTIN và mã thẻ đã báo trực tiếp cho người dùng.
Bản tổng hợp không chứa khóa API hoặc dữ liệu chi tiết của seller:
[2026-10-18-vncode-real-card.json](2026-10-18-vncode-real-card.json).
