# Kiểm thử

Desktop dùng Java 25, Maven và JUnit 5. Trên Windows, chạy `mvnw.cmd -B clean verify`; trên Linux dùng `./mvnw -B clean verify`. Kiểm thử JavaFX cần display; trên máy Linux có `xvfb-run` có thể dùng `xvfb-run -a ./mvnw -B clean verify`. Lệnh tổng chạy lâu vì có các bài kiểm tra danh mục lớn.

Chạy nhóm liên quan trước, ví dụ `./mvnw -B -Dtest=TableRowsTest,ThumbnailLoaderTest test`. Các test có thể dùng mock HTTP, SQLite tạm và license giả ký qua `LicenseDomainFixture`; chúng không chứng minh giao dịch seller thật. Không kích hoạt license thật hoặc thực hiện ghi, ký, mua hay nhận đơn thật chỉ để kiểm thử khi chưa có phạm vi cho phép.

Các công cụ phát hành có test riêng: `node --test tools/*.test.mjs`. Portal dùng Node và lockfile: trong `website/license-portal`, chạy `npm ci`, `npm run check`, `npm test`. `npm ci` cài lại `node_modules` theo lockfile; kiểm tra kết nối và công cụ sẵn có trước khi chạy.

Ghi kết quả PASS, FAIL, NOT RUN hoặc BLOCKED, số ca bỏ qua và nguyên nhân. Phân biệt unit/integration dùng mock, smoke Windows, kiểm thử đầu-cuối với dịch vụ thật và đo trên máy seller. Những ca phụ thuộc hệ điều hành hoặc CryptoPro cần báo rõ khi không chạy được.

Windows packaging dùng `build.bat exe`; `build.bat app-image` dọn `out`, vì vậy lưu bộ cài và bằng chứng cần giữ trước khi chạy. Không dùng kết quả của một SHA để khẳng định build từ SHA khác. Xem `docs/releases/BASELINE.md` và workflow tương ứng để hiểu các bước trước khi thực thi.
