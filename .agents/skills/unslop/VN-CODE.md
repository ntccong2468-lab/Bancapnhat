# Unslop trong VN Code

Unslop do [Damir Sokolovsky](https://heydamir.design/) phát triển và phát hành theo [CC BY 4.0](LICENSE). Thư mục skill được sao chép nguyên bản từ [heydamir/unslop](https://github.com/heydamir/unslop/tree/620163eedb3529d9cd23a3994ed8d287d06f760d/skills/unslop). Tệp này và bản sao giấy phép được bổ sung cho VN Code.

- Gọi `$unslop` trong dự án. Bản audit đang dùng liên kết đến [giao diện VN Code 1.6.13](../../../docs/validation/2026-11-22-unslop-ui-audit.md).
- Dùng JavaFX và hồ sơ cửa hàng tổng hợp để xem bố cục. Kiểm tra giao diện sáng, tối và cửa sổ hẹp. Kiểm tra bàn phím, tỷ lệ hiển thị và Reduce Motion trên Windows ở giai đoạn nghiệm thu thiết kế.
- Playwright và ffmpeg phục vụ chụp trang HTML và dựng video. Các công cụ này chạy độc lập với JavaFX khi có yêu cầu về nội dung web.
- Giữ nguyên phạm vi lệnh nhận đơn, in, mua, đăng ký, ký và đồng bộ. Khi đề xuất lại bố cục, trình bày bằng chứng và hướng thiết kế để chủ dự án duyệt trước.
