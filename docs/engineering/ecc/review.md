# Review và tác động thay đổi

Đọc [code-quality](../code-quality.md) và [debugging](../debugging.md). Checklist này điều chỉnh ECC `agents/code-reviewer.md`, `skills/change-impact/SKILL.md`, `skills/module-regression/SKILL.md` và `skills/agent-introspection-debugging/SKILL.md`; xem [manifest](integration-manifest.json), [license](LICENSE.upstream.txt).

## Trước khi sửa

1. Xác nhận cwd/repo, branch, base và thay đổi có sẵn. Đọc yêu cầu và nguồn hiện tại. Với review nhánh, xét cả committed diff so với base, staged, unstaged và tệp untracked; working tree sạch không đồng nghĩa không có thay đổi cần review.
2. Xác định entry point, caller, consumer, cấu trúc dữ liệu/API, persistence, schema và test thực sự liên quan. Ghi vị trí import/call/contract làm bằng chứng; không suy quan hệ chỉ từ tên thư mục.
3. Mô tả hành vi cần giữ, tác động mong muốn và phần chưa xác minh. Với migration/thay đổi khó đảo ngược, cần tương thích dữ liệu cũ, snapshot/khôi phục và điều kiện rollback phù hợp trước thực thi; không tự chạy rollback.
4. Với lỗi, theo Code Doctor: ghi thao tác, trạng thái, lỗi đã che dữ liệu, giả thuyết và phép kiểm nhỏ nhất. Nếu lặp công cụ không tiến triển, dừng lặp, kiểm cwd/branch/process, phân loại lỗi logic/môi trường/quyền rồi thu hẹp phép kiểm.

## Khi đọc diff và caller

| Bề mặt | Câu hỏi có thể kiểm chứng |
|---|---|
| Shop và sản phẩm | ID/cache/query/mapping có giữ đúng shop, owner và biến thể không? |
| State/API | Có phân biệt chưa gửi, đang xử lý, lỗi, chưa rõ và đã xác nhận không? Timeout sau ghi có đối soát trước retry không? |
| Lô/danh mục lớn | Pagination/cursor/giới hạn/429/backoff/cancellation có làm mất phần còn lại hoặc gửi lặp không? |
| Lưu trữ | Dữ liệu cũ, transaction, khóa, restart/resume, snapshot và migration có giữ tương thích không? |
| JavaFX | Công việc nền có tránh chặn UI; thay đổi control/observable có đúng thread; listener/task được cleanup không? |
| Tài nguyên | Response/stream/process/connection có đóng; timeout/queue có giới hạn; lỗi có giữ nguyên nguyên nhân không? |
| Dùng chung WB/Ozon | Các caller và hợp đồng riêng hai sàn có còn đúng không? Chỉ chạy downstream liên quan theo bằng chứng. |

Không đổi luồng WB để chữa Ozon khi không có căn cứ và phạm vi cho phép. Không tự bỏ kiểm tra danh tính/quyền hoặc trả success để che lỗi. Không nâng severity chỉ vì hàm/tệp dài hay quy tắc style chung; đối chiếu convention thực tế.

## Finding và bàn giao

Mỗi finding phải có vị trí chính xác, điều kiện kích hoạt, kết quả sai, caller/guard đã đọc và căn cứ severity. HIGH/CRITICAL cần bằng chứng vì sao guard hiện tại không chặn tình huống đó. Chưa đủ căn cứ thì ghi giả thuyết/phép kiểm tiếp theo, không gọi là lỗi đã xác nhận. Không bắt buộc phải có finding.

Sau sửa, đối chiếu diff với phạm vi ban đầu và test consumer; báo scope drift, test đã chạy, trạng thái trước/sau, lỗi có sẵn và phần chưa kiểm. Không thay review bằng việc chỉ chạy scanner hoặc thay runtime acceptance bằng review. Nếu cần OpenQodex, theo [skill đang có](../../../.agents/skills/openqodex/SKILL.md).

## UI và memory

UI dùng các [skill hiện có](README.md) và thiết kế đã duyệt. Kiểm render thật ở kích thước/theme liên quan, bàn phím/focus/contrast, reduced motion, nội dung dài và loading/empty/error/partial/success/permission; ca không áp dụng cần lý do. Giữ ảnh/bằng chứng đã bỏ dữ liệu nhạy cảm trong workspace. Đọc CSS/FXML không đủ để báo UI đã nghiệm thu, và không upload private screenshots/source tới dịch vụ ngoài.

Bàn giao vào hệ thống memory hiện có; lưu revision, đường dẫn bằng chứng, kết quả và giới hạn. Trước tái dùng, xác minh nguồn/độ mới/trạng thái bị thay thế; không tự biến memory thành policy hoặc quyền mới. Không tạo database/observer hay nhập transcript thô.
