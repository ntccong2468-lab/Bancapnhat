# Hai fixture phát hiện ở gate Windows 1.6.1

Lượt Windows `41713948097` trên `24f9761` chạy đủ 3.813 ca: 2 thất bại, 0 lỗi, 2 bỏ qua theo OS. Lượt này không tạo bộ cài và không được phát hành. Linux cùng mã đạt 3.813 ca, 0 thất bại/lỗi, 2 OS skip.

1. `MavenWrapperIntegrityTest` dùng URI `file:///C:/...` cho ZIP giả. Wrapper upstream đọc phần path bằng WindowsPathParser, từ chối `/C:/...` trước khi kiểm tra checksum. Cấu hình Maven của repo dùng HTTPS, không dùng chế độ file URI đó. Fixture mới phục vụ chính hai ZIP nguyên bản/bị sửa qua HTTP loopback, vẫn chạy JAR wrapper thật trong JVM con; yêu cầu tải cả hai, chấp nhận đúng SHA-256 và từ chối ZIP đã thay đổi. Không sửa wrapper JAR, checksum pin hoặc trust/TLS.
2. `aLongCatalogCooldownYieldsTheWorkerAndResumesTheSameSavedBatch` ngắt ở 5 giây trong lúc đóng giao dịch SQLite của checkpoint **trước khi** gửi/đọc cooldown, gây thêm thông báo cleanup file đang mở. Đây là ràng buộc timing của fixture, không phải bằng chứng worker ngủ hết Retry-After. Fixture giữ giới hạn hữu hạn 30 giây với Retry-After tăng lên một giờ; worker ngủ theo hạn remote vẫn thất bại. Bổ sung assert deadline đã lưu và resume trước hạn không gửi thêm; sau đẩy deadline về hiện tại vẫn tiếp tục đúng ba GTIN, không tạo trùng. Không đổi thời gian chờ/quota/logic production.

Sau sửa: 11/11 kiểm thử mục tiêu trên Linux đạt, không bỏ qua, 25,432 giây gồm Maven. Actionlint và `git diff --check` đạt. Thêm preflight chính hai lớp ở đầu workflow Windows, trước `clean verify` toàn bộ, để lỗi tương thích này được phát hiện sớm. Không bỏ test, không làm xanh báo cáo của lượt lỗi, không bỏ full/native/cài-gỡ/nâng cấp.

Mã runtime ứng dụng, POM, build.bat, Maven wrapper và dữ liệu seller không thay đổi so với `24f9761`. Kết quả full Windows của nguồn fixture mới được ghi ở bàn giao phát hành sau khi CI hoàn tất; không coi 11 ca Linux là nghiệm thu Windows.

## Kiểm tra bổ sung đúng nhánh chờ trong transport

Rà soát sau đó phát hiện Retry-After 3.600 giây vượt tổng ngân sách retry 300 giây của transport, nên ca trên nguồn `01b87d3` kiểm tra nhánh trả lỗi dài hạn, chưa thay thế được phần bao phủ `RateLimitDeferral` của fixture 30 giây ban đầu. Không coi hai nhánh này là tương đương.

Nhánh xuất bản sửa riêng fixture thành Retry-After **120 giây**, timeout worker **30 giây**, giữ assert deadline >110 giây, không gửi trước hạn, tiếp tục đúng GTIN và không lặp feed. Log Linux chứng minh đã đi vào nhánh retry 120.000 ms nhưng cả lớp 9 ca kết thúc trong 9,073 giây; 11/11 ca hai lớp đạt, không bỏ qua, tổng Maven 34,625 giây.

Workflow bổ sung `verify-vncode-161-cooldown.yml` kiểm tra Windows và bắt buộc `src/main`, POM, wrapper, build script trùng tuyệt đối với nguồn bộ cài `01b87d3`. Đây là kiểm tra bổ sung trên cùng đầu vào runtime, không thay cho full Windows của chính nguồn bộ cài và không đổi tag/artifact. Fixture mới và biên nhận nằm trên nhánh xuất bản; source ZIP của bộ cài giữ đúng nguồn01b đã ghim. Chỉ công bố khi cả full/native/upgrade và kiểm tra bổ sung Windows đạt. Không ghi full suite được chạy trên SHA của nhánh xuất bản.

Windows bổ sung đã đạt: run `41736451413`, source kiểm thử `4fa8e6a`, runtime inputs giống01b; 11/11 ca, không lỗi/thất bại/bỏ qua. Ca cooldown mất7,008giây, log xác nhận đã vào nhánh retry120.000ms rồi nhả worker. [Biên nhận](2026-11-03-vncode-161-cooldown-windows.json).
