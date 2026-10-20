# VNCode 1.5.3

- Đăng ký Znack WB/Ozon: lưu toàn bộ lô đã chọn trước khi xử lý, gửi lần lượt từng thẻ lên Catalog. Chỉ tính đã gửi và chuyển thẻ sang Đang xử lý sau khi có kết quả nhận feed; trạng thái xếp hàng hoặc cấp GTIN không được tính là đã đăng ký thành công.
- Trang đăng ký vẫn cho tìm kiếm, đổi bộ lọc và xem các thẻ đang chờ duyệt trong lúc chạy. Báo cáo lưu đủ từng thẻ; tiến độ phân biệt đã gửi, còn lại và cần xử lý. Thẻ đã có yêu cầu gửi chưa rõ kết quả được đối chiếu trước khi thử tiếp.
- Lô đăng ký được giữ trong dữ liệu cục bộ để tiếp tục sau khi có mạng hoặc mở app lại. Đóng cửa sổ khi đang đăng ký sẽ chuyển xuống khay hệ thống nếu hệ thống hỗ trợ, có nút mở lại và thoát. Khi đã thoát hẳn tiến trình hoặc máy tắt, công việc tiếp tục sau lần mở app kế tiếp.
- Cập nhật và khởi động: giảm kiểm tra, sao lưu lặp và chuẩn bị dữ liệu trên luồng giao diện. Không báo hoàn tất 100% trước bước bàn giao bộ cài; giữ xác minh bộ cài, checksum, dữ liệu SQLite/WAL và bản sao phục hồi.
- Giữ dữ liệu đã đồng bộ của 1.5.2, mẫu đăng ký mặc định, phạm vi TN VED của giấy tờ, các luồng đồng bộ và xử lý KIZ. Bổ sung kiểm tra nâng cấp giữ dữ liệu từ bộ cài 1.5.2 có checksum cố định.
- Ozon FBO: sản phẩm cần KIZ chuyển từ ba nhãn riêng sang một nhãn ghép 58×40 mm có DataMatrix KIZ, barcode sản phẩm, tên, article, màu và size. In lại từ lịch sử giữ đúng nhãn đã xuất, không lấy thêm KIZ. Sản phẩm không cần KIZ giữ số bản barcode hiện có.
- Ozon FBS dùng cùng mẫu nhãn ghép; giữ nguyên các trang nhãn vận chuyển chính thức.

Bộ cài chỉ được phát hành sau khi kiểm thử Java Windows mới và toàn bộ kiểm tra bộ cài/nâng cấp giữ dữ liệu đạt; bằng chứng đi kèm trong bản phát hành. Phiên kiểm thử có đầy đủ quyền theo ủy quyền của chủ dự án. Chưa nghiệm thu đăng ký/ký thẻ mới bằng chứng thư CryptoPro thật; đọc Catalog thành công không phải bằng chứng tạo thẻ mới. Khi nâng từ 1.5.2, quá trình tải và bàn giao bộ cài ban đầu vẫn do mã của 1.5.2 thực hiện; các sửa trình cập nhật có hiệu lực sau khi cài bản mới.
