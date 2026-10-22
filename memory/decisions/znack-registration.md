---
id: znack-registration
title: Znack registration UI and states
status: active
scope: project:vncode
updated: 2026-10-22
---

# Quyết định đăng ký Znack

## Decision
Ngày quyết định/bằng chứng: 2026-10-22. Giữ toàn bộ giao diện và nút thao tác
của luồng 1.5.7 khi thay nghiệp vụ; bản sửa đã nằm trong nguồn 1.5.11.

- Đăng ký gửi yêu cầu thẻ thật qua adapter; phân biệt gửi được và được Catalog duyệt.
- Chưa tạo: chưa tạo hoặc chưa xác nhận được GTIN trên Catalog.
- Đang xử lý: hiện thẻ vừa đăng ký đang chờ dịch vụ.
- Lỗi: chỉ Catalog từ chối; cho sửa rồi Áp dụng gửi lại.
- Dữ liệu chưa rõ: hiển thị đề xuất app điền sẵn; seller kiểm tra và Áp dụng.
- Timeout/ACK chưa rõ không bị gán thành từ chối và không được gửi lặp mù quáng.

Lý do: giữ thao tác seller quen dùng và phản ánh đúng trạng thái phía dịch vụ.
Bằng chứng từ gốc repo: `docs/validation/2026-10-22-znack-registration-filters-proposals.md`.
Liên quan: [GTIN/KIZ](wb-gtin-and-kiz.md), [tóm tắt](../summary.md).

## Rationale
Các ràng buộc và bằng chứng nêu trên bảo toàn hành vi đã chấp nhận và tránh lặp sai sót.

## Consequences
Khi sửa tiếp, kiểm tra trạng thái dịch vụ và đề xuất thay vì đổi giao diện hoặc gửi lại yêu cầu chưa rõ.
