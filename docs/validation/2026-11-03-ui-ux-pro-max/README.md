# Nâng cấp giao diện VN Code — UI UX Pro Max

Nhánh `feature/ui-ux-pro-max-20261103`, nền `30afa28`; gộp vào bản cập nhật 1.6.1 theo yêu cầu tiếp theo. [Xem 92 ảnh giao diện](gallery.html) hoặc [danh sách ảnh](screenshots.json). Ảnh chụp view JavaFX thật với dữ liệu tổng hợp; không có thông tin seller, không phải ảnh thiết kế dựng lại.

## Những gì đã sửa

| Nhóm | Thay đổi | Chức năng được giữ |
| --- | --- | --- |
| Toàn ứng dụng | Thay hai lớp CSS cạnh tranh bằng một bộ controls/token dùng chung; nền tối và sáng, Inter, tím cho hành động chính, trạng thái rõ và focus bàn phím | Lựa chọn theme, tùy chỉnh độ trong nền sáng, route/controller/handler |
| Khung app | Logo và nút thu gọn chung hàng; thanh bên gọn, các nút phụ giảm độ nổi; tên truy cập cho biểu tượng và chọn cửa hàng | Toàn bộ menu, thu gọn tự động, đổi/xóa shop và trạng thái license |
| Sản phẩm | Khung làm việc cùng lề với WB FBS; năm nút sửa có cùng trọng lượng, không chen ra ngoài; thanh tiến trình dành chỗ dưới danh sách | Chọn tất cả, bộ lọc, năm trường chỉnh sửa, gửi thật và đối soát hiện có |
| Znack WB/Ozon | Cùng bảng, trạng thái, hộp thoại và nút; tiến trình không phủ dòng; ô tìm kiếm/bộ lọc có tên truy cập | Các bước kiểm tra hồ sơ, đăng ký, sửa, ký, đồng bộ GTIN và phục hồi |
| Ozon FBS | Khung danh sách đầy vùng làm việc, toolbar gọn; tiến trình xuất nhãn có vùng riêng; co cột để hiển thị đầy đủ giá khi đủ diện tích | Các tab chờ nhận/đơn mới/đang giao/đã bàn giao/Kho KIZ; nhận, chọn, in và phân trang |
| Tổng quan/tài chính | Bộ lọc ngày tự xuống dòng; bảng giao dịch/phạt và các vùng thông tin dùng cùng theme | Đọc/sync/phân trang dữ liệu tài chính hiện có |
| GS1, cấu hình Znack, Kho KIZ | Đồng bộ thẻ thông tin, trạng thái, input readonly, tab và scrollbar | Đủ 5 tab Znack, nhập KIZ seller, chữ ký, cấu hình, báo cáo |
| FBO, shipment, lịch sử, mẫu in, GTIN, TN VED, sao chép, hướng dẫn, tin tức, license | Dùng cùng typography, bảng, popup, control, focus và nền sáng/tối | Các thao tác, bố cục nghiệp vụ, quyền và dữ liệu hiện có |
| Hộp thoại shop và sửa sản phẩm | Label liên kết input; nội dung dài cuộn được và nút Áp dụng/Hủy nằm trong cửa sổ | Kiểm tra dữ liệu và xác nhận trước ghi |
| Khởi động | Dùng stylesheet mới nhưng vẫn độc lập database/API/license | Phục hồi dữ liệu, khóa app, chẩn đoán và đóng an toàn |

## Skill và phương pháp

- Cài [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) đúng `.agents/skills/ui-ux-pro-max/`; nguồn cố định `713eef51b04efc98d6fa565323b495f7c9c89fd4`, CLI upstream với `--ai codex --offline`. MIT license và scripts/data đi kèm. Sáu companion skills do upstream đóng gói được giữ, không thay TASTE/Impeccable/OpenQodex đã có.
- Search thực chạy cho desktop dashboard, JavaFX virtualized table/focus/modal và text layout. Không áp dụng đề xuất Hero/CTA của marketing vào desktop. Giữ Inter sẵn có, không thêm dependency runtime.
- [Kiểm kê trước sửa](../../design/2026-11-03-ui-ux-audit.md), [bộ quy tắc chung](../../design/vncode-workspace-system.md), [biên nhận cài skill](../../../.agents/skills/ui-ux-pro-max/VNCODE-INSTALL.json).

## Xác minh và giới hạn

- 143/143 ca UI riêng biệt đạt, gồm kiểm tra bổ sung giá Ozon (đã tái hiện lỗi trước sửa); [biên nhận cuối](verify.json) ghi từng lớp và phạm vi. 135/135 kiểm thử công cụ Node và 39/39 unit tests Python search skill đạt.
- Kiểm tra 16 trang FXML + 10 pane Java, bốn ngôn ngữ, hai theme, hai cỡ cửa sổ; không khuất nút cố định. Kiểm tra thêm năm danh sách ở 940×650, 1200×720, 1480×900, với dữ liệu tổng hợp. Các nội dung có scroll không bị coi là phải luôn hiện tất cả cùng lúc.
- Ở 1200×720: WB FBS 1168×538; Sản phẩm 1168×566; Znack WB 1168×524; Znack Ozon 1168×522; Ozon FBS 1168×544. Khi tăng chiều cao 180 px, cả năm bảng tăng tương ứng.
- Đối chiếu FXML giữ nguyên 199 ID có sẵn và 63 handler; bổ sung 2 ID label. Các kiểm thử tương tác vẫn chạy thật control JavaFX, còn adapter seller trong UI suite là giả lập.
- Kiểm thử computed paint của primary/metadata/focus và thanh điều chỉnh nền; kiểm tra giữ lựa chọn/dữ liệu khi thanh tiến trình xuất hiện/ẩn; không xem screenshot là chứng cứ API đã ghi thành công.
- Nhánh giao diện không đổi API, schema, migration, điều kiện cấp phép hoặc dữ liệu seller. Chưa đo screen reader Windows, mọi thiết lập DPI, máy in thật hoặc tất cả trạng thái dữ liệu ngoài fixture. Kết quả bộ cài/native/upgrade và phát hành được ghi riêng sau khi CI hoàn tất.

## Các sửa chức năng được kế thừa trong bản gộp

- [Sửa sản phẩm và nghiệm thu API thật](../2026-11-03-product-edit-catalog-live-acceptance.md): 10/10 lượt ghi lại giá trị hiện có cho 5 trường WB/Ozon và đọc lại; một feed Catalog thật được đọc thấy đúng thẻ `draft / moderation`, chưa ký/công bố.
- [Xuất PDF Ozon theo lô](../2026-11-03-ozon-batch-pdf-audit.md): đọc chi tiết theo nhóm, giữ nguyên PDF task đúng tập đơn, cache thumbnail riêng; nhãn vận đơn được API cấp không bị loại chỉ vì thiếu KIZ. Một đơn thật/2 trang đạt 6,111 giây; in lại cùng task có cache đạt 4,989 giây. Lô 100/1.000 là benchmark giả lập, không dùng làm cam kết thời gian sàn thật.
- Các bản ghi trước về audit, FBS, KIZ và tài chính giữ trong lịch sử nguồn; bản phát hành sẽ dùng toàn bộ cây nguồn đã gộp, không lấy nhầm binary 1.6.0.
