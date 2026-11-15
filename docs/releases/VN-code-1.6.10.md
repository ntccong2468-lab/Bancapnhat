# VN Code 1.6.10

Bản cập nhật kế thừa các chức năng và dữ liệu của1.6.9, bổ sung sửa luồng tiếp tục mua KIZ, ghép GTIN Ozon và giao diện kính nổi.

- **Kính nổi bên trong toàn app:** panel, bảng/danh sách, trường nhập, nút, menu, nhóm Cài đặt và báo cáo ở cả nền sáng/tối. Giữ chữ và trạng thái rõ, vùng làm việc lớn, có chế độ Giảm chuyển động và tắt kính.
- **Báo cáo bo tròn:** bỏ khung vuông ngoài ở dialog app, có tiêu đề/nút đóng riêng, kéo và đổi kích thước khi cho phép. Cửa sổ hệ thống/CryptoPro và giấy trắng trong trình thiết kế nhãn giữ hành vi hiện có.
- **Thao tác liền mạch:** chuyển cảnh theo ngữ cảnh cho mục/mục con, menu và báo cáo; phản hồi nhấn/thả/keyboard dùng chung, không trì hoãn hay gọi lặp hành động nghiệp vụ. Sửa footer/tiến độ che danh sách và giữ năm nút sửa sản phẩm.
- **Tiếp tục lô mua KIZ chờ ký:** thao tác tại đúng cửa hàng đang mở, dùng lại pipeline, đơn, request key và hồ sơ kết nối gốc. Hiển thị Chờ xác nhận ký; không tạo yêu cầu mua mới hoặc cho phép mua thêm nhầm trên hàng đang chờ. Quyền tiếp tục chỉ áp dụng lô được chọn; không bỏ bảo vệ chủ sở hữu, ký hoặc đối soát lưu thông.
- **Ghép GTIN Ozon theo đúng mã mẫu và size:** so toàn bộ mã mẫu, không phân biệt hoa/thường/khoảng trắng. Không ghép152NAU vớiB3152NAU hoặc152NAU DASH.2XL/XXL tương đương; size Nga52 chỉ là alias khi được chính biến thể Ozon xác nhận, không dùng bảng quy đổi toàn sàn. Dùng chung matcher cho đăng ký, đồng bộ GTIN và liên kết KIZ Ozon; giữ quy tắc WB và liên kết thủ công.
- **Đọc lại liên kết chính xác:** không bỏ GTIN Ozon đã được xác nhận; proof hết hạn không bị hiểu là chưa có thẻ để tạo trùng. Sửa popup còn skin theme cũ sau chuyển sáng/tối.

Các repo OpenRewrite/Spoon và ArchUnit/JaCoCo/PIT vẫn là đề xuất nghiên cứu chờ duyệt, chưa tích hợp hoặc refactor diện rộng.

Kiểm thử giao diện/dữ liệu tổng hợp và CI không thay thế mua KIZ có phí, ký bằng chứng thư hoặc ghi dữ liệu seller thật. Chỉ xác nhận lưu thông khi có phản hồi trạng thái tương ứng; không đánh dấu tải mã là đã lưu thông. Khi nâng cấp giữ hồ sơ, hàng đợi, feed và lịch sử hiện có.


Đã công bố Latest ngày15.11.2026: [bàn giao và kiểm chứng sau công bố](../validation/2026-11-15-vncode-1610-release-handoff.md).
