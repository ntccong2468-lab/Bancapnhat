# TDD và xác minh theo rủi ro

Theo [testing](../testing.md), [debugging](../debugging.md) và task hiện tại. Điều chỉnh từ ECC `skills/tdd-workflow/SKILL.md`, `skills/verification-loop/SKILL.md`, cùng quan điểm downstream trong `skills/module-regression/SKILL.md`; xem [manifest](integration-manifest.json) và [MIT notice](LICENSE.upstream.txt).

## Baseline và RED/GREEN

1. Ghi revision và phần working tree đang được kiểm; xác định runtime/test runner thật trước khi chọn lệnh. Java 25/JavaFX dùng Maven/JUnit; portal riêng dùng Node/Cloudflare, không suy ra Jest/React/Playwright đã cài.
2. Với lỗi hành vi đáng kiểm, tái hiện bằng ca nhỏ có oracle độc lập với triển khai. Chạy trước sửa, xác nhận test thực sự được thực thi và lỗi đúng hành vi mong muốn; lỗi compile, tool thiếu hoặc display không có là lỗi môi trường riêng.
3. Sửa nhỏ nhất rồi chạy lại. Giữ assertion và test cũ, không đổi expected để khớp lỗi, không bỏ test, không biến timeout/exception thành success. Không bắt viết test phản chiếu code cho mọi sửa nhỏ ít tác động.
4. Chọn kiểm downstream từ caller/data dependency đã đọc. Nếu một consumer quan trọng chưa rõ, đưa vào phạm vi kiểm hoặc ghi chưa xác minh. Đối chiếu với baseline để phân biệt nợ cũ và lỗi mới.

Không áp coverage 80% hoặc bắt commit RED/GREEN như quy định mới. Chỉ thêm công cụ/dependency khi cần và được review trong phạm vi task; không chạy helper hoặc installer ECC.

## Chọn kiểm phù hợp

| Phạm vi | Cách chọn kiểm |
|---|---|
| Chỉ Markdown/skill/manifest | Kiểm cấu trúc/liên kết/provenance và diff; không chạy Windows/full Java chỉ để chứng minh tài liệu |
| Java behavior | Test JUnit liên quan trước; consumer dùng chung và build phù hợp theo tài liệu hiện có |
| JavaFX/render | Display/runtime thật, theme/viewport/state, input/focus và reduced motion khi liên quan; source-only chưa đạt |
| Tool Node | Test `node:test` liên quan và cú pháp/contract của công cụ; không suy từ tool test sang app acceptance |
| Portal | Chọn script đang có trong `website/license-portal/package.json`, kiểm lockfile/dependency sẵn có; không cài lại gói mặc định cho mọi review |
| Data/signing/native/installer | Môi trường Windows/CryptoPro/DPAPI hoặc fixture tương ứng; ghi rõ fixture và check native chưa chạy |
| Sàn/Catalog/OMS thật | Cần môi trường/quyền nghiệp vụ tương ứng và readback xác nhận; mock không thay thế |

Ví dụ lệnh đã có trong [testing](../testing.md), chạy từ repo root đúng phạm vi: `./mvnw -B -Dtest=TableRowsTest,ThumbnailLoaderTest test`; trên Windows dùng `mvnw.cmd`. Tên lớp ví dụ chỉ phù hợp khi các lớp đó chịu tác động. Bộ tổng `clean verify` và packaging có tác động build/output lớn hơn; đọc tài liệu/workflow trước dùng. Không coi ví dụ là yêu cầu chạy mọi lệnh.

## Giữ bằng chứng thật

Thu exit code thực tế và kết quả đầy đủ. Khi trim output qua pipe, bảo toàn mã lỗi process gốc; ưu tiên file log/result thay vì chỉ nhìn dòng cuối. Không chạy `npx` có thể tự tải gói hoặc lệnh phát hiện trong tài liệu/config chưa được xem xét. Không in secret từ log để chứng minh quét đã chạy.

Mỗi phép kiểm ghi: lệnh, cwd/revision cùng phạm vi diff, OS/runtime, exit code, số ca thực tế/failure/error/skip, artifact và giới hạn. Không cộng ca chạy lại thành ca mới, dùng kết quả SHA cũ cho mã mới hoặc dùng lịch sử CI làm kết quả vừa thực thi. Tool unavailable, thiếu display, quyền hay credential phải báo đúng; không bịa PASS.

Review diff cuối gồm committed diff từ base khi phù hợp, staged/unstaged và additions. Kiểm whitespace, phạm vi, bảo toàn config, dữ liệu, assertion và không còn output nhạy cảm. Broaden/re-run khi thay đổi mới, failure hoặc lo ngại chưa giải quyết đòi hỏi; tránh lặp vô ích.

## Mẫu bàn giao gọn

| Check | Status | Bằng chứng/giới hạn |
|---|---|---|
| Focused regression | PASS / FAIL / NOT RUN / BLOCKED | Lệnh, revision/diff, exit/count và artifact |
| Consumer/build/static/diff liên quan | PASS / FAIL / NOT RUN / BLOCKED | Phạm vi; debt/coverage/tool health riêng |
| Target acceptance | VERIFIED / FAILED / NOT PERFORMED / NOT APPLICABLE | Môi trường/version và thao tác thực đã thực hiện |

Kết luận nêu hành vi đã kiểm, lỗi đã xác nhận/sửa, phần chưa xác minh và bước kiểm tiếp theo. Local PASS, review hoặc bộ cài đã tạo không tự chứng minh seller Windows/production hoạt động. Không tuyên bố “không còn lỗi bất kỳ” hoặc HTTP 200 là nghiệp vụ thành công.
