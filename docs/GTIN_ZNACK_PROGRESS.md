# Tiến độ hoàn thiện GTIN / Znack

Yêu cầu hiện hành: PROMPT MASTER do chủ dự án gửi ngày 21.10.2026. Thực hiện lần lượt C → D → E → F → G, tái sử dụng ứng dụng Java 25 / JavaFX / Maven / SQLite hiện có. Không chạy lô ghi production, cấp mã, ký hoặc mua KIZ để kiểm thử. Không tự phát hành Latest.

## Điểm bắt đầu và bảo toàn

- HEAD bắt đầu: `5621a4f`; phiên bản nguồn `1.5.6`. Latest hiện là **1.5.5**. Bản 1.5.6 đã công bố trước lệnh tạm dừng, còn công khai nhưng không còn Latest; workflow hotfix bị tắt. Không ghi đè tag/bộ cài đã công bố.
- Working tree ban đầu chỉ có `tools/__pycache__/` chưa theo dõi; giữ nguyên. Không tìm thấy AGENTS.md trong workspace.
- Giữ dữ liệu seller, barcode/GTIN, liên kết KIZ, lịch sử, schema 6 và luồng FBS/in hiện có. Chỉ xem xét migration nếu có thiếu sót được chứng minh.
- Giữ tạm dừng đóng gói trong các giai đoạn C–F. Yêu cầu mới cho phép đánh giá/đóng gói Windows ở G sau kiểm tra; không tự công bố phiên bản hoặc thay Latest.

## C — Từ điển quốc gia: đã hoàn tất kiểm thử liên quan

Đã chứng minh bằng API thật, chỉ đọc: `/v3/dictionary/isocountry` trả HTTP 200, 254 mục, cấu trúc `result._list`; Nga là `country_iso=RU`, `country_name=Россия`. Schema thuộc tính 2630 trỏ đúng từ điển này. Lỗi 1.5.5 do đọc `result` như mảng. Sửa tối thiểu đã có trong commit `541e982`; bằng chứng: [phản hồi đã che dữ liệu](validation/2026-10-21-catalog-country-envelope.json).

Các phát hiện/sửa đã qua kiểm thử liên quan:

- Hợp đồng đã đối chiếu API 5.68: chỉ `apiversion=3`, `result._etag/_list`; không có phân trang được công bố. Phản hồi thật `no-store/no-cache`; thay cache TTL hai phút bằng snapshot cho đúng thao tác đang chạy. [Hợp đồng](validation/2026-10-21-catalog-country-contract.md).
- Đã thêm ngữ cảnh đọc quốc gia và bảo toàn cause HTTP 401/403/429, timeout/DNS/TLS, HTML/JSON/rỗng; URL/header/body được che. Thông báo riêng ở bốn ngôn ngữ, không kết luận GTIN sai/không tìm thấy.
- Đã thêm lane Catalog có deadline, hủy đọc khi đóng/chuyển cửa hàng, nút Hủy trên viên thuốc cho lần kiểm tra ban đầu, giải phóng Apply khi timeout và giới hạn retry đọc thủ công ba lần. Không hủy/giả hoàn tác request ghi đã gửi.
- Đã tách nước sản xuất khỏi xuất xứ/pháp nhân/trường Country chung và ID thuộc tính trùng số giữa sàn/Catalog. Cấu hình Россия đã được chủ dự án chỉ định vẫn phải ánh xạ qua từ điển đang xác minh.
- Đã viết hồi quy đầu cuối HTTP kiểm tra không cấp GTIN/gửi thẻ và không mất dữ liệu cũ khi đọc quốc gia lỗi; đã qua kiểm thử kết hợp và các lượt chạy lại lớp ảnh hưởng.

Kiểm tra đã có trước yêu cầu mới: Linux 129 kiểm thử tập trung đạt; Windows 86 kiểm thử tập trung đạt. Đây là bằng chứng của bản sửa tối thiểu, chưa phải kết quả của các thay đổi đang làm hoặc toàn bộ yêu cầu mới.

## D — Sản phẩm, định danh và đề xuất sửa: đã hoàn tất kiểm thử liên quan

Đã sửa trên model/repository/đường đọc sẵn có, giữ schema 6:

- Hai reader WB/Ozon hiện nhét barcode đầu tiên vào `ProductVariant.gtin` khi chưa có proof. Đang thêm biểu diễn riêng, provenance/thời điểm/bằng chứng và tương thích JSON snapshot cũ; giữ số 0 đầu.
- Mapping WB hiện tìm trùng theo size nên bỏ lọt khác màu cùng size; kiểm tra thêm đúng màu, TN VED, giấy tờ, revision và phạm vi proof. Không cấm GTIN chung của cùng biến thể thực sự trên hai sàn.
- `defaultValue` đề xuất giá trị phổ biến dù lô mâu thuẫn; đang chuyển sang đề xuất duy nhất có nguồn và ràng buộc revision để không áp tự động dữ liệu cũ.
- Giao diện đăng ký đang tách barcode và GTIN Catalog đã xác minh; giá trị chỉ mới xin mã/đã lưu cục bộ không phải bằng chứng Catalog.

Đã chạy bộ 31 lớp D bằng Maven offline/Java25/JavaFX/Xvfb; log `/tmp/vncode-master-D-tests.log` và lượt chạy lại ở dưới. Đã thêm biểu diễn barcode/GTIN riêng có nguồn/hash/thời điểm, giữ JSON cũ không ghi lại vào DB; proof cũ chỉ còn lịch sử. Đã sửa mapping biến thể, giấy tờ/cache, đề xuất/revision và journal phê duyệt nguyên tử. Journal mới là bảng thêm có điều kiện qua ensureTables hiện có, không đổi schema version hay rewrite bảng cũ.

Điểm D chuyển sang E đã xử lý trong mã: parser giữ `good_id` thật và trạng thái gốc, không bịa ID cho dữ liệu cũ thiếu bằng chứng.

## E — Đăng ký, đối soát, ký và công bố: đã hoàn tất kiểm thử liên quan

Đang sửa các khoảng trống có bằng chứng:

- Shared submit chỉ kiểm tra country bằng schema mới, còn trường bắt buộc khác có thể lấy model cũ; đang thêm validation payload đầy đủ trước send marker, không tự đổi payload.
- WB/Ozon có đường đăng ký xin GTIN mà chưa tra lại toàn bộ thẻ có sẵn; đang dùng context đọc đầy đủ theo lô/phạm vi + xác minh ứng viên bằng API exact trước khi liên kết. UNKNOWN hoặc đọc thiếu trang phải chặn xin mã mới.
- Draft đã lưu của batch WB có thể tiếp tục sau restart mà không đọc lại facts/schema. Đang so lại nội dung phê duyệt, đổi thì NEEDS_ACTION thay vì lặng lẽ gửi.
- Thay nhóm TN VED trên thẻ đã có không được tự xin mã mới qua Đăng ký thường; giữ thẻ cũ, dùng thao tác xin GTIN mới có xác nhận/phạm vi riêng.
- Hàng đợi hiện thiếu pause/cancel/resume và retry vô hạn; đang thêm trạng thái bền vững, giới hạn 5 lần lỗi, tạm dừng ở ranh giới thẻ. Request đã gửi vẫn giữ receipt để đối soát.
- Consent ký chỉ gắn owner+GTIN; đang ràng buộc payload/chứng thư/môi trường và hash tài liệu đã duyệt, không dùng consent cũ để tự ký nội dung mới.
- Giữ kết quả đọc tốt khi lần đọc mới lỗi trong cùng tài khoản; khóa thao tác ghi khi readiness chưa xác minh, không xóa trạng thái thật cũ. Đổi tài khoản/phạm vi phải xóa phần trình bày cũ.
- `good_id` thật và trạng thái gốc được giữ trong metadata; thời điểm read là receipt HTTP thực, kể cả response lô 25. Parser/source đã freeze, 24 ca chờ kiểm thử.

Không dùng mock làm bằng chứng tạo/ký production. Chưa chạy kiểm thử E khi các phần điều phối/binding còn đang sửa.

## F — Đồng bộ WB/Ozon và báo cáo: đã hoàn tất kiểm thử liên quan

Đúng biến thể và trường API, giữ barcode/thuộc tính ngoài phạm vi, đọc lại xác nhận, báo cáo có thời điểm/lỗi/hành động, bộ đếm cùng phạm vi và chọn tất cả theo bộ lọc.

## G — Kiểm thử và bàn giao: mã/test độc lập đã xong; nghiệm thu bên ngoài còn thiếu

Chạy các kiểm tra hành vi liên quan và hồi quy FBS. Chỉ sau các giai đoạn trước mới đánh giá build Windows phù hợp. Phân biệt rõ mock, API thật chỉ đọc/ghi, Windows, CryptoPro/chứng thư thật, license production và máy in. Ghi phần chưa kiểm chứng; không tuyên bố hoàn tất toàn bộ nếu còn giới hạn.

## Nhật ký kiểm chứng mới

C: đang chạy bộ 25 lớp liên quan bằng `DISPLAY=:99 /workspace/toolchains/mvn-vncode -o -Dtest=<25 lớp C> test`. Lượt đầu phát hiện thiếu import `FriendlyErrorService` ở controller WB; đã sửa import và chạy lại. Lượt test kết hợp đầu: 341 ca / 25 lớp, 1 thất bại và 3 lỗi. Đã xác định/sửa phạm vi quyền đọc lại thẻ WB đã gửi và DB fixture của chẩn đoán; đang xử lý hai kiểm tra giao diện mới. Các ca CountryModel 21, Ozon workflow 48, HTTP đầu cuối 20, DynamicPreflight 10 đạt. Chưa chấp nhận C cho đến khi các lớp ảnh hưởng đạt. Không bỏ kiểm tra hoặc thay đổi dữ liệu seller. Log cục bộ: `/tmp/vncode-master-C-tests.log`.


### Kết quả cuối C

341 ca trong 25 lớp đạt theo kết quả cuối từng lớp; không lỗi/không bỏ qua. Lượt đầu và chạy lại đã phát hiện/sửa import thiếu, phạm vi quyền đọc lại thẻ WB đã gửi, DB fixture và fixture hàng đợi giao diện. Không vô hiệu hóa kiểm tra. Lệnh: bộ 25 lớp nêu ở nhật ký, rồi `-Dtest=CatalogReadFailureTest,ZnackCardRegistrationWorkflowTest,ZnackCardRegistrationControllerTest,RegistrationPreflightCancellationTest test`, cuối `-Dtest=RegistrationPreflightCancellationTest test`; dùng Maven offline/Java25/JavaFX/Xvfb. [Chi tiết](validation/2026-10-21-gtin-znack-C-tests.json). API production chỉ đọc dictionary/schema; chưa có nghiệm thu ghi, CryptoPro hay Windows của thay đổi mới.


### Kết quả cuối D

327 ca / 31 lớp đạt theo kết quả cuối từng lớp; không lỗi/bỏ qua. Lượt đầu phát hiện bốn fixture thiếu màu hoặc vô tình thay snapshot Catalog thay vì giữ hai ứng viên, và một fixture UI chưa applyCss/layout; đã sửa dữ liệu mô phỏng, giữ các guard, chạy lại 51 ca / 3 lớp đạt. [Chi tiết](validation/2026-10-21-gtin-znack-D-tests.json). Lệnh bộ 31 lớp (log ở trên), chạy lại `-Dtest=RegistrationCardIndexTest,ZnackCardRegistrationControllerTest,ProductBulkDialogTest test`. Chưa ghi production. Quyền sử dụng GTIN của nhà cung cấp chỉ được công nhận khi có bằng chứng; chưa bổ sung API GS1 hay quyền nhà cung cấp không có hợp đồng/credential.


### Đối chiếu hợp đồng E

Tài liệu National Catalog 5.68 §3.1.4, [nguồn chính thức](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/), xác nhận `/v4/product-list` trả danh sách thẻ của chủ API key; tối đa 1.000 thẻ/trang và `offset + limit <= 10000`. Đã sửa nguồn đọc và client guard; phản hồi thiếu tổng/trang hoặc vượt cửa sổ trở thành UNKNOWN, giữ cache tốt, không cấp mã mới. Chưa tự chia khoảng ngày vì tài liệu chưa xác nhận ranh giới/tie-break đủ để chứng minh không sót. Không áp giới hạn này tùy tiện sang endpoint True API khác; lượt đăng ký vẫn yêu cầu tổng đầy đủ ở mọi chế độ. Cursor UI cũ được kiểm tra/reset tương thích, dữ liệu tốt không xóa.

API key chứng minh phạm vi danh sách của chủ key; chưa phải bằng chứng độc lập rằng INN của key khớp chứng thư đang cấu hình. Chưa nghiệm thu ký CryptoPro/quyền production; không tuyên bố các phần đó đã được xác minh.

Bổ sung nhỏ: nhận đúng tên trường sản xuất Ozon `Страна-изготовитель`, để nước sản xuất rõ ràng như Китай không bị thay bằng cấu hình mặc định Россия. Thêm hồi quy trước lượt E.


### Lượt kiểm thử E đầu tiên

Compile main thành công (644 lớp). Bộ 37 lớp E chạy 491 ca: 39 thất bại, 8 lỗi, không bỏ qua; chưa chấp nhận E. Các schema/country/observation/lookup/consent mới đạt. Đang sửa fixture chưa cung cấp schema đầy đủ, mô phỏng category fallback không nhất quán, dấu phê duyệt legacy và vòng đời dialog JavaFX; đồng thời sửa guard schema đầy đủ trước cấp mã trên Ozon và cửa sổ hủy/khởi chạy lại WB. Không nới kiểm tra hay bỏ test để đạt. Log `/tmp/vncode-master-E-tests.log`; sẽ chạy lại các lớp ảnh hưởng.


### Kết quả cuối E

498 ca / 37 lớp đạt theo kết quả cuối từng lớp, không lỗi/bỏ qua; [kết quả](validation/2026-10-21-gtin-znack-E-tests.json). Lượt đầu và chạy lại được giữ trong bằng chứng, không che 39 thất bại/8 lỗi ban đầu. Đã sửa guard schema đầy đủ trước cấp mã Ozon, resume cấu hình thương hiệu, kiểm tra không quota khi sửa thẻ cũ, scope và hash ký, trạng thái hàng đợi/đọc lại, fixture schema/HTTP đầy đủ và vòng đời dialog. Một biến thể mâu thuẫn không còn làm hỏng snapshot đọc đủ của cả lô; lỗi scope/auth/hạ tầng vẫn chặn. Manual ký khi một thẻ trả404 dừng để xem lỗi trước khi duyệt, không ký mù; tự động đã tách các thẻ chưa đủ điều kiện. Chưa nghiệm thu production/CryptoPro thật.

### Phát hiện F đang xử lý

- Bộ đếm/báo cáo hiện có lẫn scope toàn shop và lô chọn; đang đưa selection vào trước khi tạo báo cáo.
- Cần đọc lại dữ liệu sau thời gian chờ gửi WB/Ozon, ngoài permit HTTP để không khóa FBS; giữ final local ownership/license guard ngay trước request. API không có CAS được xác minh, không thể tuyên bố khóa nguyên tử các thay đổi từ bên ngoài.
- Hợp đồng Ozon hiện hành: `ALL` bỏ archive, `ARCHIVED` riêng; phải quét cả hai, kiểm tra `total_items` (và `total` legacy trước ngày loại bỏ) và định danh đầy đủ. Danh sách thiếu tổng/trang không được cấp phép ghi. WB theo cursor tăng dần và total từng trang. [Hợp đồng F](validation/2026-10-21-gtin-marketplace-contract.md).
- Đang bổ sung báo cáo có barcode, trạng thái Catalog gốc, thời điểm đọc, lỗi/hành động tiếp; lọc toàn run và xuất dữ liệu đã che bí mật.


### Lượt F đầu — dừng để sửa hồi quy hiệu năng, không tính là đạt

Compile 645 lớp đạt. Bộ 41 lớp F bắt đầu và 13 lớp đầu đạt, nhưng lô Ozon 10.000 vẫn lặp đọc/projection bằng chứng cục bộ qua ProductSnapshotReader ở bước preview/confirm. Hai stack mẫu ở 78,91s và 176,65s ghi CPU gần toàn bộ thời gian (SQLite chuẩn bị truy vấn và đọc thuộc tính), không phải chờ API. Root dừng JVM kiểm thử bằng SIGTERM, Maven exit1; giữ log `/tmp/vncode-master-F-tests.log`, stack `/tmp/vncode-master-F-stack.txt` và `-stack-late.txt`. Không bỏ test, sẽ chạy lại cùng bộ sau sửa: chỉ bỏ projection GTIN hiển thị khỏi bản đọc native tạm thời không persist; vẫn giữ proof Catalog mới riêng, đối chiếu biến thể và đọc lại trước khi ghi.


### Lượt F hoàn chỉnh đầu tiên và sửa cuối

Sau tối ưu projection tạm thời, bộ 42 lớp chạy 380 ca trong 2m43s: 10 thất bại/1 lỗi, không bỏ qua. WB 10.000 đã đọc lại đủ; Ozon lô thứ hai bị scope `targets` của transport chặn nhầm. Đã sửa bằng `prepare` để chọn lại tập đã duyệt trong cache trước khi dựng request, vẫn bắt buộc đọc mới tại ranh giới gửi. Bốn lỗi lô Ozon và hai fixture kích hoạt, ba fixture/giao diện GTIN, một lỗi binding bộ lọc báo cáo, một kỳ vọng số dòng CSV vật lý đang được chạy lại. CSV thực tế có ô xuống dòng hợp lệ; kiểm thử mới đọc theo bản ghi CSV thay vì đếm newline. Không làm yếu guard.

GTIN-only không yêu cầu registry giấy tờ cho mọi mã: đọc thẻ published/đúng chủ thể/biến thể/ID bằng API, giữ chặn nếu cùng nguồn đã có giấy tờ với trạng thái registry UNKNOWN. Chỉ luồng có thay đổi DOCUMENTS mới yêu cầu registry. Như vậy không tự thêm 10.000 POST đọc registry không cần thiết vào tác vụ chỉ thêm barcode. `SourceRefresh` mặc định production vẫn là API thật; fixture phải truyền rõ nguồn giả lập.

Giới hạn chưa nghiệm thu production: đọc thẻ được chọn có deadline3phút và nhóm25GTIN theo API; chưa đo một cohort10.000 qua server thật. Timeout/rate-limit là UNKNOWN và không ghi, không giả là thiếu thẻ. Tốc độ lô giả lập không phải cam kết tốc độ API thật.


### Kết quả cuối F

386 ca / 42 lớp đạt theo kết quả cuối từng test case, không lỗi/bỏ qua; [kết quả](validation/2026-10-21-gtin-znack-F-tests.json). Lượt cuối chỉ chạy lại fixture CSV và ca105 Ozon truyền thiếu nguồn giả lập; không cần chạy lại hai lô10.000 đã đạt. Cả hai lô10.000 đã đọc lại đủ, giữ barcode cũ: WB4 requestghi/214read, Ozon100 requestghi/272read. Đây là API giả lập với planner/adapter/SQLite thật, không phải tốc độ/quota production. Báo cáo CSV đủ10.001 bản ghi, lọc toànrun, giữ xuống dòng trong ô, che bí mật; UIgiữ khe HTTP riêng cho FBS.

G sẽ chạy các lớp còn lại của bộ kiểm thử Maven, gồm đăng ký/CryptoPro mô phỏng/FBS/in/migration/khởi động, và `verify` tạo JAR + runtime libs trên Linux. Đặt nguồn thành1.5.7 để không ghi đè danh tính bản1.5.6 đã công bố. Không tạo EXE hoặc thayLatest.

### Hồi quy G đang chạy

Bộ còn lại341 lớp đang chạy bằng `verify` (không bỏ test);42 lớp F được giữ bằng chứng riêng để không lặp lô10.000. Node `node --test tools/*.test.mjs`:103/103 đạt,0lỗi/bỏqua. G phát hiện7 lỗi fixture:6ca ProductEditBatchIntegrationTest dùng trangOzon không có tổng và trả active trong ARCHIVED;1ca OzonCatalogMappingServiceTest chỉ có cờ published cũ không có ID/trạng thái gốc. Đã sửa dữ liệu giả lập để phản ánh hợp đồng hiện hành, giữ guard và mọi xác nhận hành vi; chờ chạy lại33ca/hai lớp. Không sửa production vì các lỗi fixture này. Log `/tmp/vncode-master-G-verify.log`, `/tmp/vncode-master-G-node.log`.

### Kết quả lượt hồi quy G đầu tiên

341lớp/2245ca:2thất bại,8lỗi,2bỏ qua; Maven `verify` dừng trướcpackage (05:38), chưa cóJAR đạt. Hai ca đã có điều kiện Windows bị bỏ qua đúng trên Linux: `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows` và `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`. Không tắt kiểm tra để đi tiếp.

Ngoài7lỗi fixture nêu trên,2caOzonworkflow và1ca integration phát hiện lỗi thật: repository publish ghi đè cache bằng cờ tradeUnit/published cục bộ và mấtID/trạng thái gốc, làm complete bị guard chặn. Đang sửa publication lấy exactObservedCard thực, trade-unit chỉ từ identified_by thực; giữ proof tốt và ràng buộc goodId khi complete. XML lượt đầu đã lưu `/tmp/vncode-master-G-xml/`; cần chạy lại các lớp bị ảnh hưởng rồi build.

### Kết quả cuối G và bản dựng

Lượt chạy lại22 lớp/403 ca đã sửa hết lỗi production và các fixture cũ; còn1 fixture âm mới vô tình giữ identified_by do payload đã có sẵn. Đã xóa rõ trường trong dữ liệu giả lập để mô phỏng trường thiếu, giữ kiểm tra âm/dương, rồi chạy đủ11 ca ObservationGuard và `verify` đạt. Không bỏ test hoặc thay guard production ở lượt cuối.

Kết quả cuối không trùng qua F +341 lớp còn lại + các lượt chạy lại: **383 lớp,2.641 ca:2.639 đạt,0 thất bại/lỗi,2 ca chỉ dành Windows chưa chạy trên Linux**. Node103/103 đạt. [Bằng chứng đầy đủ và từng lượt](validation/2026-10-21-gtin-znack-G-tests.json).

Đã sửa publication Ozon bằng exactObservedCard thực; lưu good_id/trạng thái/định danh trade-unit/thuộc tính/thời điểm từ API, không tạo proof từ cờ cục bộ. Giữ việc kiểm tra registry giấy tờ chưa rõ qua các biểu diễn GTIN tương đương; trạng thái Catalog PUBLISHED vẫn được giữ đúng. Complete Ozon và kích hoạt WB phải khớp ID thẻ thật và biến thể; WB recovery không bị cờ/ID cache sai ngăn đồng bộ lại.

Đã đọc API production chỉ đọc một thẻ xanh: HTTP200, good_id dương, published, good_signed=true, identified_by khớp GTIN ở cấp trade-unit; không có GTIN ở cấp ngoài nên phải khớp identified_by. [Cấu trúc đã che dữ liệu](validation/2026-10-21-catalog-published-shape.json). Đây là mẫu từ100 dòng, không phải đồng bộ toàn bộ hay tạo thẻ mới.

Build JAR1.5.7 +74 thư viện manifest thành công; ZIP kiểm CRC đủ. JAR SHA-256 `baf6dd64212d1bebed7ff7baa2120ad427024694b1412501f5ebaf0e0fa4d241`. Đã mở main window trên Linux/Xvfb bằng thư mục dữ liệu riêng rỗng; integrity SQLite ok, không lỗi khởi động. [Biên nhận build/giới hạn](validation/2026-10-21-gtin-znack-build.json), [bàn giao H](validation/2026-10-21-gtin-znack-handoff.md).

Chưa nghiệm thu tạo/cấp mã/ký/ghi marketplace production, Windows/CryptoPro thật, license production hoặc máy in thật. Giữ giới hạn Catalog10000/deadline3phút đã nêu; không biến timeout thành không tìm thấy. Không có bộ cài Windows mới, không phát hành và không đổi Latest.
