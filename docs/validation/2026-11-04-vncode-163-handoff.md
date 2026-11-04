# VN Code 1.6.3 — bàn giao phát hành

**Đã công bố [1.6.3 lên Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.3) lúc 2026-11-04T23:11:51Z**, release `434558799`. Nguồn ứng dụng/tag bất biến `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2`, kế thừa toàn bộ 1.6.2 và sửa Ozon `ccd7fc1`. Nhánh phát triển `feature/vncode-163-approved-ui`; các commit sau nguồn ứng dụng chỉ cập nhật publisher và bằng chứng phát hành.

## Phạm vi đã đưa vào bộ cài

- Giao diện JavaFX đã duyệt: hai theme, thanh điều hướng gọn, danh sách ưu tiên diện tích; Tổng quan bỏ ba KPI và khoảng trống. Quản lý/thêm/sửa và thùng rác cạnh chọn cửa hàng; thông tin đồng bộ vẫn trong menu. Giữ handler, phạm vi dữ liệu và xác nhận xóa.
- Drawer chi tiết giữ danh sách, Enter/Escape và trả focus; bản nháp cấu hình Znack giữ riêng theo danh tính cửa hàng. Chuyển động ngắn có thể ngắt và giảm chuyển động theo hệ điều hành/tùy chọn. Sửa tương phản template editor ở theme sáng và bản địa hóa trạng thái trống.
- Nhận Ozon đối soát chung sau cả tập đã gửi; trạng thái trả chậm được đọc lại, không gửi nhận trùng. Một đơn con lỗi không chặn đơn con hợp lệ khác. KIZ tùy chọn bị từ chối/chưa có mã chỉ cho tiếp tục khi Ozon mới nhất xác nhận được nhận; phản hồi chưa rõ hoặc yêu cầu KIZ bắt buộc giữ đối soát.
- PDF Ozon vẫn tải theo lô, sort theo cùng cấu hình WB trên toàn bộ lô; giữ nguyên nội dung/kích thước vận đơn gốc và đặt nhãn KIZ kết hợp của đúng đơn ngay sau đó. Bỏ trang barcode sản phẩm riêng. Phiếu nhặt hàng sort cùng quy tắc và giữ đúng số vận đơn.
- Theo yêu cầu đã xác nhận, KIZ bị Ozon từ chối vẫn có thể in từ reservation đúng đơn. Kết quả từ chối API được giữ riêng; xuất PDF không được ghi thành gán KIZ thành công. Không ghép mã vest sang danh mục khác hoặc đoán chủ trang theo vị trí.
- Giữ schema 6, UpgradeCode, dữ liệu cửa hàng/GTIN/hàng đợi/history/license; không triển khai portal hoặc prototype credential migration.

[Ghi chú bản cập nhật](../releases/VN-code-1.6.3.md), [30 ảnh native](../design/native/2026-11-04/README.md), [review ship](../design/native/2026-11-04/REVIEW.md), [đối chiếu nguồn](2026-11-04-vncode-163-source-inclusion.json).

## Kiểm chứng bộ cài và cập nhật

- [Windows build 41918387266](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41918387266) SUCCESS: **3.844 ca, 0 thất bại/lỗi, 2 bỏ qua theo hệ điều hành**, 521 lớp. Preflight UI/wrapper/Catalog đạt; không thay full suite bằng kiểm thử mục tiêu. [Biên nhận Windows](2026-11-04-vncode-163-windows.json).
- Native/migration/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp thụ động bằng đúng EXE từ 1.6.2 giữ dữ liệu đều đạt. Hash MSI nhúng khớp bản phát hành. Trước build có 296 ca riêng biệt/24 lớp mục tiêu và Node 144/144; không chạy full Linux.
- [Publisher 41931634446](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41931634446) SUCCESS; đủ 16 tài sản trước khi chuyển Latest. Không thay/xóa release cũ. [Biên nhận công bố](2026-11-04-vncode-163-publication-receipt.json).
- [16 tài sản tải công khai](2026-11-04-vncode-163-public-assets.json) khớp kích thước/SHA-256; [source ZIP/JAR](2026-11-04-vncode-163-package.json) đúng nguồn, 2.769 tệp được đối chiếu, chỉ chuẩn hóa CRLF cho văn bản. JAR chứa UI đã duyệt và các lớp sửa nhận/ghép PDF; không có badge vận đơn tự tạo.
- [Bộ cập nhật từ binary 1.6.2](2026-11-04-vncode-163-updater.json) đọc Latest 1.6.3 và chọn đúng MSI/hash/kích thước. Chưa quan sát popup hoặc cài trên máy seller. [Latest đã xác minh](2026-11-04-vncode-163-published-latest.json).

Lượt công bố `41931139279` dừng sau khi đã tạo draft vì danh sách releases chưa cập nhật. Đã xác minh và dùng đúng draft ID `434558799`, không tạo bản nháp trùng; 145/145 ca công cụ publisher đạt, giữ nguyên bộ cài đã kiểm chứng. Dispatch dùng tên nhánh và khóa checkout bằng SHA; GitHub không nhận SHA làm workflow ref trong lượt này.

## Bằng chứng thật và giới hạn

- Trước đóng gói đã nhận thêm 2/2 đơn mới qua service production, đọc lại `awaiting_deliver` sau phản hồi chậm đầu tiên, không gửi trùng. Lượt trước đó 22/22 đã được xác nhận. [Lần đọc hàng đợi 20:11 UTC](2026-11-04-vncode-163-ozon-queue-followup.json) còn 0 đơn chờ trong cửa sổ unfulfilled đã kiểm tra. [Biên nhận nhận đơn](2026-11-04-vncode-163-ozon-live-recheck.json).
- Người dùng xác nhận file KIZ thuộc gilet. [PDF thật](2026-11-04-vncode-163-ozon-paired-pdf.json): 25 vận đơn + 13 KIZ gilet = 38 trang, 2 trang nhặt hàng; 2 task native/0 lượt tải nhãn riêng; 6,999 giây export/9,401 giây đầu-cuối. Đối chiếu độc lập 13 DataMatrix đúng reservation/đơn và không trùng, 25 vận đơn giữ nguyên byte nội dung; thứ tự và số vận đơn đạt. 12 đơn khác danh mục không dùng mã gilet.
- 13 mã này không được gửi lên API trong lượt xuất PDF; đây là bằng chứng in kết hợp cục bộ, không phải Ozon đã chấp nhận KIZ. File, phản hồi API và KIZ thật giữ ngoài Git. Không mua KIZ có phí hoặc kích hoạt license thật.
- Lô 1.000 là fixture, chưa có benchmark 1.000 đơn thật hay thao tác trên máy Windows seller. Ảnh UI dùng dữ liệu tổng hợp; API/PDF thật được phân biệt trong hồ sơ. Installer chưa ký Authenticode; giới hạn security review 1.6.1 vẫn còn.

## Tiếp tục và kinh nghiệm

Run `41902237002` trước đó có 3 lỗi kỳ vọng thứ tự trang cũ trong `OzonLabelServiceTest`. Đã sửa đối chiếu theo danh tính và thứ tự WB, giữ kiểm tra nội dung byte/kích thước, chạy đạt 52 ca lớp nhãn và chạy lại full Windows. Mã ứng dụng không đổi so với `14a1049`; [biên nhận sửa kiểm thử](2026-11-04-vncode-163-windows-sort-regression.json).

Run chờ `41917831124` bị hủy trước khi có job; build được khóa theo SHA nguồn để không phải chờ nhánh khác trong repo. Giữ nguyên toàn bộ gate. Nguồn/build cũ `6de9c73`/`41895958691` đã hủy khi người dùng bổ sung yêu cầu PDF; chỉ nguồn `f8d2cf967ecc8fe9de32cd3b57c70de642751ff2`/`41918387266` được phát hành. Không dời tag hoặc thay binary/checksum đã công bố. Full Windows mất nhiều thời gian; giữ gate và dùng tiếp kết quả đã đạt. Luôn đối chiếu artifact, source ZIP, runtime và updater phiên bản đang cài trước báo phát hành hoàn tất.

Đọc journal trước khi tiếp tục giao dịch Ozon thật; không chạy lại runner nhận đơn hoặc cấp KIZ vì thiếu output ở tiến trình cũ. Local print và API acceptance là hai trạng thái riêng. Giữ reservation theo đúng đơn khi KIZ bị từ chối; không dùng lỗi chung làm lý do tạo lại giao dịch chưa rõ kết quả.
