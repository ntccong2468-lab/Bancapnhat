# Bốn kiểm thử ngữ nghĩa mua KIZ — 16.11.2026

**PASS: 4 ca, 0 failure/error/skip, exit 0.** Thời gian tiến trình Xvfb/Maven: 4,587 giây. Đây là kiểm thử SQLite/API/chữ ký/license tổng hợp, không phải giao dịch mua KIZ thật hoặc CryptoPro trên Windows.

## Phạm vi đã thực thi

| Test có sẵn | Ca | Điều kiện đã xác minh |
| --- | --- | --- |
| `ZnackCirculationPolicyTest#everyNewPurchasePinsCirculationWithoutChangingTheGlobalPreference` | 2 tham số | Hai điểm vào mua đều ghim `autoIntroduction=true` cho lô mới, dù thiết lập toàn cục đang false; không sửa thiết lập toàn cục. |
| `ZnackSigningRecoveryTest#pausedPaidReadinessDoesNotOpenSignerOrScheduleAutomaticRetries` | 1 | Lô đang chờ xác nhận ký dừng ở readiness; không gọi signer, không lập lịch retry tự động, không đưa mã vào tồn dùng được; giữ lô gốc. |
| `ZnackSigningRecoveryTest#foregroundResumeKeepsPaidIdentityRawCodesAndConsumedInventory` | 1 | Tiếp tục trực tiếp dùng đúng lô đã có; nhận readback tổng hợp `INTRODUCED`; giữ mã thô và trạng thái mã đã dùng, không tạo đơn mua/chứng từ mới. |

Các assertion chạy thật trong JVM/JUnit. Signer và kết quả API đều là fake của các test hiện có; không suy kết quả này thành xác nhận SUZ/True API đã mua, ký hoặc lưu thông thật.

## Cách chạy và nguồn class

Không viết hoặc sửa test. Không biên dịch lại. Đã dùng đầu ra class/resource được kiểm chứng ở nhiệm vụ UI trước, sao sang thư mục mới rồi chạy trực tiếp goal `surefire:test` với bộ chọn ba method bên trên. Hai giá trị tham số khiến tổng cộng bốn ca. [Lệnh đầy đủ, argv và giới hạn](command.json).

```text
xvfb-run -a sh /workspace/toolchains/apache-maven-3.8.5/bin/mvn
  -B -o -s <private-settings> -gs <private-settings>
  -Dmaven.repo.local=/workspace/toolchains/m2
  -Dvncode.appdata.dir=<new-isolated-profile>
  -Dtest=ZnackCirculationPolicyTest#everyNewPurchasePinsCirculationWithoutChangingTheGlobalPreference,ZnackSigningRecoveryTest#pausedPaidReadinessDoesNotOpenSignerOrScheduleAutomaticRetries+foregroundResumeKeepsPaidIdentityRawCodesAndConsumedInventory
  surefire:test
```

JDK `/workspace/toolchains/jdk-25.0.4.1`, Maven 3.8.5, cached dependencies, offline, riêng settings/profile/report và môi trường đã bỏ biến credentials. Fixture license ký cục bộ do extension JUnit có sẵn tạo; không kích hoạt license thật. Các file cũ không bị dùng làm thư mục output.

## Bằng chứng bảo toàn

- **1.442** đầu vào build khớp hash giữa workspace hiện tại và snapshot UI đã xác minh; toàn bộ **1.437** tệp dưới `src` khớp.
- Sao chính xác **2.081** tệp production class/resource và **1.278** tệp test class/resource sang thư mục mới. Đây là số tệp, không phải số class Java.
- JAR tham chiếu: SHA-256 `b3ea7e8e20eeda53c38754f38509e3364267d458e297216a18966c4f8eab315b`.
- Sau chạy, toàn bộ cây **7.705** tệp bằng chứng/snapshot v2 trước giữ hash nguyên; nguồn app hiện tại, nguồn bản sao và cả hai cây class không đổi.

[Kết quả/JUnit và kiểm kê](RESULT.json) · [Input proof](input-proof.json) · [Log](run.log). Hai XML JUnit mới ở `source-copy/target/surefire-reports/`; hash và tên testcase đã ghi trong RESULT.

Không chạy bản Vncode 1.3.0 hoặc WBCode, không kiểm API thật, native Windows, app startup hoặc build/phát hành mới. Bốn ca này chỉ xác nhận ngữ nghĩa đang có trong mã VNCode hiện tại; không tự xác định nguyên nhân lỗi trên máy seller nếu thiếu log/trạng thái thật tương ứng.
