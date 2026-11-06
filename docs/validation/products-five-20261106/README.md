# Bằng chứng kiểm năm nút sửa sản phẩm — 06.11.2026

Đọc [báo cáo](../2026-11-06-products-five-audit.md), [biên nhận API thật đã bỏ dữ liệu seller](../2026-11-06-products-five-live.json) và [kết quả kiểm thử](tests.json) trước khi tiếp tục.

- `ProductFiveButtonLiveProbe.java`: bản lưu harness kiểm production service/adapter/HTTP. Lượt đã thực hiện gửi lại **giá trị đang có** của năm trường trên **một** bài WB, không tạo dữ liệu nghiệp vụ mới. Mã trong Git thêm cờ bắt buộc `--execute-current-values`; bản có cờ đã được biên dịch, không chạy ghi thêm. Mỗi trường chỉ gửi một lần; đọc lại đến `RECONCILED` trước trường kế tiếp. Transport chỉ cho phép mutation `content/v2/cards/update`, tối đa năm lần.
- Hồ sơ SQLite, chọn sản phẩm, request/response và journal thật nằm ngoài Git trong thư mục riêng quyền `0700`. Không đưa các tệp đó vào PR, scanner ngoài, ảnh hoặc bộ nhớ. Mất thư mục `/tmp` không phải lý do chạy lại giao dịch. Biên nhận công khai đã ghi nhận **COMPLETE, 5/5**; không gửi lại lượt này.
- Harness dùng `LicenseDomainFixture` từ test classpath trong hồ sơ tách biệt. Nó không kích hoạt license từ xa và không nằm trong JAR phát hành. Bằng chứng này kiểm đường ghi sản phẩm, không chứng minh license/chứng thư của máy seller.
- Giấy tờ mặc định của hồ sơ thử được đặt bằng đúng tuyên bố đã có trên bài thử, cùng số/ngày/loại, để tránh gán giấy tờ của doanh nghiệp khác. Đây không phải thay đổi cấu hình trên máy seller hay gán giấy tờ trong ảnh cho 131 bài.
- `ProductDeclarationPreview.java`: helper **chỉ đọc dữ liệu tổng hợp cục bộ**, chụp JavaFX thật của hộp thoại mới; không dùng seller API. [Ảnh](../assets/products-five-20261106/configured-declaration.png).
- `whole-source-scan.json`: trạng thái từng scanner, phiên bản, phạm vi và xử lý mười cảnh báo Gitleaks; không chứa giá trị bị phát hiện. OpenQodex cục bộ chỉ chọn Gitleaks, không có AI reviewer độc lập.
- `openqodex-ci-health.json`: artifact workflow trên nguồn `af3bf5c`, năm scanner chạy thật; phạm vi workflow theo thay đổi, không thay thế lượt quét toàn source.
- `build.json` và `startup.json`: JAR nguồn cuối, hash/class đối chiếu, mở/đóng production Launcher trên Linux/Xvfb bằng profile trống, không fixture/license activation.
- `handoff-secret-check.json`: kiểm bổ sung các tệp nhãn/bàn giao mới bằng Gitleaks; 20 tệp, 0 ứng viên.
- `large-stress-incomplete.json`: lượt full bị ngắt khi nhóm 10.000 GTIN còn chạy. Ba stack lưu lại xác định bài **WB** tại thời điểm chụp; nhãn Ozon trong thông tin ban đầu đã được đính chính. Không tính nhóm này PASS.

Biên dịch helper (không chạy mutation):

```sh
/workspace/toolchains/jdk-25.0.4.1/bin/javac \
  -cp "target/classes:target/test-classes:$(cat /tmp/ozon-combined-classpath.txt)" \
  -d /tmp/products-five-public-helper-classes \
  docs/validation/products-five-20261106/ProductFiveButtonLiveProbe.java \
  docs/validation/products-five-20261106/ProductDeclarationPreview.java
```

Chụp UI tổng hợp trong phiên Xvfb đã khởi tạo:

```sh
DISPLAY=:168 XDG_CACHE_HOME=/tmp/products-five-cache \
  /workspace/toolchains/jdk-25.0.4.1/bin/java \
  -cp "/tmp/products-five-public-helper-classes:target/classes:target/test-classes:$(cat /tmp/ozon-combined-classpath.txt)" \
  com.vncode.app.ui.products.ProductDeclarationPreview \
  docs/validation/assets/products-five-20261106
```

Các lệnh trên dùng đường toolchain của môi trường kiểm. Giá trị credential chỉ được lấy từ Secrets của môi trường; không dán vào lệnh hoặc ghi vào báo cáo. Ozon hiện trả `403/code 7` vì API key vô hiệu hóa; chưa có nghiệm thu ghi Ozon trong lượt này.
