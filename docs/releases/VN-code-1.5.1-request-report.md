# Báo cáo yêu cầu VNCode 1.5.1

Đã viết luồng báo cáo/sửa thẻ Znack lỗi WB và Ozon: chọn tất cả, xem lỗi, Chỉnh sửa/Hủy, đề xuất trước/sau, bổ sung trường và giấy tờ, Áp dụng. Bảng đóng trước khi chạy nền; viên thuốc dưới giữa bảng theo dõi từng thẻ. Giữ GTIN, gửi đúng good_id, không coi receipt là thẻ đã xuất bản.

Theo yêu cầu tiếp theo, đã đọc lại API National Catalog 5.68 và đưa quy tắc vào mapper/validator mặc định v3. Giữ TN VED hợp lệ, chuẩn hóa node bốn/mười số, nhận đúng nhóm Green và schema có trường phụ thuộc. Phạm vi giấy tờ không khớp được báo rõ để seller xác nhận hoặc thay giấy tờ; không bịa đây là lý do từ chối của Catalog. Tiếp tục checkpoint cũ chưa gửi cũng mang đúng good_id. Thẻ xanh cũ được bảo vệ.

Giữ các thay đổi 1.5.1 đã lưu: GTIN theo lô WB/Ozon, KN3/thay KIZ đỏ trong shipment, sửa năm trường sản phẩm theo lô và đọc lại sàn, logo VN Liquid Glass. Giữ schema 6, nền 1.5.0 và ưu tiên FBS.

263 ca kiểm thử liên quan đã qua trong một lượt Linux/Xvfb; hai ca bổ sung cho checkpoint cũ được chạy riêng, 73 ca công cụ release qua. Nguồn Windows trước thay đổi đã qua 1913 ca, sau đó hủy build và publication để cập nhật quy tắc mới. Nguồn hiện tại cần lượt Windows/bộ cài mới trước khi đưa Latest; kết quả cuối cùng được ghi trong build-info và release-acceptance đính kèm khi phát hành.

**Chưa hoàn tất nghiệm thu production:** chưa tạo/sửa/ký thẻ thật bằng CryptoPro doanh nghiệp hoặc xác nhận license production và lượt ghi WB/Ozon thật từ bản desktop này. API thật trong đợt đối chiếu chỉ đọc thẻ, registry và schema; kiểm thử ghi dùng API giả lập. Việc phát hành theo yêu cầu chủ repo không đổi các mục chưa nghiệm thu thành đã qua.
