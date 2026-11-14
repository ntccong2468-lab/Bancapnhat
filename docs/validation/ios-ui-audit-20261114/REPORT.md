# VN Code: vì sao giao diện chưa giống kỳ vọng iOS / Liquid Glass

Kiểm tra ngày 14.11.2026 trên nguồn **`a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3` / 1.6.10**. Đây là audit đọc mã và chụp JavaFX thật trên Linux với hồ sơ tổng hợp, không sửa UI, không phải ảnh máy Windows của seller. [So ảnh](comparison/index.html), [receipt](receipt.json), [so nguồn UI với1.6.9](ui-source-provenance.json).

## Kết luận

Nhận xét “chưa giống iOS 27” có cơ sở ở mức chất liệu và độ liên tục: bản hiện tại là **desktop workbench có màu, bo góc và chrome lấy cảm hứng Apple**, chưa phải một hệ Liquid Glass đầy đủ. Các lớp kính hiện tại chủ yếu là paint bán trong suốt trên nền phẳng; phần lớn màn hình là bảng và sheet đục. Nhiều control/cell vẫn giữ bố cục JavaFX riêng của màn cũ.

Không thấy bằng chứng stylesheet mới bị bỏ quên hoặc không được tải trong nguồn đang kiểm. `ThemeService.applyTheme` nạp cả `theme.css` và `workspace-controls.css` (dòng142–143), và ảnh mới có đúng palette, sidebar nổi, header52px và sheet18px. Bản1.6.10 vừa sửa nghiệp vụ GTIN/KIZ **không thay toàn bộ giao diện**: hai stylesheet, motion, font và hai màn sản phẩm/Ozon được so đều giữ nguyên byte so nguồn1.6.9. Vì vậy chỉ đưa phần GTIN này vào bản mới sẽ không làm giao diện tự biến thành phong cách khác.

## Bốn nguyên nhân có bằng chứng

1. **Kính đang được mô phỏng bằng màu alpha, chưa có vật liệu blur/depth thực.** `ThemeService.java:234–258` tạo các biến RGBA theo slider; `workspace-controls.css:149–175` vẽ hai lớp màu và shadow nhỏ cho sidebar/header. Không có backdrop sampling/blur/refraction trong đường này. Trên ground phẳng cùng tông, lớp bán trong suốt nhìn gần như một panel đục. Master prompt người dùng cho phép fallback khi runtime không hỗ trợ blur, nên đây không phải lý do để tuyên bố app thiếu mọi hiệu ứng; nhưng fallback hiện tại tạo cảm giác ít “kính” hơn mong đợi.

2. **Phần chiếm diện tích lớn nhất cố ý đục và phẳng.** `workspace-controls.css:181–199` dùng `-bg-secondary` và `-fx-effect:null` cho sheet/card; `:369–400` áp nền đục, không effect cho bảng và dòng. Điều này giữ chữ rõ và danh sách lớn, nhưng phần nhìn giống Liquid Glass chỉ còn ở viền ngoài. Cải tiến cần làm rõ phân lớp chrome/overlay và control, không blur toàn bộ bảng để giả cảm giác kính.

3. **Bản native không giữ nguyên cấu trúc component của bản HTML đã xem trước.** Ảnh sản phẩm HTML chia rõ Sản phẩm/Mã trên sàn/Biến thể/Giấy tờ; native dùng `ProductsPane.java:232–257` với cụm bốn dòng tên/category/article và hai nhóm metadata bên phải. Màn Ozon vẫn dùng filter trước tab và cột STT/đơn/ảnh/thông tin/GTIN của FXML (`ozon-dashboard-view.fxml:44–68`), khác bố cục bản mẫu. Style chung đã đổi, nhưng cell và hierarchy chưa được thiết kế lại đồng bộ để tạo một cảm giác mới hoàn toàn. Đây là khoảng cách triển khai, không phải lỗi dữ liệu của seller.

4. **Motion mới có một phần, chưa có toàn bộ sự liên tục của prompt.** `HomeController.java:643–646` thay nội dung cũ ngay rồi gọi motion cho view mới; `MotionBoundary.java:11–19` dừng view cũ và chỉ animate incoming. `WorkspaceMotion.java:53–61,114–120` dùng fade/translate/scale với KeyFrame và arrival spline; chưa phải engine spring physics. `DESIGN.md:423–431` ghi rõ không có shared-image route và không có reverse close giống preview cho dialog. Prompt gốc yêu cầu old-page fade/exit, shared-element, underline/pill di chuyển và spring mềm. Không thể coi tất cả phần đó đã hoàn tất chỉ vì có duration token. Lượt chụp này bật Reduced Motion để ổn định ảnh nên **không phải benchmark FPS hoặc nghiệm thu cảm giác chuyển động**.

## Điều đã kiểm trực tiếp

- Chạy lại **NativeGlassEvidenceTest** bằng JUnit Launcher hiện có, không chạy Maven hay production `Launcher`: **1 ca đạt, 0 lỗi/bỏ qua, 42,728 giây**, 25 route, 136 PNG, 1.340 kiểm tra control; proxy license ghi nhận0 lần gọi.
- Dữ liệu SQLite tổng hợp và reader Ozon/sản phẩm được inject. Không gửi nhận đơn, sửa sản phẩm, mua/ký KIZ hoặc kích hoạt license.
- Ảnh mới1440×900: vùng danh sách sản phẩm1171×553, Ozon1171×552. Các màn không bị giới hạn vào một khung nhỏ ở giữa.
- So trực tiếp hai cặp ảnh: FBS Ozon tối và sản phẩm WB sáng. Ảnh trái là **HTML preview08.11** đã được duyệt hướng thiết kế; ảnh phải là **JavaFX hiện tại chụp mới14.11**. Tên ảnh native cũ có chữ `approved` không được dùng như bằng chứng người dùng đã duyệt từng chi tiết triển khai sau đó.
- Thumbnail trống trong ảnh native do fixture không cấp URL ảnh; không được báo đây là lỗi mất ảnh của app thật. Các số24 lặp ở tab cũng là fixture, không phải báo cáo nghiệp vụ thật.

## Trạng thái cấu hình trên máy seller chưa biết

`ThemeService.java:81–83,110–121` giữ lựa chọn bật kính và mức slider; `:91–107` tôn trọng Reduced Motion của app/hệ điều hành. Nếu seller tắt kính hoặc bật giảm chuyển động thì hiệu ứng ít hơn nữa. **Chưa đọc cấu hình Windows thật, nên không kết luận đây là nguyên nhân đang xảy ra trên máy người dùng.** Các ảnh mới chỉ chứng minh source hiện tại render được như ảnh đính kèm.

## Hướng cải tiến nên làm tiếp, theo mức ảnh hưởng

1. Làm lại ngôn ngữ vật liệu cho header/sidebar/overlay để nhìn có lớp và chiều sâu ngay ở nền sáng lẫn tối; dùng vật liệu/snapshot blur giới hạn hoặc fallback paint tốt hơn theo khả năng runtime. Giữ bảng cuộn đục, chữ rõ và toàn bộ vùng làm việc lớn.
2. Chuẩn hóa control và cell theo cùng bố cục: bộ lọc, tab/segmented control, group hành động, row metadata, popup và modal. Đối chiếu trực tiếp ảnh trước/sau, không chỉ đổi radius/màu chung.
3. Hoàn tất transition có exit/enter theo ngữ cảnh và shared-element khi thật sự có phần tử liên kết; thêm chuyển động indicator và đóng overlay có cleanup. Giữ hủy animation, trạng thái danh sách, keyboard/reduced motion và không chờ animation để gọi API.

Nên dựng bản xem trước hai nền từ chính component JavaFX của app, ưu tiên sản phẩm/FBS/đăng ký rồi mới nhân rộng. Lượt audit này **chưa áp một redesign mới vào source**. Không cần đổi framework hoặc sao chép font/icon độc quyền Apple để cải thiện cảm giác.

## Hai skill và giới hạn công cụ

Đã dùng TASTE app-screen reference (đọc toàn bộ) để kiểm mật độ/hierarchy và IMPECCABLE Operate/native audit để ưu tiên brief và xem rendered result. Không chạy web DOM inspector/detector để tuyên bố JavaFX đạt. Hướng Apple do người dùng chỉ định phải thắng mặc định TASTE vốn hạn chế glass; không được lấy rule mặc định để làm brief thành một theme phẳng.

`impeccable context` chạy một lần; báo sidecar tài liệu cũ hơn DESIGN và trường Platform mô tả Windows/JavaFX không nằm trong enum tool. Đây là vấn đề context của công cụ thiết kế, **không phải nguyên nhân runtime mất hiệu ứng**. Không tự sửa sidecar, không gán sai app thành iOS/web và không nâng phiên bản skill trong lượt này. Bản v4.9.0 được tool báo có sẵn nhưng chưa cài; việc cập nhật không cần thiết để hoàn tất audit này.
