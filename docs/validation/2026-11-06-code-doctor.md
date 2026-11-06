# Code Doctor — cài đặt, soi mã và chạy VN Code ngày 06.11.2026

Nguồn gốc: Latest **1.6.4**, tag `86d76d86aae1ea55c5aa014a0bf209c3b1ed99ad`; nhánh đang làm kế thừa `e386aee` (mã ứng dụng không đổi so với tag, các commit sau tag là publisher/bàn giao). Cài skill tại `338d46e20fa82376005daaf39ac5ae4bea2b3c3f`; nguồn sửa cuối **`c2e9dcfc56b4287f7ece05bc058e310fa5bd9942`**, nhánh `audit/code-doctor-20261106`. [PR #21](https://github.com/ntccong2468-lab/Bancapnhat/pull/21) OPEN/draft, base `fix/znack-state-wb-gtin-20261105`. Không thay phiên bản hoặc tài sản Latest đã phát hành.

## 1. Chẩn đoán và bằng chứng

Đã đọc hướng dẫn repo, trạng thái Git, dữ liệu bàn giao 1.6.4, ZIP người dùng cung cấp; đối chiếu Latest/PR GitHub trước sửa. Working tree sạch khi bắt đầu. ZIP có hai tệp Markdown, không có script thực thi; cài nguyên văn `.agents/skills/code-doctor/SKILL.md`, thêm đường dẫn vào `AGENTS.md` và [hướng dẫn sử dụng](../engineering/code-doctor.md). SHA-256 nguồn và file cài nằm trong [biên nhận](2026-11-06-code-doctor-evidence.json).

Baseline hiện tại đạt **197 test / 7 lớp** trước sửa runtime. Rà theo các đường đồng bộ FBS, đọc/cập nhật trạng thái, kho KIZ/PDF, nhận diện/ghi GTIN và đăng ký Catalog; sau đó chạy các nhóm tương ứng. Đây là kiểm tra theo phạm vi đã chọn, không phải chứng nhận toàn bộ ứng dụng không còn lỗi.

**Lỗi đã xác nhận:** một đơn Ozon trả 404 hoặc có payload thiếu thông tin bắt buộc chặn việc cập nhật các đơn hợp lệ phía sau ở đồng bộ chi tiết thủ công, fallback trạng thái nền và nhánh đọc bổ sung chi tiết hàng đợi lấy đơn mới (thủ công/nền).

- Ca thủ công: 101 đơn, đơn đầu lỗi; trước sửa đơn thứ 101 vẫn `awaiting_packaging` dù HTTP fixture của nó trả `awaiting_deliver`. Hai trường hợp 404/thiếu quantity đều đỏ.
- Ca nền: 5 đơn qua hai trang chi tiết; trước sửa ném lỗi ở trang đầu, không có checkpoint mới để đọc phần còn lại. Hai trường hợp 404/thiếu quantity đều đỏ.
- Hàng đợi lấy đơn mới: nếu một dòng danh sách thiếu dữ liệu và lần đọc bổ sung lại thiếu quantity, trước sửa các dòng hợp lệ cùng trang không được lưu. Hai ca thủ công/nền đều đỏ, xanh sau sửa.
- Bốn ca kiểm tra dừng ngay khi gặp 401/429 vẫn đạt trước và sau sửa.
- Khi hoàn thiện bản sửa, thêm kiểm tra nhịp trang phát hiện cảnh báo 15 giây bị áp vào từng trang còn lại. Hai ca đỏ kỳ vọng 1 giây; đã chỉnh về nhịp 1 giây khi còn trang, chờ 15 giây để thử lại sau toàn lượt. Không phải benchmark tốc độ API thật.

[Biên nhận tái hiện đỏ/xanh và cài skill](2026-11-06-code-doctor-evidence.json).

## 2. Nguyên nhân

`OzonPostingSyncService.refreshActiveDetails()` đọc từng đơn trong vòng lặp nhưng không tách lỗi riêng của đơn khỏi lỗi toàn luồng. Một `IOException` thoát ngay trước khi cursor sang trang sau. Dữ liệu hợp lệ phía sau không được đọc/lưu.

`FbsSyncService` có cùng vấn đề tại fallback `FBS_STATUS`: `getPosting`/`requirePostingDetail` có thể ném lỗi trước nhánh kiểm tra dữ liệu chưa đủ. Scheduler nhận lỗi cùng checkpoint cũ và có thể thử lại cùng đơn mãi. Cảnh báo dữ liệu thiếu cũng chưa được mang qua các trang tới cuối lượt. Hai nhánh hàng đợi đã cách ly HTTP 404 nhưng bỏ sót lỗi `FBS_INCOMPLETE_POSTING` từ trình xác minh payload.

## 3. Thay đổi

| Tệp | Thay đổi và kết quả |
| --- | --- |
| `.agents/skills/code-doctor/SKILL.md` | Cài nguyên văn skill người dùng gửi. |
| `AGENTS.md`, `docs/engineering/code-doctor.md` | Chỉ dẫn chọn skill và cách tiếp tục sử dụng trong repo. |
| `OzonPostingSyncService.java` | Với 404 hoặc `FBS_INCOMPLETE_POSTING`, giữ bản ghi cũ và đọc tiếp các đơn/trang khác; sau toàn lượt vẫn ném lỗi đầu để không báo hoàn tất giả. Giới hạn số lỗi đính kèm. Áp dụng cùng cách ly cho đọc bổ sung trong trang danh sách/hàng đợi. |
| `FbsSyncService.java` | Đọc tiếp sau lỗi riêng của đơn, lưu cờ incomplete vào checkpoint, giữ cảnh báo tới trang cuối và không xác nhận `last_complete`. Giữ nhịp trang 1 giây; thử lại lỗi sau lượt. Khi lấy đơn mới, vẫn lưu các dòng hợp lệ nếu chi tiết của một dòng khác không đầy đủ. |
| `OzonSyncServiceTest.java`, `FbsSyncServiceTest.java` | Thêm 10 ca cho 404/dữ liệu thiếu và 401/429, kiểm tra 101 đơn, checkpoint qua SQLite, bảo toàn trạng thái/quantity, thời gian chờ và không báo hoàn tất sai. |

Chỉ cách ly hai lỗi đã chứng minh: 404 của đúng lần đọc đơn và `FBS_INCOMPLETE_POSTING`. Lỗi danh tính, credential, giới hạn API và mất mạng tiếp tục thoát theo luồng sẵn có; không tự coi đơn lỗi là hủy, không xóa dữ liệu hoặc gửi nhận/gán KIZ thay người dùng. Không thay schema, UI, quota API hoặc điều kiện xác minh seller.

## 4. Kiểm thử và chạy ứng dụng

[25 báo cáo JUnit cuối](2026-11-06-code-doctor-tests.json): **507 ca, 0 thất bại, 0 lỗi, 0 bỏ qua**. Nhóm rộng chạy đạt trước chỉnh nhịp trang và hai nhánh hàng đợi; sau chỉnh đã chạy lại toàn bộ `FbsSyncServiceTest`, `OzonSyncServiceTest` và `SyncCoordinatorTest` (**72 ca**) đồng thời đóng gói JAR từ nguồn cuối. Các hash nguồn theo từng giai đoạn có trong JSON; không gọi đây là một lần chạy toàn bộ suite Java.

- FBS WB/Ozon: phân trang, giữ dữ liệu, đổi chủ tài khoản, đối soát và scheduler.
- PDF/KIZ: lô 100 đơn có 100 vận đơn + 100 KIZ/lượt, in lại giữ mã, quét DataMatrix 203/300 dpi; 1.000 vận đơn là fixture với 10 task/0 lượt tải riêng.
- Znack/GTIN: vòng đăng ký/đọc lại, xử lý 429, không gửi trùng khi chưa rõ kết quả, adapter WB/Ozon và đối chiếu Catalog.
- JavaFX/FXML, kích hoạt cửa sổ, khóa dữ liệu và phục hồi khi mở lại.
- Node: **150/150** ca công cụ đạt. `git diff --check` đạt. Java được kiểm tra kiểu qua biên dịch Maven, không có type checker Java riêng.

Các lệnh thực tế (wrapper của môi trường dùng Java 25, Maven 3.8.5 và cache dependency đã cấu hình):

```bash
# Baseline trước sửa runtime: PASS 197 ca / 7 lớp
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q -Dtest=FxmlSmokeTest,OzonAcceptanceKizTest,OzonPrintBundleServiceTest,Kn3OzonPrintProjectionTest,RegistrationCardIndexTest,GtinMatchingServiceTest,GtinSyncCoordinatorTest test

# Tái hiện thủ công: FAIL 2/4 trước sửa; PASS 4/4 sau sửa
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q '-Dtest=OzonSyncServiceTest#oneUnavailablePostingCannotStarveLaterActiveDetailPages+activeDetailRefreshStopsImmediatelyForAuthenticationOrRateLimitFailure' test

# Tái hiện nền: FAIL 2/4 trước sửa; PASS 4/4 sau sửa
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q '-Dtest=FbsSyncServiceTest#ozonStatusFallbackContinuesPastBadPostingAndRetainsWarningAcrossPages+ozonStatusFallbackStopsForAuthenticationAndRateLimits' test

# Hồi quy rộng: PASS, sau hai sửa cách ly lỗi, trước chỉnh nhịp trang cuối
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q -Dtest=FbsSyncServiceTest,OzonSyncServiceTest,SyncCoordinatorTest,OzonPostingRepositoryTest,OzonPostingTrackingStoreTest,OzonAcceptanceKizTest,OzonPrintBundleServiceTest,OzonPrintBundleRollbackTest,OzonPrintReadinessServiceTest,OzonPrintBatchReadTest,OzonLabelServiceTest,Kn3OzonPrintProjectionTest,RegistrationCardIndexTest,GtinSyncCoordinatorTest,GtinMatchingServiceTest,GtinCatalogIndexTest,OzonGtinAdapterTest,WbGtinAdapterTest,ZnackRegistrationBatchServiceTest,ZnackRegistrationBatchHttpIntegrationTest,RegistrationSubmissionEngineTest,FxmlSmokeTest,MainApplicationActivationTest,AppDataLockTest,LocalDataMigrationActivationTest test

# Tái hiện nhịp trang: FAIL 2/2 trước chỉnh 15s -> 1s
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q '-Dtest=FbsSyncServiceTest#ozonStatusFallbackContinuesPastBadPostingAndRetainsWarningAcrossPages' test

# Sau chỉnh nhịp, chạy lại cả hai lớp: PASS 38/38
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q -Dtest=FbsSyncServiceTest,SyncCoordinatorTest test

# Hàng đợi đọc bổ sung: FAIL 2/2 trước sửa
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q '-Dtest=FbsSyncServiceTest#malformedDetailForOneQueuePostingDoesNotDropHealthyPostings,OzonSyncServiceTest#malformedDetailForOneUnfulfilledPostingDoesNotDropHealthyPostings' test

# Nguồn cuối gồm cả nhánh hàng đợi: PASS 72/72 và đóng gói JAR
DISPLAY=:166 /workspace/toolchains/mvn-vncode -q -Dtest=FbsSyncServiceTest,OzonSyncServiceTest,SyncCoordinatorTest package

# Công cụ và build trung gian trước hai sửa nhánh hàng đợi: PASS.
# Package -DskipTests không chạy lại test; package nguồn cuối ở lệnh trên có chạy test.
node --test tools/*.test.mjs
/workspace/toolchains/mvn-vncode -q -DskipTests package
git diff --check
```

**Chạy ứng dụng thật tại điểm vào `com.vncode.app.Launcher`:** đã chạy từ classes và từ chính JAR vừa build, với hồ sơ tạm mới, không có cửa hàng/khóa seller hoặc license thật. JAR mở workspace trong **5.915 ms**, viewport 1300 × 700; đóng bình thường và kiểm tra lấy lại khóa dữ liệu thành công. Lượt từ classes là 2.942 ms; hai số này thuộc Linux/Xvfb và dữ liệu trống, không phải cam kết tốc độ trên máy seller.

[Ảnh workspace vừa chạy](assets/code-doctor-20261106/startup.png), [biên nhận startup/JAR](2026-11-06-code-doctor-startup.json), [mã probe tái lập](code-doctor-20261106/CodeDoctorStartupProbe.java). Probe ở tài liệu kiểm chứng, không nằm trong JAR production; không dùng license fixture. License tại hồ sơ mới là `UNVERIFIED`, quyền seller API không được cấp và không yêu cầu kích hoạt.

Lệnh mở JAR thực tế được gọi qua Python subprocess với danh sách đối số (classpath gồm JAR và dependency đã được Maven giải quyết):

```text
DISPLAY=:166 /workspace/toolchains/jdk-25.0.4.1/bin/java --enable-native-access=ALL-UNNAMED -cp /tmp/vncode-code-doctor-probe:/workspace/vncode-znack-reconcile/target/VNcode-1.6.4.jar:<các đường dẫn trong /tmp/ozon-combined-classpath.txt> CodeDoctorStartupProbe /tmp/vncode-code-doctor-evidence/final-startup
```

Đường dẫn classpath tạm chỉ dùng trong môi trường này. Để tái lập ở môi trường khác, sinh classpath bằng Maven `dependency:build-classpath`, biên dịch probe bằng Java 25 và trỏ đầu ra vào thư mục kiểm thử riêng; probe tự tạo hồ sơ app mới.

## 5. Phần chưa kiểm chứng và trạng thái phát hành

- Không chạy lại toàn bộ hơn 3.800 test Java hoặc Windows/native installer cho sửa mới; 3.864 ca Windows của Latest 1.6.4 là bằng chứng lịch sử của nguồn khác, không dùng làm kết quả sửa mới.
- Các ca FBS/PDF/KIZ/Catalog/GTIN dùng HTTP server và SQLite/PDF tổng hợp. Không có lượt nhận đơn, ghi GTIN, gửi/ký thẻ, mua/gán KIZ hoặc kích hoạt license thật mới trong tác vụ này.
- Thử 101/1.000 đơn không chứng minh Ozon chấp nhận mọi đơn thực tế. Đơn 404/thiếu dữ liệu vẫn cần đối soát và được giữ trạng thái chưa xác minh; luồng thủ công vẫn báo chưa hoàn tất sau khi đã lưu các đơn hợp lệ.
- Các sửa và skill ở nhánh audit/PR, chưa merge hoặc phát hành xuống app. **Latest giữ nguyên 1.6.4**, không ghi đè tag hoặc bộ cài. Không deploy portal hoặc đưa prototype migration credential vào runtime.
