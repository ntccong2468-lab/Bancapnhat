# VN Code — Unslop giai đoạn 4

Tích hợp trên `5a78502` sau khi giai đoạn bố cục đã kiểm xong. Thay đổi nằm ở lớp điều phối chuyển động dùng chung và kiểm thử liên quan.

## Thay đổi

- Chuyển trang ngang cấp và tab dùng hiệu ứng mờ ngắn. Chuyển cấp tiến/lùi đi 12 px theo hướng điều hướng. Trình bày của bảng và cỡ chữ giữ ổn định từ khung đầu. [Chuyển động](../../src/main/java/com/vncode/app/shared/WorkspaceMotion.java) · [Thời lượng](../../src/main/java/com/vncode/app/shared/WorkspaceMotionTokens.java).
- Xóa ảnh tạm của lần chuyển trước khi chụp lần kế tiếp. Khi kéo đổi kích thước, bỏ ảnh tạm và giữ trang đang hoạt động cùng lựa chọn. [Ranh giới chuyển động](../../src/main/java/com/vncode/app/shared/MotionBoundary.java).
- Nút thật thu nhẹ 2% khi giữ và trở về đúng kích thước. Hàng bảng, tiêu đề cột, tab và mục menu dùng phản hồi trạng thái JavaFX. Cửa sổ menu/dialog mở đúng kích thước ngay. Khi đóng, lớp ảnh tạm giảm độ mờ trong lúc cửa sổ thật kết thúc. [Tương tác](../../src/main/java/com/vncode/app/shared/WorkspaceInteractionMotion.java).
- Reduce Motion giữ chuyển động theo vị trí ở mức 0 và dùng đoạn mờ 40 ms. Trạng thái đóng đi về trong suốt. Các nút dùng luồng bàn phím, modal và quyền focus có sẵn.

## Kiểm thử

Tám hồi quy được tái hiện trên `c61865e`. Khi đổi trang liên tục, ảnh tạm từ trang đã rời còn xuất hiện trong ảnh kế tiếp. Resize giữ ảnh tạm ở kích thước cũ. Ngoài ra, bố cục trang và hộp thoại co ngay ở khung đầu, nút nảy qua kích thước đích và đóng trong Reduce Motion mờ theo chiều ngược. [Log trước sửa](/workspace/reports/unslop-motion-20261122/red.log).

Sau sửa, bản thay đổi trong worktree riêng đạt 41/41 ca chuyển động và 52/52 ca giao diện phụ thuộc. Các ca kiểm việc đổi trang, giải phóng ảnh tạm, trả focus, phím kích hoạt và hủy, vòng đời modal, giao diện và kích thước cửa sổ. [Chuyển động](/workspace/reports/unslop-motion-20261122/green-focused.log) · [Giao diện phụ thuộc](/workspace/reports/unslop-motion-20261122/green-consumers.log).

- Khung đầu hướng tiến đo `translateX=12`, hướng lùi `-12`, hệ số cỡ là 1. Resize và khung cuối đều ổn định ở 0 px với số ảnh tạm bằng 0. Bản nháp và lựa chọn thử được giữ khi chuyển nhanh, Ctrl+F đến đúng một lần. Sự kiện GTK tổng hợp kích hoạt Reduce Motion, chuyển mờ với tọa độ 0 và không tạo ảnh tạm. [Số đo chuyển động](/workspace/reports/unslop-layout-motion-20261122/phase4-isolated/motion.log).
- Robot JavaFX dùng Space và Enter mở menu GS1, Esc đóng và đưa focus về nút mở cả 8/8 ca. Hộp thoại thử nhận focus vào trường nhập, Tab đi tiếp, Esc trả lại focus cho nút mở ở cả hai nền. [Menu GS1](/workspace/reports/unslop-layout-motion-20261122/phase4-isolated/Gs1KeyboardProbe.log) · [Hộp thoại](/workspace/reports/unslop-layout-motion-20261122/phase4-isolated/DialogFocusProbe.log).
- Bản tích hợp JavaFX/Linux render 6 tổ hợp màn/nền ở mỗi hệ số GTK 1,00, 1,25 và 1,50. Có 54 phép đo kích thước, các bảng ở trong vùng nhìn và hệ số cửa sổ thực đo trùng cấu hình. [100%](/workspace/reports/unslop-layout-motion-20261122/phase4-integrated/scale-1.0/layout.log) · [125%](/workspace/reports/unslop-layout-motion-20261122/phase4-integrated/scale-1.25/layout.log) · [150%](/workspace/reports/unslop-layout-motion-20261122/phase4-integrated/scale-1.5/layout.log).

Bản tích hợp giai đoạn 3 và 4 qua Maven `verify` với 114/114 ca. Số đo chuyển động, menu và hộp thoại được lặp lại trên bytecode tích hợp. JAR chứa đúng 10 tài nguyên đã sửa và bốn lớp chuyển động. [Maven](/workspace/reports/unslop-layout-motion-20261122/phase4-integrated.log) · [Số đo tích hợp](/workspace/reports/unslop-layout-motion-20261122/phase4-integrated/motion.log) · [JAR](/workspace/reports/unslop-layout-motion-20261122/jar-check.log).

## Phạm vi nghiệm thu

Các màn nghiệp vụ dùng hồ sơ tổng hợp. DPI Windows, thiết lập Reduce Motion trên Windows và quy trình seller thật chưa được nghiệm thu. Môi trường hiện chưa có kết nối tới máy Windows đã cấu hình. Latest vẫn là VN Code 1.6.13.
