# Ozon: nhận đủ đơn và lấy nhãn theo lô — 29.10.2026

Mã nguồn `72aad02b8167190f4b2ae27d5853111b75441a0a`, nhánh `fix/ozon-large-fbs-batch-labels`, kế thừa bản công bố 1.5.18. Hash cây nguồn/test/POM `a19817fc368b95fa43b5771f4950465d04b32b014b70c0269d61bb1c8b854175`. Chưa tạo bộ cài hoặc thay Latest trong lượt sửa này; Latest vẫn 1.5.18 tại nguồn `5330e99`. Phiên phát hành tiếp theo phải tăng phiên bản, giữ binary/tag 1.5.18.

## Nguyên nhân và thay đổi

Ô chọn toàn bộ ở đầu bảng Ozon chỉ thêm 100 dòng đã render. Nút chọn tất cả theo bộ lọc riêng đã đọc mọi trang nhưng hành vi hai nút khác nhau. Header nay dùng cùng truy vấn chọn toàn bộ theo cửa hàng, trạng thái và tìm kiếm; giữ giới hạn lựa chọn 10.000, bảng chỉ render 100 dòng/trang. Trạng thái tích/khuyết tính trên toàn bộ tập lọc, bỏ tích xóa lựa chọn qua mọi trang. Tooltip và nội dung trợ năng ghi đúng phạm vi. Thao tác Nhận tiếp tục dùng giao thức nhận/tách/gán KIZ hiện có của Ozon cho từng đơn; không gửi một payload nhận nhiều đơn sai schema.

API danh sách v4 giới hạn 100 dòng mỗi trang; luồng cursor hiện có vẫn đọc đến `has_next=false`. Kiểm chứng thật chỉ đọc ngày 29.10: HTTP 200 cả 4 trang 100 + 100 + 100 + 3, tổng 303 đơn khác nhau, không trùng. Không có giới hạn tổng 100 đơn từ API. Có thêm fixture 303 đơn xác minh chính service lưu đủ trang cuối và gửi đúng cursor từng trang.

Lấy nhãn dùng `/v3/posting/fbs/package-label/create`, theo dõi `/v2/posting/fbs/package-label/get`, tối đa 1.000 đơn/tác vụ. Trước đây các receipt `v3s:` từ lần in riêng vẫn bị tải riêng khi chọn in toàn bộ. Nay gộp receipt lẻ READY không có dấu cách ly vào lô mới cùng đơn chưa có nhãn. Đọc job cục bộ theo nhóm 500 ID và ghi các job mới trong một transaction thay cho mở kết nối/insert riêng từng đơn. Lô 1.001 nhãn gồm 1.000 receipt lẻ và một đơn mới được kiểm thử với 2 create, 2 poll, 2 download khi tác vụ sẵn sàng ngay; thực tế có thể cần nhiều poll hơn.

Trước khi tạo tác vụ phải claim toàn bộ nhóm trong transaction, lưu ranh giới chưa biết kết quả. Bất kỳ job thay đổi/chưa xong nào làm rollback toàn bộ claim. Mất receipt mới giữ RECONCILE_REQUIRED, không tạo lại. Nhãn từng phải cách ly vì thiếu danh tính giữ marker BATCH_PAGE_IDENTITY cả khi READY; không gộp lại gây vòng tải từng đơn. Reprint receipt có sẵn vẫn dùng đường cũ nếu không có quyền tạo tác vụ mới; mọi create mới vẫn kiểm tra PRINT_NEW.

Chuẩn bị chi tiết đặt trước 4 đơn thay cho 2, để đọc dữ liệu chồng với xử lý DB; giữ nguyên tối đa 2 HTTP trong budget chung, quota/cooldown và ghi giữ KIZ theo thứ tự. Không thêm request không giới hạn. PDF vẫn kiểm tra mã đơn/barcode vận chuyển, không suy trang theo vị trí; trang không đủ bằng chứng tiếp tục dùng tác vụ riêng. Không bỏ kiểm tra KIZ để tăng tốc. Hai nhãn chính Ozon và cách lưu lịch sử giữ nguyên.

## Kiểm chứng

- Hồi quy rộng: **183/183**, 22 lớp, 2 phút 59 giây. Bao gồm nhận KIZ trước ship, tách từng đơn vị, reservation, in/lịch sử/readiness, 1.000 trang đảo, selector/barcode, phân trang/cursor, quota và UI.
- Sau chỉnh cuối điều kiện gộp theo quyền và tooltip, chạy lại phần bị ảnh hưởng: **40/40**, 3 lớp, 42,219 giây. 39 ca trùng lượt rộng và 1 ca mới; tổng duy nhất 184, không cộng thành 223. Lượt 39 trước đó là tập con.
- UI: checkbox chọn đủ 1.000 đơn, qua trang vẫn giữ, bỏ chọn toàn bộ và lọc đúng cửa hàng; bấm Nhận xử lý đủ 101 đơn của fixture (99 thành công, 1 chờ, 1 bị chặn), báo cáo đủ 101 dòng và giữ lại 2 đơn cần xử lý.
- PDF/HTTP localhost: lô 1.001, giới hạn 1.000, gộp nhãn lẻ đã lưu, đảo trang, barcode/text không rõ, cách ly và in lại, mất receipt/claim rollback. Giữ đúng KIZ và xuất tệp atomically qua service in hiện có.
- Lượt mục tiêu đầu có một lỗi fixture: chờ 4 HTTP cùng lúc trong budget 2. Đã sửa latch về 2 và giữ budget production; các lượt thành công sau ghi riêng. Không bỏ qua lỗi đó hoặc tính lượt đầu là đạt.

Chưa chạy full Java suite/native Windows hoặc đo xuất PDF trên đơn seller thật cho mã này. API thật chỉ đọc danh sách; không nhận đơn khách, tạo tác vụ nhãn thật, mua hay tiêu KIZ. [Biên nhận](../validation/2026-10-29-ozon-large-fbs-batch-labels.json).

## Đã đưa vào bộ cài

Các sửa Ozon phía trên đã được kiểm thử đầy đủ và phát hành trong **Latest 1.5.19**. [Biên nhận công bố](../validation/2026-10-29-vncode-1519-published-latest.json), [bàn giao bộ cài](../validation/2026-10-29-vncode-1519-handoff.md).
