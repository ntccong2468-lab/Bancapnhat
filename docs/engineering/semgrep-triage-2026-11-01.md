# Đối chiếu kết quả Semgrep — 01.11.2026

## Phạm vi và cách đọc kết quả

- Semgrep OSS **1.183.0**, rule pack `p/java` và `p/javascript`; báo cáo JSON của lượt kiểm tra liệt kê **735 đường dẫn được quét**, 13 kết quả và không có lỗi thực thi được báo cáo. JSON không liệt kê đường dẫn bị bỏ qua, nên các số này không chứng minh mọi tệp hoặc mọi cấu hình đều được quét.
- Có **12 cảnh báo SQL tạo bằng chuỗi** và **1 cảnh báo SHA-1**. Từng cảnh báo được đối chiếu với nguồn dữ liệu và nơi gọi hiện tại; đánh giá này không phải kiểm thử xâm nhập và không khẳng định toàn bộ repository không có SQL injection hay lỗ hổng mật mã.
- Không dùng dữ liệu seller hay gọi API thật cho phần đối chiếu này. Không thay đổi mã chỉ để xóa cảnh báo.

## Các cảnh báo SQL

Kết luận cho từng dòng dưới đây là **chưa thấy đường đưa dữ liệu không tin cậy vào đoạn SQL được báo**, dựa trên các caller hiện tại; không phải bằng chứng tổng quát rằng SQL trong dự án an toàn. Tên bảng/cột của `PRAGMA` và DDL không thể thay trực tiếp bằng tham số giá trị như điều kiện `WHERE`, nên nguồn của chúng vẫn cần được giữ cố định hoặc kiểm soát bằng danh sách cho phép khi bảo trì.

| # | Vị trí được Semgrep báo | Nguồn dữ liệu và căn cứ đối chiếu | Kết luận trong phạm vi đã xem |
|---|---|---|---|
| 1 | `src/main/java/com/vncode/app/config/Database.java:261` | `hasColumn` là private; các table/column mà `ensureColumnExists` nhận hiện đến từ hằng số tại dòng 200–206. | Chưa thấy dữ liệu seller đi vào tên bảng. |
| 2 | `src/main/java/com/vncode/app/config/Database.java:287` | `createIndexIfNotExists` là private; tên index, bảng và cột được truyền bằng hằng số tại dòng 209–214. | Chưa thấy đầu vào bên ngoài ở chỗ ghép DDL. |
| 3 | `src/main/java/com/vncode/app/features/finance/AnalyticsDatabase.java:253` | `ensureColumn` là private; ba lời gọi hiện tại tại dòng 161–163 dùng tên bảng/cột và định nghĩa hằng số. | Chưa thấy đầu vào bên ngoài ở `PRAGMA table_info`. |
| 4 | `src/main/java/com/vncode/app/integration/wb/WbOrderRepository.java:540` | `getPackingOrders` là private. Điều kiện phụ từ switch các chuỗi SQL cố định hoặc danh sách `Long` chuyển bằng `Long.toString`, giới hạn 10.000 ID; `shopId` được bind. Xem dòng 482–497, 534–539. | Chưa thấy chuỗi seller được chèn trực tiếp vào điều kiện. |
| 5 | `src/main/java/com/vncode/app/integration/wb/WbOrderRepository.java:761` | Prefix xóa đến từ bốn chuỗi SQL cố định tại dòng 734–742; chỉ số lượng `?` được tạo động, `shopId` và ID bind bằng số tại dòng 750–759. | Chưa thấy giá trị đơn hàng đi vào cấu trúc SQL. |
| 6 | `src/main/java/com/vncode/app/integration/wb/WbProductRepository.java:153` | Placeholder được tạo từ kích thước batch tại dòng 121–125; `shopId` và từng `nmId` được bind tại dòng 143–152. | Chưa thấy dữ liệu sản phẩm được chèn thành SQL. |
| 7 | `src/main/java/com/vncode/app/integration/wb/WbSchemaSupport.java:411` | `columnExists`/`ensureColumn` là private; các lời gọi migration hiện tại dùng tên bảng/cột hằng số, ví dụ dòng 320–364. | Chưa thấy input bên ngoài ở `PRAGMA table_info`. |
| 8 | `src/main/java/com/vncode/app/integration/wb/WbSchemaSupport.java:426` | `createIndexIfTableExists` là private; các lời gọi hiện tại dùng tên index/bảng/cột hằng số, ví dụ dòng 385–393. | Chưa thấy input bên ngoài ở DDL. |
| 9 | `src/main/java/com/vncode/app/integration/znack/ZnackRepository.java:164` | `deleteForShop` là private; chỉ ba tên bảng hằng số được truyền tại dòng 99–101; `shop_id` được bind tại dòng 162–163. | Chưa thấy tên bảng do seller điều khiển trong các caller hiện tại. |
| 10 | `src/main/java/com/vncode/app/integration/znack/ZnackSchemaSupport.java:511` | `countRows` là private; lời gọi hiện tại tại dòng 122 dùng tên bảng migration hằng số. | Chưa thấy bảng nhận từ đầu vào bên ngoài. |
| 11 | `src/main/java/com/vncode/app/integration/znack/ZnackSchemaSupport.java:524` | `hasColumn` là private; các lời gọi hiện tại dùng tên bảng/cột hằng số, ví dụ dòng 265 và 292–298. | Chưa thấy input bên ngoài ở `PRAGMA table_info`. |
| 12 | `src/main/java/com/vncode/app/shared/AppDataRecoveryService.java:167` | `countIfTableExists` là private; các lời gọi tại dòng 145–149 dùng năm tên bảng hằng số. | Chưa thấy input bên ngoài trong tên bảng được ghép. |

Nếu bổ sung caller mới nhận tên bảng, cột, thứ tự hoặc SQL từ API/người dùng, cần kiểm tra lại boundary và dùng danh sách cho phép cho định danh; bind tất cả giá trị dữ liệu. Các trường hợp chuỗi có dấu nháy, dấu phân cách SQL và ID sai kiểu nên được đưa vào test của caller mới.

## Cảnh báo SHA-1

| # | Vị trí | Sử dụng thực tế và căn cứ | Kết luận/giới hạn |
|---|---|---|---|
| 13 | `src/main/java/com/vncode/app/integration/znack/signature/CryptoProSignedCertificateReader.java:50` | SHA-1 tính thumbprint DER của chứng thư để so với thumbprint 40 ký tự đã chọn. Hàm đối chiếu issuer/serial của signer tại dòng 38–47 và thumbprint tại dòng 50–51; chú thích dòng 16–17 nêu đây là đọc identity, còn dịch vụ nhận kiểm tra chữ ký CMS. | Không phải bằng chứng ứng dụng dùng SHA-1 làm thuật toán ký CMS. SHA-1 có nguy cơ collision về mặt mật mã; chưa chứng minh được một đường khai thác trong luồng đã xem. Không thay thuật toán đơn phương vì định dạng thumbprint 40 hex và tương thích CryptoPro/Windows đang được sử dụng; cần đánh giá threat model, khả năng bổ sung fingerprint SHA-256 và kiểm thử với chứng thư thật trước khi đổi. |

Các kiểm tra tiếp theo nên bao gồm chứng thư đúng/sai thumbprint, sai signer/issuer, nhiều signer và dữ liệu CMS lỗi; việc xác minh chữ ký thực tế và độ tin cậy chứng thư cần được xem xét riêng, không suy ra chỉ từ fingerprint.
