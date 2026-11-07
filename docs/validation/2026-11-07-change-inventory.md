# Danh mục chỉnh sửa VN Code để chủ dự án đối chiếu — 07.11.2026

Thời điểm đối chiếu Git/GitHub: **07.11.2026, 13:42 MSK (UTC+03:00)**. Đây là báo cáo tổng hợp các yêu cầu trong phiên và các bàn giao tương ứng, không phải một lượt sửa hoặc nghiệm thu API mới. Các yêu cầu lặp được gộp theo chức năng; quyết định sau cùng của người dùng được ưu tiên.

## 1. Bản trên app và mã chưa phát hành

| Phạm vi | Trạng thái đã kiểm tra |
| --- | --- |
| Latest công khai | **v1.6.4**, nguồn/tag `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`, công bố **06.11.2026 00:13:28 MSK**. [Release](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.4). |
| Mã đang chuẩn bị | Nhánh `audit/code-doctor-20261106`; nguồn ứng dụng cuối `5ad16bf3f7ed7d4d3911fd44ab39f42c9f24483c`; HEAD khi đối chiếu `23eb77aa6e328d6f0b01ed9a0203ede09e155dce` là bàn giao. |
| PR | [#21](https://github.com/ntccong2468-lab/Bancapnhat/pull/21), OPEN/draft, base `fix/znack-state-wb-gtin-20261105`. Chưa merge, chưa tạo bộ cài phát hành cho các sửa audit mới. |
| Phạm vi phiên báo cáo này | Đọc mã, Git, bàn giao và CI; không gửi lại giao dịch seller, không mua KIZ, không ký/kích hoạt license hoặc phát hành. |

**Các sửa ở phần 2 chưa có trong app tải từ Latest 1.6.4.** Phần 3–4 là các thay đổi đã có trong nhánh nguồn phát hành kế thừa đến 1.6.4; mức nghiệm thu thật được ghi riêng ở phần 5. Có mã xử lý không đồng nghĩa đã kiểm chứng mọi đơn hoặc mọi sản phẩm của seller.

## 2. Các sửa bằng Code Doctor và soi code sau 1.6.4

Đã cài [Code Doctor](../../.agents/skills/code-doctor/SKILL.md) từ ZIP người dùng gửi. Đã dùng quy trình đọc đường lỗi, tái hiện trước sửa, sửa theo nguyên nhân và ghi phạm vi kiểm chứng; OpenQodex/CodeQL hỗ trợ quét mã. Không gọi scanner là reviewer AI độc lập.

| Mã | Lỗi/nguyên nhân đã xác định | Đã sửa | Bằng chứng và giới hạn |
| --- | --- | --- | --- |
| D01 | Khi đọc chi tiết Ozon thủ công, một đơn 404 hoặc thiếu quantity làm dừng các đơn phía sau. | Cách ly lỗi của đơn, giữ dữ liệu cũ, đọc tiếp các đơn/trang hợp lệ, vẫn báo phần chưa hoàn tất. | Tái hiện lô 101 đơn trước/sau sửa bằng HTTP/DB kiểm thử; chưa nghiệm thu nhận đơn thật bằng key hiện tại. |
| D02 | Đồng bộ trạng thái nền thoát trước khi lưu cursor, có thể quay lại cùng đơn lỗi mãi. | Lưu checkpoint và cờ dữ liệu thiếu qua các trang; không đánh dấu quét đủ khi còn đơn chưa xác minh. | Kiểm SQLite qua nhiều trang; giữ điều kiện dừng khi lỗi quyền/quota. |
| D03 | Trang hàng đợi có một đơn cần đọc bổ sung nhưng payload vẫn thiếu dữ liệu làm mất cơ hội lưu các đơn tốt cùng trang. | Sửa cả đường thủ công và nền để lưu các dòng hợp lệ, giữ cảnh báo cho dòng lỗi. | Hai ca tái hiện đỏ trước sửa, đạt sau sửa. |
| D04 | Thời gian chờ cảnh báo 15 giây bị áp cho từng trang còn lại. | Trang kế tiếp tiếp tục sau 1 giây; thử lại lỗi sau toàn lượt, theo giới hạn API. | Đã kiểm scheduler; đây không phải số đo độ trễ sàn thật. |
| D05 | Quá nhiều bộ lọc trạng thái Znack và thẻ cần đối chiếu bị lẫn vào lỗi. | Còn **Chưa tạo / Đang xử lý / Hoàn tất / Lỗi**, cộng Tất cả; mục Lỗi chỉ nhận thẻ bị Catalog từ chối. | Đã kiểm phân hoạch dữ liệu vào đúng một nhóm. |
| D06 | GTIN/checkpoint cũ được ưu tiên dù có thẻ hiện tại khớp hơn. | Ưu tiên thẻ đúng article, màu và size hiện tại; nhiều thẻ cùng khớp vẫn báo cần chọn. | API WB/Catalog thật + hồi quy; không tự gộp sắc độ màu hoặc suy size. |
| D07 | Trạng thái ghép sản phẩm che trạng thái công bố thật, lỗi lịch sử đè lên thẻ đã công bố. | Tách trạng thái Catalog khỏi kết quả ghép; báo cáo theo thẻ/GTIN vừa đối chiếu. | Thẻ published nhưng sai màu vẫn có thể cần ghép, không bị gọi là Catalog từ chối. |
| D08 | Snapshot hết hạn làm khóa nút GTIN dù worker có thể tự đọc mới. | Cho khởi động cập nhật từ dữ liệu lưu; worker đọc xác minh lại nguồn hết hạn trước ghi. | Không kéo dài hiệu lực cache hay gọi API chỉ vì mở màn hình/lọc. |
| D09 | Khác TN VED bị chặn ở nhiều tầng cập nhật GTIN WB. | Bỏ điều kiện so TN VED ở matching, preview/dispatch/resume và adapter WB; giữ barcode/thuộc tính/giấy tờ hiện có. | Dữ liệu thật có 229 ứng viên published, 204 cặp khác nhóm TN VED; đọc mới 5 mẫu đạt. **Chưa gửi GTIN lên WB trong lượt này.** |
| D10 | Hộp Sửa giấy tờ lấy giấy tờ cache và cho chọn hai loại không đúng yêu cầu mới. | Mặc định cấu hình đúng cửa hàng; cố định **Tuyên bố sự phù hợp**, bỏ selector/radio cũ, có lối mở cấu hình. | WB request thật `type=2`; cấu hình Certificate không tự đổi thành Declaration. |
| D11 | WB loại giấy tờ `2` và form `declaration` bị coi là hai loại, gây trùng cùng số giấy tờ. | Chuẩn hóa alias, dùng chung merge cho preview/patch; giữ ID từ bài đích vừa đọc, không mang ID bài khác sang. | Baseline 37 ca có 1 lỗi tái hiện; sau sửa nhóm 73 ca đạt. |
| D12 | Thiếu expiry có thể bị hiểu là vô thời hạn; nhãn kỹ thuật gây khó hiểu. | Không tự suy vô thời hạn, yêu cầu ngày hoặc xác nhận; dịch nhãn tuyên bố, giữ nguồn chủ sở hữu/phạm vi đúng. | Năm nút WB đã gửi lại giá trị đang có và đọc lại thành công; chưa đổi thử giá trị kinh doanh mới. |
| D13 | KIZ mapping WB đếm toàn bộ barcode hợp checksum thay vì số thẻ Catalog thật, báo mơ hồ sai. | Chọn từ thẻ Catalog thực sự đủ điều kiện; barcode phụ không che một thẻ hợp lệ; nhiều thẻ hợp lệ vẫn cần chọn. | Cùng mẫu API thật: trước `GTIN_AMBIGUOUS`; sau registry/chọn dữ liệu đạt một ứng viên. |
| D14 | Mapping phục vụ mua KIZ bị chặn vì TN VED trên sàn khác Catalog. | Theo chỉ đạo mới nhất, **bỏ so TN VED ở cả WB và Ozon KIZ mapping**; đơn mua dùng metadata TN VED/giấy tờ của Catalog. | Mẫu WB khác TN VED vượt bước chọn dữ liệu bằng API thật; Ozon chưa kiểm được do 403. Chưa tạo verified mapping hoặc mua có phí. |
| D15 | Thẻ không tồn tại bị trả lỗi cục bộ chung. | Chuyển đúng `missing_catalog_card` thành `GTIN_CARD_NOT_FOUND` ở hai service mapping. | Lỗi mạng/quyền vẫn giữ nguyên; không đổi lỗi chưa rõ thành thành công. |

**Nguồn sửa:** `338d46e`/`c2e9dcf` (Ozon), `5710392` (Znack/GTIN), `af3bf5c`/`fd20aa8` (giấy tờ), `5ad16bf` (KIZ mapping). Không thay schema, phiên bản phát hành hoặc triển khai portal trong các đợt này.

Bàn giao: [Ozon/Code Doctor](2026-11-06-code-doctor.md), [Znack/GTIN](2026-11-06-znack-four-states-wb-gtin.md), [năm nút sản phẩm](2026-11-06-products-five-audit.md), [KIZ mapping và sơ đồ](2026-11-07-kiz-mapping-live-audit.md).

## 3. Các chỉnh sửa chức năng đã có trong nguồn Latest 1.6.4

| Mã | Nhóm yêu cầu | Thay đổi đã có và giới hạn cần phân biệt |
| --- | --- | --- |
| P01 | Thay luồng Znack theo WBCode, dùng chung WB/Ozon | Đã đối chiếu mã/luồng WBCode và có báo cáo/sơ đồ. WB/Ozon dùng chung phần gửi, theo dõi, sửa và ký; vẫn có adapter sàn và workflow điều phối riêng. |
| P02 | Tạo từng thẻ hoặc chọn tất cả rồi Đăng ký | Nút thường đi vào tạo/gửi thẻ, không bắt buộc vào menu thử lại GTIN. Gửi National Catalog bằng API key, `POST /v3/feed`, lưu `feed_id`, đọc `/v3/feed-status` và đối soát thẻ. |
| P03 | Dữ liệu hồ sơ và TN VED khi tạo thẻ | Chuẩn hóa theo biến thể; TN VED lấy từ sản phẩm trên sàn, không lấy mã trên giấy tờ thay thế. Kiểm danh mục/thuộc tính, xuất xứ và nguồn dữ liệu; không tự bịa trường thiếu. |
| P04 | Chống tạo trùng, giữ GTIN/good_id | Tìm thẻ hiện có, giữ receipt/payload và GTIN/good_id khi sửa được; chưa rõ server nhận hay chưa thì đọc lại trước. Có checkpoint qua restart và phối hợp chống trùng trong cùng DB. |
| P05 | Gửi liên tục, không kẹt 0/N hoặc tự dừng sau 5 lượt | Đã bỏ tự pause do năm lần đối soát chưa xong; ưu tiên thẻ chưa gửi, giữ yêu cầu đang theo dõi, xử lý 429/cooldown. Pause/Cancel do seller và giới hạn API vẫn có hiệu lực. |
| P06 | Sửa thẻ lỗi, ký/công bố rồi GTIN | Có tối đa hai vòng tự sửa cho lỗi có quy tắc/căn cứ, lịch sử trước/sau; XML/CMS và quyền ký theo hồ sơ được duyệt; đọc xác nhận công bố trước bước đồng bộ. Chưa tự xử lý được mọi trường thiếu. |
| P07 | Znack quá nhiều báo cáo, trạng thái sai | 1.6.4 đã giữ quan sát Catalog mới, đọc bổ sung thẻ biết good_id, tách chờ ký và lỗi, gọn vùng tiến độ/báo cáo. **Bốn bộ lọc và các sửa D05–D09 mới hơn vẫn chưa phát hành.** |
| P08 | Lưu đồng bộ GTIN, không làm lại toàn bộ mỗi lần | Có cache/checkpoint/receipt bền vững, tiếp tục phần chưa hoàn tất; vẫn đọc mới khi cần xác minh thay đổi/quyền/độ mới. |
| P09 | Ozon danh mục lớn, giới hạn 10.000 và GTIN trùng | Có điểm tiếp tục/cửa sổ đọc Catalog, đối soát barcode xuất hiện thật trên Ozon; xung đột GTIN thuộc sản phẩm khác được báo và có đường đăng ký mới có đối chiếu. Stress nguồn mới còn chưa hoàn tất. |
| P10 | WB giá các size trước GTIN | Có đọc giá theo nhóm, tính trung bình và xác nhận cập nhật giá trước append đúng nmID/chrtID; giữ barcode/thuộc tính/giấy tờ; giới hạn API/429. Chưa nghiệm thu ghi giá thật trong các lượt gần đây. |
| P11 | Dùng thẻ đăng ký từ WB cho bài Ozon khác tên | Có **Ghép GTIN từ WB**, đề xuất cặp đúng biến thể để seller duyệt, lưu liên kết cố định, kiểm lại trước ghi; không ghép tự động chỉ bằng tên gần giống. |
| P12 | Năm nút sửa thông tin sản phẩm | Có sửa thương hiệu/chất liệu/giới tính/TN VED/giấy tờ, kiểm thuộc tính theo sàn, đọc thẻ mới nhất trước ghi và đọc lại sau gửi. Sửa nhận diện brand/danh tính theo lô và revision Ozon không đổi vì metadata moderation. Mặc định tuyên bố mới ở D10–D12 chưa xuống app. |
| P13 | Ozon nhận hơn 100 đơn, lỗi action null | Đã sửa null action, chia và duyệt hết tập chọn, kết quả riêng từng đơn, đối soát kết quả chưa rõ thay vì gửi lặp; đơn lỗi không buộc bỏ các đơn hợp lệ. Sửa chống kẹt đọc chi tiết mới D01–D04 chưa phát hành. |
| P14 | Phân biệt đơn Ozon mới nhận và đã in | Có **Chờ nhận → Đơn mới → Đang giao hàng** sau in, trạng thái in lưu bền; **Đã bàn giao** là nhóm riêng. Đơn mới nhận thêm không bị coi là đã in. Tên biến `packing` trong code không phải tên tab người dùng thấy. |
| P15 | Đồng bộ FBS WB/Ozon | Tăng nhịp cửa hàng đang xem, phân trang tới hết, bảo toàn dữ liệu mới hơn khi phản hồi cũ về muộn, checkpoint/đối soát sau lỗi. Là polling có hạn mức, **không bảo đảm độ trễ bằng 0**. |
| P16 | WB tab Đơn nợ và in nhãn | Có shipment từ danh sách giao lại API WB, mở shipment chỉ hiện đơn nợ, thông tin như đơn hàng và nút nhãn bên phải. Màu là quy ước app theo trạng thái API; danh sách WB có độ trễ theo sàn, không phải mọi thiếu quét xuất hiện ngay. |
| P17 | Ozon PDF theo lô | API task nhãn tối đa 1.000 đơn/task, dùng PDF chung/cache; đọc nguồn nhóm tối đa 100 mã đơn, không mặc định gọi nhãn riêng 1/N. Fallback còn dùng khi thực sự thiếu/mơ hồ hoặc chỉ in một phần task. |
| P18 | Giữ vận đơn gốc, bỏ barcode Ozon riêng | Giữ nội dung vận đơn Ozon trả; bỏ trang barcode sản phẩm đứng riêng khi đã nhận diện chắc chắn; giữ vận đơn và KIZ đúng đơn. Trang chưa rõ không bị tự xóa nhầm. |
| P19 | Mỗi đơn có nhãn kết hợp KIZ | 1.6.4 sửa preflight làm mất KIZ đã dành; giữ KIZ đúng posting/product/exemplar, in lại giữ mã. Nếu Ozon từ chối KN3 nhưng vẫn cho nhận, có thể in đúng KIZ đã gắn có bằng chứng; giữ riêng kết quả từ chối. Không có KIZ đúng danh mục thì chưa thể có nhãn kết hợp cho đơn đó. |
| P20 | PDF nhặt hàng Ozon giống thứ tự WB | Có sắp theo quy tắc WB, đồng bộ thứ tự vận đơn; có số vận đơn đúng từng đơn từ `scanit`, bốn số cuối nhấn nền đen. Không lấy bốn số posting tự suy thay mã thật. |
| P21 | Kho seller nhập khác kho mua | KIZ seller tải lên áp quy tắc danh mục, không buộc GTIN trùng; kho mua dùng mapping GTIN đúng biến thể. File người dùng đã kiểm là mã thiếu cryptotail và bị Ozon từ chối; nhập được không có nghĩa sàn chấp nhận. |
| P22 | Thiếu KIZ thì đề xuất mua, Áp dụng rồi tự in | Có bảng phần thiếu, trừ tồn/pipeline chờ, seller xác nhận rồi mua; kiểm lưu thông trước tự xuất trong phiên đang mở. Giữ UUID/receipt, không tự mua trùng. Mua tồn tại qua restart; tác vụ PDF chờ mua chưa tự chạy lại sau restart. |
| P23 | Mua KIZ dù thẻ đã có nhưng GTIN chưa về sàn | Có nguồn proof đăng ký PUBLISHED đúng biến thể, không chỉ dựa barcode marketplace; vẫn kiểm quyền/chủ sở hữu/chứng thư/phạm vi/giấy tờ. Sửa D13–D15 mở rộng KIZ mapping là mã mới chưa phát hành. |
| P24 | Cập nhật app không phải đợi toàn bộ việc nền | Có tải khi đang bận, admission barrier/checkpoint/snapshot trước cài, hủy không gọi lại request cũ. Cài đặt vẫn cần dừng tại điểm đã lưu an toàn, không ép cắt mutation đang ghi. |
| P25 | License mở app nhanh, giới hạn mỗi yêu cầu | Quyền đã ký còn hạn dùng ngay và kiểm tra server ở nền; giới hạn timeout, chẩn đoán TLS chính xác hơn, nhãn License hợp lệ thay thông báo offline gây hiểu nhầm. Chưa chứng minh khắc phục TLS trên đúng máy seller từng lỗi. |
| P26 | Giao diện mới toàn app | Đã áp dụng Taste/Impeccable/UI UX Pro Max, mẫu native được duyệt, tối/sáng, logo/nền tối, controls và motion nhất quán, giảm chuyển động theo cấu hình. Danh sách chiếm vùng làm việc như FBS WB; hàng gọn, footer không che dữ liệu, sửa cột bị cắt và vướng taskbar trên các màn. |
| P27 | Tổng quan, cửa hàng, GS1/cấu hình Znack, tài chính | Bỏ ba ô KPI/khoảng trống ở Tổng quan; header có Quản lý và xóa cửa hàng; GS1/GCP/GLN/trạng thái và năm tab Znack gồm Kho KIZ seller tải lên; chuyển lý do phạt vào Công cụ/Báo cáo tài chính. **Nguồn báo cáo phạt Ozon chưa triển khai; WB có service.** |
| P28 | Sửa thương hiệu báo `Invalid identity or value` khi chọn nhiều sản phẩm | Lỗi xảy ra trước request: từ 20 sản phẩm, phiên đọc chuyển nhầm khóa cấp sản phẩm sang khóa GTIN cần variantId. Đã dùng prepareProducts/refreshProducts và chọn theo mã sản phẩm cho cả năm trường; giữ khóa GTIN bắt buộc biến thể ở đúng luồng riêng. |
| P29 | Sửa giấy tờ Ozon có sẵn vẫn đòi tải file | Đã giữ ID/nguồn giấy tờ, đọc certificate/info và gắn đúng SKU bằng certificate/bind; chỉ giấy tờ mới cần create/upload. Có phân trang và đọc lại certificate_id; không coi bind true là chứng từ đã được duyệt. Các kiểm gần đây bị key 403 nên chưa tái nghiệm thu Ozon. |

Nguồn đối chiếu: [1.6.0](../releases/VN-code-1.6.0.md), [1.6.1](2026-11-03-vncode-161-handoff.md), [Ozon nhận đơn/nhặt hàng](2026-11-04-ozon-accept-picking-handoff.md), [UI đã duyệt](../design/native/2026-11-04/README.md), [1.6.3](2026-11-04-vncode-163-handoff.md), [Znack/WBCode và sơ đồ](../research/2026-11-05-wbcode-1318-vncode-znack.md), [PDF 1.6.4](2026-11-05-ozon-combined-handoff.md), [phát hành 1.6.4](2026-11-05-vncode-164-handoff.md), [lỗi khóa sản phẩm](../fixes/2026-10-26-product-card-bulk-edit-identity.md), [giấy tờ Ozon và layout](../fixes/2026-10-26-product-edit-layout-audit.md), [lịch sử trước 1.6.x](../PROJECT_MEMORY.md).

## 4. Các sửa do soi code/audit trước khi cài Code Doctor

Các sửa dưới đây đã được đưa vào nhánh nguồn kế thừa đến 1.6.4. Báo cáo audit cũ là ảnh chụp thời điểm đó; bảng này cập nhật riêng việc đồng hồ license đã được sửa sau audit.

| Mã | Chỉnh sửa | Phạm vi thực tế |
| --- | --- | --- |
| A01 | Toàn vẹn khi xuất cặp PDF Ozon | Kiểm lại KIZ binding trước tiêu thụ; xuất/rollback nhãn và phiếu nhặt hàng, giữ tệp cũ khi lỗi, chặn hai đích trùng nhau. Không suy thành mọi tình huống mất điện đã được nghiệm thu. |
| A02 | Hủy cập nhật | Truyền cờ hủy xuyên tác vụ để transport không tự thử lại sau hủy/đổi dữ liệu. |
| A03 | Log WB | Bỏ raw body/query nhạy cảm, che key đã biết, giữ status/Retry-After và lỗi validation cần thiết. |
| A04 | Đồng hồ license | Kiểm tra và lấy thời gian dưới cùng khóa để quyết định không dùng mẫu thời gian cũ xen luồng; giữ chính sách chữ ký/hết hạn/thu hồi. |
| A05 | SQL và tính toán biên | Tham số hóa tra cứu TN VED, gia cố hai điểm số học được scanner báo; không kết luận toàn source không có SQL injection từ một lần quét. |
| A06 | Danh sách JavaFX | Tối ưu sắp lại hàng giữ key/selection khi nội dung không đổi; trường hợp đổi nội dung vẫn dùng đường xử lý riêng. Đo 10.000 hàng tổng hợp không phải độ trễ trên máy seller. |
| A07 | Dependency | Nâng Bouncy Castle 1.84 → 1.85; Wrangler/lockfile lên 4.156 để xử lý advisory đã kiểm. Portal chưa được deploy theo đợt audit. |
| A08 | Maven wrapper | Nâng wrapper 3.3.2, ghim checksum bản phân phối Maven và kiểm ZIP bị sửa. Bootstrap JAR vẫn là phần cần rà riêng. |
| A09 | CI/workflow | Mở trigger đúng nhánh PR, tăng timeout build phù hợp; thêm CodeQL/Node/portal/audit và gate đọc SARIF đúng extensions. Không hạ ngưỡng để che kết quả lỗi. |
| A10 | Portal license | Giới hạn đọc body theo stream, trả 413, kiểm chunk/Unicode; sửa kiểm tra cấu hình khóa ký theo cảnh báo JS. Không coi test portal là deployment production. |
| A11 | GS1 và chứng thư | Thêm tìm serif font trên macOS; giữ thumbprint SHA-1 tương thích Windows/CryptoPro, phân loại nó riêng với thuật toán ký CMS. Kiểm thử macOS hiện vẫn có crash riêng. |
| A12 | OpenQodex và bộ nhớ repo | Tích hợp scanner-only, health gate chặn scanner thiếu/disabled/partial, skill repo và báo cáo; lưu CURRENT/PROJECT_MEMORY/index/AGENTS. Không có reviewer AI độc lập hoặc Memory Brain MCP đang kết nối. |

Nguồn: [audit ban đầu](../engineering/audit-report.md), [OpenQodex](../engineering/openqodex-integration.md), [review bảo mật](2026-11-03-release-security-review.md), [prototype credential](../engineering/prototypes/credential-java-handoff.md). Không dùng ghi chú “chưa sửa” của audit cũ để phủ nhận các sửa sau đó; cũng không gọi prototype là mã hóa API key đã áp dụng.

## 5. Các kết quả kiểm chứng có thể khẳng định

| Phạm vi/thời điểm | Kết quả | Không chứng minh |
| --- | --- | --- |
| Windows nguồn phát hành 1.6.4 | 3.864 ca, 0 failure/error, 2 OS skip; native, cài/gỡ, nâng cấp giữ dữ liệu; 16 asset đã đối chiếu, updater bản trước nhận đúng 1.6.4. | Không phải kết quả Windows cho sửa mới `5ad16bf`; chưa quan sát popup trên máy seller. |
| Code Doctor Ozon ngày 06.11 | Baseline 197 ca; 507 ca/25 lớp hồi quy ở các giai đoạn; 72 ca đồng bộ chạy lại và package trên nguồn cuối đợt Ozon; Node 150/150. Chạy JAR Linux bằng hồ sơ trống được. | Lô 101/1.000 trong kiểm thử là HTTP/PDF tổng hợp, không phải nhận 1.000 đơn thật. |
| Znack/GTIN mới ngày 06.11 | 330 ca/14 lớp đạt; đọc API thật WB 388 bài/1.982 biến thể và Catalog 1.380 thẻ; JVM mới đọc lại cache giữ số lượng/trạng thái. | Không có mutation GTIN hoặc feed trong lượt này. |
| Năm nút WB ngày 06.11 | **5 POST thật**, mỗi trường một lần, 43 read, cả năm `RECONCILED`. | Gửi lại giá trị đang có trên một bài, không phải đổi giá trị mới hoặc sửa đủ 131 bài; Ozon 403 nên 0 mutation mới. |
| Hồi quy rộng nguồn giấy tờ `fd20aa8` | 3.880 ca/522 lớp: 3.878 đạt, 2 OS skip, 0 failure/error; **đã tách ba phương thức stress 10.000**. Node 150/150, portal 67/67. | Không được ghi full suite đạt; lượt full trước đó ngắt 143, ba stress chưa hoàn tất. Không dùng số này làm kết quả nguồn KIZ mới. |
| KIZ mapping ngày 07.11, `5ad16bf` | Đọc WB/Catalog/registry thật, cùng mẫu trước lỗi mơ hồ sau chọn được một ứng viên dù khác TN VED; package đạt. | Chưa lưu proof mapping verified, chưa mua/đưa KIZ lưu thông/ký thật. Không chạy test giả lập để nghiệm thu lượt này. |
| Ghi sản phẩm/Catalog ngày 03.11 | 10 lượt gửi lại giá trị hiện có cho năm trường WB/Ozon được đọc lại; một GTIN/feed mới xuất hiện đúng thẻ `draft/moderation`. | Chưa công bố/ký thẻ đó, chưa đẩy GTIN mới về sàn. |
| Ozon thật ngày 04.11 | Có lượt nhận 22/22 và 2/2 theo trạng thái/API lúc đó; PDF 25 vận đơn + 13 KIZ = 38 trang, 2 task, không tải riêng, **6,999 giây xuất / 9,401 giây toàn lượt**. | 12 đơn khác danh mục không có KIZ kết hợp; không phải đo 100/1.000 đơn thật, không chứng minh mọi đơn vô điều kiện. |

### CI mới nhất đã đọc trong phiên báo cáo

- Nguồn HEAD **`23eb77a`**, [security run 42361945862](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42361945862): lint/audit, OpenQodex, CodeQL Java và JS **success**; toàn workflow **failure** vì Dependency Review báo repo chưa bật Dependency graph. Đây không phải một phát hiện CVE mới đã được xác nhận trong patch.
- [Build run 42361945964](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42361945964): portal success; baseline và macOS arm64 còn chạy tại thời điểm chụp; macOS x64 **failure**, JVM `Abort trap: 6`/exit 134 khi chạy `Gs1PrefixRequestDialogTest`. Chưa kết luận nguyên nhân gốc từ log này. Các job installer phụ thuộc đang skipped, chưa có bộ cài audit được xác minh để phát hành.
- Quét toàn source bằng Gitleaks ở đợt giấy tờ bao phủ 2.822 tệp, đã phân loại 10 ứng viên public/test/PEM marker; 21 scanner khác không chọn ở lượt cục bộ đó. Đợt KIZ chỉ quét bốn tệp đổi, 0 finding. CI scanner và local scanner là hai phạm vi khác nhau; không có AI reviewer độc lập.

## 6. Những phần còn thiếu/chưa nghiệm thu — cần giữ trong danh sách công việc

| Mã | Việc còn lại | Lý do/trạng thái |
| --- | --- | --- |
| R01 | Gộp sửa audit vào bộ cài mới và phát hành | D01–D15 còn ở PR #21; Latest vẫn 1.6.4. Báo cáo này không tự phát hành. |
| R02 | Báo cáo lý do phạt **Ozon** | Nguồn hiện tại `FinancePenaltySyncService.request` chủ động trả `OZON_PENALTY_UNAVAILABLE` cho sàn khác WB. Đây là tính năng còn thiếu, không chỉ là key đang lỗi. Release note cũ nói WB/Ozon rộng hơn mã hiện có. |
| R03 | Kiểm nhận/in đủ đơn Ozon thật với bản sửa mới | Key hiện trả **403/code 7/deactivated**; chưa chứng minh sửa mới nhận/in hết danh sách thật. Sàn còn có trạng thái/quyền cấp nhãn; app không thể cam kết xử lý thành công khi API từ chối. |
| R04 | Chuỗi trọn vẹn Catalog → sửa lỗi → ký/công bố → GTIN đúng sàn | Đã có mã và kiểm từng phần; chưa có nghiệm thu thật gần đây hoàn tất toàn chuỗi, kể cả restart không trùng và GTIN mới đúng biến thể. |
| R05 | Đồng bộ GTIN còn tồn trên WB | Review thật ngày 07.11 thấy **228 biến thể có ứng viên published khớp article/màu/size nhưng GTIN chưa trong barcode WB**. Đây không phải danh sách đã ghi xong. Mẫu người dùng còn lệch màu, không tự coi đúng vì chỉ đã tìm thấy thẻ. |
| R06 | Mua KIZ thật và lưu mapping có chứng thư | Thiếu cấu hình/chứng thư OMS/SUZ trong hồ sơ môi trường kiểm. Mẫu chọn được dữ liệu không phải verified mapping hoặc giao dịch có phí hoàn tất. |
| R07 | Tối ưu đọc bổ sung KIZ mapping/GTIN lớn | Fallback giấy tờ vẫn theo từng GTIN; tập thật 3.816 barcode chuẩn hóa, 3.815 không có trong Catalog list đang đọc. Chưa đổi batch, chưa chạy từng ấy HTTP hoặc đo toàn lượt. Luồng Ozon 10.000 còn nhiều đọc chứng từ. |
| R08 | Benchmark thật 1.000 đơn và đóng ba stress 10.000 GTIN | Chưa có phép đo 1.000 đơn Ozon thật; ba test stress nguồn mới chưa hoàn tất. Không dùng số 7 giây/25 đơn làm dự báo tuyến tính cho 1.000 đơn. |
| R09 | Tự xuất PDF sau restart trong lúc chờ mua KIZ | Pipeline mua lưu qua restart; continuation PDF hiện nằm trong phiên chạy. Mở lại phải chọn xuất nhãn, chưa có hàng đợi PDF tự tiếp tục. |
| R10 | Kiểm giá trị sản phẩm mới/đủ năm nút Ozon hiện tại | Các lượt thật đã gửi lại giá trị có sẵn, chưa đổi sang dữ liệu kinh doanh mới. Ozon hiện không truy cập thành công. |
| R11 | Lỗi nhỏ nhập ngày cấu hình giấy tờ | Dialog gợi ý ISO nhưng validator Settings yêu cầu `dd.MM.yyyy`; đã ghi nhận, chưa sửa. |
| R12 | TLS license trên đúng máy từng lỗi | Timeout/khởi động nền đã sửa; chưa có bằng chứng tái kiểm trên chính máy đó để xác nhận hết handshake timeout. Không có kích hoạt license/Gmail trong các lượt kiểm nói trên. |
| R13 | Mã hóa API key/OMS trong dữ liệu thật | DPAPI/migration vẫn là **prototype trên PR theo phạm vi người dùng duyệt**, chưa nối runtime hoặc áp dụng vào DB seller. Dữ liệu runtime hiện chưa được mã hóa bởi prototype này. |
| R14 | Các phát hiện audit dữ liệu/bảo mật chưa đóng | Profile OMS bị thiếu vẫn có nhánh bind từ cấu hình hiện tại; `powershell.exe`/`msiexec.exe` còn gọi theo tên; khóa công khai manifest cập nhật mặc định rỗng; Log4j 2.24.3 chưa nâng trong patch audit. Cần xử lý/triage theo bằng chứng riêng; không coi toàn audit đã sạch. |
| R15 | CI hoàn chỉnh, Windows mới, review và bộ nhớ MCP | Dependency graph chưa khả dụng, macOS x64 crash, baseline chưa xong tại checkpoint; chưa có reviewer AI độc lập; Memory Brain MCP mới là repo đề xuất, chưa cài/kết nối. Bộ nhớ Markdown trong Git có hoạt động. |

Nguồn cụ thể cho R02: [FinancePenaltySyncService.java](../../src/main/java/com/vncode/app/features/finance/FinancePenaltySyncService.java). R09: [giới hạn continuation](../fixes/2026-10-24-kiz-print-purchase-continuation.md). R13–R14: [audit và giới hạn](../engineering/audit-report.md), [prototype](../engineering/prototypes/credential-java-handoff.md). R15: [chỉ mục bộ nhớ](../memory/README.md) và hai run CI trên.

## 7. Các quyết định mới nhất để tránh làm lại sai yêu cầu

- Gửi thẻ Catalog qua API; không đổi OMS ID/connection thành đường gửi feed. OMS/SUZ phục vụ phần mua KIZ tương ứng.
- Tạo thẻ mới vẫn lấy TN VED sản phẩm trên sàn. Append GTIN WB và mapping mua KIZ WB/Ozon được phép khác TN VED theo hai chỉ đạo mới; không tự sửa mã trên Catalog/sàn chỉ để làm khớp.
- Kho seller nhập theo danh mục; kho mua giữ đúng GTIN/biến thể. Ozon từ chối KIZ không được đổi thành đã nhận mã, nhưng vẫn có đường nhận/in khi API cho phép và giữ KIZ đã gắn đúng đơn.
- Vận đơn Ozon giữ nguyên nội dung sàn trả. Yêu cầu trung gian tự thêm `4801` lên vận đơn đã được người dùng thay bằng nhãn gốc; số nhấn nền đen nằm ở phiếu nhặt hàng và dùng mã vận đơn thật.
- UI theo mẫu mới đã được duyệt và có hai theme; yêu cầu sao chép pixel giao diện WBCode cũ không thay thế thiết kế mới đã duyệt sau đó.
- Báo cáo này không có số test hoặc giao dịch mới ngoài các lượt đọc trạng thái GitHub. Không cộng chồng số test của các nguồn khác nhau thành một tổng “tất cả đã đạt”.
