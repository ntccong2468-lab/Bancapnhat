# Znack — lỗi SQLITE_BUSY / SYNC_STORAGE trên 1.4.13

Nhật ký người dùng xác nhận SQLite báo `SQLITE_BUSY` khi ghi báo cáo đồng
bộ. Hộp thoại `SYNC_STORAGE [VN_LOCAL]` là thông báo lưu trữ quá chung,
không đủ để phân biệt tranh quyền ghi với lỗi database khác.

## Nguyên nhân tái hiện được

`SyncRunRepository` mở giao dịch DEFERRED, đọc owner rồi mới UPDATE.
Trong WAL, nếu một tác vụ khác đang ghi hoặc snapshot đã cũ, bước chuyển
từ đọc sang ghi có thể trả BUSY ngay cả khi đã có `busy_timeout=5000`.
Cache/nhật ký GTIN có cùng mẫu giao dịch đọc trước ghi sau.

Log còn cho thấy lỗi tại dòng catch của `ZnackCatalogSyncService`: ghi
trạng thái FAILED thất bại đã thay thế exception nguồn ban đầu. Vì vậy
log cũ không đủ để kết luận mọi lỗi API/nguồn ban đầu đều là lỗi SQLite.

Bài tái hiện trên mã 1.4.13 thất bại đúng vị trí: một writer giữ database
khi ghi tiến độ, và 10 shop cùng ghi báo cáo. Lượt đỏ: 3 bài, 1 failure,
1 error, 1 qua. Không gọi API seller.

## Bản sửa

- Giao dịch ghi báo cáo và cache/nhật ký GTIN xin quyền ghi bằng
  `BEGIN IMMEDIATE` trước khi đọc owner. Kiểm tra owner và tính nguyên tử
  của dữ liệu/event được giữ lại.
- Chỉ giao dịch SQLite cục bộ được thử lại tối đa 3 lần, mỗi lần chờ lock
  tối đa 5 giây trên connection mới. Rollback và đóng connection trước
  lần sau; không chạy lại lỗi constraint/owner hoặc giao dịch đã commit.
  Callback không chứa HTTP, CryptoPro hay thao tác in.
- Các đường chỉ đọc vẫn dùng connection đọc; không biến truy vấn giao
  diện thành giao dịch giữ quyền ghi.
- Khi ghi báo cáo lỗi thất bại, giữ exception nguồn và thêm lỗi báo cáo
  vào suppressed; subscriber nền/giao diện nhận đúng lỗi nguồn.
- Lỗi BUSY có mã `SYNC_STORAGE_BUSY` và hướng dẫn đã dịch đủ bốn ngôn
  ngữ. Lỗi lưu trữ khác có mã `SYNC_STORAGE`, không bị coi là BUSY.

Mã nguồn sửa: `5e69d79` (bao gồm các bài kiểm thử bổ sung).

## Kiểm chứng

Lượt tập trung 63/63 qua: báo cáo, cache GTIN, nguồn đăng ký Znack,
coordinator, báo cáo Ozon FBS và thông báo lỗi. Bổ sung kiểm tra chờ lock
hết một lần rồi phục hồi, đọc cache khi có writer, giới hạn chờ, rollback,
không nhân đôi event và giữ lỗi nguồn khi báo cáo lỗi cũng thất bại.

Hồi quy toàn bộ `mvn verify` đã qua: 1.545 bài, 1.543 qua, 2 bài dành riêng
cho nền tảng Windows bỏ qua trên Linux, 0 failure/error.
[Bằng chứng JSON](2026-10-17-znack-sqlite-busy-checks.json). Các kiểm tra dùng SQLite thật với
dữ liệu tổng hợp; không chứng minh tài khoản seller đã đồng bộ API thành
công hoặc CryptoPro thật đã hoạt động. Bộ cài đã phát hành 1.4.13 không
tự thay đổi theo bản sửa trong nhánh mã nguồn.
