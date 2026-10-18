# Xác nhận ghi GTIN thật lên WB — VNCode 1.4.19

**Đã ghi và đọc lại thành công một biến thể thật.** [Bằng chứng](2026-10-18-vncode-1419-wb-live-gtin.json).

| Mục | Kết quả |
| --- | --- |
| Sản phẩm | Bolong den adidas, đen (`черный`), 3XL / Nga 54 |
| WB | nmID `443045288`, chrtID `627025126` |
| GTIN thêm | `04650738830910` |
| Thẻ Catalog | Đọc lại API thật: published, signed, trade-unit; thuộc danh sách tài khoản; article/màu/size Nga/TN VED khớp |
| Ghi WB | Một POST `content/v2/cards/update`, HTTP 200, `error=false` |
| Đọc lại | 11:15:49 ngày 18/10/2026, giờ Moscow: GTIN xuất hiện đúng biến thể, barcode 2→3 |
| Kiểm tra độc lập | 11:19:24 Moscow: tra cứu riêng bằng GTIN và nmID đều trả đúng biến thể; không gửi thêm cập nhật |
| Bảo toàn | Hai barcode cũ, bốn biến thể còn lại, ảnh, tiêu đề, thương hiệu, mô tả, kích thước, thuộc tính và dữ liệu giấy tờ gửi lại giữ nguyên |

Phép thử gọi trực tiếp API WB bằng token môi trường và dùng bộ dựng payload production `WbGtinAdapter` tại source `88ed74d`; không cài license fixture cho thao tác mạng thật. Không tạo thẻ, cấp GTIN, mua KIZ hoặc đổi Latest. Raw request/response và thông tin xác thực giữ riêng ngoài Git.

Đây là bằng chứng WB chấp nhận GTIN và lưu trên sàn; không phải nghiệm thu thao tác bấm nút qua desktop Windows có license production hoặc CryptoPro.

Trong bước chọn mẫu, R21 be khớp article/màu/size nhưng TN VED trên WB thuộc nhóm 6112, còn thẻ cũ thuộc nhóm 6103. Mẫu này bị loại **trước khi ghi**. Đã bổ sung `GTIN_TNVED_GROUP_CHANGED`: không dùng thẻ cũ khi đọc được nhóm TN VED khác; kiểm tra khi ghép, xem trước và ngay trước xử lý GTIN. Các quan sát cũ thiếu TN VED tiếp tục dùng các kiểm tra định danh hiện có. Mục này không tự cấp GTIN hoặc tạo thẻ mới.

57 kiểm thử liên quan đạt: bao gồm tình huống thực 6112/6103, nhãn từ điển Ozon, chặn trước preview WB/Ozon, thay nhóm sau preview, báo cáo tác vụ tự động và xác minh readback WB.
