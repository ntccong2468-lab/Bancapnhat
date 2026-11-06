# Kiểm năm nút sửa sản phẩm và soi lại ứng dụng — 06.11.2026

Đã dùng [Code Doctor](../../.agents/skills/code-doctor/SKILL.md) và [OpenQodex](../../.agents/skills/openqodex/SKILL.md). Nguồn sửa nghiệp vụ giấy tờ: **`af3bf5c7330bfc1b1ebf3693d4f97190e06e8a54`**; nguồn cuối thêm nhãn tuyên bố đã dịch **`fd20aa84304baed67277263e4814f2ba2569612e`**, kế thừa các sửa Ozon, bốn bộ lọc Znack và GTIN WB ở PR #21. Nguồn này **chưa phát hành**; không thay thế bộ cài Latest v1.6.4/tag `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`.

## Kết luận về năm nút và API thật

Đã thực hiện **năm POST thật lên WB**, mỗi trường một lần, qua production `ProductBulkEditService → ProductEditApiSession → WbListingAdapter → ListingHttpClient → content/v2/cards/update`. Sau mỗi lần gửi, service đọc lại WB đến trạng thái **`RECONCILED`** trước khi chuyển sang trường tiếp theo. Không dùng HTTP 200 làm tiêu chí hoàn tất.

Lượt này **gửi lại giá trị hiện có trên một bài WB** có đủ dữ liệu và tuyên bố đang hiệu lực. Kiểm UI dùng JavaFX/Xvfb và dữ liệu tổng hợp; lượt ghi thật dùng cùng service/adapter/HTTP được các nút gọi, không phải tự động bấm năm nút trên máy Windows của seller. Điều này chứng minh đường gửi và đối soát thật hoạt động với dữ liệu đã có, **chưa chứng minh mọi giá trị mới, mọi danh mục hoặc toàn bộ 131 bài trong ảnh** đều được sàn chấp nhận.

| Nút | Mutation thật | Kết quả service sau đọc lại | Số vòng đối soát sau gửi |
| --- | ---: | --- | ---: |
| Sửa thương hiệu | 1 | RECONCILED | 1 |
| Sửa chất liệu | 1 | RECONCILED | 1 |
| Sửa giới tính | 1 | RECONCILED | 1 |
| Sửa TN VED | 1 | RECONCILED | 1 |
| Sửa giấy tờ | 1 | RECONCILED | 4 |

- Bắt đầu **06.11.2026 22:44:09 MSK**, kết thúc **22:44:50 MSK**; 5 mutation, 43 lần gọi đọc. Đây là một phép kiểm tính đúng, không phải benchmark tốc độ xử lý lô.
- Request giấy tờ có đúng **một** bản ghi khớp hồ sơ mặc định; wire WB **`type=2`**, tức **Декларация о соответствии / Tuyên bố sự phù hợp**. Không gửi thành `type=1` Certificate.
- Đọc trước/sau đối chiếu tên, mô tả, thương hiệu, article, danh mục, kích thước đóng gói, ảnh, thuộc tính, danh tính/màu/size/barcode biến thể và danh tính/số/ngày/chủ sở hữu/phạm vi giấy tờ: giá trị nghiệp vụ không đổi. Không đọc API giá; không khẳng định đã kiểm giá độc lập.
- Hồ sơ thử riêng dùng đúng tuyên bố đã gắn trên bài thử làm cấu hình mặc định. Không lấy giấy tờ trong ảnh áp sang doanh nghiệp khác, không sửa cấu hình dữ liệu thật trên máy seller. Test-license fixture nằm ngoài JAR, không có kích hoạt license từ xa.
- **Ozon BLOCKED**: kiểm `/v1/roles` thật trả **HTTP 403/code 7, API key deactivated**. Không có mutation Ozon trong lượt này. Không diễn giải lỗi credential thành lỗi logic nút sửa.

[Biên nhận đã bỏ dữ liệu seller](2026-11-06-products-five-live.json). Request, response, chọn sản phẩm và journal thật giữ ngoài Git. Không gửi lại lượt đã COMPLETE; đọc hướng dẫn trong [thư mục bằng chứng](products-five-20261106/README.md) trước khi chạy harness.

## Lỗi đã xác định và sửa

**Lỗi trùng giấy tờ khi sửa WB đã tái hiện.** Đường `ProductBulkEditService.previewLoaded` so loại bằng chuỗi nguyên dạng, nên giấy tờ WB loại `"2"` và dữ liệu form loại `"declaration"` bị coi là hai giấy tờ khác nhau dù cùng số. Test baseline `configuredDeclarationReusesTheCurrentCardDocumentWithoutDuplicates` thất bại: mong một bản ghi, nhận hai. Đường preview một trường và đường patch trước đây có xử lý không đồng nhất.

Sửa cả hai đường dùng cùng `mergeWbDocuments`: chuẩn hóa alias loại, ghép theo số/loại, giữ ID gắn trên bài vừa đọc và thông tin chủ sở hữu/phạm vi cũ nếu người dùng không thay. ID từ một bài khác không được tự gửi như ID gắn trên bài đích. Đây là biện pháp giữ danh tính đúng; không có bằng chứng vendor cấm mọi ID giấy tờ dùng chung trên cùng doanh nghiệp.

**Mặc định giấy tờ trong giao diện đã đổi theo yêu cầu.**

- `ConfiguredProductDeclaration` đọc cấu hình của **cửa hàng đang chọn**; không lấy phần tử đầu tiên trong cache giấy tờ làm mặc định.
- Hộp “Sửa giấy tờ” bỏ selector giấy tờ cũ và hai radio Declaration/Certificate. Hiển thị cố định **Tuyên bố sự phù hợp**, điền số/ngày/expiry từ cấu hình, có nút mở cấu hình sẵn có.
- Nếu cấu hình thiếu hoặc đang là Certificate, form chỉ rõ cần cấu hình tuyên bố; không đổi nhãn giấy tờ chứng nhận thành tuyên bố để gửi sai loại.
- Thiếu expiry không được tự coi là vô thời hạn. Seller cần bổ sung ngày hoặc xác nhận “Không có ngày hết hạn”. Giữ các bước kiểm tra và đọc lại sau ghi.
- Với Ozon, chỉ tái sử dụng reference cache có số/loại/ngày phù hợp và ID duy nhất từ endpoint certificate/list của chính shop; adapter đọc lại certificate/info trước khi gắn. Tạo giấy tờ mới vẫn cần file và metadata Ozon yêu cầu; chưa nghiệm thu thật do credential bị khóa.
- Cập nhật nhãn tuyên bố ở vi/ru/zh và phần xác nhận để không hiện chuỗi kỹ thuật `declaration` cho seller. Không thay thiết kế các màn hình không liên quan.

Tệp chính: [ConfiguredProductDeclaration.java](../../src/main/java/com/vncode/app/features/products/ConfiguredProductDeclaration.java), [ProductBulkEditService.java](../../src/main/java/com/vncode/app/features/products/ProductBulkEditService.java), [ProductBulkEditPane.java](../../src/main/java/com/vncode/app/ui/products/ProductBulkEditPane.java), [RegistrationDocumentConfigDialog.java](../../src/main/java/com/vncode/app/ui/znackregistration/RegistrationDocumentConfigDialog.java), bốn tệp i18n và hai lớp regression test. Không đổi dependency hay schema SQLite.

[Ảnh JavaFX thật, dữ liệu tổng hợp](assets/products-five-20261106/configured-declaration.png).

## Soi lại toàn ứng dụng

Phạm vi: chạy nhóm Java chức năng rộng, Node công cụ/portal; quét secret toàn source; đọc lại các đường năm nút, ghi/đối soát WB/Ozon, feed Catalog/hàng đợi, license và cập nhật bộ cài. Có chạy CodeQL trong CI nguồn giấy tờ. Đây không phải tuyên bố review thủ công từng dòng hoặc nghiệm thu API thật cho mọi chức năng của app.

### Điểm chưa đạt nghiệm thu / cần theo dõi

1. **Stress danh mục 10.000 GTIN chưa hoàn tất.** Lượt Maven full bị ngắt với mã 143 sau khi worker dùng CPU kéo dài. Ba stack lưu lại chỉ ra bài `wbTenThousandGtinUseFourWholeCardWritesAndReadBackEveryMember`, qua `WbGtinAdapter.beforeSubmit/requireWrites → RegisteredGtinProof.requireCurrent → GtinSyncRepository.catalogItems/requireShop`, và kiểm trạng thái lô. CPU JVM tích lũy 1.507,72 giây không phải thời gian riêng phương thức. Trước đó thông tin tiến độ ghi nhầm Ozon; đã sửa theo stack. Không đủ bằng chứng quy một nguyên nhân duy nhất hoặc kết luận tốc độ API thật. Không sửa vội cache/guard danh tính. Lượt chức năng tiếp theo tách đúng **ba phương thức 10.000** qua tham số Surefire; các test nguồn không bị tắt, lớp còn 11 ca chức năng được chạy. Cả ba stress lớn giữ trạng thái **NOT ACCEPTED**, không tính vào PASS.
2. **Ozon ghi thật bị credential chặn**, như phần trên. Cần credential có hiệu lực để nghiệm thu năm trường trên Ozon; không thử lại bằng key hiện tại.
3. **Gợi ý định dạng ngày trong cấu hình giấy tờ còn chưa nhất quán**: `RegistrationDocumentConfigDialog` gợi ý cả ISO, trong khi `ZnackModels.Settings.validateGoodsDocumentDate` chỉ chấp nhận `dd.MM.yyyy`. Đây là phát hiện đọc mã, mức ảnh hưởng thấp; dùng `dd.MM.yyyy` theo validator. Chưa sửa/parser chưa được mở rộng trong lượt này.
4. **Windows/native và bộ cài mới chưa được nghiệm thu/phát hành.** Linux/Xvfb không chứng minh CryptoPro, DPAPI hay thao tác thật trên máy seller. Prototype mã hóa credential trước đây vẫn chưa đưa vào runtime; không tự triển khai trong lượt kiểm này.

[Trạng thái stress và stack đã bỏ dữ liệu riêng](products-five-20261106/large-stress-incomplete.json).

### Kết quả lượt chức năng và chạy JAR cuối

- Maven `verify` theo phạm vi chức năng đã kết thúc **BUILD SUCCESS**, exit 0, lúc **06.11.2026 23:30:52 MSK**, 21 phút 39 giây: **3.880 ca / 522 lớp**, gồm **3.878 đạt, 2 bỏ qua theo OS**, 0 failure/error. Ba phương thức stress đã nêu **không được chạy trong lượt này**, không phải ba ca OS skip. Lớp `GtinBulkPipelineTest` còn 11 ca chức năng đạt.
- Hai skip là kiểm NTFS junction và entropy DPAPI trên Windows; runner hiện là Linux. Các test UI năm nút và cấu hình giấy tờ, dịch vụ/adapter WB/Ozon, Catalog, DB/phục hồi, PDF, FBS và license nằm trong lượt chức năng trên. Không có nghiệm thu giao dịch thật từ các fixture này.
- Công cụ Node **150/150**, portal **67/67**, kiểm cú pháp portal đạt. Không cộng các lượt targeted/baseline vào tổng 3.880 để làm tăng số ca duy nhất.
- JAR `target/VNcode-1.6.4.jar` từ nguồn `fd20aa8`: SHA256 **`41a51706d1528ae43a6596981d23d4a4b551a7f4a072682add941d032e8aa4e5`**; **1.933 class production** khớp byte với output đã kiểm. Không chứa các test fixture/probe đã kiểm tên. [Build/hash](products-five-20261106/build.json).
- Đã chạy production `Launcher` từ **chính JAR này**, không đưa `target/test-classes` vào classpath, hồ sơ tạm 0 shop. Workspace 1300×700 mở trong **6.589 ms**, đóng bình thường, nhả data lock; license `UNVERIFIED`, không cấp seller API, không yêu cầu kích hoạt. Đây là smoke Linux/Xvfb, không phải thời gian trên máy seller hay bộ cài Windows. [Ảnh](assets/products-five-20261106/runtime-startup.png), [biên nhận](products-five-20261106/startup.json), dùng lại [probe đã lưu](code-doctor-20261106/CodeDoctorStartupProbe.java).

### Scanner và CI

- OpenQodex **0.16.0** cục bộ, snapshot **2.822 tệp nonignored**, không có Git history: chọn **Gitleaks 8.21.2**, status `ran`; 21 scanner khác chủ động không chọn. Không gọi đó là quét mọi scanner.
- Gitleaks báo **10 ứng viên high**. Đối chiếu đủ 10: URL tài liệu công khai, khóa Ed25519 công khai, dữ liệu test hoặc literal PEM marker; **0 credential thật được xác nhận từ các cảnh báo này**. Không sửa suppression để làm số lượng về 0; giữ số và lý do từng cảnh báo trong [bằng chứng](products-five-20261106/whole-source-scan.json).
- Kiểm riêng **20 tệp nhãn/bàn giao mới hoặc đổi sau `af3bf5c`** bằng Gitleaks cùng phiên bản: exit 0, 0 ứng viên; không thay thế phạm vi scanner khác. [Biên nhận](products-five-20261106/handoff-secret-check.json).
- OpenQodex CI nguồn `af3bf5c`, [run 42282863166](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42282863166): Semgrep, Gitleaks, OSV, Ruff, Bandit đều `ran` theo phạm vi workflow. Health: 0 critical/major/minor, 3 nitpick, 7 outside advisories; artifact chỉ lưu health, không có đủ chi tiết để coi các advisory ngoài thay đổi đã được xử lý. Không có AI reviewer độc lập.
- CodeQL Java/Kotlin, CodeQL JavaScript/TypeScript và lint-and-audit của run trên **SUCCESS**. Toàn run **FAILURE** vì Dependency Review không dùng được khi repo chưa bật Dependency graph; không có thay đổi dependency trong patch. Không báo toàn workflow xanh.

### Lệnh và kết quả

[Số liệu từng lớp, các phương thức tách khỏi lượt chức năng, Node và trạng thái cuối](products-five-20261106/tests.json) là nguồn đối chiếu chính xác.

```sh
# Baseline tái hiện lỗi giấy tờ: 37 ca, 1 failure, 0 error
/workspace/toolchains/mvn-vncode -q \
  -Dtest=ProductBulkEditServiceTest,ProductBulkEditWbIntegrationTest,WbListingAdapterTest test

# Sau sửa: 73 ca / 6 lớp thật, 0 failure/error/skip
DISPLAY=:168 XDG_CACHE_HOME=/tmp/products-five-cache \
  /workspace/toolchains/mvn-vncode -q \
  -Dtest=ProductBulkEditServiceTest,ProductBulkEditWbIntegrationTest,ProductBulkDialogTest,ProductBulkEditPaneTest,WbListingAdapterTest,OzonListingAdapterTest,RegistrationDocumentConfigDialogTest test

# Full attempt: INTERRUPTED, không phải PASS
DISPLAY=:168 XDG_CACHE_HOME=/tmp/products-five-cache \
  /workspace/toolchains/mvn-vncode -B verify

# Chức năng rộng; ba phương thức stress lớn được báo riêng
DISPLAY=:168 XDG_CACHE_HOME=/tmp/products-five-cache \
  /workspace/toolchains/mvn-vncode -B \
  '-Dtest=*Test,*Tests,Test*,*TestCase,!GtinBulkPipelineTest#*TenThousand*' verify

node --test tools/*.test.mjs

# Trong website/license-portal
npm --cache /tmp/products-five-npm-cache ci --ignore-scripts
npm --cache /tmp/products-five-npm-cache run check
npm --cache /tmp/products-five-npm-cache test
```

`RegistrationDocumentConfigDialogTest` chưa tồn tại; tham số targeted thực tế khớp sáu lớp, không phải bảy. Portal lần đầu thiếu `linkedom/node_modules`; cài đúng lockfile bằng `npm ci --ignore-scripts` rồi chạy lại đạt 67/67, không sửa source/lockfile để né lỗi.

Lệnh quét nguồn đầy đủ đã chạy:

```sh
NODE_USE_ENV_PROXY=1 NODE_USE_SYSTEM_CA=1 \
  OPENQODEX_HOME=/tmp/products-five-openqodex-state OPENQODEX_AUTO_UPDATE=off \
  node /tmp/products-five-openqodex/package/dist/bin.js scan \
  --cwd /tmp/products-five-full-scan-source \
  --config /tmp/products-five-gitleaks-only.yaml \
  --format sarif --report-dir /tmp/products-five-full-openqodex-final

/tmp/products-five-openqodex/bin/gitleaks detect \
  --source /tmp/products-five-full-scan-source --no-git --redact \
  --report-format json --report-path /tmp/products-five-direct-gitleaks.json
```

OpenQodex final exit 0/status `ran`, direct Gitleaks exit 1 vì mười finding. Lượt cài scanner ban đầu không nhận proxy của Node nên thất bại, không được tính PASS; lượt sau dùng proxy/CA môi trường và checksum phát hành chính thức. Không cài hook, bật AI review ngoài hay gửi issue/report tự động.

## Bàn giao

Các sửa nằm trong [PR #21](https://github.com/ntccong2468-lab/Bancapnhat/pull/21), chưa merge/phát hành. Không ghi Catalog, mua/gán KIZ, nhận đơn, kích hoạt license hay gửi Gmail trong lượt này. Không dùng năm lần resend WB để suy ra nghiệm thu các giao dịch khác. Giữ nguyên journal của giao dịch đã COMPLETE; nếu cần thử giá trị nghiệp vụ mới, lấy dữ liệu đúng của đúng bài/seller và đối soát trước khi mở giao dịch mới.
