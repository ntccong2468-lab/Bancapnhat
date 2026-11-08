# Liquid Glass native — phương án đã được duyệt

Người dùng đã duyệt phương án ngày 08.11.2026 và bổ sung: **Thêm cửa hàng tích hợp trong Quản lý, hiển thị cạnh ô tên cửa hàng**. Đây là triển khai Java 25/JavaFX thật trên nhánh `feat/native-liquid-glass-20261108`, kế thừa sửa WB màu/giấy tờ `ae2d48a` và bàn giao preview `7b1dcd2`. Nguồn UI **`5f816b1df7772a8fb40771c6851b146c4356f76d`**. Không chuyển app sang HTML. [Biên nhận kiểm chứng](2026-11-08-native-liquid-glass.json).

## Đã áp dụng

- Hai nền pearl/charcoal, Inter, hành động xanh, lớp nổi có nền vật liệu giới hạn. Bảng/danh sách có nền đục; slider không thay opacity chữ hoặc toàn cửa sổ. JavaFX dùng paints và highlight, không hứa có OS backdrop blur.
- Sidebar 202px, 194px khi cửa sổ hẹp, thu gọn 68px; toolbar 52px, chrome nổi với khoảng cách 8px, sheet 18px/control 10px. Giữ work-area/taskbar/window controls hiện có.
- Menu **Quản lý** cạnh selector có Chỉnh sửa/Thêm cửa hàng, callback gốc. Bỏ nút Thêm trùng trong sidebar. Lối bắt đầu và tìm nhanh mở menu Quản lý; không tạo lối ghi cửa hàng khác.
- Ctrl+K tìm và gọi các thao tác điều hướng hiện có; không chạy khi vùng app bị khóa để bàn giao cập nhật. Không gọi API mới cho hiệu ứng hoặc việc tìm kiếm.
- Màn Cài đặt native dùng lựa chọn sáng/tối/theo hệ thống, nghe OS theme, giảm chuyển động, bật/tắt kính, opacity, ngôn ngữ và callback thật cho dịch/cập nhật/tài khoản/thông tin. Không hiển thị máy in giả khi chưa gắn callback.
- Chuẩn hóa 16 FXML và các Java pages thành sheet/toolbar/tab/footer chung. Năm nút sửa sản phẩm giữ callback và giấy tờ tuyên bố; footer giữ vị trí, vô hiệu hóa khi chưa chọn. Znack dùng tab phản chiếu ComboBox trạng thái gốc, không thay truy vấn, bốn trạng thái vẫn giữ.
- Summary/status dùng cùng footer để tăng chiều cao bảng, cảnh báo thay đổi giá WB vẫn hiện đủ. Caption Chọn tất cả có tooltip/accessible text nói rõ toàn bộ kết quả lọc. Ozon giữ dòng cao theo số mặt hàng, không che hàng phụ để đạt mật độ.
- Route cache, lifecycle, scope cửa hàng, lựa chọn/scroll, confirmation và mọi backend/API/schema/license giữ nguyên. Không chạy lại giao dịch thật đã hoàn tất từ các lượt trước.
- Motion có ngữ cảnh, hủy được khi thao tác nhanh: sibling, supply-detail Forward/Back, tab, drawer, popup/modal, sidebar. Reduced motion dùng opacity 40ms, không dịch/chỉnh scale. Không có shared-image route mới vì sản phẩm hiện sửa qua modal.
- Sửa cascade disabled để primary/success/danger và icon không trông như nút đang dùng được; giữ riêng trạng thái sync đang hoạt động. Font/ảnh/icon đã có trong repo, không thêm tài sản độc quyền Apple.
- Review native phát hiện cột thao tác bị ép khi chuyển bảng đã cache từ cửa sổ lớn về 1280px: giữ đủ tên Tạo thẻ/Cập nhật WB và Thao tác Ozon bằng giới hạn cột phù hợp, phân bổ lại chiều rộng; không giảm chiều cao danh sách.
- Sửa màu menu Quản lý sau khi đổi nền trong cùng lượt FX: CSS bridge của PopupControl từng giữ palette chủ cũ dù opacity đã là 1. Áp theme/material lên public PopupControl cùng root, giữ inline style gốc và không dùng API nội bộ. Probe FXML thật cho độ tương phản 13,44:1 nền sáng, 12,17:1 nền tối; có regression mở lại cùng popup qua sáng → tối → sáng.

## Kiểm chứng và giới hạn

**562 ca / 96 lớp đạt, 0 failure/error/skip; package thành công** trên nguồn UI đã chốt. 1.972 class trong JAR khớp byte output, resources khớp source và Git; SHA JAR `48ec9f1ef9c9048cc0ccaa7dfa60860f561968cfcb4af1fc411af053eea18dae`. Biên nhận liên kết ghi chính xác từng lượt Maven, số ca/lớp, source hash, build và phạm vi review. Những lần đầu phát hiện selector kiểm thử cũ, footer tốn chỗ và paint disabled đã được giữ trong log ngoài Git; không dùng lượt thất bại để chứng nhận nguồn cuối.

**139 PNG native**, gallery và ZIP `/workspace/vncode-native-glass-20261108/VNcode-native-glass-20261108.zip` (17.254.964 byte, SHA `fb6fdbb370e59653ff9b55b281ed462ecbf01240beac9ef6064848fe1f214769`); ZIP/ảnh ngoài Git, có thể mất khi workspace thay. 136 ảnh native được chụp lại bằng một lượt NativeGlassEvidenceTest với xfwm4; một ca này nằm trong 562, không cộng dồn. Source/test/classes/POM/JAR trước/sau y hệt. Ba ảnh palette cùng nguồn.

Review độc lập vòng đầu xem toàn bộ ảnh/contact sheet và source mẫu; yêu cầu hai sửa nêu trên. Sau sửa, reviewer đọc lại toàn bộ 20 ảnh đăng ký, popup cùng hai nền và hồi quy liên quan; verdict **ship riêng hai finding đã chấm**, không tái chứng nhận toàn bộ surface/Windows/API.

Ảnh native ở `/workspace/vncode-native-glass-20261108`: FXML/Java controls thật trong shell tổng hợp khớp bố cục Home, SQLite/reader tổng hợp để tránh startup seller. Không phải profile của seller hoặc ảnh từ Windows. Kiểm menu Thêm chỉ tăng bộ đếm callback, không thêm cửa hàng thật; license API của fixture từ chối mọi request. Review không được coi là nghiệm thu API hoặc tốc độ nhận/in đơn thật.

Chưa xác minh Windows/DPI/multi-monitor/Narrator/hiệu năng trên máy seller hoặc bộ cài. Latest 1.6.6 vẫn nguyên trạng; chưa tạo tag, publisher hoặc cập nhật asset. POM tạm giữ 1.6.6 cho candidate cục bộ; JAR mới không thay binary công khai cùng phiên bản. Cần chọn phiên bản mới và kiểm Windows trước một lượt phát hành riêng.

DESIGN.md/sidecar đã gộp token được duyệt và PRODUCT.md đã đánh dấu 37 ảnh 1.6.3 là lịch sử. Native phase không chạy web detector vì công cụ không có verdict cho JavaFX; dùng computed paints, kiểm controls và ảnh native. Các source readback/OMS/KIZ/Catalog trước vẫn có giới hạn riêng, không được mở rộng bởi phê duyệt UI này.

Chạy trực tiếp entrypoint production `com.vncode.app.Launcher` từ đúng JAR hai lần trên Linux: mở workspace 4.862 ms (bare Xvfb) và 3.807 ms (xfwm4), 0 cửa hàng, không test/probe class trong classpath, không seller-key env, đóng WM_DELETE/exit 0 và nhả khóa. Hồ sơ thử bị xóa sau kiểm. Public updater đọc mạng không thành công ở process đã lược môi trường; không dùng smoke này xác nhận kết nối API. Cùng JAR hết viền trắng ngoài popup khi bật compositor; bare Xvfb báo transparent window không hỗ trợ, scene fill null và mép trắng nằm ngoài layout của root. Không có sửa production để che giới hạn môi trường.
