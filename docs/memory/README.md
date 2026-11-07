# Bộ nhớ dự án dành cho AI

Bộ nhớ này được lưu cùng mã nguồn trong Git. AI mở repository/nhánh có các tệp này được hướng dẫn đọc lại qua [AGENTS.md](../../AGENTS.md). Đây không phải bộ nhớ tài khoản ChatGPT hay lời bảo đảm mọi phiên tự truy cập được repository.

## Đọc phần cần thiết

1. Đọc [CURRENT.md](CURRENT.md) để biết nhánh đang làm, mã đã sửa, Latest và phần chưa hoàn tất.
2. Đọc bàn giao đúng chủ đề ở bảng dưới. Dùng `rg` tìm từ khóa trong [PROJECT_MEMORY.md](../PROJECT_MEMORY.md) nếu cần lịch sử cũ; các mục “chưa phát hành” cũ mô tả thời điểm ghi, phải đối chiếu mục mới và biên nhận phát hành.
3. Kiểm tra trạng thái Git, PR và release hiện tại trước khi sửa hoặc phát hành. Bộ nhớ không thay thế phản hồi API mới, không tự mở rộng quyền thao tác thật của người dùng.

| Chủ đề | Tài liệu gốc |
| --- | --- |
| Tổng hợp chỉnh sửa, Code Doctor/soi code, phần thiếu và chưa phát hành ngày 07.11 | [Danh mục đối chiếu](../validation/2026-11-07-change-inventory.md) |
| Toàn bộ lịch sử sửa, quyết định, kinh nghiệm | [PROJECT_MEMORY.md](../PROJECT_MEMORY.md) |
| KIZ mapping mua KIZ: API thật, bỏ chặn khác TN VED WB/Ozon | [Báo cáo/sơ đồ](../validation/2026-11-07-kiz-mapping-live-audit.md), [HTTP thật](../validation/kiz-mapping-20261107/live-api.json), [assertion](../validation/kiz-mapping-20261107/real-regression.json), [build](../validation/kiz-mapping-20261107/build.json) |
| Năm nút sửa sản phẩm, WB POST thật và giấy tờ mặc định tuyên bố | [Báo cáo](../validation/2026-11-06-products-five-audit.md), [5 ghi thật](../validation/2026-11-06-products-five-live.json), [3.880 ca và giới hạn stress](../validation/products-five-20261106/tests.json), [ảnh](../validation/assets/products-five-20261106/configured-declaration.png) |
| Code Doctor API thật, bốn bộ lọc Znack và GTIN WB không so TN VED | [Bàn giao](../validation/2026-11-06-znack-four-states-wb-gtin.md), [API thật](../validation/2026-11-06-code-doctor-live.json), [330 ca](../validation/2026-11-06-znack-four-states-tests.json) |
| Code Doctor: cài skill, sửa kẹt đồng bộ Ozon và chạy JAR | [Cách dùng](../engineering/code-doctor.md), [báo cáo](../validation/2026-11-06-code-doctor.md), [507 ca/25 lớp](../validation/2026-11-06-code-doctor-tests.json) |
| Sửa PDF Ozon thiếu nhãn kết hợp và phát hành gộp Znack 1.6.4 | [Bàn giao](../validation/2026-11-05-ozon-combined-handoff.md), [191 test](../validation/2026-11-05-ozon-combined-tests.json) |
| Latest 1.6.4: Znack + PDF, Windows/nâng cấp và updater đã xác minh | [Bàn giao 1.6.4](../validation/2026-11-05-vncode-164-handoff.md), [đối chiếu nguồn](../validation/2026-11-05-vncode-164-source-inclusion.json) |
| Znack, nhận diện Catalog, GTIN WB, sơ đồ WBCode/VN Code | [So sánh](../research/2026-11-05-wbcode-1318-vncode-znack.md), [bàn giao](../validation/2026-11-05-znack-reconcile-handoff.md) |
| Nhận đơn Ozon, KIZ, phiếu nhặt hàng | [Bàn giao Ozon](../validation/2026-11-04-ozon-accept-picking-handoff.md), [định dạng KIZ](../research/2026-11-04-ozon-kiz-input.md) |
| PDF Ozon theo lô, cache, giới hạn benchmark | [Bàn giao PDF](../validation/2026-11-03-ozon-batch-pdf-audit.md) |
| Sửa thông tin sản phẩm và nghiệm thu Catalog bằng API | [Biên bản ghi thật](../validation/2026-11-03-product-edit-catalog-live-acceptance.md) |
| Giao diện native đã duyệt, ảnh sáng/tối | [Ảnh 1.6.3](../design/native/2026-11-04/README.md), [kiểm tra UI UX Pro Max](../validation/2026-11-03-ui-ux-pro-max/README.md) |
| 1.6.3: bản phát hành hiện tại lúc ghi | [Bàn giao và bằng chứng](../validation/2026-11-04-vncode-163-handoff.md) |
| 1.6.2 / 1.6.1 / 1.6.0 | [1.6.2](../validation/2026-11-04-vncode-162-handoff.md), [1.6.1](../validation/2026-11-03-vncode-161-handoff.md), [1.6.0](../validation/2026-10-31-vncode-160-handoff.md) |
| License, mua KIZ, mapping, lưu cache và các sửa trước 1.6.x | Tìm đúng mục trong [lịch sử dự án](../PROJECT_MEMORY.md); xem bàn giao được liên kết tại đó |
| Prototype mã hóa credential — chưa đưa vào runtime | [Bàn giao prototype](../engineering/prototypes/credential-java-handoff.md) |

## Repo memory đã tìm được

**Đề xuất: [Codex Memory Brain](https://github.com/ccycv/codex-memory-brain)** — giấy phép MIT. Đã đọc README và kiểm tra metadata công khai ngày 05.11.2026; HEAD được quan sát là `a93df08868dea5afe8c91e7810e00943f5a5991a`.

Theo README, công cụ lưu bộ nhớ cục bộ bằng SQLite/FTS5, hỗ trợ tìm kiếm theo repository, checkpoint tác vụ và tiếp tục dự án qua MCP. Các công cụ được mô tả gồm `memory_context`, `memory_search`, `memory_remember`, `memory_task_checkpoint`, `memory_resume_project`. Đây là ứng viên phù hợp để tìm kiếm bộ nhớ khi client Codex cho phép cấu hình MCP.

**Trạng thái thực tế:** chưa chạy installer, chưa cài hoặc kết nối MCP đó trong Codex Web; chưa gửi tài liệu VN Code vào công cụ ngoài. Bộ nhớ đang hoạt động ở mức tệp Git và hướng dẫn AGENTS. Nếu cài về sau, đọc/review mã và cách lưu dữ liệu ở revision đã chọn trước khi chạy; dùng hồ sơ đã bỏ dữ liệu nhạy cảm làm nguồn, giữ Markdown trong Git làm bản bàn giao có thể kiểm tra. README không thay thế kiểm thử khả năng tích hợp thực tế.

Tham khảo thêm: [MCP Memory Server chính thức](https://github.com/modelcontextprotocol/servers/tree/main/src/memory), lưu knowledge graph cục bộ; cũng cần client cấu hình MCP. Không cần cài cả hai để sử dụng các tệp bộ nhớ trong repo hiện tại.

## Cập nhật sau mỗi phần việc

- Giữ `CURRENT.md` ngắn, chỉ phản ánh trạng thái mới nhất: mục tiêu, nhánh/commit nghiệp vụ, PR, Latest, phần đã sửa, bằng chứng và việc còn lại. Ghi thời điểm có múi giờ.
- Thêm mục mới vào `PROJECT_MEMORY.md` và liên kết bàn giao; không xóa lịch sử để làm đẹp kết quả. Ghi cả quyết định và lý do, lỗi đã gặp, cách tránh lặp lại.
- Phân biệt kiểm thử mô phỏng, đọc API thật, ghi API thật, chạy Windows và bản thực sự đã công bố. HTTP 200/receipt không tự chứng minh thành công nghiệp vụ.
- Không lưu API key, token, mã KIZ, thông tin license hoặc danh tính seller thật. Không đưa raw response/PDF/journal riêng vào Git; chỉ giữ số liệu và bằng chứng đã loại dữ liệu nhạy cảm. Đường dẫn `/tmp` không bảo đảm tồn tại qua phiên.
- Khi chỉ sửa tài liệu bộ nhớ, kiểm tra liên kết, mốc commit/PR/release và diff; không cần chạy lại toàn bộ kiểm thử ứng dụng.
