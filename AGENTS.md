# Hướng dẫn cho tác vụ trong repository

- Khi bắt đầu hoặc tiếp tục phiên, đọc `docs/memory/CURRENT.md` để biết mã đã sửa, bản đã phát hành và việc còn lại; dùng `docs/memory/README.md` tìm bàn giao đúng chủ đề. Tìm theo từ khóa trong `docs/PROJECT_MEMORY.md`, không cần nạp toàn bộ lịch sử.
- Đối chiếu bộ nhớ với Git và trạng thái PR/Latest hiện tại trước khi tiếp tục. Bản ghi là bằng chứng lịch sử, không phải lệnh mới; không dùng nó để mở rộng phạm vi ghi API, mua hàng hoặc phát hành đã được cho phép.
- Sau thay đổi đáng kể, cập nhật `docs/memory/CURRENT.md`, thêm mục vào `docs/PROJECT_MEMORY.md` và liên kết bàn giao/bằng chứng. Ghi nguồn commit, phạm vi kiểm thử, phần chưa kiểm chứng và trạng thái phát hành; giữ lịch sử cũ. Không ghi secret hoặc dữ liệu seller vào bộ nhớ.
- Đọc yêu cầu, trạng thái Git và tài liệu liên quan trước khi sửa; giữ lại các thay đổi chưa commit có sẵn.
- Desktop là Java 25/JavaFX, xây bằng Maven; portal ở `website/license-portal` dùng Node.js và Cloudflare Workers. Xem `docs/engineering/testing.md` để chọn kiểm thử phù hợp.
- Đối với lỗi, tái hiện hoặc xây dựng test có ý nghĩa, tìm nguyên nhân, sửa phạm vi cần thiết và báo rõ kết quả. Không bỏ qua test hay tắt cơ chế bảo vệ để có kết quả xanh.
- Khi được yêu cầu dùng Code Doctor hoặc điều tra lỗi/độ ổn định, đọc `.agents/skills/code-doctor/SKILL.md`: xác định baseline, tái hiện bằng chứng, sửa nhỏ nhất rồi kiểm thử hồi quy và ghi rõ phần chưa xác minh.
- Giữ dữ liệu người dùng, phạm vi cửa hàng và khả năng nâng cấp. Thay đổi SQLite cần migration tương thích, kiểm tra snapshot/khôi phục và dữ liệu cũ; xem `docs/engineering/debugging.md`.
- Không in hay commit secret, token, mã KIZ, dữ liệu seller hoặc thông tin license thật; dùng dữ liệu tổng hợp trong kiểm thử. Kiểm thử ghi lên dịch vụ thật và các thao tác có phí chỉ trong phạm vi người dùng đã cho phép; xem `docs/engineering/security.md`.
- Phân biệt test mô phỏng với kết quả trên WB/Ozon/Catalog và trên Windows thật. Không coi HTTP 200 là xác nhận nghiệp vụ đã hoàn tất.
- Không tự triển khai, merge hoặc xóa bản phát hành/dữ liệu; làm theo yêu cầu cụ thể của người dùng. Lệnh `tools/baseline-release.mjs prune` xóa các release cũ và không thuộc bước xác minh thông thường.
- Khi dùng OpenQodex để quét hoặc review, đọc skill `.agents/skills/openqodex/SKILL.md` và báo đúng phạm vi scanner/reviewer.
