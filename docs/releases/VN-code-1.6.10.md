# VN Code 1.6.10 — mã nguồn sửa lỗi, chưa phát hành Latest

Kế thừa đầy đủ nguồn và chức năng của 1.6.9. Bản này được chuẩn bị thành ZIP mã nguồn theo yêu cầu ngày 14.11.2026; không tạo bộ cài hoặc thay Latest.

- Thêm thao tác **Tiếp tục lô mua** cho lô KIZ đang chờ xác nhận ký ngay tại cửa hàng đang mở. Tiếp tục đúng pipeline, đơn, request key và hồ sơ kết nối đã lưu; không tạo yêu cầu mua mới.
- Hiển thị **Chờ xác nhận ký** khi pipeline thực sự đã tạm dừng, thay vì chỉ hiển thị đang tải mã hoặc đối soát lưu thông. Ẩn thao tác mua thêm trên chính hàng đang chờ để tránh mua nhầm lần nữa.
- Giữ lại mã nguyên nhân CryptoPro đã biết trong chẩn đoán an toàn; khi chưa biết, báo cần xác nhận ký thay vì giả định một lỗi chứng thư/PIN cụ thể. Bổ sung hướng dẫn tiếp tục bằng bốn ngôn ngữ.
- Xác nhận tiếp tục chỉ có hiệu lực cho lô được chọn. Giữ cơ chế dừng sau lỗi/hủy ký, quyền license, chủ sở hữu, hồ sơ gốc và các bảo vệ đối soát trước khi gửi lại.

Đã đối chiếu repo VNCode đầu tiên và tài liệu SUZ: không thay API/auth bằng mã cũ hoặc bỏ các sửa mới về phục hồi buffer, lưu thông KIZ và chống mua/gửi trùng. Đề xuất OpenRewrite, ArchUnit, JaCoCo và PIT được lập riêng để duyệt; chưa cài hay refactor toàn app.

Kết quả kiểm thử và phần chưa nghiệm thu được ghi trong tài liệu bàn giao của bản nguồn. Kiểm thử với signer/API tạm không thay thế mua/ký thật bằng chứng thư của seller.

- Sửa popup giữ class theme cũ sau đổi nền sáng/tối; đồng bộ riêng class của skin và giữ style binding/class của caller. Có regression RED trước sửa, GREEN và full verification trên nguồn cuối.

[Bàn giao và kiểm thử](../validation/2026-11-14-kiz-source-repair.md) · [Đề xuất repo chờ duyệt](../research/2026-11-14-code-quality-proposals.md) · [Thử ghép đúng152NAU,size2XL/XXL/52](../research/simple-ozon-match-20261114/README.md). Phép thử chỉ là báo cáo trên API thật, chưa thay matcher của app hoặc ghi GTIN.
