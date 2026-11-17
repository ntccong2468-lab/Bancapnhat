# ECC cho Codex Web và kiểm VNCode — 15.11.2026

**Đã hoàn tất kiểm chứng trong môi trường Linux/Xvfb.** Bộ nền mới chạy đủ 4.700 ca: 4.698 đạt, 0 failure/error, 2 ca phụ thuộc Windows bỏ qua. Hai sửa UI hiện hành được kiểm riêng 280/280 và khởi động thành công từ chính JAR mới. Các phạm vi này không cộng thành một tổng ca mới và không thay thế Windows/API seller thật.

## Phạm vi và nguồn

Người dùng đã duyệt kế hoạch tích hợp chọn lọc ECC và yêu cầu dùng skill kiểm VNCode hoạt động bình thường. Nhánh cục bộ: `chore/ecc-codex-web-20261115`, base `a7c4d008ae450a82b6cfbfbb79e5532eca6bfa1e`. ECC chính thức tại `d2ef2680594f9a17526a6e1a2e18b3d242c5d17b`; xem [nguồn và license](../../engineering/ecc/integration-manifest.json).

## Thay đổi

- Thêm ba skill `vncode-ecc-review`, `vncode-ecc-security`, `vncode-ecc-tdd-verification`; root AGENTS chỉ thêm định tuyến và giữ nguyên nội dung cũ.
- Thêm [hướng dẫn tích hợp](../../engineering/ecc/README.md), ma trận tương thích, checklist review/security/verification, manifest nguồn và MIT notice. Giữ Code Doctor, OpenQodex, Taste/Impeccable/UI UX Pro Max và hệ thống memory hiện có.
- Thêm validator Node không dependency và kiểm thử hồi quy. Review độc lập phát hiện rồi sửa hai khe hở: mất nguồn/ánh xạ nguồn vẫn PASS và YAML object bị nhận nhầm là chuỗi mô tả. RED thực tế 13 đạt/4 lỗi; GREEN 17/17 đạt. Không dùng thử lỗi có chủ đích làm bằng chứng app lỗi.
- Sửa `tools/UiLayoutProbe.java`: dùng ThemeService như app thật; chờ kết thúc chuyển cảnh có deadline; xác nhận production stylesheet và trạng thái ổn định trước chụp.
- Sửa lỗi hiển thị Znack/GTIN: bỏ overlay thông báo rỗng bị trùng, giữ placeholder rỗng/loading của TableView. Chỉ thay controller UI và FXML tương ứng; không đổi logic gửi API hay trạng thái nghiệp vụ.

## Kết quả hiện có

| Phép kiểm | Kết quả | Phạm vi |
|---|---|---|
| Validator và nguồn | PASS | 12 skill cũ +3 mới; 11 artifact, 11 nguồn; source byte và MIT trùng snapshot đã pin |
| Test công cụ Node cuối | PASS 191/191, 0 skip | Bao gồm 17 test validator; không cộng 17 lần nữa |
| Portal syntax/test | PASS; 67/67, 0 skip | Dữ liệu/HTTP fixture; không đăng ký Gmail hoặc kích hoạt license thật |
| Portal npm audit | PASS, 0 finding | Lockfile hiện có, 111 dependency; không phải chứng nhận source security |
| Launcher từ JAR hiện hành trên Linux | PASS | CodeSource của Launcher và controller Znack đúng JAR hiện hành; workspace hiện sau 6,097 giây, đóng bình thường, nhả khóa; hồ sơ thử cô lập, không kích hoạt thật |
| Bộ Java nền đầy đủ | PASS 4.700 ca/585 suite; 4.698 đạt, 0 failure/error, 2 skip | Fresh offline `clean verify`, 3.761,781 giây; class/resource đóng băng từ base trước hai sửa UI; [receipt](full-result.json) |
| Nhóm UI hiện hành/build | PASS 280/280, 23 suite, 0 skip | `clean verify` trong bản sao nguồn cô lập; 1.424 input build trùng nguồn hiện hành trước/sau; [receipt](focused-result.json) |
| JAR hiện hành | PASS | 7.774.115 byte, SHA-256 `4a1b13a71bef839b9eccb1970003d90b0301e56b839aac5e4d4097a6f866355c`; 2.067 entry input trùng byte, manifest do Maven tạo được kiểm riêng, 74 runtime JAR đúng classpath, không có fixture test |
| Công cụ chụp đã sửa | PASS 288 layout/72 PNG | Theme production, chuyển cảnh đã kết thúc, 0 nút tràn ngang; dùng app class nền |
| UI hiện hành qua overlay | PASS 72 PNG, 8 assertion | Overlay biên dịch từ hai tệp UI hiện hành; đúng một thông báo rỗng, xem trực tiếp 18 ảnh; không phải bộ cài production |

Các lượt test là những phạm vi riêng. Không cộng kết quả chạy lại, test con hoặc lịch sử phát hành vào số ca hiện tại. Portal ban đầu có hai tệp test không tải được vì thiếu `linkedom`; khôi phục đúng lockfile bằng `npm ci --ignore-scripts` với cache workspace rồi mới đạt 67/67. Không đổi package/lockfile, không cài ECC runtime hoặc chạy lifecycle script dependency.

Ảnh dữ liệu thử, theme production và đã chờ chuyển cảnh: [Znack trước sửa](znack-empty-before.png) · [Znack sau sửa](znack-empty-after.png). Ảnh sau dùng controller/FXML hiện hành qua overlay kiểm thử đã mô tả; không phải ảnh app Windows của seller.

Hai skip Linux được định danh: `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors` và `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows`. Không tắt hay loại test để đạt kết quả. Bộ nền dùng class/resource đã biên dịch trước sửa UI, so lại byte JAR với manifest đóng băng; hai tệp source UI đã sửa trong working tree được báo riêng, không gọi đây là full test của một SHA sạch mới.

Nhóm hiện hành chạy `clean verify` riêng trong bản sao nguồn cô lập, 1.424 input build vẫn khớp repository ở lần đối chiếu cuối. [Bằng chứng nguồn cuối](final-current-build-input-proof.json) liên kết SHA-256 của ba receipt: bộ nền, nhóm hiện hành và Launcher hiện hành. JAR hiện hành được kiểm entry input, manifest/plugin Maven và hai metadata POM được sinh hợp lệ; không có file/class ngoài dự kiến. Đây là JAR kiểm thử cục bộ, không phải bộ cài Windows đã phát hành.

Hai bài GTIN lớn xác minh 10.000/10.000 dòng mỗi sàn bằng API mô phỏng: Ozon 100 lượt ghi theo lô; WB 4 lượt ghi toàn thẻ. Không diễn giải số này thành giao dịch trên tài khoản seller thật.

## Tệp và lệnh kiểm chứng

Danh sách đầy đủ tệp tạo/sửa kèm hash nằm trong [file-inventory.json](file-inventory.json). Gói tích hợp gồm ba `.agents/skills/vncode-ecc-*/SKILL.md`, root AGENTS, sáu tệp trong `docs/engineering/ecc/`, hai công cụ `tools/validate-ecc-integration{,.test}.mjs`. Ngoài ra sửa helper `tools/UiLayoutProbe.java`, hai tệp UI Znack; cập nhật ba tệp memory và biên bản/evidence tại thư mục này.

Các lệnh đã thực thi: Maven `clean verify` toàn bộ trên mã biên dịch nền; Maven `-Dtest=<23 lớp UI được ghi trong receipt> clean verify` trên bản sao hiện hành; `node --test tools/*.test.mjs`; portal `npm run check`, `node --test test/*.test.js`, `npm audit --package-lock-only --ignore-scripts --audit-level=high --json`; validator với và không có `--upstream-root`; javac/helper chụp UI và Launcher harness cô lập. Lệnh, runtime, exit code, count và hash chi tiết nằm trong [receipt tổng](receipt.json) cùng các receipt con.

## Review nghiệp vụ và giới hạn

Đã áp dụng trực tiếp ba SKILL.md mới qua AGENTS cùng Code Doctor để rà: đăng ký Catalog/journal/feed, GTIN WB/readback, mua và lưu thông KIZ, nhận đơn/PDF Ozon, khởi động/khóa/migration. Chi tiết ở [review hành vi](behavior-review.md), [review UI](ui-review.md). Chưa xác nhận lỗi chức năng mới trong năm đường nghiệp vụ đã đọc.

Ozon vẫn có nhánh chỉ vận đơn khi chuẩn bị/render KIZ riêng đơn thất bại, kèm lý do. Không tuyên bố mọi đơn thật đều nhận được hoặc luôn có nhãn kết hợp dựa trên mock/source review. Không ghi API seller, mua KIZ, ký thật, kích hoạt license hay gửi Gmail trong đợt này.

Tám finding Java CodeQL lịch sử và giới hạn extraction GS1 chưa được xử lý/miễn trừ; không có lần quét Java CodeQL mới trong môi trường này. Dependency audit 0 finding không thay thế CodeQL. Công cụ scanner CLI chưa có, không tự cài để thay kết quả bằng một giả định PASS.

UI được kiểm trên Linux/Xvfb, dữ liệu thử và các trang chủ yếu rỗng. Không suy từ kích thước fixture rằng 100.000 sản phẩm đã render. Chưa nghiệm thu Windows seller/CryptoPro/DPAPI, thao tác bàn phím, FPS/chuyển động hay API nghiệp vụ thật. Các ảnh đầu thiếu stylesheet production/chụp giữa chuyển cảnh đã bị loại khỏi kết luận UI production; không sửa app để chữa lỗi của công cụ chụp.

## Tương thích Codex Web và bảo toàn

Đã dùng skill theo cách đọc trực tiếp từ AGENTS. Catalog executor hiện tại trả danh sách rỗng; chưa kiểm một phiên Web mới để chứng minh tự nhận skill. Không đăng ký hook, agent, plugin, MCP hay config CLI. Markdown là hướng dẫn, không tự cấp quyền hoặc thực thi.

Đối chiếu baseline: 805 tệp runtime chỉ có hai tệp UI nêu trên thay đổi; 603 Java nghiệp vụ ngoài ui/shared, 614 tệp test, 27 tệp CI, 53 tệp portal và 291 tệp skill hiện có giữ nguyên byte. Không xóa tệp nguồn, đổi schema, dependency, thiết kế kính nổi hoặc gate phát hành. [Guard bảo toàn](scope-preservation.json) và inventory ghi phạm vi thực tế; nội dung AGENTS ban đầu giữ nguyên byte làm prefix.

Latest quan sát trong đợt này vẫn `v1.6.10`, release `445977967` immutable. Công việc hiện tại là source/infrastructure và một sửa UI chưa phát hành; không tạo bộ cài Windows hoặc thay Latest.

## Phần tiếp theo

Kiểm chứng và memory của công việc này đã hoàn tất. Những kiểm Windows/API thật và native auto-discovery cần môi trường tương ứng; không dùng báo cáo này làm bằng chứng chúng đã đạt. Tám finding CodeQL lịch sử vẫn cần đánh giá/khép lại riêng. Nên đo riêng chi phí kiểm GTIN 10.000 dòng và tối ưu có benchmark trong một việc riêng, giữ nguyên các guard danh tính và hồi quy lớn. Nhánh vẫn cục bộ/working tree, chưa push hoặc phát hành; không cần cài runtime ECC để dùng các checklist qua AGENTS.
