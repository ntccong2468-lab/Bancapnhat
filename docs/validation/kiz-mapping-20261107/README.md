# Tái lập kiểm KIZ mapping bằng API thật

[Báo cáo](../2026-11-07-kiz-mapping-live-audit.md), [HTTP và review](live-api.json), [assertion thật trước/sau](real-regression.json), [build](build.json), [scanner](scanner.json), [hash helper](manual-probes.json), [kiểm secret bàn giao](handoff-secret-check.json).

Các helper là công cụ kiểm ngoài JAR. Chúng đọc API WB/Catalog/Ozon thật, không tạo license/chứng thư, không gửi đơn mua. Profile SQLite chỉ chứa bản sao dữ liệu API trong thư mục `/tmp` riêng quyền 0700; không dùng profile app đang cài. Mã nguồn trong repo thêm guard thư mục; bản Java đã compile, Python đã kiểm cú pháp, không tính là lượt nghiệm thu API mới.

1. `read-access.py`: tạo thư mục riêng và đọc trang đầu WB/Catalog cùng quyền Ozon. Lượt gốc chạy inline; helper lưu lại tương đương và bổ sung chặn redirect/thư mục.
2. `fetch-live.py --directory ...`: đọc hết WB, danh sách Catalog và chi tiết theo 25 GTIN. Endpoint allowlist chỉ có các đường đọc; không lưu header/giá trị credential.
3. `KizMappingApiAudit.java`: đưa phản hồi HTTP thật qua parser và repository production, chạy review và lưu số liệu. Nhãn source là **LIVE_API_DIAGNOSTIC_OWNER_NOT_VERIFIED**, không phải scope đã xác minh. Việc gọi riêng parser nội bộ dùng reflection phục vụ phân tích; không vượt guard của `synchronize`/mua.
4. `KizMappingFreshRead.java`: chọn mẫu có một thẻ published quan sát được, đọc lại WB + Catalog + registry bằng SDK production, chạy review mới. Nếu dữ liệu mới không còn mẫu tương ứng, cần báo không tái hiện; không dựng sản phẩm hay giấy tờ để ép kết quả.

Ví dụ trên môi trường Linux đã có Java/Maven/dependency của repo:

```sh
python3 docs/validation/kiz-mapping-20261107/read-access.py \
  --directory /tmp/vncode-kiz-api-audit
python3 docs/validation/kiz-mapping-20261107/fetch-live.py \
  --directory /tmp/vncode-kiz-api-audit
```

Chọn thư mục mới cho mỗi lượt. Script access từ chối ghi đè biên nhận sẵn có. Đặt `VNCODE_WB_TEST_API_KEY`, `VNCODE_CATALOG_TEST_API_KEY`, `VNCODE_OZON_TEST_CLIENT_ID`, `VNCODE_OZON_TEST_API_KEY` qua Secrets của môi trường; không dán khóa vào lệnh hoặc chat. Credential chỉ gửi đúng hostname được cấu hình; giữ proxy/CA môi trường.

Biên dịch cả hai Java helper vào thư mục classes riêng, với classpath `target/classes` và các dependency JAR, **không cần `target/test-classes`**. Chạy analyzer với tham số thư mục `/tmp` và tên `real-gates` để chuẩn bị mẫu/cache. Chạy fresh reader với cùng thư mục và tên `after`; JVM dùng proxy/CA môi trường như lệnh trong báo cáo.

`after-registry` chạy analyzer trên tập phản hồi thật đã lưu, bổ sung đúng phản hồi registry của mẫu vừa gọi API. Đây là phân tích dữ liệu thật, không phải thêm lượt HTTP hoặc xác nhận proof/doanh nghiệp. [Biên nhận](live-api.json) phân biệt rõ đọc live, parse, review và phần chưa nghiệm thu.

Kết quả nguồn `5ad16bf`: bỏ so TN VED marketplace/Catalog ở mapping WB/Ozon; WB chọn một thẻ hợp lệ giữa các barcode phụ, còn nhiều thẻ thật phù hợp vẫn cần xử lý mơ hồ. Luồng mua giữ kiểm chứng thư/doanh nghiệp, trạng thái, giấy tờ, đúng biến thể, kho, UUID và đối soát. Chưa thử mua KIZ có phí hoặc lưu mapping đã xác minh trong lượt này; Ozon trả 403/code 7. Không sử dụng các số liệu ở đây để khẳng định giao dịch mua đã thành công.
