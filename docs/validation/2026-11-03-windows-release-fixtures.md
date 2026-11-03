# Hai fixture phát hiện ở gate Windows 1.6.1

Lượt Windows `41713948097` trên `24f9761` chạy đủ 3.813 ca: 2 thất bại, 0 lỗi, 2 bỏ qua theo OS. Lượt này không tạo bộ cài và không được phát hành. Linux cùng mã đạt 3.813 ca, 0 thất bại/lỗi, 2 OS skip.

1. `MavenWrapperIntegrityTest` dùng URI `file:///C:/...` cho ZIP giả. Wrapper upstream đọc phần path bằng WindowsPathParser, từ chối `/C:/...` trước khi kiểm tra checksum. Cấu hình Maven của repo dùng HTTPS, không dùng chế độ file URI đó. Fixture mới phục vụ chính hai ZIP nguyên bản/bị sửa qua HTTP loopback, vẫn chạy JAR wrapper thật trong JVM con; yêu cầu tải cả hai, chấp nhận đúng SHA-256 và từ chối ZIP đã thay đổi. Không sửa wrapper JAR, checksum pin hoặc trust/TLS.
2. `aLongCatalogCooldownYieldsTheWorkerAndResumesTheSameSavedBatch` ngắt ở 5 giây trong lúc đóng giao dịch SQLite của checkpoint **trước khi** gửi/đọc cooldown, gây thêm thông báo cleanup file đang mở. Đây là ràng buộc timing của fixture, không phải bằng chứng worker ngủ hết Retry-After. Fixture giữ giới hạn hữu hạn 30 giây với Retry-After tăng lên một giờ; worker ngủ theo hạn remote vẫn thất bại. Bổ sung assert deadline đã lưu và resume trước hạn không gửi thêm; sau đẩy deadline về hiện tại vẫn tiếp tục đúng ba GTIN, không tạo trùng. Không đổi thời gian chờ/quota/logic production.

Sau sửa: 11/11 kiểm thử mục tiêu trên Linux đạt, không bỏ qua, 25,432 giây gồm Maven. Actionlint và `git diff --check` đạt. Thêm preflight chính hai lớp ở đầu workflow Windows, trước `clean verify` toàn bộ, để lỗi tương thích này được phát hiện sớm. Không bỏ test, không làm xanh báo cáo của lượt lỗi, không bỏ full/native/cài-gỡ/nâng cấp.

Mã runtime ứng dụng, POM, build.bat, Maven wrapper và dữ liệu seller không thay đổi so với `24f9761`. Kết quả full Windows của nguồn fixture mới được ghi ở bàn giao phát hành sau khi CI hoàn tất; không coi 11 ca Linux là nghiệm thu Windows.
