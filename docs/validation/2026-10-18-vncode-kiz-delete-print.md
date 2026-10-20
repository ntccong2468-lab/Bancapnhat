# Kho KIZ: xóa danh mục, in/xuất lại và định nghĩa mã hỏng

Trạng thái: sửa trên mã nguồn sau bản nền 1.4.19 (`0cee8c2`), **chưa phát hành**. Giữ các sửa GTIN/CryptoPro đang có trong workspace.

## Định nghĩa đã được seller làm rõ

“Mã hỏng” trong yêu cầu là **mã bị WB/Ozon từ chối, hiển thị КИЗ màu đỏ**. Số `Preview.damaged` hiện có chỉ đếm mục không đọc được hoặc sai cấu trúc trong tệp; đó không phải kết quả xác minh của sàn.

Vì vậy báo cáo nhập đã đổi thành “mã đúng định dạng / lỗi định dạng” và hiển thị riêng **WB/Ozon chưa kiểm tra khả năng chấp nhận mã**. Không hiển thị `0 mã hỏng` để ngụ ý mã đều được sàn chấp nhận. Giữ bộ lọc cấu trúc hiện có, nhận lại tên tệp/nội dung/mã từng dùng theo yêu cầu seller, và không thêm kiểm tra Znack hoặc buộc trùng GTIN sản phẩm.

Chưa có chức năng xác minh hàng loạt việc sàn chấp nhận mã ngay lúc tải tệp. Chỉ tệp văn bản và kiểm thử giả lập không thể xác nhận trạng thái đỏ của một mã thật. Lỗi API, mạng hay trạng thái đơn cũng không đủ để kết luận bản thân mã đã hỏng; cần kết quả gắn với đúng mã và đơn. Luồng in Ozon hiện có chủ yếu giữ và in mã cục bộ; trạng thái in nội bộ không phải bằng chứng Ozon chấp nhận mã.

## Lỗi tái hiện và sửa

1. **Ozon in lại sau xóa danh mục:** tác vụ vẫn giữ mã và chứng cứ cấp mã, nhưng chuẩn bị in lại suy ra thiếu SKU/GTIN do danh mục không còn trong danh sách hiển thị. Sửa dùng chứng cứ cấp mã đã giữ của chính posting, item, sản phẩm gốc và chủ shop. Đơn mới không được mượn chứng cứ đó. Không mở lại danh mục đã xóa hoặc tự mua/thay mã khi in lại.
2. **Tồn kho ẩn sống lại:** hủy in/khôi phục sau gián đoạn đưa mã đang giữ về `AVAILABLE` dù danh mục đã bị seller xóa. Sửa trả về `ARCHIVED` trong trường hợp đó; giữ nguyên cấp phát hoàn thành, mã đang giữ và lịch sử. Khởi động app sửa các dòng `AVAILABLE` còn sót từ đường trả mã cũ theo cách lặp lại an toàn.
3. **WB nhập được nhưng xuất nhãn lỗi mã trùng:** kho chấp nhận các lần nhập độc lập cùng nội dung, nhưng bước in vẫn chặn theo chuỗi mã. Sửa kiểm tra trùng đơn vị tồn kho theo ID. Giữ chính xác bản ghi mã nguồn cho từng đơn, tránh gộp hai mã nguồn cùng nội dung hoặc gán nhầm bản ghi cho đơn đã có mã trên sàn.

## Giả lập đã chạy

Ngày 18/10/2026, kết thúc **11:37:56 UTC**: **147 kiểm thử, 23 lớp, 0 failure, 0 error, 0 skip**, Java 25.0.4.1/Linux/JavaFX/Xvfb. [Kết quả chi tiết](2026-10-18-vncode-kiz-delete-print.json).

- Hai kiểm thử xóa danh mục ban đầu tái hiện `AVAILABLE` thay vì `ARCHIVED`, và `MissingMappingException` trên Ozon. Hai kiểm thử WB ban đầu tái hiện `DUPLICATE_KIZ_RESERVATION`.
- JavaFX: bấm nút Xóa và xác nhận thật trong giao diện, làm mới không tạo lại danh mục; nhập tệp và đọc báo cáo trạng thái xác minh.
- WB: dùng kho/SQLite, tạo PDF nhãn và phiếu thật, lưu lịch sử, in lại từ bản chụp lịch sử. Dùng metadata sàn giả lập; kiểm tra mã nguồn khác ID dù cùng chuỗi, không sửa liên kết của đơn đã có mã.
- Ozon: MockWebServer mô phỏng đọc posting; dùng hàng đợi cấp phát và bộ xuất PDF production, tạo nhãn và in lại sau xóa danh mục, giữ nguyên ID cấp phát và chỉ trừ kho một lần. Đơn khác không dùng lại chứng cứ của posting cũ.
- Bao gồm kiểm thử nhập tệp, schema và dữ liệu cũ, kho hai sàn, FIFO, luồng mua hiện có, lịch sử, quyền license, dự trữ và trả mã.

[Ảnh báo cáo nhập trên ứng dụng giả lập](evidence/2026-10-18-kiz-import-verification-state.png) chỉ chứa dữ liệu/số lượng giả. Không gửi KIZ thật lên sàn, không mua mã, không thử mã thật bằng API và không nghiệm thu bộ cài Windows trong lượt này.
