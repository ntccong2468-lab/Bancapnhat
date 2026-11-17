# VNCode: review hành vi bằng Code Doctor và ECC

Ngày: 15.11.2026. Target `/workspace/vncode-ios-motion-20261114`, nhánh `chore/ecc-codex-web-20261115`, base `a7c4d008ae450a82b6cfbfbb79e5532eca6bfa1e`.

## Phương pháp và kết luận có phạm vi

Đã đọc và áp dụng trực tiếp nội dung ba skill mới `vncode-ecc-review`, `vncode-ecc-security`, `vncode-ecc-tdd-verification` qua SKILL.md, cùng Code Doctor và hướng dẫn security/debugging hiện có. Đây là cách dùng Markdown có quan sát được trong phiên hiện tại; **không phải bằng chứng Codex Web đã tự phát hiện/đăng ký skill, agent hoặc hook**.

Review đọc mã tập trung năm đường nghiệp vụ và các caller/guard liên quan dưới đây. **Chưa thiết lập được lỗi chức năng mới có tình huống tái hiện đủ căn cứ; không sửa runtime một cách suy đoán.** Kết luận này không đồng nghĩa mọi chức năng không còn lỗi. Build, test suite, UI render, Windows và giao dịch sàn có bằng chứng thực thi riêng; agent review không chạy hoặc nhận kết quả của chúng làm kết quả của mình.

Không gọi API seller, nhận đơn, mua KIZ, ký, kích hoạt license, chạy installer/scanner ngoài hoặc sử dụng token. Không sửa tệp target trong công việc review này. Chỉ tạo báo cáo ngoài repo.

## Năm đường đã đối chiếu

| Đường đi | Bằng chứng nguồn/guard quan sát được | Regression hiện có cần đối chiếu kết quả thực thi |
|---|---|---|
| Đăng ký Catalog → journal → feed receipt → đọc trạng thái | `RegistrationSubmissionEngine.java:14–80` giữ payload đóng băng và chỉ gọi một lần trên Attempt; `ZnackCardRegistrationWorkflow.java:727–745` kiểm owner, quyền, source review và lưu biên gửi trong journal; `883–927` lưu feed trước theo dõi và phân biệt FEED_SUBMITTED/PROCESSING/READY_TO_SIGN/PUBLISHED | `RegistrationSubmissionEngineTest`, `RegistrationCommandJournalTest`, `RegistrationLargeBatchDurabilityTest`, `RegistrationBatchReopenFeedTest`, `RegistrationStorageConcurrencyTest` |
| Đẩy GTIN WB → giữ card mới nhất → kiểm WB thật đã áp dụng hay chưa | `WbGtinAdapter.java:139` đọc lại ở biên dispatch; `144–165` chỉ APPLIED khi GTIN, biến thể, vendorCode và dữ liệu cần giữ cùng khớp; `197–213` dựng update từ card hiện tại và kiểm fingerprint/xung đột. Không coi HTTP 200 là bằng chứng áp dụng | `WbGtinAdapterTest`, `WbGtinPriceRepairTest`, `GtinSyncCoordinatorTest`, `OzonSkuGtinMatcherTest` cho phần matcher sàn riêng |
| Mua KIZ → phục hồi pipeline → xác minh lưu thông | `ZnackPurchaseCoordinator.java:441–488` phục hồi theo settings/pipeline gốc và đối soát CREATING_ORDER; `772–811` kiểm GTIN/order/count trước đọc trạng thái, chỉ ghi INTRODUCED khi allIntroduced; `915–939` đặt lần kiểm tiếp theo và tách trạng thái cần xác nhận ký/quyền | `ZnackSigningRecoveryTest`, `ZnackOrderReconciliationSafetyTest`, `ZnackCirculationPolicyTest`, `ZnackPurchaseMigrationSafetyTest`, `ZnackKizPurchaseReadbackTest`, `KizPurchaseMappingProofTest` |
| Ozon nhận đơn/KIZ → nhãn vận đơn theo lô → ghép nhãn KIZ | `OzonAcceptanceKizService.java:24–42` chỉ bỏ qua KIZ tùy chọn khi detail mới xác nhận không mandatory, cho ship và đúng sản phẩm; `97–130` thay mã giới hạn 8 lần/45 giây mỗi lượt, giữ trạng thái để tiếp tục; `OzonLabelService.java:134–210` task bền vững tối đa 1.000 posting, lưu biên chưa rõ trước tạo task; `OzonPrintBundleService.java:639–660` ghép KIZ cạnh nhãn vận đơn và kiểm số trang; `OzonKizLabelAppender.java:44–68` kiểm binding sản phẩm/exemplar và mã đã giữ đúng đơn | `OzonAcceptanceKizTest`, `OzonLabelServiceTest`, `OzonBatchPdfPerformanceTest`, `OzonPrintBundleServiceTest`, `OzonCombinedLabelRendererTest` |
| Khởi động/lưu dữ liệu → khóa → snapshot → migration | `AppDataLock.java:30–75` giữ khóa liên tiến trình và đóng channel khi fail; `85–95` đóng khóa/channel; `LocalDataMigrationGate.java:49–76` giữ ownership trước recovery và chặn DB symlink/schema mới hơn; `Launcher.java:47–50` đóng session nếu startup bị hủy | `AppDataLockTest`, `LocalDataMigrationActivationTest`, `LocalDataSnapshotServiceTest` |

Các đường dẫn Java trong bảng nằm dưới `src/main/java/com/vncode/app/`; tên test đã đối chiếu tệp tồn tại dưới `src/test/java/com/vncode/app/`. Chỉ sự tồn tại và đọc nội dung test không chứng minh chúng đã PASS.

Trong `RegistrationLargeBatchDurabilityTest`, hai tình huống hiện có phân biệt rõ: 300 lượt gửi tới HTTP fixture cục bộ và 10.000 biến thể đã duyệt qua mở lại/pagination **không gửi network write**. Không dùng chúng để tuyên bố 10.000 thẻ đã đăng ký lên Catalog thật.

## Giới hạn cụ thể của PDF Ozon

Luồng hiện tại có fallback vận đơn đơn lẻ được chủ ý giữ, không phải thay đổi mới của ECC:

- `OzonPrintBundleService.java:555–577`: nếu prepare KIZ thất bại riêng đơn, lưu lý do trong `carrierOnly`; lỗi trì hoãn/rate-limit được tách sang kết quả chờ/thất bại.
- `639–646`: lỗi metadata/render KIZ riêng đơn cũng lưu lý do `carrierOnly`.
- `650–660`: vẫn sao nhãn vận đơn gốc; chỉ ghi print proof và tăng số KIZ page khi phần KIZ thật sự render/ghép thành công.
- `OzonLabelService.java:195–206`: API có thể trả `unprinted_postings`; không biến các posting đó thành thành công giả.

Vì thế chỉ có thể khẳng định mã đang dùng nhãn theo lô và có nhánh ghép KIZ với kiểm count/identity. **Không thể khẳng định tất cả đơn/cửa hàng thật luôn có KIZ kết hợp hoặc luôn được Ozon nhận** chỉ từ review và test tổng hợp. Cần đọc kết quả per-order và nghiệm thu seller thật đúng phạm vi khi có yêu cầu cụ thể.

## Finding bảo mật lịch sử giữ nguyên

Đã đọc báo cáo exact-source `docs/validation/ios-motion-20261114/windows-b74/security/REPORT.md`, receipt và nguồn ở các vị trí liên quan. Lần CodeQL lịch sử có 8 finding Java: một SHA-1 severity 7.5, hai relative-path command, ba local temp disclosure và hai unreleased lock. Java gate vẫn FAIL; extraction `Gs1SignatureInk.java` vẫn có giới hạn. Đây không phải lần quét CodeQL mới.

| Nhóm | Ngữ cảnh nguồn đọc lại | Kết luận review lần này |
|---|---|---|
| SHA-1 7.5 | `CryptoProSignedCertificateReader.java:62–71`: 40-hex thumbprint là ID chọn chứng thư Windows/CryptoPro, không phải thuật toán ký CMS. Signer còn được khớp issuer/serial ở `38–51`, dịch vụ nhận xác minh chữ ký | Không đổi sang SHA-256 vì sẽ không chọn được chứng thư hiện có. Không waive/đổi gate hoặc tuyên bố finding đã giải quyết |
| Hai lock finding | `CertificateSigningQueue.java:18–24`: tracked boolean và finally mở khóa certificate/gate khi đã lấy thành công, kể cả interrupted work | Không thiết lập được tình huống rò khóa mới từ đường đã đọc; không xóa guard/chỉnh analyzer để qua gate |
| Relative command | `UpdateInstallerService.java:126–132`, `WindowsSecretProtector.java:64`: gọi `powershell.exe` theo tên tương đối | Đường tìm executable/khả năng ghi CWD/PATH cần đánh giá trên Windows. Chưa tái hiện hijack; giữ là nợ bảo mật lịch sử, không làm fix đoán trong Linux |
| Temp-file disclosure | `ListingMediaReader.java:31`, `AppPaths.nativeTempDir():75–93`, updater temp directory | Quyền ACL/thư mục thật phụ thuộc OS và cấu hình. Chưa kiểm Windows ACL hoặc scanner mới; không đánh dấu đã khắc phục |

Không thêm suppression, waiver, loại test hay sửa source security/runtime trong review này. Comment/SuppressWarnings đang có trong nguồn là trạng thái baseline, không phải thay đổi của ECC.

## Kiểm chứng và phần còn lại

- **Đã thực thi:** đọc source/caller/guard/checklist mới, đọc bằng chứng CodeQL lịch sử, xác nhận các test đại diện có thật, phân biệt mock/HTTP fixture với API thật.
- **NOT RUN bởi agent review:** test/build/static analyzer/UI/native Windows/seller API. Kết quả của tiến trình kiểm thử do agent khác chạy cần lấy từ receipt và log thực tế của tiến trình đó.
- **Thay đổi target:** không có trong công việc này; file báo cáo nằm ngoài repo.
- **Tiếp theo:** đối chiếu fresh test/build với các test đại diện, kiểm startup UI cô lập nếu runtime hỗ trợ; giữ rõ Windows/CryptoPro/DPAPI và nghiệp vụ seller chưa nghiệm thu. Không mở rộng sang giao dịch thật chỉ vì dùng skill review.
