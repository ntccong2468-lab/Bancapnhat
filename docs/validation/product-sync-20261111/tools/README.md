# Lượt đọc WB bằng production batch

`LiveWbProductSyncRead.java` là harness chẩn đoán chỉ đọc, không nằm trong runtime app. Bản dùng khi nghiệm thu được lưu cùng bằng chứng để có thể đối chiếu hash. Không chứa khóa hoặc ID sản phẩm thật.

- Nhận thư mục riêng chứa các trang WB `wb-page-N.json` đã đọc trong cùng tài khoản, đường ghi receipt và nhãn nguồn tại thời điểm chạy.
- Lấy quyền WB từ Secret `VNCODE_WB_TEST_API_KEY`; dùng proxy/CA hiện hành, có xác minh TLS. Transport chỉ cho phép đọc `content/v2/get/cards/list`, chặn mutation và mọi endpoint khác.
- Dùng `WbProductSnapshotReader.openBatch`, parser và `ProductRepository` production trên profile SQLite tạm. Không nạp `target/test-classes`, không giả license, ký hoặc kích hoạt license.
- Profile tạm chứa dữ liệu đọc chỉ phục vụ đối chiếu; luôn được dọn trong `finally`. Raw pages và khóa không được thêm vào Git. Receipt công khai chỉ có số lượng, trạng thái và thời gian.

Lệnh Java của lượt đo (classpath lấy từ Surefire XML, loại `target/test-classes`):

```text
/workspace/toolchains/jdk-25.0.4.1/bin/java --enable-native-access=ALL-UNNAMED -cp <private-harness-classes:production-classpath> LiveWbProductSyncRead /tmp/vncode-product-sync-audit/private /tmp/vncode-product-sync-audit/receipts/live-wb-batch-read.json WORKTREE_FROZEN_BEFORE_SOURCE_COMMIT
```

Kết quả nằm ở [receipt](../live-wb-batch-read.json) và [manifest các đường WB đã dùng](../live-wb-source-manifest.json). Nhãn worktree tại lúc chạy được giữ nguyên; commit cuối chỉ được gắn bổ sung sau khi đối chiếu hash. Đây là kiểm tra đọc/parse/lưu dữ liệu trong môi trường cloud, không phải thao tác cửa sổ VN Code trên máy seller, không phải nghiệm thu Ozon hoặc benchmark đồng bộ toàn bộ app.
