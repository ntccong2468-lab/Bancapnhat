---
id: wb-gtin-and-kiz
title: WB GTIN confirmation and pending KIZ
status: active
scope: project:vncode
updated: 2026-10-22
---

# Quyết định WB GTIN và KIZ

## Decision
Mốc nguồn: 1.5.11, ngày 2026-10-22.
GTIN lên WB chỉ được xác nhận khi readback khớp chính xác, không coi ACK là
thành công. Giữ barcode đã có; gửi cả card theo giới hạn 3.000 card/10 MB.
Test fake API cho lô 10.000 GTIN dùng 4 lượt ghi; ACK trễ giữ pending rồi đối soát,
không phát lại yêu cầu chỉ vì chưa có readback. Đây không phải số liệu shop thật.

Catalog đang duyệt và chưa có GTIN tạm thời vẫn có thể mua/gán KIZ khi bản ghi
đăng ký có danh tính đáng tin: đúng shop/product/variant/SKU/GTIN, feed/goodID đã
được chấp nhận, INN/chứng thư/môi trường production và thuộc tính đóng băng khớp.
Từ chối sai phạm vi, sandbox, mơ hồ hoặc bị Catalog từ chối. Không bỏ toàn bộ kiểm
tra danh tính chỉ để vượt lỗi thiếu GTIN.

Mã đã mua chỉ gán khi AVAILABLE và IN_CIRCULATION; RECEIVED vẫn chờ đối soát.
Anchor `pending_registration` không hoạt động/ẩn khỏi Catalog UI, được xác minh
lại trước khi dùng; khi publish thật kích hoạt cùng UUID/pipeline. Giữ mã đã gán,
đơn không cần KIZ, tồn nhập khẩu và điều kiện FBO nghiêm ngặt. Chống mua trùng.

Lý do: cho đơn pending tiếp tục mà không dùng nhầm danh tính/mã chưa lưu thông.
Bằng chứng từ gốc repo: `docs/validation/2026-10-22-wb-10000-audit.md`,
`docs/validation/2026-10-22-kiz-pending-audit.md`.
Liên quan: [chưa nghiệm thu shop thật](../open-loops/production-reconciliation.md),
[tóm tắt](../summary.md).

## Rationale
Các ràng buộc và bằng chứng nêu trên bảo toàn hành vi đã chấp nhận và tránh lặp sai sót.

## Consequences
Báo riêng test mô phỏng và nghiệm thu production. Giữ đối soát/readback và kiểm tra danh tính khi sửa luồng pending.
