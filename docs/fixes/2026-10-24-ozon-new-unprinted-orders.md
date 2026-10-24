# Ozon FBS: tách đơn chưa in khỏi đơn đã xuất nhãn

Yêu cầu bổ sung của chủ dự án trong khi chuẩn bị 1.5.14. Đã hủy lượt Windows `40077739159` của ứng viên `e6aa47c8b961149895af4e475e127155c1a2bb12` để chốt lại toàn bộ mã, không công bố ứng viên thiếu thay đổi này.

Bốn nhóm đơn và tab Kho KIZ:

| Nhóm | Điều kiện |
| --- | --- |
| Chờ nhận | Ozon `awaiting_packaging` |
| Đơn mới | Ozon `awaiting_deliver`, chưa có xác nhận xuất PDF thành công trong phạm vi tài khoản |
| Đang giao hàng | Ozon `awaiting_deliver`, đã xuất PDF thành công; có dòng «Đã xuất nhãn, đang chờ bàn giao cho Ozon» |
| Đã bàn giao | Ozon `delivering` |

«In tất cả» trong Đơn mới lọc ngay bằng SQL trước đếm, sắp xếp, phân trang và đóng băng danh sách. Không chỉ lấy 100 dòng đang hiển thị. Đơn mới xuất hiện sau khi đóng băng danh sách để lượt in tiếp theo xử lý. Nhãn lỗi, hủy hoặc chỉ tải nhãn vận đơn chưa đủ để chuyển nhóm. Đã in vẫn có thao tác in lại từng đơn; việc phân nhóm cục bộ không gọi API chuyển đơn sang vận chuyển.

Bảng additive `ozon_fbs_print_receipts` lưu shop/posting/owner hash và thời điểm, không lưu raw KIZ hoặc dữ liệu người mua. Chỉ ghi sau publish toàn bộ nhãn và picking PDF thành công, kể cả đơn miễn KIZ. Lô chỉ ghi khi toàn bộ tệp chung đã xuất xong. Đọc lại biên nhận theo đúng owner; không suy từ tác vụ nhãn đang tải hoặc exemplar đã chuẩn bị sang kết luận đã in. Backfill các báo cáo cũ có item SUCCEEDED/FBS_PDF_CREATED và bằng chứng pdfBatchComplete=true, đúng shop/posting/owner. Cập nhật cache bình thường không xóa biên nhận; receipt FK theo đúng posting/shop. Không tăng schema version cho bảng bổ sung tương thích.

136 ca mục tiêu đạt (log `/tmp/vncode-1514-all-changes-targeted.log`). Bao gồm:

- Chọn Đơn mới, bấm In tất cả thật trên JavaFX, kiểm tra bảng xác nhận chỉ có đơn mới và hủy trước xuất; sau receipt thành công, đơn chuyển nhóm; đồng bộ đơn mới thêm không trộn lại đơn cũ.
- 1.001 đơn, 650 receipt: còn 351 đơn mới, phân trang 100/51 và tìm kiếm/đếm/freeze cùng điều kiện. Kiểm tra idempotency, khác shop, owner thay đổi, posting không tồn tại và rollback.
- Khôi phục database trước bảng receipt, backfill chỉ báo cáo thành công, chạy init hai lần, integrity_check và foreign_key_check sạch.
- PDF thành công ghi receipt, lỗi PDF/thiếu quyền không ghi. Giữ các ca hai nhãn, 1.000 đơn, đọc barcode/DataMatrix, in lại và reservation.
- 57 FxmlSmokeTest, các ca bốn nhóm trên bốn ngôn ngữ, hai theme và hai cỡ cửa sổ; kiểm tra nút/khung phân trang không bị cắt.

Full Linux trên SHA cuối đạt 2.986 ca (2.984 đạt, 2 bỏ qua OS), không lỗi/thất bại, lúc 08:59:55 UTC. Windows đạt cùng số ca; native và nâng cấp từ 1.5.13 giữ dữ liệu đạt. **1.5.14 đã lên Latest lúc 09:34:12 UTC**, runtime công khai chứa lớp receipt/PrintState và FXML bốn nhóm đúng mã chốt `09718c8`. [Bằng chứng phát hành](../validation/2026-10-24-vncode-1514-published-latest.json). Không nhận/gán KIZ/mua có phí hoặc chuyển trạng thái đơn production để thử tính năng này.
