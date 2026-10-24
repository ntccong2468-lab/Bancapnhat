# Tăng tốc gửi từng thẻ National Catalog

Nguồn nền là 1.5.14 đã phát hành (`09718c8`), bàn giao `537dfa2`; nhánh sửa `perf/catalog-single-card-throughput`. Yêu cầu giữ mỗi thẻ một yêu cầu gửi, tăng tốc mà không chuyển sang feed nhiều thẻ. Đây là mã sửa tiếp, chưa nằm trong bộ cài Latest 1.5.14.

## Thay đổi

- `ZnackApiClient` dùng chung transport mặc định để tái sử dụng kết nối keep-alive/TLS giữa các phiên Catalog. API key, token, guard, dấu thời điểm quét và quy tắc không phát lại mutation vẫn thuộc từng client/request. Transport không giữ cookie, credential hoặc cache phản hồi dùng chung.
- Với WB, bước chuẩn bị trả cả draft và Catalog session vừa được kiểm tra cho đúng thẻ. Batch chuyển session đó tới worker gửi thay vì mở lại. Worker vẫn kiểm tra owner, cấu hình, license và guard credential trước gửi; mỗi thẻ tiếp theo được chuẩn bị bằng phiên riêng. Kết quả cũ không được dùng làm bằng chứng thẻ đã xuất hiện trên Catalog.
- Danh mục hợp lệ có thể dùng lại tối đa 60 giây trong cùng session/credential/TN VED. Cache có giới hạn 128 mục, phân biệt token và profile; kết quả rỗng hoặc lỗi không được giữ. Hết hạn hoặc đồng hồ lùi thì đọc lại. Mỗi lần đọc từ cache vẫn kiểm tra credential guard. WB chuẩn bị/gửi và Ozon chuẩn bị/xác nhận hưởng cùng tối ưu này.
- Bước xin GTIN WB bỏ lần đọc pool/quota bị lặp ở preflight. Kiểm tra danh mục vẫn chạy; ngay trong khóa cấp GTIN, đọc pool và kiểm tra hạn mức hiện hành trước khi cấp. Giữ số lượng 1, GTIN chưa được gán, nhật ký chống gửi lặp và đối soát phản hồi không rõ.
- Nếu lượt đọc pool/quota thất bại trong chính invocation đang chạy và callback gửi chưa được gọi, giữ trạng thái chưa gửi (`PREFLIGHT_ERROR`) để batch đợi rồi tự tiếp tục. Không tạo trạng thái xin GTIN chưa rõ từ lỗi đọc này. Yêu cầu đã gửi hoặc journal UNKNOWN từ phiên trước vẫn phải đối soát; không mở lại bằng suy đoán.
- Country dictionary/model, kiểm tra nguồn sản phẩm, giấy tờ theo ngày/TN VED, POST từng thẻ, đọc feed/thẻ thật, bộ đếm xác nhận, giới hạn 429/Retry-After và ký không đổi quy tắc.

## Kiểm tra

Các phép đo dưới dùng API giả lập/HTTP server cục bộ và SQLite thật; không phải tốc độ hoặc giao dịch trên Catalog production:

- Hai `ZnackApiClient` mới gọi HTTP server qua cùng một socket (sequence 0 rồi 1), giữ hai Authorization khác nhau.
- 100 lượt chuẩn bị và 100 lượt kiểm tra danh mục gửi trong cùng session: một lần đọc danh mục hợp lệ, 100 lần đọc quota. Không gộp quota với metadata.
- Lô ba thẻ đi qua batch, chuyển session sang từng writer, cấp ba GTIN và POST ba yêu cầu riêng, đọc lại cả ba feed/thẻ và hoàn thành đủ ba. Không mở lại factory. Ba lượt đọc danh mục, sáu lượt đọc pool/quota cho chuẩn bị/cấp mã; mỗi thẻ giữ GTIN/feed ID riêng.
- Đổi token/TN VED/session, TTL 60 giây, đồng hồ lùi, kết quả rỗng, lỗi mạng, credential bị thu hồi và quota hết sau chuẩn bị. Credential đổi chặn trước allocation/POST; draft chưa gán vẫn dùng lại được khi quota tạo mới đã hết.
- 429 ở lần đọc pool trước cấp GTIN: không allocation/POST, journal REJECTED do chưa gửi, đợi đúng batch và tiếp tục đủ ba thẻ mà không phải bấm Đăng ký lần nữa. Phản hồi thất lạc sau gửi không áp dụng quy tắc này.

Mã tối ưu `9f798ed` có 162 ca liên quan cuối đạt, sau hồi quy rộng 1.235 ca. Bổ sung tiếp xử lý ảnh lô tự dừng sau năm lần đọc ở `3ede758`; mã tổng hợp đạt **1.240 ca hồi quy, 190 ca mục tiêu, không lỗi/thất bại**. [Biên nhận tối ưu](../validation/2026-10-24-catalog-single-card-throughput-verify.json), [biên nhận tổng hợp](../validation/2026-10-24-catalog-registration-retry-continuation-verify.json), [bàn giao tự tiếp tục](2026-10-24-catalog-registration-retry-continuation.md). Không suy ra tỷ lệ tăng tốc production từ số lần gọi fixture. Phần chờ duyệt của Catalog vẫn do dịch vụ quyết định.
