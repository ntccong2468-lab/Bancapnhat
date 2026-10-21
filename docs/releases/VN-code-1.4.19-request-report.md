# Đối chiếu yêu cầu sửa VNCode 1.4.19

Đã báo người dùng trước khi build/phát hành ngày 18/10/2026, theo yêu cầu:
“phát hành bản mới nhưng báo cáo trước cho tôi những gì chỉnh sửa tôi giao đã sửa chưa”.

| Yêu cầu mới nhất | Đã chỉnh sửa | Bằng chứng và giới hạn |
|---|---|---|
| Giữ bản mới nhất đã phát hành làm nền | Có, nền 1.4.18 được ghi theo tag/commit bất biến | [Bản nền](BASELINE.md); 1.4.19 kế thừa các chức năng đã có |
| Đồng bộ toàn cửa hàng, không chỉ 20 sản phẩm | Có, quét lại và lấy chi tiết toàn bộ, không phụ thuộc trang/bộ lọc | API thật: WB 388 sản phẩm/1.982 biến thể, Ozon 175 sản phẩm; kiểm thử nhiều trang |
| TN VED, giấy tờ, chất liệu, giới tính, thương hiệu, barcode và GTIN | Có, đọc/persist chi tiết và giữ trường thực sự thiếu | [Báo cáo kiểm tra](../validation/2026-10-18-vncode-1419.md); dữ liệu thật đã được kiểm tra parser/projection |
| Cập nhật GTIN đứng 0/tổng | Có, từng biến thể, tiến độ sau từng kết quả, lỗi riêng từng dòng | Một lần ghi thật WB đã readback thành công qua API; nút ghi trên Windows chưa nghiệm thu được |
| Thêm xóa danh mục kho KIZ | Có, chung FBS WB/Ozon, không tự tái tạo sau làm mới | Có xác nhận xóa, bảo toàn mã giữ và lịch sử đơn |
| Nhận lại tệp cùng tên, mã trùng, mã từng dùng | Có, mỗi lần tải là lô mới và giữ từng lượt mã | Không xét Znack/GTIN/historical duplicate cho kho seller; kho mua giữ quy tắc hiện có |
| Giữ lọc mã hỏng, báo cáo và cập nhật số mã ngay | Có, tự nhận mã hợp lệ rồi hiện báo cáo | File mẫu: 600 dùng được, 0 hỏng; kiểm thử tệp hỗn hợp và cập nhật số lượng sau nhập/giữ |
| Xác nhận cập nhật GTIN thật lên WB | Đã xác nhận qua API | [Một GTIN thật, đúng biến thể, barcode cũ còn nguyên](../validation/2026-10-18-vncode-1419-wb-live-gtin.md) |
| Nghiệm thu bằng nút trong ứng dụng | **Chưa hoàn tất** | [Đã mở ứng dụng, nút bị khóa trong cloud Linux không có phiên Windows seller hợp lệ](../validation/2026-10-18-vncode-1419-button-acceptance.md) |

Không tuyên bố tất cả luồng thật đã được nghiệm thu. Báo cáo trên phân biệt mã đã sửa,
kiểm thử tự động, API thật và thao tác qua ứng dụng Windows.
