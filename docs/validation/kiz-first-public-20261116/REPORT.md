# Mua KIZ WB/Ozon — đối chiếu bản công bố đầu tiên và sửa nguồn hiện hành

16.11.2026, Europe/Moscow. Đã triển khai và kiểm nguồn cuối:1.576/146suite đạt; JAR và actual Launcher đạt trên Linux/profile tổng hợp. Chưa phát hành, chưa nghiệm thu giao dịch thật.

## Đối chiếu đúng nguồn

| Mốc | Bằng chứng |
| --- | --- |
| Bản công khai đầu tiên hiện còn truy cập được | [Vncode v1.1.33](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.33), release406111854, published07.10.2026 20:05:18UTC/23:05:18MSK, đánh dấu prerelease |
| Source của đúng bản đó | `3b5c725ec8b481556cb6635859b08dcdb66ab053`; tag SHA và asset build-info khớp; mirror đầy đủ, không shallow |
| Source mới được sửa | Nhánh `feature/ozon-znack-order-kiz-20261116`, HEAD nền `a7c4d008ae450a82b6cfbfbb79e5532eca6bfa1e` cộng toàn bộ sửa ECC/UI/Ozon trước và working-tree diff mới |
| Latest được đọc lại trong nhiệm vụ | Bancapnhat v1.6.10/release445977967 immutable, công bố15.11.2026, 16asset; chỉ đọc, không thay đổi |

Mốc root README và import mã app1.1.32 khác bản release đầu. Source1.1.33 vẫn mang package WCode nhưng được công bố là VN code; đã có Ozon. Không thực thi bộ cài, script hoặc code upstream. [Provenance](original/release-provenance.json), [build-info](original/first-release-build-info.json), [Latest](original/latest-public-release.json), [bảng đối chiếu/sơ đồ nghiên cứu](original/REPORT.md), [hash10 lớp](original/purchase-source-comparison.json).

**Không chép lại cả module cũ.** Auth, safety và queue core vẫn giống sau chuẩn hóa package; bản cũ cũng thiếu các kiểm tra cần thiết. Source hiện hành đã sửa đúng buffer GTIN, partial download, phục hồi khối chưa đủ, ambiguous POST, theo dõi lưu thông và Ozon exact acceptance. Các phần đó được giữ.

## Lỗi xác nhận và bản sửa

| Nhóm lỗi và bằng chứng | Sửa thực tế |
| --- | --- |
| Tải thông thường lưu thẳng phản hồi trong khi luồng recovery đã kiểm OMS/order/GTIN; sai GTIN thô, vượt quantity, hai raw payload cùng CIS có thể làm kho sai và kẹt readiness. RED13ca/9assertionfail. | Kiểm cả bộ mã trước khi insert; đối chiếu identity được server cung cấp, GTIN nhúng, CIS duy nhất và lượng còn thiếu. Giữ exact duplicate idempotent, legacy dạng chuỗi và toàn bộ GS1/crypto; chặn primitive/shape sai và HTTP200 chứa lỗi. |
| `/codes` dùng GET nhưng thực sự cấp mã. Mất phản hồi khi buffer cònACTIVE có thể khiến lần poll/khởi động lại gọi cấp tiếp. RED24ca/16assertionfail. | Ghi marker bền vững **sau xác thực, trước request cấp mã**. Kết quả chưa rõ chỉ đọc danh sách khối và tải lại khối đã cấp của đúng đơn; không suy danh sách rỗng là chưa cấp và không tạo đơn mua thay thế. Healthy response đã lưu đủ mới xóa marker. HTTP4xx từ chối rõ được phân biệt với408/5xx/transport. |
| Recovery được dùng nhiều hơn sau sửa WAL cũng có thể lưu trùng CIS khác đuôi mật mã, vượt quantity hoặc mã sai kiểu dữ liệu. RED12/12 ở đường replay trước validator chung. | Dùng cùng kiểm tra toàn gói trước lưu cho normal và replay; không làm thay đổi mã đã giữ/tiêu thụ hoặc legal status cũ. |
| Auto-introduction readiness và xác nhận chứng từ dùng kiểm CIS không owner; observer-only đã có kiểm owner. Đúng CIS/GTIN nhưng sai/thiếu chủ sở hữu vẫn có thể báoINTRODUCED. RED30ca/22assertionfail. | Chỉ hoàn tất khi tất cả mã đúng chủ sở hữu của hồ sơ mua gốc; dùng cùng response, không thêm request lặp. Giữ đườngAPPLIED/giấy tờ và helper legacy. Thêm30ca WB/Ozon/owner thiếu-sai-hỗn hợp/positive fallback. |
| Nhánh polling introduction bỏ mất `result.message()` nên nguyên nhân chưa xác minh owner không hiển thị. RED sau sửa owner còn10assertionfail. | Giữ thông báo chờ trong pipeline, không đổi pending thành thành công hoặc thất bại giả. |
| Session FBS chờ mã vòng lặp dù coordinator đã dừng để chờ ký; thẻ GTIN FBS WB/Ozon thiếu nút tiếp tục đúng lô; dòng mới có thể che dòng cũ đang chờ ký. RED pause/message14ca/12fail, UI10ca/8fail, summary15ca/10fail. | Thoát vòng chờ với lý do và pipelineId cần tiếp tục, nhả trạng thái bận. Hiện **Tiếp tục lô mua**, ẩn mua mới ở lô đang chờ ký; dùng đúng `resumeSigning(existingId)` và hồ sơ đã lưu. Summary thường/mã mua/phân trang ưu tiên đúng guard ký hiện có qua query batch, khôngN+1; trường hợp khác giữ trạng thái mới nhất. |

Các RED là kiểm thử có ý nghĩa trên nguồn baseline hoặc checkpoint trước đúng bản sửa; không cộng chúng thành số giao dịch/lỗi khách hàng. Một số lần đầu có fixture null/empty hoặc lỗi môi trường, được ghi riêng trong báo cáo owner và không dùng làm RED nghiệp vụ. [Chỉ mục RED](red-evidence-index.json).

## Tệp thay đổi và ranh giới giữ nguyên

**14 tệp production sửa, không thêm production mới:**

- `integration/znack/ZnackKizCodeService.java`: kiểm gói mã, WAL và recovery an toàn.
- `ZnackIntroductionReadinessService.java`, `ZnackIntroductionService.java`: xác nhận đúng owner trước hoàn tất lưu thông.
- `ZnackPurchaseCoordinator.java`: giữ nguyên thông báo pending; đúng một dòng.
- `features/kiz/KizPrintPurchaseSession.java`: dừng chờ khi cần xác nhận ký; giữ cơ chế đếm tồn Ozon tùy chọn từ nhiệm vụ trước.
- `features/kizmapping/KizMappingRepository.java`: giữ lô cần tiếp tục không bị dòng mới che.
- `ui/ozon/OzonDashboardController.java`, `ui/supply/SupplyDetailController.java`: nút tiếp tục đúng lô; không tạo quyền ký hoặc cơ chế mua khác.
- `integration/znack/ZnackErrorMessages.java`, `shared/FriendlyErrorService.java` và bốn locale: giải thích đang đối soát mã đã cấp, không báo hoàn tất giả hoặc hiện mỗi mã lỗi kỹ thuật.

Bảy lớp test mới; ba test cũ chỉ bổ sung owner hợp lệ cho dữ liệu positive để đáp ứng điều kiện mới. Không giảm/bỏ assertion hoặc mất các response negative cũ. [Điều chỉnh fixture](circulation-fixtures.json), [manifest24 tệp](source-freeze.json), [trước/sau từng tệp](source-delta.json). Không đổi API endpoints/payload tạo đơn, schema/migration, dependency/POM, CI, style/design, license, portal hoặc version.

Giữ mọi sửa ECC/UI và Ozon trước nhiệm vụ, gồm CTA đăng ký, GTIN mapping, kho nhập seller theo danh mục/không ép cùng GTIN, tải nhãn theo lô và exact paid acceptance. **Mã mua cho Ozon vẫn chỉ được dùng như đã gán khi Ozon xác nhận đúng posting/sản phẩm/exemplar/mã.** Những sửa SUZ/circulation trên áp dụng chung WB và Ozon; không bỏ điều kiện Ozon hoặc tạo/cắt đuôi mật mã.

Luồng tải mã production chỉ được gọi từ coordinator. Guard RUNNING theo shop/pipeline và queue mutation GTIN đã có bảo vệ các producer thực; không thêm CAS/khóa mới chỉ vì test gọi thẳng public service song song.

Review độc lập đã đối chiếu đủ 24 hash nguồn/test, bảo toàn baseline và selector146lớp, chưa phát hiện blocker bổ sung trong phạm vi này. Đây là review chỉ đọc, không thay kết quả thực thi. [Báo cáo](independent-review.md), [biên nhận](independent-review.json).

## Kiểm thử và build

Đã dùng Code Doctor và ba workflow ECC qua đọc SKILL.md theo AGENTS; không cài dependency, CLI, hook hoặc scanner mới. Mỗi owner dùng source copy/profile tổng hợp độc lập, JDK25/Maven/Xvfb cache và offline.

| Phạm vi focused đã chạy | Kết quả |
| --- | --- |
| Owner/circulation và legacy readback/signing | 295/295, 8suite, exit0 |
| FBS resume UI và downstream hiện có | 98/98, 11suite, exit0 |
| Summary hồi phục ký/không che lô | 97/97, 7suite, exit0 |
| Normal download/readback/EXHAUSTED | 51/51, exit0 |
| Pause/session/4locale | 35/35, exit0 |
| WAL unknown/restart/replay, reviewer test độc lập | 36/36, exit0 |

Các nhóm trên có chồng lặp, **không cộng tổng**. [Circulation](circulation-result.json), [UI](fbs-resume-result.json), [summary](summary-result.json), [download](download-result.json), [pause](pause-result.json), [WAL](unknown-download-result.json). Chi tiết: [shared](shared-implementation.md), [WAL](unknown-download-implementation.md), [circulation](circulation-implementation.md), [UI](fbs-resume-implementation.md), [summary](summary-implementation.md).

**Fresh clean verify đã đạt1.576/1.576ca,146suite,0failure/error/skip,exit0.** Source copy mới, selector146lớp gồm đủ10tệp test mới/sửa; 1.441 buildinput và24tệp freeze. [Kết quả từng suite/hashJUnit](combined-result.json), [lệnh chính xác](verification-command.json). Bảy lớp mới có129ca đã nằm trong1.576, không cộng lại. Kết quả1.954 của nhiệm vụ trước là baseline lịch sử, không thay kết quả mới. JAR và actual Launcher từ chính JAR mới đã đạt như mô tả bên dưới.

## JAR và app thực trong cloud

JAR hiện hành7.810.919byte, SHA-256`6a60df306c99179a87230d6878d2d551da6746ea4af33e79159fd6156c76178f`. Đã so đúng2.081 productionentry/2.040class Java25 với đầu ra compile; kiểm CRC/tên entry an toàn/metadataPOM/manifest Maven/khôngtestfixture. Đủ74runtime JAR, hash không đổi so baseline; source/copy1.441buildinput và24tệp freeze giữ nguyên. [Biên nhận JAR](jar-integrity.json).

Đã thực sự gọi `com.vncode.app.Launcher.main` từ chính JAR mới đứng đầu classpath; workspace mở sau **6,035giây**, đóng bình thường và nhả data lock. SQLite integrityOK/schema6/FK0/0shop. Profile/license tổng hợp, outbound bị chặn; ảnh workspace rỗng đã được xem. Đây là kiểm app chạy thật trong Linux cloud, không phải mua KIZ thật hoặc máy Windows seller. [Biên nhận Launcher](launcher-result.json).

Reviewer artifact độc lập cũng xác nhận CRC/tênentry, bytecompile/metadata/Java25/74runtime và khôngfixturetest: [biên nhận artifact](independent-artifact-review.json). [Biên nhận cuối](FINAL-RECEIPT.json) chốt trạng thái từng gate và phần chưa thực hiện.

## Hợp đồng SUZ và phần chưa kiểm thật

[PDF SUZ chính thức](https://docs.crpt.ru/gismt/files/pdf/API_%D0%A1%D0%A3%D0%97_3.0.pdf) đã được tải lại mới, HTTP200/SHA`0a019c4208f8a781aa431a6a3dad60c537e33f268529e24a0678721022fe1313`, giống byte bản đã kiểm trước. [Biên nhận hợp đồng](original/suz-issuance-contract.json).

`GET /api/v3/codes` cấp mã, không phải thao tác chỉ đọc. `blocks/retry` chỉ lấy khối từng cấp và có điều kiện API-first/notclosed/unblocked. Không có tham số `keepOpen/autoClose=false` trong hợp đồng đã đọc; không tự bịa tham số hoặc suy danh sách khối rỗng là bằng chứng chắc chắn chưa cấp. Nếu phía SUZ không cho lấy lại khối, app phải giữ đơn/mã và báo chờ/đối soát, không mua trùng hoặc báo đủ giả. Không thể cam kết sửa được mọi trạng thái phía dịch vụ chỉ bằng mã app.

Chưa thực hiện mua KIZ tính phí, ký CryptoPro, ghi Catalog/WB/Ozon, nhận đơn thật, activation/license hoặc Gmail trong nhiệm vụ này. Chưa kiểm source mới trên Windows/CryptoPro/DPAPI hoặc bộ cài. Không chạy full ứng dụng10.000GTIN/CodeQL mới; nợ security lịch sử và giới hạn GS1 extraction không được miễn. Không gọi fixture API/PDF là nghiệm thu thương mại hoặc đo tốc độ mạng thật.

Chưa commit/push, tăng version, tạo bộ cài Windows hay phát hành Latest. [Sao lưu baseline3.767tệp](baseline-backup.json) đã chốt trước sửa, SHA nguồn`4ba047c9abd86286d07342e973e8b4dacd98dce52906e22489e1526ed821f703`; backup trước feature Ozon và mọi gói đã giao giữ nguyên.
