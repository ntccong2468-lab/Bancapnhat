# Hệ giao diện VN Code

UI UX Pro Max là tham chiếu chính. VN Code là công cụ vận hành nhiều dữ liệu: nội dung làm việc và phản hồi cụ thể được ưu tiên.

- Nền tối gần đen, surface charcoal; nền sáng xám rất nhạt, chữ đậm. Accent tím giới hạn cho lựa chọn và tác vụ chính. Trạng thái luôn có chữ; không chỉ màu.
- Inter từ bundle, body 14, metadata 12–13, tiêu đề 20–22. Không tải font qua mạng.
- Khoảng cách 4/8/12/16; control 34–38 cho chuột/bàn phím desktop, vùng dữ liệu co giãn đầy khung. Icon Feather 16–18; toggle sidebar giữ 38.
- Control focus 2px, không đổi kích thước khi hover/pressed/focus; neutral border và solid popup. Text mục tiêu 4.5:1, focus 3:1. Màu lỗi/thành công có token riêng dark/light.
- Giữ thanh chỉnh độ trong nền sáng: thay alpha của paint, không giảm opacity chữ/control cha. Thông tin popup vẫn opaque.
- Status đang chạy có vùng dưới bảng, không đè lên dòng cuối; trạng thái tạm xóa phải trả lại không gian và giữ selection/scroll.
- Giữ table/list ảo hóa, không dựng thêm hàng hoặc polling. Giữ nguyên handler/API, chỉ bố cục và trình bày.
- Cửa sổ desktop thu gọn sidebar tự động như hiện tại; toolbar wrap/overflow có sẵn. Dialog vừa owner/visual bounds; bảng rộng có cuộn bên trong.
