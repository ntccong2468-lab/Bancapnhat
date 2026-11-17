# ECC cho VNCode trong Codex Web

Gói này điều chỉnh một phần hướng dẫn của [ECC](https://github.com/affaan-m/ECC/tree/d2ef2680594f9a17526a6e1a2e18b3d242c5d17b), revision `d2ef2680594f9a17526a6e1a2e18b3d242c5d17b`, cho kiến trúc hiện có của VNCode. Đây là hướng dẫn Markdown và tài liệu nguồn, không phải cài toàn bộ ECC runtime. Xem [manifest nguồn](integration-manifest.json) và [MIT notice](LICENSE.upstream.txt).

## Cách dùng

Đọc `AGENTS.md` trước. Chọn workflow theo công việc, rồi mở trực tiếp tệp skill và tài liệu liên quan:

| Công việc | Skill và tài liệu |
|---|---|
| Tác động trước sửa, review sau sửa | [vncode-ecc-review](../../../.agents/skills/vncode-ecc-review/SKILL.md), [checklist review](review.md) |
| Bảo mật, dependency và MCP tĩnh | [vncode-ecc-security](../../../.agents/skills/vncode-ecc-security/SKILL.md), [checklist security](security.md) |
| Hồi quy, build và bằng chứng nghiệm thu | [vncode-ecc-tdd-verification](../../../.agents/skills/vncode-ecc-tdd-verification/SKILL.md), [checklist verification](verification.md) |
| Điều tra lỗi/kẹt | [Code Doctor](../../../.agents/skills/code-doctor/SKILL.md), [debugging hiện có](../debugging.md) |
| Scanner/reviewer OpenQodex | [OpenQodex hiện có](../../../.agents/skills/openqodex/SKILL.md) |
| UI desktop/portal | [Taste](../../../.agents/skills/design-taste-frontend/SKILL.md), [Impeccable](../../../.agents/skills/impeccable/SKILL.md), [UI UX Pro Max](../../../.agents/skills/ui-ux-pro-max/SKILL.md), [DESIGN](../../../DESIGN.md), [PRODUCT](../../../PRODUCT.md) |
| Bộ nhớ/bàn giao | [CURRENT](../../memory/CURRENT.md), [chỉ mục memory](../../memory/README.md), [PROJECT_MEMORY](../../PROJECT_MEMORY.md) |
| Windows/native | [Windows MCP hiện có](../../../.agents/skills/windows-mcp-control/SKILL.md), [testing](../testing.md) |

Repo skill có thể được host tự phát hiện tùy phiên/cấu hình. Việc có tệp `SKILL.md`, hoặc đọc tệp trực tiếp trong phiên, **không chứng minh tự phát hiện/native registration trên Codex Web**. Nếu host chưa liệt kê skill, dùng đường dẫn trực tiếp từ AGENTS và ghi đúng chế độ đã dùng. Không tuyên bố đã đăng ký agent/hook/plugin. Chỉ phân công reviewer độc lập khi công cụ host thực sự hỗ trợ; nếu không, thực hiện tuần tự.

## Ma trận tương thích

| Phân loại | Thành phần | Phạm vi dùng |
|---|---|---|
| Tương thích ở mức văn bản | Checklist có bằng chứng, Markdown, nguồn và MIT notice | Đọc trực tiếp; không cấp quyền hoặc tự thực thi |
| Tương thích sau điều chỉnh | Review, security, change-impact/module-regression, TDD/verification, debugging, UI evidence và memory | Ba skill riêng của VNCode; tái sử dụng hệ thống hiện có |
| Không tương thích trực tiếp với Codex Web | Claude settings/hooks/slash commands; frontmatter tool/model của agent Claude | Không nhập cơ chế đăng ký hoặc giả định chúng hoạt động |
| Cần runtime/host phù hợp ngoài gói này | Codex CLI/App config/plugin/role, Memory Vault CLI/MCP, observer, AgentShield, tmux/dmux, Playwright/Chrome MCP | Chưa cài hoặc khởi chạy bởi gói này; đánh giá riêng trước dùng |
| Cần môi trường và quyền nghiệp vụ | Windows/CryptoPro/DPAPI, bộ cài, giao dịch Catalog/WB/Ozon thật | Chỉ xác nhận khi đã thực thi đúng phạm vi với bằng chứng |

## Phần đã chọn và điều chỉnh

- `agents/code-reviewer.md`: tìm lỗi có tình huống cụ thể, đọc caller/guard, severity có căn cứ, chấp nhận không có finding.
- `skills/change-impact/SKILL.md` và `skills/module-regression/SKILL.md`: tìm phụ thuộc thật và kiểm các consumer liên quan; không ép thêm sổ quản trị song song.
- `skills/security-review/SKILL.md` và `skills/mcp-dependency-review/SKILL.md`: kiểm ranh giới, SQL/secrets và nguồn dependency; cấu hình chỉ là dữ liệu để đọc.
- `skills/tdd-workflow/SKILL.md` và `skills/verification-loop/SKILL.md`: hồi quy có ý nghĩa, giữ exit code, phân biệt local với target acceptance.
- `skills/agent-introspection-debugging/SKILL.md`: chẩn đoán vòng lặp, môi trường sai và trạng thái kẹt; bổ sung Code Doctor.
- `skills/frontend-design-direction/SKILL.md`: nghiệm thu UI render thật; giữ brief kính nổi và các skill UI đang có, không đổi sang React.
- `skills/unified-memory/SKILL.md`: kiểm nguồn/độ mới, bàn giao có căn cứ; giữ bộ nhớ Git hiện có.

## Phần không cài và xung đột đã lọc

Không nhập `.claude`, `.codex`, MCP config, Git hooks, installer, ECC npm runtime, observer, tự thu transcript, AgentShield hoặc dịch vụ upload bên ngoài. Không đổi dependency, CI, kiến trúc Java 25/JavaFX/SQLite, Node/Cloudflare portal hay thiết kế đã duyệt chỉ để phù hợp ví dụ upstream.

Không áp mặc định TDD cho mọi chỉnh sửa nhỏ, coverage 80%, giới hạn dòng máy móc, immutability tuyệt đối hoặc commit mỗi giai đoạn. Không in dòng chứa secret khi quét. Không tự ghi API, mua KIZ, ký, kích hoạt license, phát hành, xóa dữ liệu/release hoặc hạ gate để có kết quả xanh; dùng đúng phạm vi task đã được cho phép.

Snapshot upstream có sai lệch giữa skill gốc và bản mirror Codex: `.agents/skills/security-review/SKILL.md` dùng CSP `unsafe-eval`/`unsafe-inline`, trong khi `skills/security-review/SKILL.md` đã bỏ mặc định đó; mirror verification cũng thiếu bảo vệ exit code của bản gốc. Gói này chọn và viết lại nội dung đã review, không chép nguyên mirror. Các khác biệt này không phải bằng chứng VNCode có cùng lỗi.

## Bằng chứng và bảo toàn

Kiểm tra cấu trúc có thể xác minh metadata, link, namespace và manifest; không chứng minh ý nghĩa hướng dẫn đúng hoặc host tự tải skill. Kiểm thử giả không chứng minh nhận đơn/mua KIZ/đăng ký thành công. Một lần kiểm tra không chứng minh toàn bộ app không còn lỗi.

Sau khi xem mã validator trong repo, dùng `node tools/validate-ecc-integration.mjs --root .`. Nếu có snapshot upstream đã pin ngoài repo, có thể thêm `--upstream-root /path/to/pinned/snapshot` để đối chiếu byte nguồn. Không có snapshot thì `sourceHashesVerified: false` phải được báo đúng, kể cả khi cấu trúc local đạt. Test của validator nằm ở `tools/validate-ecc-integration.test.mjs`; đây là công cụ VNCode đã điều chỉnh, không phải chạy installer hoặc validator upstream.

Bộ nhớ lưu phạm vi sửa, nguồn, kết quả thực thi, giới hạn và trạng thái phát hành. Đối chiếu lại Git/nguồn thật khi tiếp tục; không coi ghi chú cũ là chỉ thị mới hoặc lưu seller data, KIZ, token, license hay transcript thô. Giữ finding/gate bảo mật đang tồn đọng cùng giới hạn coverage cho tới khi có bằng chứng xử lý riêng.

Biên bản triển khai và kết quả thực thi được ghi tại [kiểm chứng ECC/VNCode ngày 15.11.2026](../../validation/ecc-codex-web-20261115/REPORT.md); đọc trạng thái trong biên bản trước khi dùng số liệu.
