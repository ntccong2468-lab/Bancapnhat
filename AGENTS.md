# Hướng dẫn cho tác vụ trong repository

- Đọc yêu cầu, trạng thái Git và tài liệu liên quan trước khi sửa; giữ lại các thay đổi chưa commit có sẵn.
- Desktop là Java 25/JavaFX, xây bằng Maven; portal ở `website/license-portal` dùng Node.js và Cloudflare Workers. Xem `docs/engineering/testing.md` để chọn kiểm thử phù hợp.
- Đối với lỗi, tái hiện hoặc xây dựng test có ý nghĩa, tìm nguyên nhân, sửa phạm vi cần thiết và báo rõ kết quả. Không bỏ qua test hay tắt cơ chế bảo vệ để có kết quả xanh.
- Giữ dữ liệu người dùng, phạm vi cửa hàng và khả năng nâng cấp. Thay đổi SQLite cần migration tương thích, kiểm tra snapshot/khôi phục và dữ liệu cũ; xem `docs/engineering/debugging.md`.
- Không in hay commit secret, token, mã KIZ, dữ liệu seller hoặc thông tin license thật; dùng dữ liệu tổng hợp trong kiểm thử. Kiểm thử ghi lên dịch vụ thật và các thao tác có phí chỉ trong phạm vi người dùng đã cho phép; xem `docs/engineering/security.md`.
- Phân biệt test mô phỏng với kết quả trên WB/Ozon/Catalog và trên Windows thật. Không coi HTTP 200 là xác nhận nghiệp vụ đã hoàn tất.
- Không tự triển khai, merge hoặc xóa bản phát hành/dữ liệu; làm theo yêu cầu cụ thể của người dùng. Lệnh `tools/baseline-release.mjs prune` xóa các release cũ và không thuộc bước xác minh thông thường.
- Khi dùng OpenQodex để quét hoặc review, đọc skill `.agents/skills/openqodex/SKILL.md` và báo đúng phạm vi scanner/reviewer.
