# Ozon FBS: bỏ nhãn sản phẩm trùng

PDF Ozon thông dụng gồm một trang vận đơn và một trang barcode sản phẩm. Luồng cũ giữ cả hai rồi thêm nhãn kết hợp KIZ/barcode, thành ba nhãn. Luồng mới giữ vận đơn và thay trang sản phẩm đã được bao phủ đầy đủ bằng nhãn kết hợp: đơn một sản phẩm còn hai nhãn. Số trang trong kết quả xuất và in hàng loạt tính theo các trang thực sự giữ lại.

Áp dụng chung cho in một đơn, in lại và in tất cả. Trang vận đơn giữ nguyên nội dung và kích thước. Việc nhận dạng dựa trên barcode sản phẩm đã đồng bộ và đủ KIZ cho mọi đơn vị tương ứng; giữ các trang có QR/DataMatrix vận chuyển, mã khác, định danh đơn hoặc bố cục chưa xác định. Đơn không có nhãn kết hợp vẫn giữ nhãn sản phẩm. Không thay nhãn kết hợp, giấy nhặt hàng, quy tắc KIZ, license, API hoặc schema dữ liệu.

Kiểm thử offline trên PDF tổng hợp đạt **46/46**, không thất bại, lỗi hoặc bỏ qua. Kiểm tra gồm xuất đúng hai nhãn, đối chiếu nguyên byte nội dung trang vận đơn, quét KIZ và barcode ở 203/300 DPI, nhiều đơn vị, in lại, in hàng loạt có/không KIZ và luồng 1.000 đơn. Bộ nhận dạng cũng giữ được DataMatrix vận chuyển nhỏ dạng chữ nhật cạnh barcode sản phẩm. [Kết quả và checksum mã đã kiểm thử](2026-10-20-ozon-two-label-checks.json).

Đây là bản sửa mã nguồn cho lần cập nhật tiếp theo. Không tạo bộ cài Windows hoặc phát hành mới trong thay đổi này; tag/bộ cài 1.5.4 đã công bố được giữ nguyên.
