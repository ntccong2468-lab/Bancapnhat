# Bàn giao: xác nhận thẻ Catalog và hàng thu gọn

- Nền ứng dụng 1.5.12; nhánh `fix/catalog-readback-compact-rows`. Không đổi version, tạo bộ cài, tag hoặc Latest.
- Hoàn tất điều kiện bộ lọc theo thẻ đã đọc lại, lượt đọc cuối lô, cùng đường đăng ký hàng loạt/từng thẻ Ozon, khôi phục lô Ozon cũ, bí danh quốc gia WB/Catalog và chiều cao hàng 104/76 px.
- `clean verify` cuối đạt: 2.929 ca, 2.927 đạt, 2 bỏ qua theo hệ điều hành. Đã xem ảnh JavaFX thật dùng dữ liệu tổng hợp.
- Người dùng đã bấm Đăng ký cho một fixture cụ thể; đã xin đúng 1 GTIN mới, POST đúng 1 feed lên API production và đọc thấy đúng good_id/article/màu/size/TN VED. Catalog tăng 1.253 → 1.254 thẻ. Kết quả đọc gần nhất: draft/moderation; chưa ký và không ghi GTIN lên WB/Ozon.
- Đã chạy nguyên phản hồi API thật qua parser/trạng thái của VNCode trên app-data tạm: CardModeration, cardVerified=true, thuộc Đang xử lý. Chưa nghiệm thu thao tác nút trong app Windows của seller.
- Biên nhận, payload và định danh seller giữ riêng trong `/tmp/vncode-live-catalog-20261023/`; không đưa vào repo. Đã có `live-attempt.json` trạng thái CATALOG_CONFIRMED: không chạy lại allocation/feed. Nếu cần theo dõi, chỉ đọc lại đúng GTIN/feed đã lưu.
- Trước nghiệm thu ký/xuất bản tự động, đối chiếu thêm chuẩn hóa danh mục/nước trong validateReviewedBusinessFields: API nhận danh sách ID/ISO nhưng đọc lại object danh mục/tên nước. Lượt tạo thẻ và bộ lọc đã kiểm chứng, chưa kết luận luồng ký đạt.

Chứng cứ: [kiểm thử](2026-10-23-catalog-readback-compact-verify.json), [API thật](2026-10-23-catalog-one-card-api-live.json), [mô tả và ảnh](../fixes/2026-10-23-catalog-readback-compact-rows.md).
