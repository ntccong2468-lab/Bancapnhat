# Sửa thương hiệu theo lô: Invalid identity or value

Người dùng báo nút Áp dụng sửa thương hiệu dừng với `Invalid identity or value [VN_LOCAL]` trên nền 1.5.17.

## Nguyên nhân

Trang Sản phẩm WB gộp các size thành một dòng sản phẩm, nên `ProductRepository.freezeProductKeys` trả mã sản phẩm với `variantId` rỗng. Khi chọn từ 20 sản phẩm và đủ điều kiện dùng chỉ mục theo lô, `ProductEditApiSession.convert` chuyển các khóa này thành `GtinSyncModels.ProductKey`, vốn bắt buộc có mã biến thể. Constructor ném lỗi trước khi gửi cập nhật lên sàn. Cùng lỗi xảy ra với thương hiệu, chất liệu, giới tính, TN VED và giấy tờ.

Các bài kiểm thử theo lô cũ cung cấp mã biến thể, nên không tái hiện khóa sản phẩm mà giao diện thực tế sử dụng.

## Thay đổi

- Phiên sửa sản phẩm dùng `prepareProducts` / `refreshProducts` của chỉ mục để chọn theo mã sản phẩm và xác minh đúng cửa hàng/sàn.
- Giữ luồng đọc nhanh theo lô và đọc lại sau cập nhật. Các mã size/offer, barcode và quyền sở hữu trong chỉ mục vẫn lấy từ dữ liệu sàn.
- Luồng GTIN tiếp tục dùng khóa biến thể bắt buộc hiện có. Không tạo mã size giả, không thay barcode khi sửa thương hiệu, không nới điều kiện xác nhận cập nhật thành công.

## Kiểm chứng

- Trước sửa, cả 5 trường đều tái hiện đúng exception tại `ProductEditApiSession.convert` bằng khóa lấy từ bộ lọc sản phẩm thật trên SQLite tổng hợp.
- Sau sửa, 7 trường hợp qua đường tự chọn chỉ mục đạt: thương hiệu với 1/19/20 sản phẩm và 4 trường còn lại với 20 sản phẩm. Gửi payload bằng reader/adapter thật qua transport giả lập, đọc lại đủ từng sản phẩm, tiến trình đủ số thành công; size/barcode và giấy tờ cũ được giữ. Gọi Áp dụng lại không gửi lặp.
- Bài 1.000 sản phẩm WB dùng khóa cấp sản phẩm rỗng size; Ozon giữ offer thật. Có kiểm tra phạm vi cửa hàng/sàn, chỉ mục đóng và barcode mới khi làm mới. Hồi quy **302/302 ca đạt**, không lỗi/thất bại/bỏ qua, 8 phút 35 giây; gồm giao diện Áp dụng, sửa sản phẩm, adapter WB/Ozon và pipeline GTIN 10.000 mã. [Biên nhận kết quả](../validation/2026-10-26-product-card-bulk-edit-identity-verify.json).

Đây là sửa mã nguồn trên nhánh `fix/product-card-bulk-edit-identity`, nền bản đã phát hành 1.5.17. Chưa đóng gói bộ cài hoặc thay Latest; không thay tag/binary 1.5.17. Kiểm thử dùng dữ liệu tổng hợp, SQLite và API giả lập; chưa ghi thương hiệu vào bài seller production hoặc nghiệm thu Windows native cho sửa này.
