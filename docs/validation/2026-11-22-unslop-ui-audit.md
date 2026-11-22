# Audit giao diện VN Code 1.6.13 bằng Unslop

Ngày 22.11.2026, giờ Moskva. Mã ứng dụng `3f679a1df43c0f143a1bb62c97d33fde7cec3cab`, bản Latest `v1.6.13`. Nhánh audit dựa trên `7570360`, commit bàn giao của bản này. Mốc này gồm cài skill cấp dự án, kiểm tra tương thích, audit và đề xuất để duyệt.

## Cài đặt và tương thích

Đã đọc toàn bộ README và SKILL.md của Unslop tại `620163eedb3529d9cd23a3994ed8d287d06f760d`. [Nguồn đã đọc](https://github.com/heydamir/unslop/tree/620163eedb3529d9cd23a3994ed8d287d06f760d)

| Phần | Kết quả |
|---|---|
| Cài cấp dự án | `.agents/skills/unslop/` có SKILL.md, manifest Codex, tài liệu tham chiếu, ảnh ví dụ và script theo revision đã ghim. Kèm giấy phép CC BY 4.0 và ghi công Damir Sokolovsky. |
| Codex | Đường dẫn và front matter theo định dạng repo skill. Dùng trực tiếp hướng dẫn trong phiên audit này. Phép thử khởi động một Codex App Server lồng trong sandbox bị chặn quyền ghi, nên chưa quan sát được việc nút chọn skill trong giao diện Codex Web tự cập nhật. |
| JavaFX | Áp dụng checklist spacing, color, icons, copy, type, layout, details, visuals và motion lên FXML, CSS JavaFX, controller và ảnh native. |
| Snapshot và video | Node, Chromium, Playwright và ffmpeg đã có. Snapshot HTML qua HTTP loopback đã tạo ảnh 800 × 600. Chromium do môi trường quản lý chặn các ca dùng `file://`. Video được cung cấp dưới dạng script tùy chọn trong skill. |

[Hướng dẫn chính thức của Codex về repository skills](https://developers.openai.com/codex/skills)

## Phạm vi và bằng chứng

- Rà soát shell, điều hướng, theme, bố cục và motion dùng chung. Kiểm các tuyến Tổng quan, Sản phẩm, WB và Ozon FBS/FBO, nguồn cung, Znack/Catalog, Liên kết KIZ, GS1, tài chính và phạt, lịch sử in, thiết kế mẫu, cài đặt, license, hướng dẫn và TN VED. Phạm vi luồng con gồm thao tác hàng loạt, xác nhận mua/đăng ký, giấy tờ và trạng thái xử lý.
- Ảnh từ controller và FXML JavaFX của 1.6.13 chạy trong Linux/Xvfb với hồ sơ tổng hợp. Ma trận dùng 1366 × 768 và 1000 × 650, sáng và tối. Có 48 ảnh màn và hộp thoại, cộng 10 ảnh có hàng giả hoặc thao tác cuộn. Nhiều màn ở trạng thái chưa chọn cửa hàng hoặc dữ liệu rỗng. Ảnh Tổng quan chỉ chụp panel dashboard bên trong, chưa đủ để kết luận về bố cục của màn Tổng quan hoàn chỉnh.
- Kiểm đọc nguồn cho hành vi chưa trực tiếp tái hiện. Nghiệm thu Narrator, DPI Windows, tài liệu thật, chữ ký và giao dịch thật thuộc phạm vi kiểm thử riêng.

## Phát hiện ưu tiên

| Ưu tiên | Quan sát | Hướng đề xuất |
|---|---|---|
| Cao · Znack ở cửa sổ hẹp | Ở 1000 × 650, bảng rộng 756 px. Cột **Thao tác** nằm ngoài mép. Thanh cuộn ngang ẩn và `scrollToColumn` không đưa cột vào vùng nhìn thấy trong fixture. Bảng sử dụng chế độ resize ràng buộc, cùng nhiều cột có chiều rộng tối thiểu. [FXML](../../src/main/resources/com/vncode/app/ui/znackregistration/znack-card-registration-view.fxml#L43) · [Controller](../../src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java#L1028) | Đảm bảo chọn, tên, trạng thái và lệnh luôn truy cập được. Ở cửa sổ hẹp, trình bày article, màu và size dưới tên, cho xem đầy đủ thuộc tính từ hàng. |
| Cao · Đăng ký và tự ký | WB đưa **Tự ký** vào menu chuột phải của bảng. Mặc định hiện tại bật. Màn duyệt nguồn cuối chưa nêu lựa chọn này. [Controller](../../src/main/java/com/vncode/app/ui/znackregistration/ZnackCardRegistrationController.java#L181) · [Màn duyệt](../../src/main/java/com/vncode/app/ui/znackregistration/RegistrationSourceReviewDialog.java#L35) | Hiển thị lựa chọn đang có ngay trước xác nhận lô. Giữ nguyên quyền, lựa chọn mặc định và đường ký hiện tại. |
| Cao · Ngữ cảnh trước mua và in | Hộp mua KIZ chỉ hiện GTIN và số lượng, kiểm số lượng sau khi hộp đã đóng. FBO giữ số lượng khi đổi bộ lọc nhưng lệnh in lấy các hàng đang hiện. Ozon FBS có cả chọn trên trang và in toàn bộ kết quả đang lọc. [KIZ](../../src/main/java/com/vncode/app/ui/kizmapping/KizMappingController.java#L322) · [FBO](../../src/main/java/com/vncode/app/ui/fbo/FboPackingController.java#L233) · [Ozon](../../src/main/java/com/vncode/app/ui/ozon/OzonDashboardController.java#L697) | Ghi cửa hàng, mã, số đơn hoặc số lượng và phạm vi ngay trước xác nhận. Hiện lỗi cạnh ô nhập. Giữ đúng tập mà các lệnh hiện tại sử dụng. |
| Cao · Mẫu in và trường lỗi | Ảnh compact cho thấy danh sách thành phần hẹp và khung xem trước tem phải cuộn ngang. Mã đọc cho thấy trường kích thước hoặc vị trí sai có thể giữ model cũ rồi nút Lưu ghi model đó. Xóa và reset tác động trực tiếp lên mẫu. [Designer](../../src/main/java/com/vncode/app/ui/print/PrintTemplateDesignerController.java#L269) · [Kiểm tra trường](../../src/main/java/com/vncode/app/ui/print/PrintTemplateDesignerController.java#L775) | Ưu tiên vùng xem tem, đưa thuộc tính vào panel co giãn. Gắn lỗi vào từng trường, làm rõ kết quả lưu. Trình bày rõ mẫu nào sẽ xóa hoặc đặt lại trước khi xác nhận. Giữ kích thước và tọa độ bản in. |
| Vừa · Lệnh ẩn và bàn phím | GS1 chuyển cấu hình và báo cáo vào menu chuột phải của tiêu đề. Một số checkbox hoặc nút chỉ có icon ở WB/FBO và lịch sử in thiếu tên trợ năng. Mở một số hàng shipment WB dựa vào click chuột. [GS1](../../src/main/java/com/vncode/app/ui/gs1/Gs1Pane.java#L403) · [WB](../../src/main/java/com/vncode/app/ui/packing/PackingController.java#L464) · [Lịch sử](../../src/main/java/com/vncode/app/ui/history/PrintHistoryController.java#L50) | Dùng menu tác vụ có thể focus cho các lệnh phụ. Hiện tên và tooltip bản địa hóa, giữ một đường mở chi tiết bằng bàn phím. |
| Vừa · Báo cáo và khoảng trống | Ảnh Tài chính compact cho thấy một số nhãn KPI và phần lớn tên cột bị rút gọn. Màn GS1 compact chia vùng trên cuộn riêng và vùng thư ở phần dưới, khiến chi tiết tài khoản cần cuộn thêm. | Đặt chỉ số ưu tiên và dữ liệu chi tiết theo lớp. Dùng trạng thái rỗng có hướng dẫn và đưa thư GS1 xuống sau thông tin trạng thái khi chiều cao hạn chế. Tổng quan tiếp tục theo thiết kế bỏ ba ô KPI đã duyệt. |
| Vừa · Tiêu đề, ngôn ngữ và validation | CSS đặt tiêu đề nhóm này ở 24 px nhưng selector `.h2` theo sau khiến Ozon/Znack xuống 20 px, trong khi Products có override riêng. Hộp giấy tờ tiếng Việt vẫn có **Cancel**. Thông báo TN VED nhập sai ở Catalog dùng câu yêu cầu chọn trường. [CSS](../../src/main/resources/com/vncode/app/styles/workspace-controls.css#L152) · [Hộp giấy tờ](../../src/main/java/com/vncode/app/ui/znackregistration/RegistrationDocumentConfigDialog.java#L55) · [Catalog](../../src/main/java/com/vncode/app/ui/catalogsync/CatalogSyncPane.java#L116) | Dùng cấp chữ chung. Chuẩn hóa từ ngữ từng ngôn ngữ và chỉ đúng trường cần chỉnh, giữ nguyên quy tắc xác thực dữ liệu. |
| Vừa · Bề mặt và phân cấp | Chrome và nút đã có token dùng chung. Ảnh sáng và tối cho thấy cạnh nổi lặp ở header, nút, thẻ và panel. SplitMenuButton **Cài đặt** còn mặt vuông bên trong. Màu chữ và nút ở một số cặp token đặc đã đo đạt từ 4,94:1 trở lên cho các vai trò được kiểm. [Theme](../../src/main/resources/com/vncode/app/styles/workspace-controls.css#L155) · [Cài đặt](../../src/main/resources/com/vncode/app/ui/shop/shop-sidebar-view.fxml#L88) | Dùng một cấp nổi rõ ở điều hướng hoặc hộp thoại. Trong bảng, phân biệt hàng chọn, focus và lỗi bằng nền, viền mảnh và chữ. Kiểm tương phản trên lớp nền thực trong cả hai theme. |
| Thấp · Motion | App có token và nhánh giảm chuyển động theo thiết lập hệ điều hành. Một số thao tác lặp áp scale hoặc hiệu ứng bật nhẹ lên cả vùng nội dung. Lượt này chưa đo FPS hay input latency của cây có dữ liệu lớn. [Token](../../src/main/java/com/vncode/app/shared/WorkspaceMotionTokens.java#L8) · [Tương tác](../../src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java#L255) | Giữ motion giải thích điều hướng và xuất hiện hộp thoại. Ưu tiên phản hồi nhỏ, nhanh khi chọn hàng hoặc nhập liệu. Giữ Reduce Motion và kiểm nhảy focus sau nhiều thao tác liên tiếp. |

Các phần đang có ích cho hướng này gồm sidebar thu gọn, danh sách sản phẩm ảo hóa, chuyển hàng sản phẩm theo chiều rộng, phân trang Ozon đi theo tab, trạng thái GS1 có chữ và nguồn, cùng các kiểm soát xác nhận giữa chừng. Điểm dữ liệu lịch sử in Ozon và theo dõi kết quả đồng bộ tài chính cần xác minh bằng fixture riêng nếu mở một tác vụ nghiệp vụ.

Trong Hướng dẫn, nhãn **Thêm ảnh/video** đang dùng cho thao tác thay mục cùng loại. Đề xuất nêu rõ mục sẽ được thay khi có media sẵn. Kiểm các hộp Cài đặt và License cùng shell để giữ đường thao tác bàn phím và chi tiết trạng thái. Hàng TN VED với font scale cao và bảng chia đôi của FBO cần đo trên Windows trước khi đổi chiều cao hay chiều rộng tối thiểu. [Hướng dẫn](../../src/main/java/com/vncode/app/ui/guides/GuidesPane.java#L89) · [Màn FBO](../../src/main/resources/com/vncode/app/ui/fbosupply/fbo-supply-orders-view.fxml#L55)

## Hướng thiết kế để duyệt

1. **Sản phẩm** — Bàn làm việc của seller WB/Ozon để tìm đơn, nhận biết sản phẩm, chọn mã và hoàn tất đóng hàng.
2. **Tính cách** — Điềm tĩnh, chính xác, nhanh khi lặp thao tác.
3. **Chất liệu** — Nền than chì tối và xám sáng, lớp kính nhẹ cho lớp nổi, vùng dữ liệu phẳng hơn để đọc trong thời gian dài. Giữ Inter của ứng dụng và một màu nhấn xanh hiện có.
4. **Điểm nhìn chính** — Bảng đơn hoặc sản phẩm với ảnh thực từ dữ liệu, và khung xem tem đúng tỷ lệ ở Thiết kế mẫu.
5. **Chi tiết nhận diện** — Mã vận đơn hoặc GTIN luôn nằm cạnh trạng thái chữ, có vị trí nhất quán từ bảng đến màn chi tiết hoặc xác nhận.

Ưu tiên diện tích dữ liệu. Ở màn hình hẹp, giảm trang trí, xuống dòng bộ lọc, rút gọn chrome và ghép metadata trong hàng trước khi giảm cỡ chữ. Mốc đề xuất gồm lưới khoảng cách 4 px, tiêu đề trang 24 px, tiêu đề phần 16–18 px và dữ liệu 13–14 px. Độ dày chữ, focus và tương phản sẽ được duyệt trên ảnh JavaFX ở cả hai theme.

## Thứ tự thực hiện sau duyệt

| Giai đoạn | Phạm vi | Kiểm thử sau giai đoạn |
|---|---|---|
| 1 | Khoảng cách, nền, màu và cấp nổi ở shell, bảng, menu và dialog. | Dựng ảnh sáng/tối ở hai cỡ cửa sổ, đo tương phản, kiểm focus và mặt vuông lồng. |
| 2 | Icon, nội dung và typography, ưu tiên đơn, Znack, GS1 và kiểm tra trường nhập. | Kiểm các gói ngôn ngữ, nhãn dài, tooltip và thao tác hoàn toàn bằng bàn phím. |
| 3 | Bố cục bảng và form, lần lượt từ FBS/FBO và Sản phẩm đến Znack/KIZ/GS1 rồi Tài chính, Lịch sử, Thiết kế mẫu, Cài đặt, Hướng dẫn, License, TN VED. | Fixture rộng/hẹp với dữ liệu đủ, dữ liệu rỗng, trạng thái tải và lỗi. Đối chiếu tập chọn hoặc in, các lệnh, giấy tờ và xác nhận trước/sau. Kiểm tem giữ kích thước và bố cục. |
| 4 | Chi tiết hình ảnh và motion cho chuyển trang, tab, menu, hộp thoại và resize. | So khung đầu/cuối, đo thao tác lặp, đảm bảo focus và trạng thái không đổi. Kiểm Windows DPI 100/125/150% và Reduce Motion. |

## Kết quả kiểm thử và tệp

- Đã chạy unit test upstream Unslop. 31/35 đạt, bốn ca snapshot tệp `file://` bị chính sách Chromium chặn. Snapshot riêng qua HTTP loopback đạt và lưu đủ HTML cùng ảnh.
- Chạy suite validator ECC của VN Code. 20/20 ca đạt.
- Java 25/Linux/Xvfb, 133/133 ca thuộc 14 bộ UI, FXML, layout, theme, accessibility và motion đạt. Lượt khảo sát riêng ba bộ motion đạt 20/20, trong đó có ca trùng phạm vi với lượt 133 ca.
- Bằng chứng cục bộ nằm ở `/workspace/reports/unslop-audit-20261122`, gồm log, ảnh JavaFX, ghi chú phạm vi và kết quả thử cuộn bảng.
- Tệp cài mới nằm trong `.agents/skills/unslop/`. Tệp audit này và hai tệp bộ nhớ dự án ghi lại phạm vi và hướng triển khai. Tệp giao diện JavaFX, CSS, FXML, nghiệp vụ, backend và các skill đã tồn tại giữ nguyên ở mốc duyệt này.
