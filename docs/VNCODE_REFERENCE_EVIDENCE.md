# Bằng chứng nghiên cứu repository tham khảo

Ngày: 21.10.2026 (Europe/Moscow). VN Code tại `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`, phiên bản nguồn1.5.7. Java25/JavaFX/Maven/SQLite, schema6. Không đổi license, không ghi dữ liệu production, không merge/phát hành.

Chỉ đọc mã; không chạy ứng dụng/script tham khảo hoặc sao chép mã vào production. honest-sign và National_cat_CHZ không có đầy đủ giấy phép; test-wcode ghi phần mềm thương mại. sklad-cz có MIT nhưng loại trừ thành phần CryptoPro/pycades riêng. Các sửa VN Code được triển khai độc lập dựa trên hợp đồng chính thức và mã đang có.

## Cách đọc bằng chứng và trạng thái triển khai

Các phần nghiên cứu dưới đây được ghi **trước khi sửa**, đối chiếu VNCode tại base `b4672258090b51d6e40f1266cc180c6d1c4946f7`. Citation dòng mã và các nhãn “CẦN SỬA/CHƯA CHẠY TEST” trong phần khảo sát giữ ý nghĩa lịch sử đó. Trạng thái nguồn sau triển khai, file và test cụ thể nằm ở đầu [kiểm toán nguồn](VNCODE_SOURCE_AUDIT.md); kết quả chạy/build nằm trong [báo cáo kiểm thử](VNCODE_ZNACK_TEST_REPORT.md).

Đã triển khai hẹp trên module hiện có: KIZ-01/02/03/04, SIG-01/02, WB-01/02, CIS-01/02, CAT-01/02 và UI-01. CAT-02 dùng hành động review đã có, cập nhật cùng `good_id`/GTIN qua feed với `moderation=1`, không cần tạo hệ gửi kiểm duyệt song song; không tự ký hoặc ghi sàn. PRINT-01 đã bổ sung guard đồng thời và khôi phục khi khởi động. Kết quả tự động cuối: 393 lớp/2.788 ca, 2.786 đạt, 0 thất bại/lỗi, 2 ca chỉ dành Windows chưa chạy trên Linux; Node 103/103; JAR và mở cửa sổ chính cục bộ đạt. Các giới hạn Windows/CryptoPro/API ghi production vẫn chưa nghiệm thu. Không có việc thực thi mã tham khảo, sao chép mã chưa rõ giấy phép hoặc thao tác seller production trong phần nghiên cứu này.

IDs dùng thống nhất: CAT-01 = HS-01, CAT-02 = HS-02; SIG-01/02 = hai vấn đề runner/probe; WB-01/02 = limiter/trang nguồn WB. ID cũ là bí danh nghiên cứu, không phải lỗi mới khác.

# Đối chiếu `honest-sign` với VN Code

Ngày nghiên cứu: 21/10/2026. Đây là kết quả đọc mã và tài liệu; chưa sửa VN Code, chưa chạy Maven, chưa gửi yêu cầu tạo/sửa/ký thẻ thật.

## Nguồn và phạm vi

| Nguồn | Định danh đã kiểm tra |
| --- | --- |
| VN Code | `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit `b4672258090b51d6e40f1266cc180c6d1c4946f7` |
| honest-sign | `equescodebelike/honest-sign`, nhánh `main`, commit `fc2f30b48913fa2fddb9a70c9c277693d3db7753`, ngày commit 06/06/2026 |
| Bản tham khảo cục bộ | `/workspace/references/vncode-source-audit/honest-sign` |
| Tệp thực sự tồn tại | `.gitignore`, `README.md`, `hs-cli.py`, `docs/docs.crpt.ru.html` |
| Giấy phép | Không có LICENSE/COPYING/SPDX; GitHub metadata `license: null`. Chỉ nghiên cứu hành vi và đối chiếu tài liệu chính thức, không sao chép mã. |

Clone công khai thành công; không cần đăng nhập hoặc xử lý vượt quyền. Không chạy script bên ngoài. Tài liệu đi kèm tham khảo là API **5.62, 05/06/2026**, trong khi template VN Code ghi API **5.68, 28/09/2026**. Vì vậy không coi mọi khác biệt của CLI là tính năng VN Code cần thay thế.

Bằng chứng phiên bản tài liệu: [README.md dòng 135–142](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/README.md#L135-L142).

## Luồng và hợp đồng quan sát được

| Nghiệp vụ | honest-sign: tệp/hàm/bằng chứng | VN Code hiện tại và kết luận |
| --- | --- | --- |
| Xác thực | `hs-cli.py::request`, khóa `apikey` trong query; URL mặc định sandbox; không triển khai Bearer/CryptoPro. [28–54](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L28-L54) | `ZnackApiClient` đã có phân biệt khóa Catalog/Bearer, xác thực phạm vi, chặn redirect và che query nhạy cảm. **ĐÃ CÓ; không hạ mức bảo vệ theo CLI.** |
| Liệt kê thẻ | `cmd_list`: GET `/v4/product-list`, limit/offset/status/ngày, chỉ một trang. [60–81](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L60-L81) | `NationalCatalogGtinSource`, `ZnackProductService` đã phân trang, giới hạn theo đúng API khóa Catalog, checkpoint và trạng thái chưa xác minh nếu không thể quét đầy đủ. **ĐÃ CÓ, mạnh hơn CLI.** |
| Đọc một thẻ | `cmd_get`: GET `/v3/feed-product`, `gtin` hoặc `good_id`. [85–94](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L85-L94) | `ZnackNationalCatalogService::observedCardStatus` và `readCard`: danh tính/GTIN/chủ sở hữu/thời điểm đọc và trạng thái thật; 401/403/parse/network không coi là không tồn tại. **ĐÃ CÓ.** |
| Đọc danh mục/thuộc tính | GET `/v3/categories`, `/v3/attributes`, cho truyền category/TN VED. [192–211](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L192-L211) | `ZnackApiClient` có danh mục/mandatory/full model; `validateCurrentRegistrationDraft` và kiểm tra cuối trước POST đã kiểm tra mandatory, kiểu và preset của cả trường tùy chọn, phạm vi giấy tờ, nước. **ĐÃ CÓ; không được ghi trong báo cáo rằng VN Code còn thiếu toàn bộ bước này.** |
| Tạo/cập nhật | `cmd_create`: POST `/v3/feed`; có `good_id` là cập nhật. `moderation=1` chỉ khi có cờ. [111–162](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L111-L162) | `ZnackNationalCatalogService::submit/normalizedFeed` dùng cùng hợp đồng; template mặc định đã có moderation=1 và identified_by trade-unit. Đã chặn đổi TN VED khác thẻ thật, chủ sở hữu sai và gửi lặp khi kết quả chưa rõ. **ĐÃ CÓ.** |
| Theo dõi feed | CLI lấy feed_id rồi chờ hai giây, đọc trạng thái đúng một lần; có lệnh status riêng. [162–184](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L162-L184) | Workflow WB/Ozon đã có checkpoint, nền, khôi phục, đọc lại thẻ và publication thật; feed_id không bị coi là đã xuất bản. **ĐÃ CÓ; không thay queue bằng vòng chờ của CLI.** |
| Gửi kiểm duyệt thẻ nháp có sẵn | `cmd_moderate`: GET `/v3/feed-moderation?good_id=...`. [187–189](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L187-L189) | Tìm kiếm toàn repo chưa thấy triển khai endpoint này. Luồng thẻ mới đã moderation=1. Thẻ Draft có sẵn được nhận diện, gắn lại và hiển thị cần thao tác, nhưng chưa có bước riêng chuyển Draft đó sang kiểm duyệt. **CẦN BỔ SUNG theo chính sách review hiện có; không tự động ký hoặc tự tạo GTIN khác.** |
| Phân tích lỗi feed | CLI đọc các mảng thông báo trong `result.result`, bỏ khóa totalErrors. [165–176](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/hs-cli.py#L165-L176) | VN Code đọc `result.item` và `result.error_details`; chưa đọc định dạng `result.result` dạng chuẩn cũ. **CẦN BỔ SUNG fallback chẩn đoán có giới hạn**, xem CAT-01 (HS-01). |
| Ký/GTIN/KIZ/lưu hàng đợi | Không có triển khai CryptoPro/CAdES, cấp GTIN, kho KIZ, repository checkpoint hoặc kiểm thử trong bốn tệp này. | **KHÔNG PHẢI TÍNH NĂNG THAM KHẢO CÓ THỂ CHUYỂN SANG.** VN Code đã có các module riêng; chỉ đối chiếu bằng tài liệu đúng nghiệp vụ. |

## Phát hiện có thể tái hiện và đề xuất kiểm thử

### CAT-01 (HS-01) — P2: thiếu lý do từ chối khi phản hồi feed dùng định dạng `result.result`

- **Nguồn:** honest-sign `hs-cli.py::cmd_create`, dòng 172–176; tài liệu API chính thức mục 3.2.3.
- **Vị trí VN Code:** `src/main/java/com/vncode/app/integration/znack/registration/ZnackNationalCatalogService.java::readFeedProgress` dòng **686–714**; `collectFeedErrors` dòng **782–789**; `CatalogFeedback::rejection` dòng **36–37**.
- **Hiện tại:** chỉ đọc item/error_details; bỏ qua `result.result`. Phản hồi Rejected vẫn bị coi là lỗi, nhưng không giữ nguyên lý do của Catalog; UI chỉ nhận mã chung `CATALOG_REJECTED`.
- **Đầu vào minh họa theo hợp đồng:** `{"result":{"feed_id":7126,"status":"Rejected","result":{"0":["Неверный тип атрибута"],"totalErrors":"1"}}}`.
- **Mong muốn:** giữ trạng thái Rejected, thông báo lý do chính xác đã che dữ liệu nhạy cảm, không gọi tạo/ký lại. Nếu totalErrors>0 nhưng lý do trống, báo rõ Catalog xác nhận có lỗi nhưng chưa trả chi tiết.
- **Phân biệt bằng chứng:** API **5.68 vẫn công bố hai dạng**: verbose=true → `error_details`; false/không truyền → `result`. `ZnackApiClient::nationalCatalogFeedStatus` dòng **205–206** luôn truyền verbose=true. Vì vậy đây là bổ sung khả năng đọc phản hồi/hồ sơ cũ và tăng độ rõ chẩn đoán; **chưa có bằng chứng API hiện tại luôn bỏ qua verbose hoặc lỗi này là nguyên nhân mọi thẻ không lên Catalog**.
- **Phương án sửa hẹp:** bổ sung parser định dạng chuẩn đã công bố, không đổi endpoint; chỉ thu thông báo thuộc entry phù hợp nếu xác định được, không gán lỗi size khác vào thẻ đang xét; không ghi payload/API key; không diễn giải totalErrors là thành công.
- **Kiểm thử cần thêm:** Rejected + mảng chuỗi; tổng lỗi nhưng thiếu nội dung; cùng lúc verbose + legacy không lặp thông báo; feed có nhiều entry/GTIN không trộn lý do; chuỗi chứa query/token được che; không tạo tác dụng phụ. Chưa chạy kiểm thử mới trong đợt nghiên cứu.
- **Rủi ro:** nếu gán mọi entry vào từng GTIN sẽ báo sai sản phẩm. Nên chỉ báo lỗi chung feed khi không có liên kết entry đáng tin cậy, giữ rõ phạm vi feed.

Tài liệu gốc cùng cấu trúc trong bản tham khảo: [mục 3.2.3](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/docs/docs.crpt.ru.html#L2934). Đã đối chiếu [tài liệu chính thức National Catalog 5.68](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/), mục 3.2.3; HTML SHA-256 `d2d0f01e96a060f73ba55f48d819292e7d5d0f9092e9129a80d1e44405130491`, cùng phiên bản/hàm băm được ghi trong [bằng chứng hợp đồng Catalog](validation/2026-10-21-catalog-country-contract.md).

### CAT-02 (HS-02) — P1 tại base: Draft hiện hữu chưa có đường review và gửi duyệt

- **Nguồn:** `hs-cli.py::cmd_moderate` dòng 187–189; tài liệu chính thức 3.2.5 chỉ áp dụng thẻ hiện đang Draft theo good_id hoặc gtin+inn.
- **Vị trí VN Code:** `RegistrationExistingCards::lookup` đã tìm đúng thẻ theo chủ sở hữu, article/màu/size/TN VED; `ZnackCardRegistrationWorkflow::adoptExisting` dòng **378–384**; `observedProgress` dòng **373–376**; `refreshStatus` dòng **454–469**. Workflow Ozon có nhánh nhận lại thẻ hiện hữu tương đương. Tìm kiếm toàn source/test không thấy chuỗi `feed-moderation`.
- **Hiện tại:** Draft thật được gắn lại và lưu PROCESSING với `CATALOG_CARD_DRAFT`; các lần làm mới đọc lại Draft. Không có yêu cầu riêng chuyển Draft đó sang kiểm duyệt. UI đã phân biệt Draft cần thao tác; không phải đang báo Draft là xanh.
- **Mong muốn:** với Draft hiện hữu đã được seller xem/xác nhận đủ dữ liệu hiện tại, có một hành động kiểm duyệt được kiểm tra chủ sở hữu/danh tính/schema/giấy tờ. Lưu receipt/trạng thái trước khi theo dõi; nếu timeout chưa rõ kết quả phải đọc lại, không lặp GET thay đổi dữ liệu tùy tiện.
- **Phạm vi:** không áp dụng cho mọi đăng ký mới vì template mới đã moderation=1; không đổi thẻ Published/Moderation/NotSigned; không tự xin GTIN mới; không thay thế bước ký.
- **Kiểm thử cần thêm:** Draft đúng danh tính → một lệnh kiểm duyệt, không allocate GTIN/không tạo lại; thiếu trường/chưa review → không gửi; timeout → reconcile thay vì replay; thẻ đã kiểm duyệt không gọi lại; WB/Ozon đều tiếp tục theo dõi.
- **Rủi ro:** endpoint dùng GET nhưng thay đổi dữ liệu, không được đặt vào retry GET chung; cần xác định hành động seller và chính sách review hiện có trước khi thêm; chưa tự tạo thao tác legal-write mới.

Tài liệu tham khảo: [mục 3.2.5](https://github.com/equescodebelike/honest-sign/blob/fc2f30b48913fa2fddb9a70c9c277693d3db7753/docs/docs.crpt.ru.html#L3262).

**Kết quả triển khai sau khảo sát:** đã bổ sung eligibility chỉ cho Draft thuần có danh tính/biến thể/phạm vi được xác minh và chưa có feed. Seller phải xem toàn bộ trường bắt buộc và bấm Áp dụng, cả khi không thiếu trường; nếu đang bổ sung dữ liệu trên sàn thì sau đó vẫn hiện bước review đầy đủ. Luồng cập nhật hiện có gửi cùng `good_id`/GTIN với `moderation=1`; việc tài liệu còn có endpoint `feed-moderation` không bắt buộc tạo client/workflow khác. Không áp dụng cho Published/Moderation/NotSigned hoặc bản ghi không chứng minh được danh tính; không cấp GTIN/ký/ghi sàn tự động cho lần sửa Draft này. Các lần đồng bộ thụ động không gửi feed; timeout phải đối soát thay vì POST lại. Source và test UI/backend tương ứng được liệt kê trong kiểm toán nguồn; chưa coi việc viết test là kết quả chạy đạt hoặc đăng ký production thành công.


## Không áp dụng trực tiếp từ tham khảo

- Không dùng những mã danh mục/thuộc tính vật liệu xây dựng trong README làm mặc định cho hàng thời trang.
- Không dùng `is_tech_gtin`/`is_sim` để tránh thuộc tính bắt buộc hoặc quy định маркировка.
- Không suy ra `is_set` và `is_kit` là một; đây là các ý nghĩa khác nhau trong hợp đồng.
- Không sửa categories numeric-array của VN Code chỉ vì CLI dùng `{cat_id:...}`. Tài liệu đi kèm có cả hai hình thức ví dụ; cần hợp đồng hiện hành nếu muốn đổi biểu diễn.
- Không chép parser `id=value::type`: dấu phân tách đó có thể xung đột giá trị giấy tờ `số:::ngày`; VN Code đã có model thuộc tính và giấy tờ riêng.
- Không chép request helper của CLI: thiếu timeout rõ ràng, không có bảo vệ retry tác dụng phụ, trả lỗi thô và không có kiểm tra owner/variant.

## Đánh giá nghiệm thu của phần nghiên cứu

Đã xác minh mã tham khảo và commit, giấy phép, endpoint/tham số thực sự được dùng, các giới hạn và sự khác biệt so với mã VN Code tại commit chỉ định. Không phát hiện bằng chứng cần viết lại `ZnackNationalCatalogService` hoặc thay CryptoPro bằng tham khảo này. Hai bổ sung ở trên cần triển khai độc lập từ hợp đồng chính thức và kiểm thử; phần nghiên cứu này không tuyên bố đã sửa hoặc đã đăng ký thẻ thật thành công.


# Kiểm toán National_cat_CHZ và đối chiếu VN Code

Thời điểm nghiên cứu: 21/10/2026. Phạm vi hiện tại chỉ đọc mã và tái hiện SQL trên SQLite trong bộ nhớ; chưa sửa mã VN Code, chưa chạy Maven/JUnit, không gọi API seller và không chạy mã Python từ kho tham khảo.

## 1. Nguồn và giới hạn sử dụng

- VN Code: `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`.
- Kho tham khảo: https://github.com/Chenuss/National_cat_CHZ ; bản đọc cục bộ `/workspace/references/vncode-source-audit/National_cat_CHZ`.
- Commit tham khảo: `1c237dd2e25517016b2105990fba0dd8c2dcf93e`, ngày 28/05/2026; checkout detached, không sửa kho này.
- Giấy phép: [readme.md:146–148](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/readme.md#L146-L148) ghi “MIT”. Toàn bộ danh sách tệp tracked không có LICENSE/COPYING/NOTICE hoặc toàn văn giấy phép. Trạng thái quyền sao chép: **CHƯA XÁC MINH**; chỉ nghiên cứu hành vi, không sao chép mã. Các sửa VN Code phải triển khai độc lập.
- Kho còn chứa `.env`, `catalog.db`, bytecode `__pycache__`; đã thấy tên tệp qua Git nhưng không đọc `.env`/database và không thực thi bytecode/script. Không sử dụng dữ liệu hay thông tin đăng nhập trong kho.

## 2. Tệp thực tế và bằng chứng

Ba tên trong yêu cầu đều tồn tại; không giả định từ tài liệu:

| Nội dung | Bằng chứng tại commit đã ghim | Nhận xét kỹ thuật |
|---|---|---|
| Client cũ / luồng CLI | [catalog_client.py:52–120](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L52-L120); [readme.md:25–43](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/readme.md#L25-L43) | CLI hướng dẫn chạy client cũ. Chỉ có một GET danh sách, không vòng offset và không truyền timeout ở dòng84. Không thể dùng làm bằng chứng đã đồng bộ hết cửa hàng. |
| Client mới | [nk_client.py:130–161](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L130-L161) | Có lớp NKCatalogClient mới; không thấy CLI cũ hoặc loader nhập lớp này trong toàn bộ tệp Python tracked. Không đồng nhất tính năng của lớp mới với luồng chạy cũ. |
| GTIN / thẻ đủ trạng thái | [nk_client.py:575–617](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L575-L617) | GET feed-product cho good_ids/gtins, tối đa25, nối `;`; chủ động tắt ETag. Đây là đọc thẻ, không cấp GTIN, không gửi kiểm duyệt hay ký. |
| Danh sách và phân trang | [nk_client.py:653–689](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L653-L689) | Wrapper nhận limit/offset, clamp limit1000; ghi chú giới hạn10.000/khung ngày. Không có thuật toán quét hết/dò ngày an toàn được tích hợp. Không suy ra tự động chia khoảng ngày là đầy đủ ở biên thời gian. |
| Mô hình thuộc tính | [nk_client.py:758–788](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L758-L788) | GET attributes theo cat_id hoặc TN VED; không ETag. |
| Lưu thuộc tính | [load_attributes.py:153–228](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L153-L228); [251–268](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L251-L268) | Cập nhật cờ thẻ, DELETE rồi INSERT lại thuộc tính/định danh, commit cuối batch. Loader không dùng client mới. Lược đồ SQL có UNIQUE(good_id,attr_id): [db_schema.sql:171–200](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/db_schema.sql#L171-L200). Không lấy mẫu này để thay hệ thống multivalue hiện có của VN Code. |
| Lưu trạng thái / GTIN | [catalog_client.py:141–163](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L141-L163); [200–230](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/catalog_client.py#L200-L230); [models.py:62–111](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/models.py#L62-L111) | good_id làm khóa, GTIN lưu chuỗi; UPSERT. Model mới thêm ETag. Có model SyncState nhưng chưa thấy luồng CLI lưu/khôi phục nó: [models.py:507–524](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/models.py#L507-L524). |
| ETag | [nk_client.py:148–149](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L148-L149); [386–389](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L386-L389); [431–461](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L431-L461); [842–851](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L842-L851) | Gửi If-None-Match, phân biệt304, có wrapper etagslist. Nhưng danh sách ETAG_SUPPORTED_METHODS chỉ được khai báo, không được request() kiểm tra. get_countries dòng756 vẫn bật ETag cho endpoint ngoài danh sách: không sao chép nguyên chính sách này. |
| HTTP429 | [nk_client.py:546–562](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L546-L562) | Đọc Retry-After dạng giây, fallback300s, ném lỗi cho caller; không đọc HTTP-date. Loader cũ đệ quy vô hạn khi429: [load_attributes.py:70–82](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/load_attributes.py#L70-L82). |
| Retry / timeout | [nk_client.py:398–497](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/nk_client.py#L398-L497) | Timeout thật được truyền ở412; retry kết nối/timeout/5xx với backoff cap60s. Hàm request generic không phân biệt mutation. Không đem retry này vào cấp GTIN/mua KIZ/ký. Chỉ gán session.timeout tại257 không thay thế timeout mỗi request. |
| Migration tham khảo | [migrate_db.py:29–56](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/migrate_db.py#L29-L56); [122–145](https://github.com/Chenuss/National_cat_CHZ/blob/1c237dd2e25517016b2105990fba0dd8c2dcf93e/migrate_db.py#L122-L145) | ALTER thêm cột, tạo bảng, commit/rollback. Không có bằng chứng backup/khóa app/khôi phục job như VN Code. Không thay migration VN Code bằng script này. |

Không có bộ test pytest/unittest/tệp test tracked trong kho tại commit trên. `audit_report.md` chứa cả nhận xét lỗi cũ và mã đề xuất, thậm chí mô tả etagslist khác code hiện tại; chỉ được dùng như ghi chú, không coi là hành vi đã triển khai.

## 3. Đối chiếu với VN Code hiện tại

Các dòng VN Code dưới đây thuộc commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`.

| Thành phần VN Code | Trạng thái | Bằng chứng / kết luận |
|---|---|---|
| NationalCatalogGtinSource | ĐÃ CÓ | `integration/znack/registration/NationalCatalogGtinSource.java:24–49,109–178`: danh sách1000/trang với cửa sổ10.000 khi dùng Catalog key; batch25; kiểm tra total/dupe/truncated; checkpoint khi429/timeout; không biến lỗi thành kết quả không tìm thấy. Source registration cần total đầy đủ, không dùng cursor cũ làm bằng chứng vắng mặt. |
| CatalogScanCheckpoint | ĐÃ CÓ | `.../CatalogScanCheckpoint.java:25–37`: khóa(shop_id,source), lưu tiến độ/quan sát, TTL2h; loader kiểm tra tương thích pageSize ở source110–112. Giữ cache đã biết cho đến quét thành công. |
| Thẻ/thuộc tính/ID | ĐÃ CÓ | `.../CatalogProductDataParser.java:21–70`: raw good_id/status, giá trị list, thuộc tính và giấy tờ. `.../ZnackNationalCatalogService.java:160–213`: đọc schema bắt buộc/đầy đủ và dependencies; không cần database thứ hai. |
| GTIN tồn tại / matching biến thể | ĐÃ CÓ | `.../RegistrationExistingCards.java:19–95`: một snapshot đầy đủ trong mỗi thao tác, exact barcode/article/color/typed size, đọc lại thẻ thật; lỗi/scope không rõ không cấp mới. Test `RegistrationExistingCardsTest`, `NationalCatalogListWindowTest` hiện diện; chưa chạy lại trong đợt nghiên cứu này. |
| Chống cấp GTIN lặp | ĐÃ CÓ | `.../ZnackCardRegistrationWorkflow.java:573–615`: tra tồn tại, checkpoint ALLOCATING_GTIN, lưu GTIN + READY_TO_SUBMIT trước POST. `.../ZnackApiClient.java:160–164` chặn retry endpoint GET có tác dụng cấp mã. `.../ZnackCardRegistrationRepository.java:436–449,472–505` giữ danh tính/không ghi GTIN khác vào thẻ hiện có. |
| Khôi phục đăng ký | ĐÃ CÓ | `.../ZnackRegistrationBatchStore.java:18–57,100–126`: hàng đợi bền vững, retry_at/count và điều khiển giữa các thẻ; chỉ CHECKING chưa allocation được khôi phục sang preflight. `ZnackCardRegistrationWorkflowTest:212–220,269+` có kiểm thử mất phản hồi cấp mã và dùng lại GTIN/feed. |
| HTTP retry mutation | ĐÃ CÓ | `ZnackApiClient.java:97–109,160–164,232–238,289–320`: client không replay mutation, GET cấp mã/nhận block mã dùng đường không lặp; retry429 chỉ yêu cầu idempotent. VN Code an toàn hơn generic retry của kho tham khảo ở điểm này. |
| Khóa request mua KIZ | ĐÃ CÓ | `ZnackSchemaSupport.java:245–267` unique(shop_id,request_key); `ZnackPurchaseCoordinator.java:113–125` replay theo khóa; `ZnackGtinWorkflowTest:459–495` có ca request lặp. Chưa xác nhận toàn bộ luồng đúng vì hai lỗi P0 bên dưới. |
| App lock, snapshot, migration | ĐÃ CÓ | `shared/LocalDataMigrationGate.java:49–59,75–100`: lấy quyền sở hữu, recover, chặn schema mới hơn, tạo/reuse snapshot trước init. Tuy nhiên migration purchase có reset nguy hiểm bên dưới. |
| HTTP ETag Catalog | CẦN BỔ SUNG nếu có lợi hiệu năng / P2 | Chưa có cơ chế HTTP conditional `If-None-Match`/`etagslist`; body `_etag` của country dictionary đã được lưu trong bằng chứng dictionary và không phải HTTP ETag. Không phải lỗi ghi sai; không áp dụng cho feed-product/product-list. `docs/validation/2026-10-21-catalog-country-contract.md:84–92` đã phân biệt body _etag với HTTP ETag và ghi country dictionary no-store; không thêm conditional cache cho country dựa trên body _etag. |
| Hơn10.000 thẻ/cửa sổ và timeout | CHƯA XÁC MINH cho mở rộng | Hiện trả CATALOG_LIST_RANGE_UNSUPPORTED và không xác nhận thiếu thẻ khi vượt phạm vi. Deadline quét là3phút; selected đọc không giữ resume để tránh âm tính cũ. Không tự chuyển sang thuật toán cắt ngày của kho tham khảo khi chưa có bảo đảm biên ngày/order/cursor từ đặc tả. |

“ĐÃ CÓ” ở bảng trên là đã xác nhận bằng mã và tệp test, không phải tuyên bố test vừa chạy hoặc API production đã thành công.

## 4. Lỗi P0 cần sửa trước: migration có thể mua KIZ lại

- Repository: VN Code; file `src/main/java/com/vncode/app/integration/znack/ZnackSchemaSupport.java`.
- Hàm: `createTables` dòng227–240 và `normalizeGtins` dòng440–455.
- Hiện tại: mọi pipeline active thứ hai cùng GTIN hoặc cùng GTIN sau chuẩn hóa bị đưa về `QUEUED`, xóa error; áp dụng cả `CREATING_ORDER`, `RECONCILING_ORDER`, `POLLING_ORDER`, `DOWNLOADING_CODES`. `order_id` vẫn giữ nguyên.
- Đường hậu quả: `ZnackPurchaseCoordinator.java:413–435` cho QUEUED kích hoạt lại VALIDATING; `createOrder:524–530` gọi `orders.buy` không xét order_id cũ. Với dữ liệu cũ/collision13–14 số, một đơn đã mua hoặc chưa rõ kết quả có thể bị mua lần nữa.
- Bằng chứng tái hiện: [JSON tái hiện migration tại base](validation/2026-10-21-source-audit-migration-risk.json). Chạy đúng hai câu UPDATE lấy từ source trên SQLite in-memory độc lập, không chạy Java/kho tham khảo. Cả ca duplicate cùng GTIN và ca13/14 chữ số đều biến row `RECONCILING_ORDER`, order_id102 thành QUEUED và xóa uncertainty. Đây là bằng chứng SQL; chưa chạy hồi quy Java.
- Mong muốn: chỉ tái xếp hàng công việc chứng minh chưa gửi; giữ mọi remote order ID, receipt, mã đã tải và trạng thái uncertain/sent. Migration collision không được tự tạo lại mua hàng.
- Phương án sửa hẹp: phân loại durable evidence trước chuẩn hóa, giữ/reconcile hoặc giữ chặn những hàng đã gửi/không rõ; điều chỉnh index khi cần theo hướng không cho tạo mới trong khi còn giao dịch chặn. Không reset trạng thái đã gửi chỉ để tạo unique index. Giữ mọi dữ liệu gốc và migration idempotent.
- Kiểm thử cần thêm: duplicate legacy có hai order_id thật; canonical collision13/14; CREATING_ORDER không có phản hồi; POLLING/DOWNLOADING có mã đã lưu; init hai lần; tiếp tục worker không thêm POST mua.
- Rủi ro: queue FIFO mua KIZ, khôi phục DB cũ và normalization; không mở rộng sửa FBS/in nhãn/license.
- Phân loại: **CẦN SỬA — P0 — ĐÃ TÁI HIỆN SQL / CHƯA KIỂM THỬ JAVA**.

## 5. Lỗi P0 cần sửa trước: đối soát SUZ coi UNKNOWN là không có đơn

- Repository: VN Code; file `src/main/java/com/vncode/app/integration/znack/ZnackKizOrderService.java`.
- Hàm: `reconcile`, dòng82–116; API wrapper `ZnackApiClient.orderList:227–229` chỉ ép JsonObject.
- Hiện tại: thiếu orderInfos, null hoặc sai kiểu trở thành mảng rỗng tại85–86. Các row/createdTimestamp/buffer không đọc được bị skip. Mảng matches rỗng trả NOT_FOUND tại114.
- Đường hậu quả: `ZnackPurchaseCoordinator.reconcileOrder:566–572` sau5phút đổi đơn đang uncertain thành FAILED và giải phóng hàng chờ. Một HTTP200 `{}`, JSON báo lỗi nghiệp vụ hay danh sách hỏng có thể vì thế cho mua tiếp dù đơn cũ đã được SUZ tạo.
- Mong muốn: dữ liệu sai/thiếu/không đầy đủ là UNKNOWN và giữ RECONCILING; chỉ NOT_FOUND khi response list hợp lệ/đầy đủ chứng minh không có đúng đơn. Không suy luận từ dữ liệu không parse được; không chọn nhầm đơn GTIN khác chỉ vì quantity giống nhau.
- Bằng chứng: chuỗi nhánh source nêu trên. `ZnackModuleTest` khoảng698 kiểm tra payload hợp lệ; `ZnackGtinWorkflowTest:496–605` kiểm tra enum kết quả mô phỏng, có ca confirmed-missing giải phóng queue. Tìm toàn bộ orderInfos/reconcile không thấy test malformed list dẫn đến NOT_FOUND. Chưa chạy Java trong pha nghiên cứu.
- Phương án sửa hẹp: validate wrapper/business errors/OMS scope và từng row liên quan; sai shape/identity/time/quantity/buffer phải ném lỗi an toàn để coordinator giữ đối soát. Không dùng fallback[] cho missing. Giữ exact one-match và conflict; không gọi mua lại để “thử”. Giới hạn phạm vi absence theo hợp đồng list chính thức, không tự giả định phân trang.
- Kiểm thử cần thêm: `{}`, orderInfos:null/object, business error HTTP200, row thiếu orderId/time, buffer malformed/GTIN invalid/quantity fractional, OMS mismatch, valid empty và multiple candidates. Với local attempt đã quá5phút, malformed vẫn chặn đơn sau và POST count không tăng.
- Rủi ro: hiện thông báo cần đối soát lâu hơn khi API lỗi; đây là hành vi an toàn cần thiết để tránh tính phí lặp.
- Phân loại: **CẦN SỬA — P0 — BẰNG CHỨNG LUỒNG MÃ / CHƯA KIỂM THỬ JAVA**.

## 6. Hướng triển khai và báo cáo kiểm thử trung thực

1. Sửa hai lỗi P0 trong service/schema đang có; không tạo database/module thứ hai.
2. Bổ sung hồi quy migration và SUZ malformed-response trước khi đụng hiệu năng/ETag.
3. Chạy Maven/JUnit phù hợp và regression FBS/KIZ/migration trên dữ liệu tổng hợp; không thử mua/đăng ký/ký/cập nhật seller thật.
4. Chỉ sau kết quả test mới ghi ĐÃ SỬA/ĐÃ KIỂM THỬ. Đợt nghiên cứu này không có build/test Maven và không xác nhận Windows/CryptoPro/API production.


# Đối chiếu `sklad-cz`: CryptoPro, xác thực và chứng cứ trạng thái

## Phạm vi và mốc mã nguồn

- VN Code: `/workspace/Bancapnhat`, nhánh `upgrade/znack-source-audit-20261021`, commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`.
- Tham khảo: `https://github.com/akdengi/sklad-cz`, nhánh mặc định `master`, commit `c6a72c642ade6066dcc9b42dc480a1f59ecb9760`, ngày commit `2026-10-09T02:09:48+04:00`, thông điệp phiên bản `v1.5.5`.
- Bản clone chỉ để đọc: `/workspace/references/vncode-source-audit/sklad-cz`. Không cài dependency hoặc chạy mã tham khảo; không dùng chứng thư, tài khoản hoặc PIN seller.
- Kết luận kiểm thử tại thời điểm lập đoạn báo cáo: **CHƯA CHẠY TEST** trong đợt nghiên cứu này; chỉ đọc mã, tìm kiếm toàn repository và đối chiếu các test đã tồn tại. Không có thay đổi mã VN Code bởi người lập đoạn báo cáo này.

## Giấy phép

[`LICENSE:1–21`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/LICENSE#L1-L21) ghi MIT, bản quyền năm 2026 Александр Казанцев. Có thể nghiên cứu/tái sử dụng theo điều kiện giữ thông báo giấy phép khi sao chép phần đáng kể. Tuy nhiên [`LICENSE:25–40`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/LICENSE#L25-L40) nêu rõ `pycades` chưa công bố license riêng và CryptoPro CSP/CAdESCOM là sản phẩm độc quyền cần license độc lập. Không đưa `pycades`, CryptoPro binary hoặc mã wrapper chưa rõ license vào VN Code. Không cần sao chép mã Python để khắc phục lỗi Java đã xác định.

## Các thành phần thực sự tồn tại

| Nội dung | Bằng chứng đã ghim commit | Hành vi thực tế |
|---|---|---|
| Ký Windows | [`app/cz_api.py:80–127`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L80-L127) | Khởi tạo COM trên luồng gọi, tìm chứng thư theo thumbprint, tạo `CAdESCOM.CPSigner`, `CheckCertificate=True`, nạp UTF-8 qua Base64, gọi `SignCades(...,1,False,0)` rồi bỏ xuống dòng. `False` là cấu hình attached trong lời gọi này. |
| Danh sách chứng thư Windows | [`app/cz_api.py:130–181`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L130-L181) | Quét hai store, gộp theo thumbprint, loại chứng thư không có khóa riêng và lọc INN bằng chuỗi xuất hiện trong subject. Chưa thấy kiểm tra thời hạn rõ ràng trong hàm này. |
| Ký Linux | [`app/cz_api.py:186–200`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L186-L200) | Dùng `pycades`, tìm SHA1, `CheckCertificate=True`, ký BES với detached cố định `True`. Khác cấu hình Windows; không áp dụng nguyên xi cho các ngữ cảnh API khác nhau. |
| Xác thực True API | [`app/cz_api.py:262–321`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L262-L321) | GET `/auth/key`, ký challenge, POST `/auth/simpleSignIn` với `uuid/data/unitedToken/inn`, lưu token trong biến toàn cục; HTTP timeout 15 giây. Cache toàn cục này không được ràng buộc theo chứng thư, môi trường hoặc công ty. |
| Kiểm tra KIZ | [`app/cz_api.py:324–390`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L324-L390) | POST `/cises/info`, giới hạn nội bộ 1000, chia 900; đọc lại token một lần khi 401. Đoạn làm sạch dùng marker `91EE12` cố định nên không phải quy tắc tổng quát mọi nhóm mã. |
| Data Matrix | [`app/utils.py:134–160`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/utils.py#L134-L160), [`app/utils.py:249–252`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/utils.py#L249-L252), [`app/routes/labels.py:72–86`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/routes/labels.py#L72-L86) | Phân tích AI01/21/91/92 từ cấu trúc cuối mã, bảo toàn GS/FNC1, chuyển FNC1 cho encoder `treepoem` có `parsefnc=True`. Cấu trúc 44 ký tự crypto/khóa 4 ký tự là phạm vi của parser này, không phải bằng chứng áp dụng mọi mã маркировка. |
| Gửi tài liệu | [`app/cz_api.py:688–716`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L688-L716), [`app/cz_api.py:991–1022`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L991-L1022) | Ký JSON UTF-8 gốc, Base64 riêng `product_document`, POST `/lk/documents/create`; có retry sau 401. Đây là tài liệu lưu thông/thu hồi, không phải quy trình tạo thẻ National Catalog. |
| Đọc trạng thái tài liệu | [`app/cz_api.py:393–475`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L393-L475), [`app/cz_api.py:1039–1065`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/cz_api.py#L1039-L1065) | GET `/doc/list?pg&number`, lấy bản ghi đầu; sau gửi ngủ 2 giây và bắt trạng thái từ chối. Nếu chưa đọc được kết quả vẫn có thể trả `success=True` cho việc gửi. Không thể coi đây là bằng chứng công bố cuối cùng. |
| Cấu hình PIN | [`app/routes/settings.py:57–78`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/routes/settings.py#L57-L78), [`app/utils.py:101–108`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/utils.py#L101-L108) | Route lưu `cz_key_pin` vào JSON thông thường. Không áp dụng cách lưu này vào VN Code. Việc ô nhập dùng type=password không làm dữ liệu lưu trên đĩa được mã hóa. |

Tài liệu mô tả của chính dự án: [`docs/15-api-chestny-znak.md`](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/docs/15-api-chestny-znak.md#L23-L110). Đây là tài liệu dự án tham khảo, **không phải đặc tả chính thức CRPT/CryptoPro**. Khẳng định trong tài liệu rằng mọi `csptest` đều cho chữ ký không phù hợp chưa đủ chứng cứ để bỏ fallback VN đang có; cần kiểm chứng đúng binary/version/ngữ cảnh/chữ ký trên Windows được phép.

## Đối chiếu toàn repository VN Code

Đã tìm toàn `src/main`, `src/test` cho `CryptoPro`, `CAdESCOM`, `Signature`, `CertificateSigningQueue`, `PIN/pincode/pin_code/password`, các lời gọi `runner.run`, `verifyingSigner`, `cancel` và các lớp xác thực. Không kết luận thiếu chức năng từ một file đơn lẻ.

| Yêu cầu | VN Code và bằng chứng tại commit gốc | Phân loại |
|---|---|---|
| Phát hiện CryptoPro và nhiều nền tảng | `CryptoProCommandRunner.java:50–129`: PATH, thư mục 32/64-bit và tên cryptcp; `CryptoProSignatureProvider.java:28–34,81–99,112–126`: cryptcp → csptest → CAdESCOM có điều kiện. | **ĐÃ CÓ**, giữ nguyên. |
| Đúng định dạng theo API | `ZnackSignatureContext.java:3–17`: attached cho True API auth, detached cho Catalog auth/SUZ/tài liệu; `CryptoProSignatureProvider.java:67–80`, `CsptestSignatureProvider.java:52–70`, `WindowsCadesSignatureProvider.java:77–110,147–195,349–364`: truyền đúng context vào từng provider, kiểm tra cấu trúc CMS. | **ĐÃ CÓ**; chữ ký CryptoPro thật **CẦN KIỂM THỬ WINDOWS**, không suy diễn từ chữ ký mock. |
| Đúng chứng thư/chủ thể | `CryptoProSignedCertificateReader.java:27–57`: lấy chứng thư thật từ signer CMS, kiểm tra issuer/serial, SHA1 khớp lựa chọn, INN; `ZnackRegistrationScope.java:22–60`: private-key metadata, validFrom/validTo, INN/environment/fingerprint trước và sau ký; `ZnackAutomationController.java:733–749`: ký thử cục bộ và đọc lại chứng thư thật trước gửi auth. | **ĐÃ CÓ**, mạnh hơn lọc subject chuỗi của tham khảo. |
| Không treo JavaFX trong ký thử | `ZnackAutomationController.java:711–749,837–838`: background `Task` qua `AppTaskExecutor`; discovery tương tự tại `619–648`. | **ĐÃ CÓ**. Không thay bằng ký đồng bộ của route tham khảo. |
| Không lưu PIN plaintext | Tìm toàn repository không thấy trường lưu PIN CryptoPro vào Settings/SQL/FXML; `CryptoProSignatureTest.java:164–173` có khẳng định command không chứa `-pin`. `ZnackSanitizer.java:22` che PIN nếu xuất hiện trong diagnostic. Các `password` GS1 là mật khẩu thư được bảo vệ Windows, không phải PIN token. | **ĐÃ CÓ theo mã đã đọc**; không thêm lưu PIN. |
| Tuần tự hóa theo chứng thư | `CertificateSigningQueue.java:13–24`, `ZnackSigningSession.java:57–75`: khóa theo fingerprint chuẩn hóa; ngắt khi chờ khóa; lỗi ký chặn tự mở lại cho tới seller cho phép. | **ĐÃ CÓ**. |
| Consent ký đã duyệt | `RegistrationSigningConsent`, `RegistrationSigningReview`, các workflow WB/Ozon đã ràng buộc payload/XML/hash/owner/scope và đọc lại. | **ĐÃ CÓ**, ngoài phạm vi sửa signer; giữ nguyên. |
| Hủy/timeout tiến trình thực tế | `CryptoProCommandRunner.java:30–46` có thiếu cleanup và giới hạn đọc stream, phân tích bên dưới. | **CẦN SỬA P1**. |
| Giữ đúng nguyên nhân khi probe CAdESCOM bị hủy/timeout | `WindowsCadesSignatureProvider.java:305–325,452–455`: mọi `CryptoProException` từ probe bị bọc thành `CADESCOM_MISSING`. | **CẦN SỬA P1 về chẩn đoán**, không thay đổi thuật toán ký. |

## Lỗi cụ thể và phương án sửa tối thiểu

### SIG-01 — Hủy luồng không dừng tiến trình ký; timeout chưa bao phủ đọc kết quả

1. Repository: VN Code `b467225...`.
2. File/class/method: `src/main/java/com/vncode/app/integration/znack/signature/CryptoProCommandRunner.java`, `CryptoProCommandRunner.run`, dòng `30–46`; stream reader tại `166–168`.
3. Hành vi: `Process` nằm trong try; khi `waitFor` bị interrupt thì chỉ gắn lại interrupt và ném `CANCELLED`, không destroy process hoặc descendants/đóng streams. Khi quá hạn thì chỉ destroy process trực tiếp. Hai `CompletableFuture.supplyAsync` đọc `readAllBytes` không có cleanup hoặc deadline. Sau khi process trực tiếp thoát, `stdout.join`/`stderr.join` không có giới hạn: child kế thừa pipe có thể giữ tác vụ quá thời gian cấu hình. `readAllBytes` cũng không giới hạn dung lượng diagnostic.
4. Ảnh hưởng: token/PIN dialog hoặc tác vụ cryptcp/PowerShell có thể còn sống sau thao tác hủy; reader common-pool bị giữ; queue theo chứng thư được mở khóa dù tiến trình cũ chưa chắc đã dừng. Đây là lỗi quản lý vòng đời, không phải bằng chứng chữ ký đang sai.
5. Chứng cứ test hiện tại: `CryptoProCommandRunnerTest.java` chỉ có ba test tìm executable; `CryptoProSignatureTest.rejectsInvalidOutputAndMapsTimeout` dùng runner fake ném TIMEOUT nên không chứng minh process cleanup.
6. Sửa đề nghị: giữ nguyên command, provider, format và certificate selector; áp dụng một deadline cho process và thu kết quả; đóng streams/cleanup reader; dừng process tree thuộc lần gọi trên timeout/interruption, có chờ hữu hạn; giữ interrupt flag và mã CANCELLED; không retry ký sau lỗi không xác định; giới hạn dung lượng output với lỗi rõ ràng nếu cần.
7. Test: helper process Java cục bộ vô hại, không CryptoPro/USB/API. Các ca: stdout+stderr thành công; timeout dừng helper; interrupt dừng helper và giữ interrupt; tiến trình cha thoát nhưng tiến trình con giữ pipe vẫn bị giới hạn; child bị thu dọn; output lớn không giữ vô hạn; command không có PIN và context ký không đổi. Kiểm tra cleanup không làm treo runner/test suite.
8. Rủi ro: runner còn dùng discovery certmgr, probe CAdESCOM và XML GS1; phải chạy regression các nhóm này. Process-tree behavior thật trên Windows phải đánh dấu riêng, không nhận test Linux là kiểm thử token Windows.

### SIG-02 — Probe CAdESCOM làm mất mã hủy/timeout

1. File/function: `WindowsCadesSignatureProvider.requireAvailable`, dòng `305–325`; `unavailable(CryptoProException)`, dòng `452–455`.
2. Hành vi: runner ném TIMEOUT hoặc CANCELLED nhưng probe gói thành CADESCOM_MISSING. Cause còn giữ, mã ở lớp ngoài gây thông báo thiếu component trong khi seller hủy hoặc thiết bị phản hồi lâu.
3. Mong muốn: chỉ phân loại thiếu binary/COM thật thành unavailable; giữ nguyên TIMEOUT/CANCELLED và cause. Không fallback host mới sau khi ký có thể đã chạy.
4. Test đề nghị: inject runner cho probe (narrow overload package-private nếu cần); TIMEOUT/CANCELLED giữ code và cause; component thiếu vẫn map CADESCOM_MISSING; không có lần probe/host thêm sau hủy.
5. Phạm vi/rủi ro: chẩn đoán và availability check; không đổi API, schema hoặc signer. Giữ các test `windowsCadesRetriesOnlyFailuresBeforeSigningIn32BitPowerShell` và `doesNotRetryUnclassifiedCryptcpSigningFailureThroughCades`.

Không phát hiện P0 đủ bằng chứng trong phần signer được giao. Không báo cáo các cơ chế đã có như tính năng mới. `CryptoProCertificateInfo.usable` cố ý cho phép chứng thư chưa có private-key metadata để ký thử xác minh; test hiện có khẳng định điều này, còn guard ghi thật kiểm tra chặt hơn. Không sửa rộng bộ lọc này theo tham khảo.



### CIS-02 — Bằng chứng tương thích `errorCode=0` và giới hạn xác minh

Tham chiếu thứ cấp đã ghim `sklad-cz` commit `c6a72c642ade6066dcc9b42dc480a1f59ecb9760`: [docs/15-api-chestny-znak.md:149–158](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/docs/15-api-chestny-znak.md#L149-L158) và [app/routes/units.py:543–546](https://github.com/akdengi/sklad-cz/blob/c6a72c642ade6066dcc9b42dc480a1f59ecb9760/app/routes/units.py#L543-L546) xử lý mã lỗi 0 là không có lỗi. Đây không phải đặc tả chính thức TrueAPI; schema chính thức cho trường này **chưa xác minh lại** trong lượt nâng cấp.

Nguồn VNCode sau sửa dùng chung `ZnackErrorMessages.hasCisLookupError` cho CIS readback và kho KIZ, xét cả wrapper ngoài và `cisInfo`: chấp nhận chính xác số 0/chuỗi "0", vẫn kiểm tra đúng CIS/GTIN/chủ thể/trạng thái; dữ liệu malformed, nonzero hoặc thông điệp lỗi không được biến thành thành công. Hồi quy liên quan: `KizWarehouseTest`, `ZnackCisReadbackTest`, `ZnackIntroductionIdentityTest`. Không dùng sửa tương thích này để tuyên bố đã nghiệm thu tra KIZ production.


## Những cách của tham khảo không áp dụng

- Không dùng token cache toàn cục không gắn owner/environment cho ứng dụng nhiều cửa hàng.
- Không sao chép PIN plaintext, retry ký/POST mù hoặc báo thành công chỉ từ HTTP200/lượt gửi.
- Không dùng attached/detached cố định cho tất cả API.
- Không lấy 2 giây rồi kết luận tài liệu hoàn thành; giữ monitor/checkpoint VN hiện có.
- Không coi xác thực True API hoặc gửi tài liệu lưu thông là tạo thẻ National Catalog.
- Không dùng trạng thái lưu thông hay cấu trúc KIZ cố định để suy ra kết quả chấp nhận nhãn của WB/Ozon.

## Nhóm kiểm thử hiện có cần chạy khi được điều phối

`CryptoProCommandRunnerTest`, `CryptoProSignatureTest`, `CryptoProSignedCertificateReaderTest`, `WindowsXmlSignatureTest`, `ZnackSigningSessionTest`, `SignatureTestFailureTest`; thêm regression module Znack/GS1 và scope/consent theo phạm vi thay đổi thực tế. Chưa chạy Maven/build trong giai đoạn nghiên cứu này.

Ký thật bằng CryptoPro: **CẦN KIỂM THỬ WINDOWS** với chứng thư được phép; không dùng tài khoản production để đăng ký/cấp GTIN/mua KIZ/ký pháp lý chỉ nhằm kiểm thử.


# Đối chiếu tham chiếu WCode và luồng GTIN Wildberries của VNCode

Quan sát ngày 21.10.2026 UTC. Đây là phần báo cáo khảo sát; chưa sửa mã VNCode, chưa chạy Maven, chưa gọi API seller hoặc thao tác production trong lượt khảo sát này.

## Danh tính và quyền sử dụng nguồn

| Nguồn | Nhánh/commit đã cố định | Bằng chứng | Kết luận |
| --- | --- | --- | --- |
| VNCode đang mở `/workspace/Bancapnhat` | `upgrade/znack-source-audit-20261021`, gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7` | `git rev-parse HEAD`; mã `com.vncode.app` | Nguồn VNCode được kiểm toán; không đồng nhất với repository tham chiếu. |
| `https://github.com/rupphi/test-wcode` | `znack-registration-test`, `8f7c3d1ac8598158029b8d037e2d3c128e240727`, thời điểm commit `2026-09-09T12:38:38+07:00` | Bản clone chỉ nhánh tại `/workspace/references/vncode-source-audit/test-wcode`; `pom.xml:7–10`: Java/Maven, `com.tuandev/FBSBarcode`, phiên bản `1.1.32`; README:3,17–25: JavaFX/SQLite | Đây là WCode/nhánh thử đăng ký, **không phải** VNCode production và không phải Wbcode mới nhất. |
| Giấy phép của tham chiếu | Không có LICENSE/COPYING/NOTICE cấp quyền tái sử dụng trong danh sách tệp được quản lý; README:89–91 ghi `Phần mềm thương mại © TuanDev.` | Đã tìm toàn bộ danh sách tệp và đọc README; các class tên `license` là chức năng cấp phép của app, không phải giấy phép mã nguồn | **KHÔNG CẦN ÁP DỤNG** việc sao chép; chỉ phân tích hành vi và triển khai độc lập theo hợp đồng API. |

Không mở database, file credential, token hay KIZ trong tham chiếu. Không chạy mã, build, ứng dụng hoặc script của tham chiếu. Không cần mở thêm các repository `sellerpro-*`/`jeditor` vì nhánh và tài liệu WB đã cung cấp đủ bằng chứng trực tiếp cho phạm vi này.

## Tài liệu WB chính thức đã đọc lại

- URL: `https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml`, HTTP 200.
- Phiên bản, URL và hàm băm đã lưu trong [bằng chứng hợp đồng marketplace](validation/2026-10-21-gtin-marketplace-contract.md); không cần tệp cache cục bộ để xác định nguồn.
- SHA-256: `55adcfc425dd6a2867fe445034944950d413c063438708a9e48a4989b267c416`; trùng bản đã dẫn trong `docs/validation/2026-10-21-gtin-marketplace-contract.md`.
- YAML:2692,2744: `POST /content/v2/cards/error/list` có hạn mức 10 yêu cầu/phút, 6 giây/lượt nạp, burst 5.
- YAML:2868,2886–2899,2905–2906,2929,2943: `POST /content/v2/cards/update` ghi đè thẻ/giấy tờ; barcode size chỉ được thêm, không sửa/xóa; tối đa 3.000 nmID và 10 MB/yêu cầu, 10 yêu cầu/phút, 6 giây, burst 5; HTTP 200 chưa chứng minh đồng bộ, có thể tới 30 phút.
- `x-category: content`, `x-readonly-method: false`: phải dùng token WB có quyền Content ghi; quyền license nội bộ và quyền token WB là hai điều kiện riêng.
- Giới hạn “50 thẻ” trong master là mục tiêu cần kiểm chứng, **không phải** giới hạn endpoint update hiện tại. Không hạ từ 3.000 xuống 50 khi không có lý do an toàn/giới hạn byte cụ thể. FBS `meta/gtin` khác barcode thẻ Content; bằng chứng và phân biệt đã có trong tài liệu hợp đồng 21.10.

## Bảng đối chiếu chức năng

Đường dẫn tham chiếu bắt đầu bằng `src/main/java/com/tuandev/fbsbarcode/`; đường dẫn VNCode bắt đầu bằng `src/main/java/com/vncode/app/`.

| Phần | Bằng chứng tham chiếu | VNCode hiện tại và bằng chứng | Phân loại |
| --- | --- | --- | --- |
| Ghi GTIN lên WB | `docs/wb-znack-card-registration-test-spec.md:7,22,36` và `integration/znack/registration/ZnackCardRegistrationWorkflow.java:180–183` chủ động dừng sau Catalog; tìm toàn bộ `src/main` không có `cards/update` | Có đường UI/background→`features/catalogsync/CatalogSyncService`→`features/gtinsync/GtinSyncCoordinator`→`integration/wb/WbGtinAdapter.submitBatch`:108–120,197–224→Content update; không dùng API GTIN đơn FBS | **ĐÃ CÓ**; không báo là thiếu chỉ vì nhánh tham chiếu không triển khai. |
| Khóa đúng shop/nmID/chrtID | Nhánh thử lưu đăng ký theo `(shop_id,chrt_id)`; spec:5–6 cấm tích Descartes màu/size | `WbGtinProductReader.fromCard`:41–56 chọn duy nhất chrtID trong đúng nmID; `ListingIdentity.require`; `WbGtinAdapter.validateIdentity`:38–68 đối chiếu article/màu/hệ size/TN VED; `GtinVariantIdentity` và cache scoped | **ĐÃ CÓ**; giữ nguyên. |
| Mapping Catalog và KIZ | Tham chiếu `features/kizmapping/KizMappingRepository.java:335–367` có mapping theo subject+gender cấp nmID | `features/kizmapping/WbCatalogMappingService`:22–54 đọc nguồn đúng scope, enrich giấy tờ, từng size; :78–100 kiểm tra công bố, đơn vị bán, size/màu/TN VED, giấy tờ và GTIN xung đột; ghi mapping sau proof | **ĐÃ CÓ**, không đưa mapping cấp danh mục của tham chiếu vào để thay proof từng biến thể. |
| Cấp GTIN và checkpoint | Tham chiếu `ZnackCardRegistrationWorkflow`:106–133 khóa local khi cấp rồi lưu GTIN/payload/feed | VNCode có thêm ý định cấp GTIN bền vững, lookup Catalog hoàn chỉnh trước cấp, UNKNOWN khác NOT_FOUND, owner/scope, giữ identity qua restart; `RegistrationExistingCards`, `ZnackCardRegistrationWorkflow`, `ZnackRegistrationBatchStore` | **ĐÃ CÓ**; không quay lại luồng cũ tự gửi lại khi ERROR. |
| Phê duyệt và license | Tham chiếu có license client; nhánh thử trực tiếp ký sau `readyToSign` | `GtinSyncCoordinator.confirm`:30–38 xác nhận kế hoạch và nguồn; `WbGtinAdapter.requireWrites`:196 kiểm tra `GTIN`+`SELLER_API` trước dispatch; `ListingHttpClient`:34–64 owner, credential đúng origin, phản hồi 401/403 có mã từ chối. Ký có review XML/hash riêng | **ĐÃ CÓ**; chưa nghiệm thu token WB quyền ghi production trong khảo sát này. Không thay cơ chế license. |
| Bảo toàn thẻ và size khác | Tham chiếu không triển khai Content update | `WbGtinAdapter.update`, `payload`:167–175,270–291 dựng đúng trường writable, giữ toàn bộ size và barcode cũ, giữ `gtin` cấp thẻ và `kizMarked`, không gửi ảnh/giá cũ/read-only; các test bảo toàn/size phụ hiện có | **ĐÃ CÓ**. |
| Thẻ WB thay đổi khi chờ | Không có luồng ghi WB trong tham chiếu | `ListingHttpClient.call`:40–46 đọc lại sau limiter, ngoài HTTP permit; `WbGtinAdapter.dispatchReadback`:176–188 kiểm tra fingerprint/current proof/physical variant/trùng mã rồi boundary checkpoint trước POST | **ĐÃ CÓ**. Không tuyên bố có CAS của server; vẫn có khoảng mạng không tránh được giữa đọc và ghi. |
| Hàng loạt/10.000 GTIN | Nhánh thử mỗi feed một thẻ, ký từng thẻ; không có batch Content | `WbGtinAdapter.partition`:97–106 nhóm toàn bộ size cùng nmID, <=3.000nmID/10MB; `GtinBulkSession`:32–58 và `GtinBatchTransport` tái sử dụng một index/snapshot giới hạn bộ nhớ; không gọi full-shop cho từng thẻ | **ĐÃ CÓ**; thời gian 10.000 GTIN trên seller thật **CHƯA XÁC MINH**. |
| Đọc lại kết quả từng size | Tham chiếu `Workflow`:163–168 gán PUBLISHED ngay sau ký; thiếu WB write/readback | `WbGtinAdapter.verifyBatch`:122–136 và `verify`:227–235 cần đúng key/article/màu/size và GTIN mới + toàn bộ barcode cũ. Lỗi đọc→UNKNOWN; lỗi thẻ chỉ được gắn nếu đúng article và thời điểm dispatch. Không lấy HTTP200 làm thành công | **ĐÃ CÓ**, tốt hơn tham chiếu; không nhập lại hành vi thành công từ receipt. |
| Khôi phục/không ghi lặp | Tham chiếu lưu GTIN/feed/good nhưng `Workflow`:129 gửi lại khi ERROR và status GTIN_GENERATED; chỉ khóa RAM | `GtinSyncSchema`:30–67 có job/item/events và trigger chống hai job cùng thẻ; `GtinSyncRepository.recoverInterrupted`:291–298 biến SENDING→RECONCILE_REQUIRED; coordinator:59,89–94,132–175 đối soát kết quả chưa rõ, không phát lại write; retry only known-unsent/429 | **ĐÃ CÓ**; giữ đường an toàn hiện tại. |
| Hạn mức đọc lỗi Content | Tham chiếu không có GTIN Content error-list pipeline | `WbContentApiRateLimiter.bucket`:18–20 chỉ tách update và read100/min; trong khi `WbGtinAdapter.recentRejections`:147–170 gọi error/list nhiều trang | **CẦN SỬA P1**, xem WB-01. |
| Nguồn full WB phải không công nhận trang mâu thuẫn | Tham chiếu `WbProductSyncService.sync`:42–62 chấp nhận null/short page yếu hơn | VNCode đã thêm guard và checkpoint nhưng :59–60 vẫn nhận `cards=20,cursor.total=80`, :86 kết thúc thành công vì total<100; `RegistrationSources.readMarketplace`:100–111 dùng đúng đường này | **CẦN SỬA P1**, xem WB-02. |

## Vấn đề có bằng chứng và kế hoạch sửa nhỏ

### WB-01 — Bucket của danh sách lỗi WB sai hạn mức

- Repository/commit: VNCode `b467225`; `integration/wb/WbContentApiRateLimiter.java:18–20`, function `bucket`; caller `WbGtinAdapter.recentRejections`:152.
- Hiện tại: mọi path Content ngoài `cards/update` chạy chung tốc độ 600 ms/lượt nạp, 100 yêu cầu/phút. Lỗi thẻ có thể bị quét tới 20.000 trang trong verifyBatch. API error-list chỉ cho 6 giây/lượt nạp, 10 yêu cầu/phút, dẫn tới 429 và chậm/không đọc rõ lý do từng thẻ.
- Mong muốn: bucket riêng đúng quota error-list; `Retry-After` cũng đi đúng bucket. Read thường và update không bị cooldown chéo do error-list.
- Bằng chứng: mã phân nhánh hiện tại và YAML chính thức:2692,2744; chưa chạy tái hiện trong lượt khảo sát.
- Sửa độc lập: thêm loại bucket ERROR_LIST/tốc độ 6s, giữ update và read hiện có; không đổi kiến trúc, không tăng quota.
- Kiểm thử cần chạy: kiểm thử đồng hồ giả cho routing path; 5 burst rồi lượt6 phải chờ6s; cooldown error-list không chặn read/update và ngược lại; alias `/content/...` cùng policy; regression adapter lỗi partial/429.
- Rủi ro: error-list sẽ gọi chậm đúng hợp đồng; không đổi dữ liệu seller, FBS hay license. Không sửa quota dựa vào hàm gate đơn lẻ mà quên kiểm thử routing.
- Trạng thái khảo sát: **CẦN SỬA / CHƯA KIỂM THỬ MỚI**.

### WB-02 — Full-source WB có thể báo hoàn thành với trang mâu thuẫn

- Repository/commit: VNCode `b467225`; `integration/wb/WbProductSyncService.java:59–60,84–89`, function `sync`; caller `features/sync/RegistrationSources.java:100–111`.
- Hiện tại: `cards.size()=20` và `cursor.total=80` qua guard `total>=size && total<=100`, lưu20 rồi đánh dấu complete bởi total<100. Snapshot này có thể tạo readiness sai cho đăng ký/mapping.
- Mong muốn: số trang được công bố phải khớp dòng thực trước khi save/báo complete. Không suy ra “đã đọc hết cửa hàng” từ response tự mâu thuẫn. Trang lỗi phải fail/unknown và bảo toàn dữ liệu đã đồng bộ trước.
- Bằng chứng: điều kiện hiện tại có thể chứng minh trực tiếp bằng trường hợp20/80; strict GtinProductReader và GtinBatchTransport đã dùng điều kiện bằng nhau. Chưa khẳng định WB thật đã trả20/80 hoặc đây là nguyên nhân sự cố seller20 sản phẩm.
- Sửa độc lập: guard equality trước `saveProductBatch`; tái sử dụng kiểm tra trang nếu cần đường recover. Không reset database/cursor hay xóa cache.
- Kiểm thử:20/80 phải thất bại trước ghi/trả complete;20/20 hợp lệ;100/100 + trang sau giữ pagination; trang sau429 giữ checkpoint và không báo readiness; chỉ rõ hành vi null/empty/missingcursor. Dùng fixture API/SQLite có dữ liệu cũ, không gọi seller.
- Rủi ro: liên quan nguồn sản phẩm WB và đăng ký; cần regression FBS không bị ảnh hưởng và lỗi đọc vẫn giữ dữ liệu cũ.
- Trạng thái khảo sát: **CẦN SỬA / CHƯA KIỂM THỬ MỚI**.

## Không kết luận quá bằng chứng

- Không phát hiện P0 mới đã được chứng minh trong phạm vi WB này; điều đó không đồng nghĩa ứng dụng không còn lỗi.
- Limiter hiện khóa theo hash API key trong khi tài liệu nói theo tài khoản seller. Hai token cùng seller có thể không chia sẻ quota; chưa có thông tin định danh tài khoản đã xác minh để đổi khóa an toàn. Ghi nhận **CHƯA XÁC MINH**, không đọc token seller hay dùng claim JWT như bằng chứng cấp quyền.
- Không lấy mapping subject/gender hoặc trạng thái PUBLISHED cục bộ của tham chiếu làm bằng chứng sử dụng GTIN. Không nhập lại luồng tự ký/công bố từ ACK.
- Có sẵn kiểm thử `WbGtinAdapterTest`, `WbGtinProductReaderTest`, `WbCatalogMappingServiceTest`, `GtinSyncCoordinatorTest`, `GtinBatchTransportTest`, `GtinBulkPipelineTest`, `WbContentApiRateLimiterTest`. Chỉ đọc source của chúng; **chưa chạy lại** ở nhánh kiểm toán này.
- Không xác minh CryptoPro thật, quyền token WB Content ghi, ghi GTIN production, tốc độ10.000GTIN thật hoặc bộ cài Windows. Không phát hành/đổi Latest trong khảo sát.
