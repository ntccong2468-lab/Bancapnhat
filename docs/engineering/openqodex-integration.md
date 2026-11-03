# Tích hợp OpenQodex cho Codex Web

## Phạm vi và kiến trúc

VN Code có ứng dụng desktop Java 25/JavaFX, Maven và SQLite; portal license tại `website/license-portal` dùng JavaScript, Node.js và Cloudflare Workers. Kiểm thử gồm JUnit/JavaFX, công cụ Node và test portal. Các kết nối WB, Ozon, National Catalog, CryptoPro và license có trạng thái, dữ liệu hoặc credential riêng; kiểm thử ở đây không ghi lên các hệ thống đó.

Nhánh tích hợp này dựa trên `audit/stability-20261101`, commit `042b6d9`, thuộc [PR #14](https://github.com/ntccong2468-lab/Bancapnhat/pull/14) đang chờ review. PR #14 chưa merge vào nhánh release hay mặc định: không coi `AGENTS.md`, security workflow hoặc các sửa trước đó đã có trong phiên bản phát hành. Một PR dựa trên nhánh audit cần được review cùng mối phụ thuộc đó.

## Đánh giá OpenQodex

Nguồn đã đối chiếu là [OpenQodex 0.16.0](https://github.com/openqodex/openqodex/tree/6d879d65de94ab201bddd9ccad76e1d1bcff70ca), commit `6d879d65de94ab201bddd9ccad76e1d1bcff70ca`, giấy phép Apache-2.0. Mã nguồn là monorepo TypeScript/Node.js, dùng pnpm; CLI yêu cầu Node.js 22 trở lên. Xem [CLI](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/cli.md), [danh sách scanner](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/scanners.md) và [hướng dẫn Action](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/github-action.md).

Gói CLI npm đã kiểm tra không khai báo dependency runtime và được đóng gói sẵn; các scanner vẫn là công cụ riêng do OpenQodex chọn và tải. Hash tarball đã khớp metadata npm nhưng điều này không xác minh độc lập provenance giữa gói npm và mã nguồn GitHub.

Upstream hỗ trợ chạy reviewer riêng bằng Codex CLI hoặc Claude Code. `codex` có mặt trong môi trường này, nhưng chưa xác minh được tiến trình reviewer lồng, quyền truy cập mô hình hoặc kết quả review hoàn chỉnh trong Codex Web. Vì vậy không gọi kết quả `scan` là AI review độc lập. GitHub Action cấu hình `review: off` chỉ chạy scanner; không sử dụng hay cấu hình khóa Anthropic. Không chạy `init`, `trust` hoặc cài hook tự động.

Action và phiên bản CLI được ghim riêng; Action cài các scanner upstream chọn và cần mạng, nên thực tế còn phụ thuộc runner và nguồn tải. Rule pack Semgrep lấy từ registry tại lúc chạy, không được pin bởi OpenQodex. OSV có thể gửi tên và phiên bản dependency tới osv.dev; chế độ `--no-resolve` với Maven không chứng minh đã kiểm tra đầy đủ dependency bắc cầu. `--offline` bỏ qua Semgrep và OSV. Không đặt credential hoặc mã KIZ trong source, fixture, log hay artifact.

## Workflow và phạm vi kiểm tra

`.github/workflows/security-ci.yml` giữ các kiểm tra CodeQL, test Node, portal và npm audit đã có; bổ sung job OpenQodex scanner-only trên pull request và push tới nhánh phát triển. Action được ghim commit nguồn, đặt `review: off`, `upload-sarif: false`, `block-on-severity: major`, `fail-on-tool-error: true` và dùng cấu hình base khi kiểm tra pull request. Theo upstream, ngưỡng này xét các finding trên dòng thay đổi; các advisory dependency ngoài dòng thay đổi không quyết định verdict. Đây không phải quét đầy đủ mọi dòng repository.

Một scanner con có thể thất bại hoặc bị thiếu dù OpenQodex trả exit 0. `tools/openqodex-health-gate.mjs` kiểm tra báo cáo scanner được chọn và thất bại khi kết quả không đầy đủ hoặc không đọc được; không thay thế việc xem xét từng finding. Artifact `openqodex-scanner-health` chỉ chứa metadata đã giảm thiểu, gồm trạng thái và số lượng, không chứa nguyên văn finding, snippet, đường dẫn hay secret. CodeQL tiếp tục lưu SARIF theo cấu hình sẵn có; cần quản lý quyền truy cập artifact phù hợp.

Gate cố ý báo chưa đạt nếu scanner bị tắt, báo lỗi một phần, hoặc không có báo cáo; diff rỗng hợp lệ có thể bị chặn và cần được xem xét. Đây là chính sách fail-closed, không tự chứng minh mọi trường hợp đó đều là lỗi scanner. Workflow trong PR có thể bị chính tác giả PR thay đổi; cần review workflow và cài đặt bảo vệ nhánh phù hợp nếu muốn bắt buộc các kiểm tra.

Workflow có lịch chạy CodeQL trên checkout mặc định, trong phạm vi ngôn ngữ và tệp mà CodeQL trích xuất được. GitHub chỉ kích hoạt lịch từ workflow trên nhánh mặc định; lịch này chưa có hiệu lực ở nhánh mặc định nếu thay đổi chưa được merge vào đó. Không chạy OpenQodex diff scan trên lịch như thể đó là một lượt quét toàn bộ. Các test và build hiện có vẫn cần chạy độc lập.

Skill tại `.agents/skills/openqodex/SKILL.md` mô tả cách dùng và cách báo giới hạn; nó là adapter viết cho repository, không phải bằng chứng Codex đã nạp skill trong phiên hiện tại. Không thay thế các skill đang có.

## Bằng chứng và giới hạn

Trong Codex Cloud, CLI OpenQodex 0.16.0 đã được thử offline với fixture SQL giả: `sqllint` báo một finding và exit 1 khi ngưỡng chặn là `major`; mẫu sạch exit 0. Một lượt chọn Semgrep với `--offline` ghi scanner bị disabled nhưng vẫn exit 0, xác nhận không thể dựa riêng vào exit code để kết luận mọi scanner đã chạy. Đây chỉ là kiểm tra hành vi trên fixture, không chứng minh toàn bộ scanner, AI reviewer hay GitHub Action đã chạy thành công.

Trên commit `6e72124`, OpenQodex CLI đã chạy Gitleaks 8.21.2 đã kiểm SHA-256 trên sáu tệp thay đổi so với nhánh audit, không có candidate. GitHub Action scanner-only của PR #15 cũng đã chạy: 6 scanner hoàn thành, 16 không có tệp phù hợp, 1 candidate mức minor và 5 advisory dependency ngoài dòng thay đổi; gate sức khỏe đạt và artifact tóm tắt được tải lên. Candidate và advisory chưa được phân tích chi tiết, và đây không phải xác nhận toàn repository không có lỗi. Các kiểm thử/CI khác trong PR có thể có trạng thái riêng.

Ở thời điểm thử cục bộ: 134/134 kiểm thử công cụ Node và 67/67 kiểm thử portal đạt; `npm run check`, `npm audit --package-lock-only` và actionlint đạt. Maven `-DskipTests package` trên bản sao Linux dùng JDK 25 thành công; 21/21 test Java chọn lọc cho wrapper, TN VED, rollback PDF Ozon và hủy cập nhật đạt. Đây không phải toàn bộ test Java hoặc xác minh Windows.

Không công cụ nào trong cấu hình này bảo đảm phát hiện hết lỗi logic nghiệp vụ, race, rò bộ nhớ, hiệu năng, cấu hình hay lỗ hổng bảo mật. Cần đối chiếu finding với mã, tái hiện khi khả thi, sửa tối thiểu và chạy test hồi quy. Kết quả mô phỏng không chứng minh tương thích Windows/CryptoPro hoặc ghi thành công lên Catalog, WB và Ozon thật.
