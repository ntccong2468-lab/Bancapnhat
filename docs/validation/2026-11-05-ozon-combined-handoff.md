# PDF Ozon thiếu nhãn kết hợp — bàn giao 05.11.2026

Nhánh `fix/znack-state-wb-gtin-20261105`, kế thừa sửa Znack `33f3d6c` và bộ nhớ `69f9a8b`. Người dùng đã yêu cầu gộp **cả Znack và PDF Ozon** thành bản mới xuống app. Chuẩn bị **1.6.4**, nâng cấp từ Latest 1.6.3; chưa đổi Latest ở thời điểm ghi bàn giao này.

## Nguyên nhân và sửa

- Kết quả kiểm tra kho trước khi in được truyền thành `carrierOnly` và bỏ qua cả KIZ đã giữ cho đơn. Giờ đó là kết quả tạm; đơn có binding đã giữ/đã in được kiểm tra lại thay vì tự bỏ nhãn kết hợp.
- Khi đồng bộ phản hồi KIZ bị từ chối, `Kn3Repository.observe` loại mã khỏi kho khả dụng; trạng thái mã chuyển sang `CONSUMED / REJECTED`. Bộ in chỉ chấp nhận `RESERVED`, còn projection chung lại cấm mã đỏ. Đường in Ozon nay cho in đúng mã đã gắn với posting/product/exemplar, có nguồn và phản hồi từ chối tương ứng. Mã vẫn bị loại khỏi kho, trạng thái từ chối API vẫn giữ nguyên. Kiểm tra trước gửi API không dùng ngoại lệ in này.
- Nhãn Ozon đối chiếu KN3 bằng `product_id` của dòng đơn, không lấy nhầm SKU thay một ID khác. Ngoại lệ in và thay thế được giới hạn theo exemplar; thay mã chưa có kết quả không được dùng để in nhầm mã cũ/mới. Luồng WB mặc định giữ quy tắc cũ.
- Một trang chưa xác định được đơn từng làm mất KIZ của cả tác vụ. Nay những trang đã có danh tính vẫn ghép đúng KIZ; trang chưa rõ giữ riêng, có lý do thiếu và không đoán theo vị trí.
- DataMatrix vẽ với bước lẻ của điểm in 203 dpi khiến một chuỗi trong lô 100 không đọc được. Căn các ô và góc mã vào lưới điểm in 203 dpi, vẫn dùng vector và giữ khung 58 × 40 mm. 100 mã đều giải mã đúng ở 203 và 300 dpi sau sửa.

Vận đơn gốc vẫn được sao chép nguyên nội dung; tải theo lô; thứ tự vận đơn → KIZ đúng đơn; phiếu nhặt cùng thứ tự. Không thêm lại trang barcode Ozon lớn riêng. Kho seller nhập vẫn dựa vào đúng danh mục/nguồn, không bắt GTIN bằng sản phẩm; kho mua vẫn giữ kiểm tra mapping. Không đổi schema, thông tin license hay chạy giao dịch seller thật trong tác vụ này.

## Kiểm chứng

- **191 test / 18 lớp đạt**, 0 thất bại/lỗi/bỏ qua; Java 25 trên Linux. [Biên nhận](2026-11-05-ozon-combined-tests.json).
- Tái hiện ba lỗi bỏ nhãn do preflight/trạng thái từ chối: 3/3 đỏ trước sửa, xanh sau sửa. Một ca trang chưa nhận diện làm mất KIZ cả lô: đỏ trước sửa, xanh sau sửa.
- Lô tổng hợp **100 đơn**, PDF **200 trang mỗi lần**: 100 vận đơn + 100 KIZ. Xuất rồi in lại: 2 lượt lấy lô, 0 lượt tải riêng theo đơn, vẫn chỉ 100 mã đã dành cho 100 đơn. Đọc độc lập bằng PDFBox/ZXing đủ 400 lượt mã ở hai độ phân giải, đối chiếu đúng mã của từng trang.
- Có kiểm tra phản hồi từ chối giữ nguyên, không lấy lại mã từ kho đã loại, không in nhầm mã của đơn khác/chủ tài khoản khác, không gửi mã bị từ chối chỉ vì được phép in, chờ thay KIZ, rollback file, FBS/FBO, kho nhập/mua và lịch sử in. Ca 101 đơn nhận và 1.000 vận đơn là HTTP/SQLite/PDF mô phỏng, không phải nhận/in 1.000 đơn thật.
- [PDF xem trước bốn trang tổng hợp](assets/ozon-combined-20261105/paired-fixture-preview.pdf), [ảnh nhãn 203 dpi](assets/ozon-combined-20261105/combined-fixture-203dpi.png). Đây là dữ liệu kiểm thử, không phải đơn hoặc KIZ thật của seller.

## Phát hành tiếp theo

Khóa commit chứa cả Znack và PDF, chạy full Windows/native/cài-gỡ/nâng cấp 1.6.3 → 1.6.4. Đối chiếu EXE/MSI/hash/source/manifest và updater trước công bố Latest. Giữ toàn bộ bản đã phát hành; không đưa prototype credential migration vào runtime hoặc triển khai portal. Cập nhật biên nhận phát hành và `docs/memory/CURRENT.md` sau khi công bố thật.

Kinh nghiệm: test cũ chỉ ghi `accept_kiz=rejected` mà chưa mô phỏng đồng bộ KN3 nên không bắt được thay đổi trạng thái kho. Kiểm thử PDF phải đi qua cả readback → trạng thái kho → dựng mã → công bố file, không chỉ đếm trang. Quét nhiều serial ở DPI máy in phát hiện được lỗi mà một mã mẫu không thể hiện.
