# Mục 6 đã duyệt — sao chép dữ liệu bài đăng gốc

Người dùng đã duyệt [sơ đồ mục 6](../architecture/2026-10-16-item-6-listing-copy-proposed-flow.md). Phạm vi: WB → WB, Ozon → Ozon, WB → Ozon và Ozon → WB; chọn sản phẩm và cửa hàng đích, tạo bản xem trước từ dữ liệu gốc, mở chỉnh chi tiết khi cần, xác nhận đăng và đối soát.

## Thay đổi

| Đầu mục | Hành vi sau sửa |
| --- | --- |
| Luồng chính | Phần chọn sản phẩm/cửa hàng đích được gom gọn. Nút **Tạo bản xem trước** hoạt động sau khi đọc cấu hình kể cả chưa chọn được danh mục; backend trả đúng phần còn thiếu. **Chỉnh chi tiết** mặc định thu gọn; lỗi mở phần nội dung, biến thể, thuộc tính hoặc giấy tờ liên quan. |
| Kế thừa dữ liệu | Giữ nội dung, thương hiệu, TN VED/chất liệu và các thuộc tính nguồn qua dictionary đích, article, barcode, size/size Nga, kích thước/khối lượng và giấy tờ theo hợp đồng API hiện có. Giá trị tự điền không được lưu như một chỉnh sửa thủ công. Xóa chủ động trường văn bản không bị thay lại bằng thuộc tính nguồn. |
| Danh mục | Cùng sàn ưu tiên ID danh mục gốc còn hợp lệ. Có tên danh mục nguồn thì chỉ tự chọn tên đích tương ứng duy nhất; tên lá mơ hồ hoặc không có dữ liệu vẫn yêu cầu chọn. WB reader giữ `subjectName` làm gợi ý, không đổi ID thuộc tính giữa hai sàn. Các lựa chọn trong batch chỉ kế thừa giữa bài cùng danh mục nguồn. |
| Giá theo biến thể | Ozon import dùng giá riêng của từng biến thể, thay cho một giá chung bắt buộc. Thứ tự ưu tiên: giá riêng seller sửa → giá chung seller sửa → giá biến thể gốc. WB giữ cùng thứ tự này. Sửa giá một size không làm đổi các size còn lại. |
| Tiền tệ | Giá kế thừa không được đổi nhãn tiền tệ để thành giá mới. Nếu đổi tiền tệ, cần giá mới được seller nhập cho các biến thể liên quan. Không tự quy đổi ngoại tệ; WB giữ giới hạn RUB của payload đang hỗ trợ. |
| Lưu chỉnh sửa | Lưu riêng từng bài/shop; đọc lại dictionary lưu control đã sửa trước khi xây lại. Giá, nội dung, thuộc tính, size và ánh xạ size thủ công được phục hồi khi quay lại cửa hàng đích. Draft JSON cũ không có `variantPrices` vẫn đọc được. |
| Bất đồng bộ | Có ticket riêng cho dictionary; phản hồi cửa hàng cũ bị bỏ. Chỉnh dữ liệu hủy hiệu lực callback chi tiết bản nháp đang chờ, tránh bật lại nút Đăng bằng preview cũ. Lúc tạo preview/gửi, các control thay đổi dữ liệu bị khóa; tác vụ vẫn chạy ngoài JavaFX bằng executor có giới hạn hiện có. |
| Xem trước | Hiện dữ liệu payload thực sự chuẩn bị gửi, tên danh mục/thuộc tính đích, bảng biến thể gốc/article/barcode/giá, số ảnh và giấy tờ, kích thước/khối lượng. Thuộc tính theo biến thể và request kỹ thuật được thu gọn. Cả tiếng Việt, Anh, Nga, Trung có nhãn/báo lỗi bổ sung. |
| Ảnh Ozon | Ưu tiên ảnh `primary_image` API báo, sau đó giữ thứ tự thư viện và loại trùng. Nếu attributes không có thư viện thì dùng images từ info. Đối soát dùng cả ảnh chính thay vì chỉ so số lượng ảnh. |
| Barcode | Giữ danh sách barcode nguồn WB/Ozon khi API cho phép. Nếu Ozon create contract đang hỗ trợ không biểu diễn được toàn bộ bộ barcode, báo lỗi rõ thay vì âm thầm lấy một mã. Việc sao chép không cấp GTIN proof Catalog hay quyền mua KIZ. |
| Phần chưa chuyển được | Video, rich-content, ảnh 360/PDF xuất hiện dưới các trường nguồn đã nhận dạng nhưng ngoài contract create hiện có được báo rõ trong preview. Giấy tờ thiếu tệp và thuộc tính không ánh xạ được tiếp tục có lỗi cụ thể. Không gọi dữ liệu bị bỏ là đã chuyển đầy đủ. |
| Kết quả từng bài | Preview của một bài bị lỗi không làm mất kết quả các bài khác. Báo shop, bài và mã lỗi an toàn; giữ draft đã tạo để sửa. Thêm nội dung báo lỗi cho các điều kiện trước đây chỉ hiện thông báo chung. |
| Theo dõi sau gửi | Giữ hash xác nhận, claim/journal và checkpoint chống gửi trùng. Khi đang xem tác vụ đã gửi, tự đối soát tối đa 12 lượt, cách nhau 5 giây; vẫn có nút đối soát thủ công. Đóng view/đổi shop dừng theo dõi UI, không hủy mutation đã bắt đầu. |
| Đối soát giá WB | Đối chiếu native `nmID`, `chrtID`/`sizeID` với Prices API; giá chưa đọc lại hoặc chưa khớp thì tiếp tục trạng thái xử lý và báo `PRICE_READBACK_PENDING`. Không báo hoàn tất chỉ dựa vào card đã tồn tại. |
| Phạm vi giữ nguyên | Không sửa pipeline mua KIZ, kho/đóng đơn FBS, website license hoặc kiến trúc toàn ứng dụng trong mục 6. |

## Đối chiếu và hợp đồng API

Latest WBCode kiểm tra lại ngày 16/10/2026 vẫn là [v1.3.9](https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.9), phát hành 15/10/2026 lúc 19:56:43 Moscow. Mã nội bộ không công khai; ghi chú bản này không nêu thay đổi sao chép bài đăng. Cây [WCode công khai 457a6113](https://github.com/rupphi/test-wcode/tree/457a6113301978454f244248956962ab1fcdeb7e) có 308 đường dẫn và không có module listing/copy/clone/duplicate theo đường dẫn. Không khẳng định đã chạy hoặc sao chép engine nội bộ WBCode Latest.

Đã đọc lại [OpenAPI WB Item Management](https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml): `content/v2/cards/upload` có `sizes[].price` (integer, RUB); `api/v2/list/goods/filter` là luồng đọc lại giá đang dùng. Không thêm API ghi giá chưa xác minh. Hợp đồng đọc giá nguồn Ozon v5 đã được lưu từ trước trong [nghiên cứu API giá](../research/vncode-1412-listing-price-contracts.json).

## Kiểm thử và bằng chứng

Kết quả hồi quy cuối, commit ứng dụng, checksum tài liệu/ảnh và bằng chứng giữ pipeline mua KIZ nằm trong [JSON kiểm chứng mục 6](2026-10-16-item-6-checks.json).

Hồi quy đầy đủ trên commit `5cc986b99f18bac53aa265c25146e852c90d54f9`, kết thúc ngày 16/10/2026 lúc **21:59:27 Moscow**: **1.514 ca, 1.512 đạt, 0 thất bại, 0 lỗi, 2 bỏ qua**. Hai ca bỏ qua yêu cầu Windows: NTFS junction và tách entropy DPAPI cho license/hộp thư. Kiểm thử tập trung trước hồi quy: 71/71 đạt; ca giữ ánh xạ size thủ công bổ sung sau đó cũng đã qua trong hồi quy đầy đủ.

Ảnh từ preview dữ liệu tổng hợp, không phải bài đã đăng thật:

- [1366×768 — dữ liệu và giá theo từng biến thể](images/2026-10-16-item6-copy-ready-1366.png).
- [1024×600 — bố cục nhỏ, thao tác xác nhận cố định phía dưới](images/2026-10-16-item6-copy-ready-1024.png).

- Adapter WB/Ozon thật trong mã chạy với Seller API tổng hợp: bốn hướng preview → xác nhận → gửi → đọc lại → mở lại tác vụ. Kiểm tra preview không gửi mutation, gửi một lần, giá đích sai chưa hoàn tất, mở lại không lặp ghi.
- Giá khác nhau theo size sang Ozon; ghi đè riêng từng size; đổi tiền tệ; đọc JSON draft cũ; danh mục tên trùng; trường văn bản xóa có chủ đích; barcode nguồn nhiều giá trị; nội dung không chuyển được phải hiện trong preview.
- Giá WB phải đọc lại đúng native size. Ảnh Ozon phải giữ ảnh chính/thứ tự; ảnh chính sai không được nghiệm thu chỉ bằng số lượng ảnh.
- UI kiểm tra không cần nhập lại giá trị nguồn, dữ liệu chỉnh sửa sau khi tải lại/đổi shop, ánh xạ size thủ công, một bài lỗi trong batch, phản hồi dictionary cũ và callback preview cũ, xác nhận đăng, rời view khi mutation đang chạy.
- Chụp giao diện từ preview do adapter Ozon tạo với hai giá khác nhau, tại 1366×768 và 1024×600; dùng dữ liệu tổng hợp. Ca chụp đầu thiếu bước `applyCss/layout` khi dựng scene mới, đã sửa fixture và giữ assertion nghiệp vụ.

## Giới hạn nghiệm thu

Đây là kiểm thử mã, adapter với dữ liệu API giả lập và giao diện JavaFX trên Linux. Chưa đăng bài thật lên WB/Ozon, xác minh quyền thực tế của từng seller hoặc nghiệm thu trên máy Windows thứ hai. Các trường API không hỗ trợ được báo ngay trong xem trước; cần xác nhận khả năng chuyển dữ liệu thật theo từng sản phẩm.

Chưa tạo bộ cài, Release hoặc đổi Latest trong mục 6. Các mục 3 và 7 vẫn chờ sơ đồ/phê duyệt riêng; các mục đã sửa còn bước nghiệm thu thao tác thật trên máy seller.
