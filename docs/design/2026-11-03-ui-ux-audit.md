# Kiểm kê trước nâng cấp giao diện — 03.11.2026

Nền `30afa28`, nhánh `feature/ui-ux-pro-max-20261103`, bao gồm sửa PDF Ozon PR #18. Java 25/JavaFX 25.0.2, FXML và Java panes, Inter đóng gói, Ikonli Feather, bảng/list ảo hóa. Portal license là sản phẩm web riêng, không thay đổi trong yêu cầu giao diện desktop VN Code. Không thay API, database, quyền hay license.

## Phạm vi

20 route trong HomeController/WorkspaceNavigator: tổng quan, sản phẩm, sửa sản phẩm, WB FBS, Ozon FBS, FBO đóng hàng/đơn, shipment, KIZ mapping, cấu hình Znack/kho seller, đăng ký, GS1, lịch sử, mẫu in, hướng dẫn, tin tức, tài chính, license, TN VED, sao chép; GTIN có workspace được mở riêng. Đã kiểm kê FXML và panes Java, ThemeService, viewport, DialogLayout, toolbar/cell factory, các bài kiểm thử giao diện có sẵn. Giữ route, controller, handler, guard, cấu hình và virtualization.

## Phát hiện ưu tiên

| Mức | Hiện tượng / căn cứ | Cách xử lý |
| --- | --- | --- |
| Cao | Ba lớp CSS, light glass có pill/gradient trong khi dark có nút vuông; nhiều override kích thước/paints | Một stylesheet controls chung, token theo theme; tách layout nghiệp vụ đang ổn định |
| Cao | Pill tiến trình phủ đáy StackPane danh sách sản phẩm, Znack và Ozon; có thể che dòng đang focus | Component dùng chung giữ vùng riêng cho tiến trình, khôi phục diện tích khi đóng |
| Cao | Một số ô tìm kiếm chỉ có placeholder; icon sửa/xóa cửa hàng chỉ có tooltip | Accessible name và labelFor tường minh; giữ tên cập nhật theo ngôn ngữ |
| Vừa | Sidebar toggle chiếm riêng một hàng; nhiều nút màu mạnh cùng trọng lượng | Header logo/toggle chung hàng trên màn rộng, khoảng cách 4/8/12, hành động chính có chủ đích |
| Vừa | Nền selected/light, lỗi, readonly, date picker/spinner không nhất quán | Kiểm tra computed paint/focus của các control, popup và 4 ngôn ngữ |
| Vừa | Cấu hình và báo cáo có nội dung dài; dashboard ngày và thanh công cụ cứng | Giữ scroll/table và buttons, dùng vùng co giãn/wrap, kiểm tra cửa sổ nhỏ |

Baseline chạy 5 test UI shell/chrome/contrast: đạt. 6 ảnh trước sửa ở `/tmp/vncode-uipro-before`; có cảnh báo DB từ fixture contrast cũ không cô lập profile, không phải ghi seller. Các ảnh đều không có shop thật.

## Sử dụng skill

UI UX Pro Max được cài bằng CLI từ commit cố định; script Python chạy được và trả kết quả thật. Tìm design system hai lần cho seller/internal operations trả Minimalism & Swiss, density 8 phù hợp; phần Hero/CTA marketing không phù hợp desktop sau retry, không áp dụng. Chọn bộ token riêng dựa nguyên tắc và nhận diện tím của VN Code, giữ Inter đã đóng gói thay vì tải Fira. Không coi bảng màu tự sinh là bắt buộc. Hướng dẫn JavaFX constraints/virtualization/focus dùng được ở 25; không nâng runtime lên 26.

Mobile HTML/ARIA không áp dụng cho desktop này: dùng JavaFX accessibleText/labelFor/keyboard, kiểm chứng cửa sổ desktop nhỏ và chế độ sáng/tối; không tuyên bố ứng dụng mobile/web được hỗ trợ. Không thêm thư viện UI hoặc animation, không giả dữ liệu thật.
