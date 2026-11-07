# Manual Code Doctor probes

Các probe này chỉ đọc API WB/Catalog và ghi cache vào hồ sơ thử riêng. Không có lời gọi nhận đơn, mua/gán KIZ, feed/signature Catalog hoặc cập nhật barcode/GTIN. Không nằm trong app/JAR. Không dùng quyền local fixture làm bằng chứng license thật hay xác minh chủ doanh nghiệp/chứng thư.

- `CodeDoctorStartupProbe.java`: entry point production, hồ sơ trống, không fixture license; đọc biên nhận startup riêng.
- `CodeDoctorLiveWbCatalog.java`: production WB sync/reader, Catalog client/parser/batch reader, local repository, matching. Có fixture license **chỉ trong harness**, không kích hoạt qua mạng.
- `CodeDoctorLiveCacheReopen.java`: JVM khác đọc lại profile; không có API client/lời gọi mạng.
- `CodeDoctorWbGtinPolicyProbe.java`: production matcher trên dữ liệu đã đọc; đọc mới và validate 5 biến thể WB mẫu, không gọi submit. Article cần chẩn đoán có thể đặt qua `VNCODE_WB_TEST_ARTICLE`; giá trị không được in hoặc commit. Dữ liệu màu/size không khớp vẫn được báo như vậy.

Chạy từ root repository, với JDK 25, JAR và test classes đã build. `--classpath-file` là tệp classpath dependency Maven của môi trường; ví dụ hiện có `/tmp/ozon-combined-classpath.txt`. Không đưa giá trị credential vào tham số dòng lệnh. Secrets WB và Catalog có sẵn trong môi trường qua hai biến `VNCODE_WB_TEST_API_KEY` và `VNCODE_CATALOG_TEST_API_KEY`. Luôn giữ proxy/truststore của môi trường, không tắt TLS validation.

```bash
python3 docs/validation/code-doctor-20261106/run-live-read.py baseline --jdk-home /workspace/toolchains/jdk-25.0.4.1 --classpath-file /tmp/ozon-combined-classpath.txt
```

Probe in một đường dẫn thư mục riêng dưới `/tmp`. Dùng chính đường dẫn đó với `--directory` cho `policy` hoặc `reopen`; không chạy baseline vào profile cũ. Baseline đọc toàn bộ catalog và tôn trọng giới hạn/trạng thái API, không tự lặp khi lỗi chưa rõ. Profile chứa credential và raw seller response nên giữ 0700, không upload/commit. Chỉ chép JSON số liệu đã kiểm tra không chứa danh tính.

Lượt thực tế 06.11 dùng các Java probe với lệnh `javac -cp <JAR/test-classes/dependencies>` rồi `java` cùng proxy/truststore; launcher Python được thêm để tái sử dụng, không phải một lượt API mới đã chạy. Đã compile các probe và kiểm tra cú pháp launcher. [Kết quả đọc thật](../2026-11-06-code-doctor-live.json), [bàn giao sửa bốn bộ lọc](../2026-11-06-znack-four-states-wb-gtin.md).
