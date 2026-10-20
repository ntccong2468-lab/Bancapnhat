# Kiểm tra cuối và phát hành VNCode 1.4.15

Bản nâng cấp trực tiếp từ 1.4.13. Bản nháp 1.4.14 giữ nguyên tag, không phát hành cho seller. Mã chức năng đã sửa từ source `6ba183c` được giữ nguyên; 1.4.15 cập nhật phiên bản và sửa cách chờ/dọn dẹp fixture JavaFX sau khi lượt kiểm tra lại phát hiện 1 bài thất bại.

## Kiểm tra sau sửa fixture

- Bài Znack giữ thời hạn 10 giây, toàn bộ kiểm tra 300/600/900/1.003 dòng và lựa chọn toàn bộ vẫn giữ nguyên. Chờ callback FX và công việc nối tiếp ổn định thay vì kiểm tra một lần sau khi bộ đếm worker về 0.
- Fixture hộp báo cáo bấm nút Đóng của chính Dialog để chạy handler dừng timer, rồi đợi các lượt đọc dùng chung kết thúc trước khi đổi thư mục database.
- Ba lượt tập trung đều đạt 11/11 bài. Source cuối `16f7b43047f50057df88f4bda70939da4b634a38` đã đạt `clean verify`: 1.664 bài, 1.662 passed, 0 failures/errors, 2 platform skips; Node và Portal đạt 60/60 mỗi bộ. Native Windows `39016087535` trên đúng source này cũng đạt 1.664 bài, 0 failures/errors, 2 platform skips; launcher, GS1/DPAPI, nhiều shop/DPI, 288 bố cục và 11 đường nâng cấp giữ dữ liệu đều đạt. Không dùng lượt kiểm tra có failure để phát hành.

## Các đầu mục đã rà soát

| Mục | Kiểm tra / kết quả API đã có |
| --- | --- |
| Sản phẩm / đồng bộ sàn | WB 388 bài / 1.982 biến thể; Ozon đủ 175 list/info/attributes; phân trang và checkpoint có kiểm tra lỗi / ownership. |
| Znack / GTIN | Đọc đủ 1.276 thẻ, 1.237 published / 39 draft. Parser Ozon có 10 cặp duy nhất, 0 mơ hồ. Thử ghi Ozon và đọc lại trước đây được giữ riêng; không lặp lại lệnh đã gửi. |
| Tạo / theo dõi / ký thẻ | Bộ kiểm thử thẻ cũ/mới, đổi nhóm TN VED, duyệt / từ chối, consent và tiếp tục sau gián đoạn. Không ký thật khi cloud thiếu chứng thư doanh nghiệp. |
| FBS WB / Ozon | WB đọc được 4 đơn mới, 293 shipment, 1.547 đơn lịch sử qua 2 trang, trạng thái và membership hợp lệ; Ozon đủ 244 đơn / 245 dòng qua 3 trang. Giữ worker và lượt HTTP ưu tiên FBS. |
| FBO | WB đọc được 4 lô; Ozon trả danh sách rỗng hợp lệ. Kiểm thử phân trang, cursor, bundle và giữ cache khi lỗi. |
| Kho KIZ | Kho seller theo danh mục, ưu tiên kho nhập, không buộc Znack/GTIN; nguồn mua giữ đúng GTIN, reservation và chống gán trùng. Bộ nhập lại phân biệt mã có sẵn, đã giữ, đã dùng và chuyển kho. |
| GS1 | Nguồn cấu hình Znack, partial, owner/certificate, PDF / ảnh / chữ ký và DPAPI. Ký CryptoPro doanh nghiệp không được coi là đã nghiệm thu trực tiếp. |
| Sửa / sao chép sản phẩm | Gợi ý giá trị, xem trước, Lưu và readback; giữ các biến thể và trường gốc. Bộ TN VED và JSON giá trị riêng từng sản phẩm có hồi quy. |
| Báo cáo tác vụ | Dòng / trạng thái / lỗi, phân trang 300, giữ lựa chọn và mở lại. Fixture đóng dialog đúng lifecycle. |
| Giao diện / cài đặt | Liquid Glass xanh, độ trong nền 0–100, chữ giữ độ hiển thị, Lưu/Hủy/khởi động lại; bố cục đa ngôn ngữ, kích thước và DPI được kiểm tra native Windows. |
| License / cập nhật | Giữ pin sản xuất vncode-2, bảo vệ dữ liệu/DPAPI. Kiểm tra lại metadata Latest, EXE, checksum và changelog sau khi phát hành. Nghiệm thu kích hoạt backend sản xuất vẫn là phần chưa thực hiện. |
| Tài chính / công cụ khác | Hồi quy planner, watermark, cooldown, in/nhãn/lịch sử, cấu hình shop và các trang còn lại trong bộ đầy đủ. |

## Đối chiếu WBCode / WCode

Latest công khai WBCode `v1.3.9`; nguồn nội bộ bản này chưa truy cập được. Đối chiếu phần nguồn WCode công khai và ghi chú phát hành theo [báo cáo đồng bộ](2026-10-17-sync-release-audit.md). Không coi ghi chú phát hành là mã nguồn đã chạy.

## Phạm vi nghiệm thu

Các lượt API cuối chỉ đọc dữ liệu. Không mua/gán KIZ, không đóng đơn thật, không tạo thêm thẻ hoặc ghi GTIN thêm trong lượt này. WB mẫu danh mục bộ còn khác TN VED giữa sàn và Catalog; không sửa phân loại âm thầm để tạo kết quả đạt. Các bằng chứng ghi/đọc lại Ozon và sửa article WB ở lượt trước được giữ riêng.

Chủ ứng dụng tiếp tục yêu cầu kiểm tra lại, build và phát hành Latest sau khi phần nghiệm thu license còn thiếu đã được công bố trong phiên làm việc. Nếu phát hành thủ công theo chỉ dẫn đó, các cờ `complete` / `productionReady` và gate publisher vẫn giữ đúng kết quả; không tạo bằng chứng kích hoạt giả.

Kiểm tra lại parser của bản 1.4.15 trên phản hồi API thật đã lưu tiếp tục đạt: WB 4 đơn mới / 293 shipment, 6 ID thuộc shipment mở; Ozon 244 đơn / 245 dòng qua 3 trang; Catalog 1.276 thẻ và 10 cặp Ozon duy nhất / 0 cặp mơ hồ. Đây là kiểm tra lại phản hồi đã lưu của lượt API cuối, không phải thao tác ghi mới.

## Bộ cài đã kiểm chứng

- [Lượt Windows thành công](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39016087535).
- `VN-code-1.4.15-Windows-x64.exe`: 145.456.128 byte; SHA-256 `fc3ecc9be0eefa37bb241d9a86f6b6715bb5ad75f51ed178ce10230e51205c16`. Tệp tải từ đúng lượt build, kiểm tra PE/MZ và checksum đạt.
- [Bằng chứng Windows / nâng cấp / giao diện](2026-10-17-vncode-1415-windows-release-checks.json).
- Đã Published/Latest lúc **11:47:32 ngày 17/10/2026 (Moskva, UTC+3)**. Tải công khai không gửi Authorization trả HTTP 200; đủ 145.456.128 byte, SHA-256 và PE đều khớp artifact. Parser từ JAR Windows đã kiểm chứng của cả 1.4.13 và 1.4.15 đọc đúng phiên bản 1.4.15, EXE, digest, kích thước và ghi chú từ API Latest công khai. Chưa quan sát hộp thông báo trên máy seller thật.

## Phát hành và cập nhật trong app

- [VNCode 1.4.15 — Published/Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.4.15).
- [Bộ cài Windows công khai](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.15/VN-code-1.4.15-Windows-x64.exe).
- [Bằng chứng xuất bản, tải công khai và đọc lại metadata](2026-10-17-vncode-1415-publication.json).
- Cả 22 asset được đối chiếu SHA-256/kích thước trước xuất bản. Tag giữ nguyên source `16f7b43047f50057df88f4bda70939da4b634a38`.
- Chủ ứng dụng tiếp tục chỉ dẫn phát hành Latest sau kiểm tra lại. Việc xuất bản thủ công giữ nguyên `complete=false`, `licenseIntegration.productionReady=false`, gate publisher và mô tả phạm vi kiểm thử; không gán trạng thái nghiệm thu license sản xuất từ fixture. Ghi chú phát hành công khai đã nêu phần kích hoạt backend/chứng thư doanh nghiệp chưa kiểm tra trực tiếp.
