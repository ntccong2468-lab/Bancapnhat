# VN code 1.4.0 Preview

**Chưa phát hành.** Phần giấy phép theo tài khoản website/offline/giới hạn thiết bị
đã có thiết kế duyệt và [kế hoạch client Windows để rà soát](../superpowers/plans/2026-10-12-vn-code-account-license-client.md).
Vẫn chờ [nguồn backend](../research/2026-10-12-vn-code-license-backend-intake.md)
để lập kế hoạch backend và xác minh tích hợp thật. Chỉ phát hành 1.4.0 sau kiểm tra phần này cùng Windows.

Bản cập nhật của cùng VN code Windows do Nguyễn Thành Công phát triển và quản lý.
Giữ `%LOCALAPPDATA%\VNcodeApp`, `%LOCALAPPDATA%\VNcodeData`, shop, mẫu tem và lịch sử;
UpgradeCode giữ `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`. Logo VN trắng trên nền đen.

## Sử dụng phần mới

- **Sản phẩm:** chọn shop, đồng bộ khi cần, tìm kiếm/lọc; chọn trang hoặc toàn bộ
  kết quả đã chốt. Sửa nhãn hiệu, chất liệu, TN VED hoặc giấy tờ bằng bảng giá trị
  trước/sau rồi xác nhận. Chuyển shop không thay chủ sở hữu của tác vụ đã gửi.
  Nút Đồng bộ tải catalog và kiểm tra chi tiết/giấy tờ tối đa 100 dòng của trang
  đang xem; các trang khác cần đồng bộ riêng. Cache đọc mới được kiểm tra đúng shop;
  giấy tờ đã bị sàn gỡ được loại khỏi cache sau lần đọc đầy đủ.
- **GS1:** kiểm tra chứng thư còn hạn và INN trong Cấu hình Znack. Hạn mức GTIN
  National Catalog và tiền Честный ЗНАК là hai nguồn riêng. Chọn đúng nhóm hàng/
  tài khoản cho tiền; số chưa lấy được hiển thị trạng thái chưa lấy được.
- **Công cụ → Sao chép bài đăng:** chọn bài nguồn và shop WB/Ozon đích, ánh xạ
  danh mục, thuộc tính, giá, size và kích thước. Kiểm tra giấy tờ/GTIN đúng biến
  thể. Có preview từng đích, tối đa 100 bài × 5 shop/lần. Tài liệu Ozon cần tệp
  gốc trong giới hạn 10 MiB; phần không thể chuyển phải được xử lý rõ ràng.
  Sửa giấy tờ hàng loạt tối đa 16 tài liệu, tổng tệp gốc tối đa 10 MiB; tệp dùng
  chung được lưu một lần. Preview có ngân sách dữ liệu 64 MiB, vượt phải chia batch.
- **Tin tức:** đồng bộ từ WB; xem link và nội dung gốc. Muốn dịch tiếng Việt,
  cấu hình service account Yandex Translate có quyền/billing; dịch là dịch vụ
  tùy chọn. Khi không dịch được, giữ nguyên nội dung gốc.
- **FBS Ozon:** đơn mới → xác nhận đóng gói/chờ giao → đang giao. Có tìm kiếm,
  sắp xếp, phân trang và kết quả từng đơn. Kiểm tra marking/KIZ trước in.
  Kết quả gửi không rõ cần đối soát, không bấm gửi lại thay cho đối soát.

TN VED không có mục sidebar độc lập, vẫn có trong thông tin và tác vụ cần mã.
Các chức năng in/mapping/mẫu tem/GS1 có sẵn được giữ trong cùng ứng dụng.

## 10 cửa hàng và dữ liệu lớn

Tác vụ đọc SQL cục bộ có luồng riêng. 2 tác vụ mạng và 2 tải/giải mã ảnh dùng
chung cho toàn ứng dụng; HTTP seller tối đa 2, hàng đợi có giới hạn. Không tải
toàn catalog khi đổi shop. Dòng hiển thị phân trang, ảnh lười tải, lịch sử tác
vụ dùng summary. Cache ảnh WB/FBO và thumbnail tổng 64 MiB; đây là ngân sách
cache, không phải giới hạn RAM của toàn JVM hay một lần in lớn.

Kiểm tra mô phỏng dùng 100.000 sản phẩm/10.000 đơn, cả 6 WB + 4 Ozon và 10 Ozon.
Xem [báo cáo kiểm chứng](../validation/2026-10-11-vn-code-1.4.0.md). Chưa có phép
đo baseline/current trên Windows 2 CPU/4 GiB; không cam kết mọi máy sẽ không lag.

## Cài đặt

Đóng VN code, sao lưu dữ liệu theo chức năng có sẵn rồi chạy EXE của release
`v1.4.0-preview.1`. Java được đóng gói. Đây là nâng cấp cùng ứng dụng; bộ cài
không thay dữ liệu seller bằng fixture. Schema 4 lên 5 có snapshot kiểm tra.
Không dùng binary cũ để mở database đã lên schema mới.

Bộ cài Preview chưa ký Authenticode. `checksums.sha256`, `build-info.json` và
`update-manifest.json` của release ghi đúng bytes, URL, commit và CI run ID.
Manifest Preview là thông tin **cài thủ công**, `automaticUpdate=false`; không
đưa bản chưa nghiệm thu vào feed tự cập nhật. Không sửa tag hoặc bộ cài cũ.

## Phạm vi còn cần nghiệm thu

Xem [feature-status](VN-code-1.4.0-status.json): tài khoản/chứng thư/SMTP/IMAP/
Yandex/máy in và thao tác đăng bài/giấy tờ thật chưa nghiệm thu. Luồng GTIN
production độc lập còn khóa; các cải tiến FBO, trình thiết kế nhúng, quy tắc TN VED
và hosting chưa được công bố hoàn tất. Module tài khoản/offline/thiết bị là phần
bổ sung bắt buộc của 1.4.0 theo thiết kế mới; cần hoàn thiện trước phát hành.
