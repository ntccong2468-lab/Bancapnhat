# VNCode 1.5.1

**Đang phát triển — chưa phát hành lên Latest.** Giữ nền bản 1.5.0 đã phát hành và đưa toàn bộ thay đổi tiếp theo vào 1.5.1.

- **Tăng tốc GTIN WB/Ozon:** gộp nhiều biến thể vào lô theo hạn mức chính thức, hỗ trợ tác vụ 10.000 GTIN; chia sẻ chỉ mục sản phẩm và đọc lại theo lô. Giữ barcode cũ, các biến thể, giấy tờ và dữ liệu khác của bài đăng. Chỉ tính thành công sau khi đọc lại đúng biến thể từ sàn; giữ checkpoint cho yêu cầu chưa rõ kết quả.
- **KN3 trong shipment:** xanh khi sàn chấp nhận, đỏ khi sàn từ chối; kết quả còn chờ hoặc chưa đọc được không được báo xanh. Cập nhật theo luồng FBS hiện có, ưu tiên shipment đang mở.
- **Thêm lại KIZ:** nút chỉ xuất hiện khi có KN3 đỏ. Bảng đơn lỗi có **Xác nhận / Hủy**. Xác nhận đóng bảng, lấy mã từ nguồn KIZ đã chọn và chạy nền; viên thuốc giữa phía dưới hiển thị **Đang gán KIZ** rồi **Đã gán KIZ xong** sau khi đọc lại sàn và xác nhận phạm vi đang xử lý không còn đỏ/chờ.
- **Giữ kho và nhãn KIZ:** không xóa mã WB trước khi gửi mã thay; giữ các exemplar khác trên Ozon. Giữ riêng mã đã gửi nhưng chưa rõ kết quả; hoàn trả mã chắc chắn chưa được gửi hoặc bị từ chối ở cấp yêu cầu mà chưa áp dụng. Giữ nguyên lịch sử kho/in và dùng mã thay đã xác nhận khi lập nhãn KIZ Ozon.
- **Shipment nhỏ và nhiều trang:** nút WB xuống dòng trong cửa sổ nhỏ; báo cáo Ozon bao gồm đơn ngoài trang đang xem, phân biệt đúng tab, bộ lọc và shop.

Phần đăng ký Znack và cập nhật GTIN đang được rà tiếp theo yêu cầu ngày 19.10.2026; các sửa tiếp theo sẽ bổ sung tại đây trước khi phát hành.

**Bằng chứng đã lưu:** [GTIN theo lô](../validation/2026-10-19-gtin-bulk.md), [KN3 và thay KIZ](../validation/2026-10-19-kn3-replacement.md). Các kết quả đó có phạm vi kiểm thử riêng, không thay thế nghiệm thu bản Windows 1.5.1 hoặc thao tác tạo/ký thẻ và ghi sàn bằng phiên seller thật.
