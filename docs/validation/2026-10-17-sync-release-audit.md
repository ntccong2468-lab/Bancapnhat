# Kiểm tra toàn bộ luồng đồng bộ trước VNCode 1.4.14

Trạng thái: mã nguồn cuối đã vượt kiểm thử đầy đủ trên Linux và Windows; bản nháp 1.4.14 đã có đủ bộ cài và 22 asset được đối chiếu kích thước/SHA-256; chưa phát hành Latest. Các số liệu API dưới đây là lượt đọc thật ngày 17/10/2026, không phải chứng minh mọi thao tác ghi, ký hay mua mã đã được nghiệm thu.

## Lỗi được tái hiện và sửa

| Luồng | Lỗi / thay đổi |
| --- | --- |
| SQLite / báo cáo Znack | Run Windows `39007018087` thất bại ở kiểm thử 10 shop ghi đồng thời. Thêm hàng đợi ghi công bằng, tái sử dụng connection khi có caller chờ; giữ timeout kiểm thử, rollback và độ bền dữ liệu. Lượt Windows cuối đã đạt: 4 bài kiểm tra ghi đồng thời, suite 3,147 giây, giữ timeout kiểm thử 30 giây. |
| Sản phẩm WB | Cả đồng bộ tăng dần và đọc từ đầu đều kiểm tra đủ cards/cursor, chặn cursor lặp, chỉ ghi mốc thành công sau khi kết thúc trang cuối. |
| Sản phẩm Ozon | Phản hồi thiếu danh sách/detail/attributes hoặc sai offer_id không được biến thành sản phẩm rỗng hay snapshot thành công. Kiểm tra đúng credentials trước khi lưu. |
| FBS WB tự động và thủ công | Dùng chung kiểm tra danh sách, ID, trạng thái và thành phần shipment. Không xóa liên kết hoặc ghi số lượng về 0 vì HTTP 200 nhưng thiếu dữ liệu; không nâng mốc lịch sử khi trang sau thất bại. |
| FBS đang mở | Ưu tiên đọc lại shipment đang xem trong lúc quét nền. Có worker và lượt kết nối riêng cho đồng bộ FBS; kiểm thử FBS tiếp tục polling dù Catalog/GS1 còn bận. Sửa thêm thứ tự điều phối để backlog shop khác không chen trước lượt đơn mới đã tới hạn của shop đang mở. |
| FBS Ozon / gán mã / đóng đơn | Kiểm tra posting đúng yêu cầu, danh sách sản phẩm và quantity nguyên dương trước khi lưu hoặc tiếp tục đóng đơn. Chặn phản hồi thuộc posting khác. |
| FBO WB/Ozon | WB đọc tiếp sau 1.000 dòng. Ozon giữ checkpoint nếu lượt đọc có giới hạn chưa tải hết lịch sử. Bundle thiếu trường hoặc cursor lặp không được thay cache bằng dữ liệu rỗng / một phần. Chờ 429 dài trả lượt worker về điều phối. |
| Znack → GTIN | Giữ lỗi API gốc khi ghi báo cáo cũng lỗi. Tác vụ đã xác nhận không bị báo cáo tạm dừng cũ che khuất. Giữ bước xem trước, xác nhận, đọc lại và không phát lại lệnh ghi chưa rõ kết quả. |
| GS1 | Lỗi cấu hình vẫn hiện đúng khi ghi báo cáo thất bại. Các lượt đọc dùng chung nhận được lỗi kết thúc thay vì chờ treo; giữ trạng thái partial theo từng nguồn. |
| Ghi batch FBS / metadata Catalog | Tái hiện lỗi mới: lỗi giữa batch để lại trạng thái đơn hoặc giấy tờ/nhãn đã lưu dở. WB cập nhật 1.000 trạng thái từ 1.000 commit xuống 1; projection 204 thẻ Catalog từ 410 xuống 5 commit, đủ mọi dòng. Ghi metadata theo nhóm tối đa 100 thẻ, có rollback và kiểm tra chứng thư. |

Nhóm đầu có 6 lỗi; nhóm “Làm mới WB” có 7/7 bài thất bại. Sau sửa, 145 bài tập trung, 42 bài kiểm tra owner và 11 bài điều phối đã đạt. Source `e2b6471` đạt 1.660 bài trên cả Linux và Windows, gồm bài SQLite đồng thời từng thất bại (suite Windows 5,230 giây). Bản này tiếp tục được thay sau khi tái hiện thêm lỗi ghi dở giữa batch; bộ cài của nó không dùng để phát hành.

Source cuối hiện tại là `6ba183cd5fb4420e37df13035581fee08c2d2155`, bổ sung giao dịch batch trạng thái WB và metadata Catalog. Nhóm 116 bài liên quan đã đạt, gồm rollback, giữ giấy tờ cũ khi Catalog không cung cấp metadata và từ chối metadata của chứng thư cũ. Đo commit dùng database giả lập và các repository thật, không gọi API seller. Bộ `verify` đầy đủ đạt **1.664 bài, 0 failures, 0 errors, 2 platform skips**, mất 11 phút 39 giây. Windows `39012628654` trên đúng source này đã thành công: 1.662 bài đạt, 2 platform skips, 0 failures/errors; công cụ phát hành và Portal đạt 60/60 mỗi bộ. Bộ cài đã vượt kiểm tra native, 288 bố cục không cắt nút và nâng cấp từ 1.4.13 giữ dữ liệu. SHA-256 EXE: `ec9b0e71fef0edd20de106aeedec6f5ed6fa27de8df36d5ddbeedbaee2215385`. Xem [bằng chứng Windows](2026-10-17-vncode-1414-windows-release-checks.json). Chi tiết cuối được ghi trong JSON đi kèm.

## Phạm vi rà soát

| Mục | Kiểm tra mã / kiểm thử | API thật trong lượt này |
| --- | --- | --- |
| Điều phối / đổi shop / tiếp tục sau lỗi | Worker có giới hạn, ownership, checkpoint, cooldown, yêu cầu đến trong lúc đang chạy; ưu tiên FBS | Không cần thao tác ghi sàn để thử điều phối |
| Sản phẩm WB | Phân trang, snapshot, bộ lọc / đọc lại dữ liệu | 388 bài, 1.982 biến thể |
| Sản phẩm Ozon | Danh sách → info → attributes → từ điển; đủ ID và offer | 175 list / info / attributes |
| FBS WB | Đơn mới, trạng thái, shipment và lịch sử, cả đường tự động / thủ công | API đơn và shipment trả HTTP 401; chủ ứng dụng đang cập nhật token trong môi trường |
| FBS Ozon | Queue, detail, exemplar, reservation, journal và chặn đóng sai posting | Đọc đủ 244 đơn / 245 dòng sản phẩm qua 3 trang; parser VNCode kiểm tra thành công; thêm một detail thật |
| FBO | Danh sách nhiều trang, checkpoint, chi tiết / bundle, giữ cache khi lỗi | Ozon trả danh sách rỗng hợp lệ; WB trả 401; không có bundle thật để nghiệm thu |
| Catalog / Znack | Chủ sở hữu, chứng thư, trang đủ / partial, cache và receipt | 1.276 thẻ được liệt kê và đọc detail; parser nhận 1.237 published / 39 draft |
| Đăng ký / ký thẻ Znack | Thẻ cũ, đăng ký lại, đổi nhóm TN VED, duyệt / từ chối, consent, tiếp tục sau gián đoạn | Không tạo thêm thẻ, không ký trong lượt kiểm tra này; thẻ nháp thử trước còn là bằng chứng riêng |
| GTIN lên sàn | Ghép biến thể; xem trước → xác nhận → ghi → đọc lại; trạng thái không rõ kết quả không tự gửi lại | 10 cặp Ozon duy nhất, 0 cặp mơ hồ. Không lặp lại lệnh ghi đã thử ở lượt trước. GTIN WB mẫu bộ còn khác TN VED |
| Kho KIZ / gán KIZ | Kho seller theo danh mục, trùng mã / chuyển kho / mã đã giữ, ưu tiên kho nhập, mua đúng GTIN và reservation | Không mua KIZ hoặc gán vào đơn thật trong lượt này |
| GS1 | Xác thực qua cấu hình Znack, cache theo owner, partial, thành viên / tiền tố / số dư, PDF / ký / thư | Thiếu chứng thư CryptoPro của doanh nghiệp trong cloud; không coi dữ liệu giả lập là nghiệm thu ký thật |
| Báo cáo tài chính | Planner, phân trang, watermark, cooldown, kho dữ liệu riêng | Không gọi API tài chính thật trong lượt này |
| Sao chép / sửa sản phẩm | Kế thừa dữ liệu, giá biến thể, xác nhận ghi và readback | Không tạo bài mới / sửa bài thêm trong lượt kiểm tra này |
| Cài đặt / cập nhật Windows | Bộ kiểm thử đầy đủ, UI và nâng cấp từ 1.4.13 trên native Windows | Phải dùng installer từ đúng source vượt các kiểm tra; không dùng artifact cũ |

## Đối chiếu WBCode / WCode

WBCode Latest công khai là `v1.3.9`; ghi chú phát hành nhắc sửa tiếp tục đăng ký Znack và thẻ Ozon đang chờ. Mã nguồn nội bộ của bản này chưa truy cập được. Nguồn WCode công khai vẫn ở commit `8f7c3d1ac8598158029b8d037e2d3c128e240727`, là bản nền cũ được ghi trong [bằng chứng đối chiếu](../architecture/2026-10-15-unified-sync-evidence.json). Không tuyên bố đã sao chép hoặc chạy mã nguồn WBCode 1.3.9 nội bộ.

Xem [thiết kế ưu tiên FBS](../architecture/2026-10-17-fbs-priority-sync.md). API keys, token, nội dung đơn và raw Catalog không được đưa vào repository. Các cache đọc thật chỉ nằm trong thư mục kiểm tra riêng của môi trường.

## Điều kiện phát hành

Kiểm thử toàn bộ và native Windows trên đúng source đã đạt, gồm bài ghi SQLite đồng thời từng thất bại. Lần kiểm tra cuối lúc 07:38 UTC ngày 17/10 vẫn nhận HTTP 401 từ API đơn mới WB; chủ ứng dụng đã báo sẽ cập nhật. Không đánh dấu nghiệm thu license sản xuất hoặc ký CryptoPro chỉ bằng bài kiểm thử giả lập. Publisher tự động vẫn giữ nguyên yêu cầu bằng chứng license; việc phát hành phải ghi rõ phạm vi đã kiểm tra và phần chưa nghiệm thu.

## Bộ cài và bản nháp đã chuẩn bị

- Windows build: [39012628654](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39012628654), source `6ba183cd5fb4420e37df13035581fee08c2d2155`.
- Upload và kiểm tra đủ asset: [39014660248](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39014660248), thành công.
- [Bản nháp 1.4.14](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/untagged-24b8ad394edf6e9a8fcb), release ID `415658561`, cần đăng nhập tài khoản GitHub có quyền vào repository; đây chưa phải link tải công khai cho seller.
- `VN-code-1.4.14-Windows-x64.exe`: 145.456.128 byte; SHA-256 `ec9b0e71fef0edd20de106aeedec6f5ed6fa27de8df36d5ddbeedbaee2215385`. Đủ 22 asset, gồm bộ cài, checksum, ghi chú, các bằng chứng Windows và Portal 0.3.6. Tất cả digest/kích thước khớp artifact của lượt build đã đạt.
- Latest của seller vẫn là **1.4.13**. Chưa vượt qua kiểm tra đơn WB thật do HTTP 401 và chưa có bằng chứng license sản xuất theo yêu cầu publisher hiện hữu. Không đổi các cờ nghiệm thu hoặc giả lập bằng chứng để xuất bản.
