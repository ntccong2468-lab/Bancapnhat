# VN Code 1.6.7 — bàn giao phát hành Latest

**Đã phát hành lên Latest lúc 16:38:02 MSK ngày 09.11.2026 (13:38:02 UTC).** Release immutable **438471378**, tag **`v1.6.7`**, nguồn ứng dụng **`d0231cd6913ce3a1764067bad0901b32c535de41`**.

[Tải bộ cài EXE Windows 64-bit](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.7/VN-code-1.6.7-Windows-x64.exe) · [Release Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/latest) · [Ghi chú](../releases/VN-code-1.6.7.md).

[Receipt tổng hợp cuối](2026-11-09-vncode-167-final-release.json) ràng buộc nguồn/run và hash các bằng chứng; không thay đổi phạm vi của từng kiểm thử.

## Nguồn và bằng chứng phát hành

| Mục | Kết quả đã xác minh |
| --- | --- |
| Nguồn ứng dụng | `d0231cd6913ce3a1764067bad0901b32c535de41`, nhánh `release/vn-code-1.6.7`, tree `84912aa53b1ff0b94d0c9c7200a34f52010012b6` |
| Windows build | **42611294387**, workflow392732536, job129140099225; **SUCCESS**. [Run](2026-11-09-vncode-167-build-run.json) |
| Full Java | **4.188 ca /547 lớp:4.186 đạt,0 failure,0 error,2 OS skip đúng danh tính**; ba stress10.000 và các lớp chứng thư12/38/14 đều đạt. Preflight228 và native3 JVM mới cũng đạt; không cộng các lượt giao nhau. |
| Native và nâng cấp | Launcher production, migration3→6/snapshot, DPAPI, cài/gỡ độc lập, coexistence và nâng cấp thụ động bằng EXE từ1.6.6 giữ dữ liệu đều đạt. |
| Gói cuối | **16 tệp**, source ZIP **2.993 tệp** khớp Git; JAR production **1.987 class /Java25 (class major69)**, không fixture. [Package](2026-11-09-vncode-167-package.json), [review độc lập](2026-11-09-vncode-167-artifact-review.json) |
| Publisher | **42627047589 SUCCESS**, tooling head `b1e5e7774e1ab01d0c1c57ee934900c6927638f7`, nhánh `release/vn-code-1.6.7-publication`. [Run](2026-11-09-vncode-167-publication-run.json), [receipt](2026-11-09-vncode-167-publication.json) |
| Public bytes | **16/16 tệp đã tải lại**, SHA-256/kích thước khớp artifact Windows, package và GitHub digest. [Latest](2026-11-09-vncode-167-published-latest.json), [bảo toàn và tải thực](2026-11-09-vncode-167-public-preservation.json) |
| Lịch sử được giữ | **16 release /258 asset /45 tag cũ không đổi**; sau phát hành có17 release /46 tag. Bỏ qua download counters tăng do tải xác minh; không prune hoặc ghi đè asset/tag cũ. |
| Updater thực | Binary **1.6.6 nguyên bản → AVAILABLE1.6.7**, chọn đúng MSI mới/hash/size. [Receipt](2026-11-09-vncode-167-updater.json) |
| Review sau công bố | **PASS**: độc lập kiểm lại16 public files/534004049 bytes,547/4188 XML, lịch sử cũ và updater thật. [Receipt](2026-11-09-vncode-167-post-publication-review.json) |

Package helper SHA `1af43751eabd4020911ee1951665d05dd4f873bd4005e50c37608ca728bc219f`; receipt package ngoài phiên có SHA `345bb254e80404107a65160a77e040dcefb3ecfb0ee8e4714384dba285028958`. Review artifact độc lập đối chiếu XML thật, source/JAR/resources/sentinels và các smoke; không dùng báo cáo nguồn cũ thay nguồn cuối.

| Payload | Bytes | SHA-256 |
| --- | ---: | --- |
| EXE | 149379584 | `2da4f820660798d8b3979d90b751c1f1c4f9b1d281550b807c558558c6cdbd3b` |
| MSI | 148710661 | `b042992e037f3487cee97c470989aab86e2196cd1e67e5cb64a8c4af046924be` |
| Portable ZIP | 148299313 | `f88861e190ec4d9f61f441d525fd3575b2b4336c7543d18775a05e807d77fbea` |
| Source ZIP | 38625360 | `aeb978bfc812f0700b52661c1d810cf3b66a8ef4e4ad3cbffd9aa0061fe9c22a` |
| Verification ZIP | 2903725 | `98b54e6df7d9ff2363590aefe67225346eb8c2c0519351b83da66b2b01ca7580` |

Windows JAR SHA `6e888e5402bd3e4b8536fda0e78c423c255fd74f34f6060cf7b21e0fc506c402`; source text chỉ chấp nhận khác CRLF/LF UTF-8,2.669 tệp được chuẩn hóa khi đối chiếu. JAR Linux trước đóng gói có hash khác do môi trường compiler; không dùng hash Linux để giả byte identity của JAR Windows.

## Toàn bộ sửa mới đã nằm trong bản này

[Source inclusion](2026-11-08-vncode-167-source-inclusion.json), SHA `10f1384af2fd2b5b7b53fd50d38ff46e78546f7095099cc5e1b7a0000026c88f`, xác nhận **115 runtime input =90 Java +25 resource**, thêm POM thành116 input. Trong109 input của bốn sửa ban đầu,105 giữ hash; bốn locale giữ mọi dòng cũ và chỉ thêm3 warning key/file. Sáu file Java chứng thư/confirmation là phần bổ sung. Không thiếu handover/runtime input theo manifest cuối.

| Nguồn | Phần đã gộp |
| --- | --- |
| `ae2d48a2e397428869d553b7e008402f469a3566` | **WB mua KIZ:** giữ đủ tập màu, chuẩn hóa hữu hạn cách ghi tương đương, phân biệt thiếu màu với sai màu. **Giấy tờ:** ngày nghiêm ngặt; phân biệt thiếu/hết hạn/đình chỉ/chờ xác minh, giữ bằng chứng cũ khi registry đang xử lý, dùng ngày hết hạn của đúng giấy tờ. [Bàn giao](2026-11-08-wb-kiz-color-documents-handoff.md) |
| `5f816b1df7772a8fb40771c6851b146c4356f76d` | **Giao diện native đã duyệt:** sáng/tối/hệ thống, danh sách lớn, điều hướng gọn, material/reduced motion, Ctrl+K. **Thêm cửa hàng trong Quản lý cạnh selector**; giữ callback, sửa nút bị ép rộng và popup giữ sai màu sau đổi nền. [Bàn giao](2026-11-08-native-liquid-glass-handoff.md) |
| `6905fe0a73b5300b6f5390ed0c71dbeca62ea81a` | **KIZ/Znack/GTIN WB:** sửa sai article, bỏ sót barcode, mơ hồ giả; paid-only permit proof/projection hiện hành; theo dõi đúng feed_id. Đọc WB mới trước ghép/ghi, CAS hoàn tất046, giữ journal. **Catalog read revision/epoch riêng** chặn cache/cursor/ABA/in-flight/late write của khóa cũ; bao gồm Ozon và phục hồi paired completion sau crash. [Bàn giao](2026-11-08-kiz-znack-wb-gtin-audit.md) |
| `0a00fbd837d5e92eeb0018e480fb1891436107e9` | **Số giấy tờ Latin/Cyrillic:** chuẩn hóa hữu hạn trong phần registry có cấu trúc; giữ quốc gia/số thứ tự/ngày. HTTP400/code14 đúng số/ngày/loại thành lỗi giấy tờ riêng, không tạo proof từ lỗi. Giữ reference giấy tờ Ozon và ID/meta WB khi chỉ khác bảng chữ. [Bàn giao](2026-11-08-registration-permit-script-fix.md) |
| `e77437c5ddbd168c7cfecfcf12d202f019751d87` | **Chứng thư mới:** đọc thêm kho token/CAdES, ràng buộc DER/fingerprint, chọn provider trước ký; refresh danh sách và hủy tác vụ đọc cũ an toàn. [Bàn giao](2026-11-09-cryptopro-certificate-discovery-handoff.md) |
| `2a5c335c14874bdea18094825c48477b825ba858` | **Fixture Windows:** sau diagnostic chỉ thêm ProgressPreference vào mock COM để bỏ Progress chuẩn bị module; giữ stderr0, mọi assertion và production. [Diagnostic](2026-11-09-cryptopro-windows-progress-diagnostic.json) |
| `d0231cd6913ce3a1764067bad0901b32c535de41` | **Xác nhận đổi người ký trước mọi tác vụ ký/auth:** cảnh báo lưu ngay, xóa GTIN local, chuyển KIZ chưa dùng sang archive, đặt lại Catalog local và lưu lại API key. Cancel không ký/gửi/ghi; sau OK kiểm lại context/UI/DB. Giữ policy owner/reset/consent, không tự migration gia hạn cùngINN. |

### Các sửa1.6.6 và trước đó vẫn được giữ

Baseline1.6.6 `ac8ba1c` là ancestor của nguồn cuối; manifest kiểm lại17 mốc nguồn trong [inclusion1.6.6](2026-11-07-vncode-166-source-inclusion.json). Đây là kế thừa source, không phải giao dịch seller mới trong lượt phát hành.

- **FBS Ozon:** nhận theo lô, xử lý toàn bộ tập chọn và đối soát từng đơn; lưu trạng thái mới/in/đang giao. Tự thay KIZ seller nhập đúng danh mục, giới hạn retry rồi nhường các đơn khác và quay lại, giữ journal khi kết quả chưa rõ. WB giữ luồng riêng.
- **PDF Ozon:** lấy vận đơn theo lô, giữ nguyên nhãn chính thức của Ozon, KIZ kết hợp đúng đơn/nguồn kho; bỏ barcode Ozon riêng. Phiếu nhặt dùng `scanit`/số vận đơn thật và cùng thứ tự WB. Giữ refresh metadata theo SKU batch và guard in carrier-only đơn lẻ.
- **FBS WB:** Đơn nợ theo shipment đã giao, hiển thị/in đúng đơn; giữ dữ liệu đồng bộ, cache/checkpoint và chỉ xử lý phần thiếu.
- **Znack/GTIN:** bốn bộ lọc; lưu feed_id thì riêng thẻ đó sang Đang xử lý ngay. Barcode046 hợp lệ trên WB vào Hoàn tất; Catalog có/WB thiếu thì cập nhật nền và đọc xác nhận. Shared writer, thêm thẻ vào batch, báo cáo không đếm hai lần, resume và chống gửi trùng.
- **Mapping/KIZ:** liên kết Catalog–đúng biến thể Ozon đã duyệt; không ghép chỉ bằng tên gần giống. Giữ quyết định bỏ chặn khác TN VED giữa sàn/Catalog, vẫn kiểm danh tính/màu/size/owner/publication/giấy tờ/proof. Kho seller nhập theo danh mục; kho mua theo mapping đã xác minh.
- **Năm nút sản phẩm:** thương hiệu, chất liệu, giới tính, TN VED và giấy tờ giữ adapter/readback của từng sàn; mặc định giấy tờ cấu hình là tuyên bố phù hợp. Giữ trường cũ khi cập nhật, không coi HTTP200 là thành công nghiệp vụ.
- **Vận hành:** license kiểm nền/giới hạn thời gian, dữ liệu đồng bộ bền vững, updater progress, kho KIZ/lịch sử in/công cụ/tài chính. Prototype mã hóa credential PR-only vẫn ngoài runtime.

## Chứng thư mới và ranh giới dữ liệu

Người dùng xác nhận chứng thư mới hiện trong CryptoPro/WBCode trên cùng máy nhưng thiếu danh sách VN Code. Discovery cũ chỉ đọc uMy, trong khi signer hỗ trợ container100. Vị trí chứng thư thực của seller chưa được truy cập; không khẳng định token-only là nguyên nhân đã đo trên máy đó.

Discovery bổ sung CAdES current-user2/container100/CAPICOM2 ở cả hai host PowerShell. Script chỉ đọc/xuất chứng thư công khai; một mục hỏng không che các mục hợp lệ khác. Parser ràng buộc DER với đúng40hex bằng helper store-ID hiện có; không cấp private-key proof, không import hoặc ký khi đọc danh sách.

UI đọc mới khi Edit/dropdown; giữ lựa chọn theo fingerprint, không tự chọn renewal hoặc kế thừa VERIFIED. Shop change/refresh/Hủy/lưu hủy riêng discovery Task và dùng generation/task identity để chặn kết quả cũ. Provider chọn CAdES **trước lần ký** khi uMy thiếu đúng chứng thư nhưng CAdES xác nhận được; không đổi đường sau lỗi ký/private-key/cancel/timeout. Các tiến trình dùng cùng deadline; thời gian chờ khóa hàng đợi và cleanup tối đa khoảng500ms nằm ngoài deadline đó.

Nút Kiểm tra chữ ký vốn tự lưu sau CMS/auth thành công. Bản này thêm xác nhận trước Task nếu exact predicate `ZnackRepository.signerChanged` thấy thay đổi; sau modal kiểm lại shop/repository/loaded/selection/candidate/busy/expiry/persisted settings. Repository chỉ expose preview với Javadoc; body predicate, resetSQL, transaction/CAS và blockers giữ nguyên.

**Chưa có migration gia hạn cùngINN giữ nguyên kho/mapping hoặc tự rebind approval cũ.** Khác thumbprint vẫn chịu chính sách đổi người ký, kể cả cùngINN. Confirmation công khai tác động hiện có, không bỏ gate owner/INN/private-key/CMS/license hoặc cho phép reset khi chỉ xem danh sách. Không hủy signing Task/seller mutation khi hủy discovery.

## Kiểm thử, lịch sử thất bại và bài học

- Final focused **200/14 lớp Linux/Xvfb đạt**,0 skip; UI14 gồm8 case confirmation. Positive continuation chỉ capture Task READY; negative cases fail-fast trước Crypto/API/DB. Bỏ riêng guard tái hiện1 error do tác vụ ký bị submit, không phải lỗi compile hoặc giao dịch thật.
- Local package trước Windows có1.987 class, Java25 (class major69), không fixture; SHA `ec24b407017a40e94c58e8246b637a67f5ad8461e9500ea622c59a1f5bd98cd2`. Không cộng192+12+200 giao nhau. Hai nhánh PowerShell trên Linux chỉ kiểm cấu trúc; full Windows cuối đã chạy parser/mock COM tổng hợp thật, không phải token seller.
- Node tooling166/166 và Python verifier11/11 GREEN cùng10 expected RED controls thuộc tooling, không cộng vào4.188 application cases. Helper yêu cầu đúng547 suite, XML case/status counters, chứng thư12/38/14 và đúng hai OS skip: `Gs1PrefixTemplateStoreTest#privatePermissionsAreAppliedWhereSupported`, `WindowsSecretProtectorTest#neverFallsBackToPlaintextOffWindows`.
- Giữ bốn run FAIL42515409829/42523983786/42525209391/42570802535. Diagnostic native42584347083 có console observer gây nhiễu; không suy hồi tố nó gây tất cả lỗi full trước. Sửa Stage thử alwaysOnTop/observer Hidden, giữ4s/một click thật/clipboard/status/no-Apply. Quick control737UI và bảy nhóm3 có lặp không thay full.
- Baseline0f5/run42587222212 đã đạt4.122 ca và16 asset nhưng bị nguồn mới thay thế, không promote nó để chứng nhận chứng thư/guard.
- e774/run42605121356 FAIL220:219 đạt,1 failure,0 error/skip. Script đã trả đúng hai cert tổng hợp; assertion cuối stderr0 nhận392byte. Diagnostic42606535850 giữ expected0, chỉ thêm Base64 vào message, xác nhận một CLIXML Progress “Preparing modules for first use.”,0 Error record/0 certificate data. Fixture2a5 chỉ đặt ProgressPreference, không lọc stderr hoặc sửa production.
- Run2a5/42607033320 đã đạt220+fresh3 nhưng được chủ động **CANCELLED/superseded** trước full/package để thêm confirmation. Không biến partial run thành PASS. Full d02342611294387 là nguồn nghiệm thu cuối duy nhất.

Bài học: trace nút kiểm tra đến transaction; xác nhận trước side effect và kiểm lại context sau modal. Discovery phải bao phủ nguồn signer dùng nhưng metadata không phải quyền ký. Hủy cần cả interruption lẫn generation. Capture/phân loại stderr trước khi sửa fixture. Giữ mọi kết quả theo đúng SHA/phạm vi, không reuse báo cáo hoặc đổi ownership để làm cảnh báo biến mất.

## Các lệnh kiểm chứng đã thực hiện

Các lệnh dưới đây thuộc các lượt đã lưu receipt ở trên; cập nhật tài liệu bàn giao không chạy lại full suite hoặc giao dịch seller.

Windows run42611294387 trên đúng nguồn d023:

```powershell
.\mvnw.cmd -B clean verify
```

Focused guard200/14 lớp trên Linux/Xvfb, cùng danh sách14 lớp được lưu trong [manifest chứng thư](2026-11-09-cryptopro-certificate-discovery.json):

```bash
PATH=/workspace/toolchains/xvfb-current/root/usr/bin:$PATH xvfb-run -a /workspace/toolchains/mvn-vncode -Dtest=CryptoProCertificateDiscoveryServiceTest,CryptoProCertificateRoutingTest,CryptoProSignatureTest,CryptoProSignedCertificateReaderTest,CryptoProCommandRunnerTest,WindowsXmlSignatureTest,ZnackAutomationControllerTest,ZnackCertificateDiscoveryUiTest,ZnackAutomationInformationTest,FxmlSmokeTest,ZnackCertificateSettingsRepositoryTest,SignatureTestFailureTest,DarkReferenceScreensTest,ApprovedWorkspaceAcceptanceTest test
```

Package helper và public-preservation helper đã chạy trên các tệp thật của đúng guard build; cả hai đạt:

```bash
python3 /workspace/vncode-167-release/verify_package-certificate-guard.py --run-json /workspace/vncode-167-release/windows-run-certificate-guard.json
python3 /workspace/vncode-167-release/verify_public_preservation-certificate-guard.py --directory /workspace/vncode-167-release/public-downloads-certificate-guard --updater-receipt /workspace/vncode-167-release/updater-after-certificate-guard.json
```

Probe updater dùng binary1.6.6 nguyên bản và transport Cloud đã cấu hình, không override version. Exact Java source, hash, classpath và lệnh khôi phục được giữ trong [archive README](release-167-tools/README.md); kết quả thực nằm ở [receipt updater](2026-11-09-vncode-167-updater.json). Archive không bao gồm JAR/bộ cài hoặc credential.

## Security và giới hạn kiểm chứng

[Fresh CodeQL42611297755 trên đúng d023](2026-11-09-vncode-167-certificate-guard-security.json) giữ **8 finding Java cũ,0 finding mới; Java gate FAIL**, một high severity7.5 là helper thumbprint SHA-1 Windows/CryptoPro cũ. Không thêm SHA-1 call hoặc đổi signing/updater integrity; không hạ query/gate/suppression. JavaScript0 finding/lint-audit đạt; OpenQodex và Dependency Review skip theo dispatch. Cả6 file chứng thư/guard có extraction notices; warning/error cũ tại `Gs1SignatureInk.java` vẫn giới hạn coverage. Không tuyên bố mọi security gate xanh.

Counterexample selector/metadata bị ghép sai được ghi trong review là giả định từ injection/corrupt data; không tìm thấy producer production sau kiểm parser flush, DER binding và verified restore. Không gọi đó là lỗi seller đã tái hiện hoặc lấy nó làm căn cứ tự migration owner/approval. Rà source/security không thay nghiệm thu token/PKI thật.

**Lượt phát hành này không có seller API write, nhận đơn/gán KIZ thật, mua KIZ, gửi feed/GTIN, ký bằng chứng thư seller, kích hoạt license hoặc Gmail.** Các đọc API thật trước đó giữ scope riêng: audit62 HTTP đọc WB/Catalog;114 chưa khớp gồm113 article+1 size, không suy tất cả đều chưa đăng ký. Ba tra cứu registry xác nhận chuẩn hóa giấy tờ, không chứng minh tạo/ký/công bố thẻ.

Updater dùng binary1.6.6 thật qua Cloud proxy/CA, profile trống và không explicit app Authorization header; hạ tầng có thể gắn credential repo. Chưa chứng minh truy cập internet độc lập không xác thực, chưa quan sát popup/cài trên máy seller. Passive upgrade Windows là bằng chứng riêng.

Giới hạn còn mở: seller CryptoPro/PKI và mua/ký-công bố đầu-cuối; nguồn báo cáo phạt Ozon; tự tiếp tục PDF chờ mua sau restart; benchmark1000 đơn thật; DPI/multi-monitor/Narrator/FPS và TLS trên chính máy seller. FBS vẫn polling theo hạn mức, không cam kết độ trễ0 hoặc vượt điều kiện do sàn từ chối. Bộ cài chưa ký Authenticode, không deploy portal.

## Rollout và tiếp tục

Sau nâng cấp, chạy **Đồng bộ Znack đầy đủ một lần** để làm mới cache legacy chưa có read revision. Không xóa profile/feed/job hoặc tạo lại thẻ để làm sạch báo cáo. Giữ schema6, UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, dữ liệu và scope cửa hàng. Đọc journal/receipt trước khi tiếp tục yêu cầu chưa rõ kết quả.

[Kho công cụ phiên](release-167-tools/README.md) giữ exact helper/probe và metadata nhỏ, hashes/lệnh/giới hạn. Projection public-before là DERIVED, không phải khôi phục SHA raw cũ; các proof trước source cuối là lịch sử. Bản archive được chụp trước Publish nên các chữ pending trong đó mô tả thời điểm capture, không thay trạng thái phát hành ở bàn giao này.

Nguồn/tag/binary1.6.7 đã immutable; commit tài liệu bàn giao sau Publish không thay byte asset hoặc mã app đã đóng gói. Giữ bản1.6.6 và mọi release cũ, không prune/ghi đè. Công việc mới cần nguồn/phiên bản và kiểm thử riêng; không phát hành lại1.6.7 từ working tree tài liệu.
