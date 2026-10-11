# VN code 1.4.0 — Điều phối bản cập nhật Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Triển khai toàn bộ thiết kế Sản phẩm/GS1/sao chép/tin/FBS trong một bản nâng cấp VN code.

**Architecture:** Bốn kế hoạch theo module, cùng nhánh và một bản phát hành. Module đọc/cache có thể nghiệm thu riêng; sao chép sử dụng snapshot sản phẩm; kiểm tra Windows là cổng phát hành cuối.

**Tech Stack:** Java 25, JavaFX 25, SQLite/JDBC, Gson, OkHttp, JUnit 5 và MockWebServer; toolchain hiện có.

**Spec:** [Thiết kế đã duyệt](../specs/2026-10-11-vn-code-products-copy-design.md), commit `e8c900a76377685324cd3cc43bd30c098fb7e338`; người dùng duyệt ngày 11/10/2026.

## Global Constraints

- Cùng ứng dụng VN code Windows; Java 25/JavaFX, Maven; không thêm runtime desktop/web thứ hai.
- Giữ appdata, template, lịch sử và UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`; không sửa installer/tag đã phát hành.
- Logo VN trắng nền đen, tông tối/tím; TN VED chỉ trong tác vụ, không có mục sidebar; copy mới có đủ VI/RU/EN/ZH.
- Shop/marketplace/INN/môi trường được xác minh trước đọc/ghi; async result chỉ áp dụng đúng generation.
- Migration additive; snapshot nhất quán, integrity/foreign-key check; test luôn dùng appdata tạm.
- Mutation: đọc mới, preview, xác nhận, checkpoint, gửi một lần, đối soát; timeout không tự gửi lại. Không dùng credential seller thật trong test.
- Hợp đồng ngoài chưa xác minh phải hiện rõ chưa sẵn sàng; không suy API ghi từ screenshot/tài liệu cũ. Không gọi thao tác seller thật để kiểm thử.

Quy ước đường dẫn: các tên dưới `features/`, `integration/`, `shared/`, `ui/` đều thuộc `src/main/java/com/vncode/app/`; test tương ứng thuộc `src/test/java/com/vncode/app/`. Resource luôn ghi đầy đủ. Lệnh `MVN` bên dưới là `/workspace/wcode-setup/mvn-bancapnhat` trong cloud hiện tại, hoặc `./mvnw -B` trên máy đã có JDK 25. Mỗi task thực hiện đủ red → green trước commit; lỗi biên dịch vì class mới chưa tồn tại là red hợp lệ.

## Review Focus

1. Nâng cấp từ schema 4 có dữ liệu: bảo toàn shop, mẫu, lịch sử; kiểm tra tại D2.
2. Đổi shop trong lúc đọc/ghi: bỏ kết quả đọc cũ, giữ owner của mutation; A3/C3/D1.
3. Thông tin API thiếu/quyền không đủ: không giả số dư 0 hoặc cho đăng thiếu dữ liệu; B2/B3/C2.
4. Có hơn 500 đơn và 50.000 sản phẩm: count/sort/paging đúng, bộ nhớ có giới hạn; A1/A2/D1/D2.
5. Gửi thành công nhưng mất response: chỉ đối soát, không đăng hoặc đóng gói trùng; A4/C3/D1.

---

## Kế hoạch thành phần và thứ tự

| Module | Tài liệu | Task | Điều kiện đầu vào |
|---|---|---|---|
| A — Sản phẩm và nền hiệu năng | [Products/performance](2026-10-11-vn-code-products-performance.md) | A1–A4 | Nền 1.3.0, không cần secret để chạy fixture |
| B — GS1 và tin tức | [GS1/news](2026-10-11-vn-code-gs1-news.md) | B1–B4 | A2 cho điều phối nền; còn lại độc lập A1 |
| C — Sao chép bài đăng | [Listing copy](2026-10-11-vn-code-listing-copy.md) | C1–C4 | Snapshot/key A1; media/coordinator A2 |
| D — Ozon FBS, nghiệm thu và release | [Ozon/release](2026-10-11-vn-code-ozon-release.md) | D1–D3 | D1 sau A2; D2/D3 sau tất cả task tính năng |

Thứ tự mặc định: A1 → A2 → A3 → B1 → B2 → B3 → B4 → C1 → C2 → C3 → C4 → A4 → D1 → D2 → D3. Đây là các phần của **cùng bản cập nhật**, không tạo bốn ứng dụng. Mỗi task tự có vòng test và commit; có thể tích hợp module đã kiểm tra trong nhánh, nhưng chưa công bố hoàn tất phạm vi khi module còn thiếu.

## Bản đồ giao diện dùng chung

A1 tạo các value object bất biến trong `features/products/`: `ProductKey`, `ProductRow`, `ProductQuery`, `ProductPage`, `ProductSnapshot`, `ProductVariant`, `ProductAttribute`, `ProductMedia`, `PackageDimensions`, `ProductDocument`, `DocumentState`. A3/C1/C2/C4 dùng đúng các type này, không tạo bản sao ở UI.

- `ProductKey(int shopId, Marketplace marketplace, String cardId, String variantId)`; cả cardId và variantId là định danh sàn, không GTIN.
- `ProductQuery(int shopId, Marketplace marketplace, String text, List<String> categoryIds, DocumentState documentState, boolean missingGtin, String cursor, int limit)`.
- `ProductPage(List<ProductRow> rows, long total, String nextCursor)`; `nextCursor == null` khi hết; `ProductRow` chứa key, title, vendorArticle, marketArticle, category, brand, color, size, barcodes, verifiedGtin, thumbnailUrl, material, tnved, documentState.
- `DocumentState`: `MISSING`, `UNVERIFIED`, `PENDING`, `VALID`, `REJECTED`, `EXPIRED`; `VALID` phải có bằng chứng sàn, nguồn và checkedAt.
- `ProductSnapshot`: key, revision/content hash, title/description/brand/vendorArticle/categoryId, immutable lists media/attributes/variants/documents, package dimensions và checkedAt. Thiếu trường phải giữ unknown và issue; không giả dữ liệu từ ảnh/summary.
- `ProductVariant`: id, sellerArticle, color, size, GTIN chuỗi và cờ đã xác minh; `ProductAttribute`: id, name, values, unit. `ProductMedia`: URI, nullable hash/width/height/mimeType; `PackageDimensions`: BigDecimal lengthCm/widthCm/heightCm/weightKg, nullable unknown. `ProductDocument`: loại, số, hiệu lực, chủ thể/phạm vi, URI/tệp hoặc ID sàn, marketplace, state/source/checkedAt; không mang `VALID` qua sàn khác.

Các DTO có collection defensive copy, validation key/ownership và `toString()` không chứa credential, raw KIZ hoặc raw upstream response. Không ghi source snapshot riêng tư vào log/release fixture.

## Quyết định triển khai và nguồn ngoài

- Một bản schema mới **5** từ nền schema 4; module thêm bảng/index qua các schema helper trong `Database.initDatabase()`, không tự đổi `user_version` riêng. D2 kiểm tra binary cũ fail closed với schema mới.
- Thumbnail cache 64 MiB; 2 worker decode và 2 sync đọc đồng thời ở chế độ máy yếu; hàng đợi pending đọc/ảnh chọn **64** (quyết định triển khai), có ưu tiên tương tác và bỏ yêu cầu đọc lỗi thời. Không hủy request mutation đã gửi.
- Public frontend Честный ЗНАК hiện cho thấy `/organisation/balance`, tài khoản `contractId/productGroupId` và tiền minor-unit chia 100. Một client mới dùng `/bff-elk/v1` và bearer của phiên ELK. Đây **chưa** là bằng chứng token True API hiện tại được dùng cho BFF; B2 phải xác minh đường dẫn/auth/INN/currency đầy đủ.
- WB news hiện có ứng viên từ bản lưu 2025; frontend WB trả 498. Ozon docs gặp redirect 307. C1/C2/B3 thu hợp đồng chính thức và fixture đã loại dữ liệu riêng tư trước khi bật live API mới. Nếu truy cập vẫn bị chặn, thực hiện phần local đã xác minh, ghi chính xác blocker, không đánh dấu chức năng live hoàn tất.
- Yandex Translate có proto chính thức, tổng 10.000 ký tự/request. B4 cung cấp cấu hình DPAPI và cache/fallback; không mua cloud hoặc nhúng key của chủ app.

## Theo dõi và tự rà soát

- [x] Thiết kế tại commit `e8c900a` đã được người dùng duyệt.
- [x] Kế hoạch ánh xạ đủ mục 1–11 của spec, gồm bulk documents, copy bốn hướng, tiền/GTIN riêng, tin chính thức/dịch, Ozon và hiệu năng.
- [x] Kiểm tra type/path/dependency giữa A/B/C/D; mỗi Review Focus có task/test sở hữu.
- [x] Baseline hiện tại: 21 targeted tests, 0 failures/errors/skipped; chỉ là baseline 1.3.0.
- [ ] Người dùng xem bộ kế hoạch và chọn cách thực thi.
- [ ] Hoàn tất A1–A4, B1–B4, C1–C4, D1–D3; ghi kết quả thực tế vào validation.

Đề xuất **Native**: một implementer xử lý trong phiên này, một reviewer độc lập cuối nhánh. Các task liên quan nhiều đến snapshot/ownership/checkpoint chung; cách này giữ giao diện thống nhất và giảm chi phí khởi tạo context. Sau khi người dùng duyệt kế hoạch, dùng `superpowers:executing-plans`; reviewer cuối nhánh theo kỹ năng đó. Không sửa mã sản phẩm trong giai đoạn lập kế hoạch.
