---
id: production-reconciliation
title: Real shop GTIN and KIZ acceptance remains unverified
status: active
scope: project:vncode
updated: 2026-10-22
---

# Đối soát production chưa hoàn tất

## Owner
chủ repo/seller có quyền shop

## Opened
2026-10-22

## Next trigger
có số liệu/môi trường shop được phép đọc và nghiệm thu.

Mpp fashion: chưa đọc được tổng/đã xác nhận/đang chờ/lỗi thực tế. Lô 10.000 là
kiểm thử pipeline với API mô phỏng; không khẳng định đã gửi đủ lên shop thật.
Chưa thực hiện mua KIZ trả phí, ký chứng thư hoặc ghi dữ liệu seller thật trong cloud.

Cần: báo cáo số liệu có thời điểm và phạm vi shop, readback GTIN; kiểm tra đơn
Catalog pending mua/gán KIZ đúng danh tính, tránh trùng và đối soát mã lưu thông.
Chỉ thực hiện giao dịch thật theo ủy quyền phù hợp của người dùng tại thời điểm đó.
Không đưa bí mật/mã KIZ/thông tin khách hàng vào record.

## Why it matters
test xanh không thay thế nghiệm thu API production.
Liên quan: [quyết định GTIN/KIZ](../decisions/wb-gtin-and-kiz.md), [tóm tắt](../summary.md).
