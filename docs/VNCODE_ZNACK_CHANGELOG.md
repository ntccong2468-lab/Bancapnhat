# Thay đổi Znack/GTIN/KIZ — nhánh kiểm toán nguồn

Ngày 21.10.2026, Europe/Moscow. Nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`, nguồn 1.5.7. Đây là bản bàn giao mã để review, chưa phải bản phát hành cho seller.

## Đã sửa trong mã nguồn

- Giữ nguyên các giao dịch mua KIZ đã gửi hoặc chưa rõ kết quả khi nâng cấp/chuẩn hóa GTIN. Không đưa order có receipt về hàng mua mới; giao dịch cũ có order ID tiếp tục đọc lại.
- Phản hồi SUZ sai hoặc thiếu dữ liệu giữ trạng thái chưa xác minh, không được diễn giải là chưa mua để đặt lại đơn.
- Mua KIZ FBO kiểm tra bằng chứng Catalog còn mới và đúng sản phẩm, màu, size, chủ thể; sửa định dạng khóa yêu cầu UUID cho lô nhiều GTIN.
- Xuất nhãn KIZ/WB lưu bản nhãn và mã đã chọn trước khi trừ kho; việc xác nhận sử dụng mã và checkpoint lịch sử in cùng transaction. Nếu xuất file thất bại sau khi trừ kho, mở Lịch sử in để xuất lại đúng mã và thông tin cũ, không mua hoặc trừ thêm mã. Chặn lượt xuất đồng thời trùng phạm vi và giữ bản phục hồi qua khởi động lại.
- Đối soát lưu thông/readiness xác minh đúng từng CIS và GTIN; phản hồi đủ số dòng nhưng trùng, thiếu hoặc thuộc mã khác không còn được xác nhận thành công.
- Đối soát CIS và xác minh kho KIZ nhận mã thành công `errorCode: 0` hoặc `"0"`; mã lỗi thật, kiểu dữ liệu sai và thông báo lỗi vẫn chặn. Giữ kiểm tra danh tính mã, GTIN, chủ thể và trạng thái lưu thông.
- Hủy/timeout CryptoPro dọn tiến trình đã quan sát và giới hạn chờ đọc đầu ra. Probe CAdESCOM giữ mã hủy/timeout chính xác; không đổi thành lỗi thiếu component hoặc tiếp tục thử host sau hủy.
- Danh sách lỗi thẻ WB dùng quota riêng đúng tài liệu; trang sản phẩm WB có số dòng mâu thuẫn với cursor không được báo đã đọc hết hoặc ghi đè cache tốt.
- Chẩn đoán feed Catalog đọc thêm định dạng `result.result` được công bố; giữ lý do lỗi và phạm vi dòng, che thông tin nhạy cảm.
- Thẻ nháp đã có trên Catalog của WB/Ozon có đường kiểm tra và gửi duyệt sau khi seller xác nhận toàn bộ dữ liệu. Giữ nguyên GTIN và `good_id`, gửi cập nhật với `moderation=1`; không xin mã mới, tự ký hoặc tự ghi GTIN lên sàn. Lưu snapshot duyệt và checkpoint trước POST, kiểm tra lại thẻ sát thời điểm gửi; kết quả chưa rõ không được tự gửi lặp. Kiểm tra hàng loạt dùng một lần đọc/index Catalog cho cả lượt, không quét lại toàn Catalog cho từng sản phẩm.
- Bảng đăng ký WB/Ozon hiện riêng Size RU, trạng thái đăng ký, kiểm duyệt Catalog và kết quả GTIN trên sàn cùng thời điểm đọc. Không lấy feed accepted hoặc cờ cục bộ thay cho kết quả máy chủ.

## Bảo toàn hành vi hiện có

Giữ kiến trúc JavaFX/service/repository/hàng đợi hiện có, SQLite schema 6, quy tắc GTIN và giấy tờ, phê duyệt của seller, license, ưu tiên FBS, dữ liệu đã đồng bộ và các luồng giá/sao chép sản phẩm. Không sao chép mã từ các repository tham khảo, không thêm dịch vụ trả phí.

## Kiểm thử và giới hạn

Kết quả từng lượt, các lỗi phát hiện trong kiểm thử, lệnh build và giới hạn môi trường được lưu trong [báo cáo kiểm thử](VNCODE_ZNACK_TEST_REPORT.md). Kết quả cuối: 393 lớp/2.788 ca gồm 2.786 đạt, 0 thất bại/lỗi, 2 ca Windows chưa chạy trên Linux; Node 103/103; build JAR và mở cửa sổ chính cục bộ đạt. Không dùng kết quả baseline thay cho nghiệm thu nguồn mới.

Chưa nghiệm thu Windows/CryptoPro/chứng thư thật, license production, máy in vật lý hoặc ghi dữ liệu production. Không tạo thẻ/cấp GTIN/mua KIZ/ký pháp lý/cập nhật marketplace thật trong lần kiểm thử này. Chưa tạo bộ cài Windows, chưa merge/push hoặc thay Latest.

## Tài liệu bàn giao

- [Kiểm toán mã nguồn và các phát hiện](VNCODE_SOURCE_AUDIT.md).
- [Repository, commit, giấy phép và tài liệu API tham khảo](VNCODE_REFERENCE_EVIDENCE.md).
- [Kế hoạch, quyết định triển khai và phạm vi còn chưa xác minh](VNCODE_ZNACK_UPGRADE_PLAN.md).
- [Kết quả kiểm thử/build](VNCODE_ZNACK_TEST_REPORT.md).
