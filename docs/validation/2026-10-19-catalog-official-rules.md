# Quy tắc đăng ký Catalog công bố — VNCode 1.5.1

Nguồn chính thức: API National Catalog **5.68, 28/09/2026**, đọc lại ngày 19/10/2026 tại https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/ .

## Quy tắc đã áp dụng bằng mã

| Quy tắc | Thực hiện |
| --- | --- |
| §3.2.2: tạo mới dùng GTIN và thông tin sản phẩm; chỉnh sửa thẻ phải có `good_id` | Luồng sửa thẻ lỗi gửi đúng good_id đã đọc. Thử lại sửa thẻ giữ định danh; không nhận một good_id mới bất ngờ. Checkpoint chưa gửi từ bản cũ được bổ sung định danh trước khi chuyển sang FEED_SUBMITTING. |
| §3.2.2: TN VED đăng ký ở node bốn số hoặc mười số | Bốn số khớp tiền tố của thuộc tính 13933 mười số; bỏ 3959. Khi feed mang đủ mười số, bỏ cả 3959 và 13933 trùng. Chuẩn hóa cả request chưa gửi được lưu từ bản cũ; sai nhánh bị chặn trước POST. |
| §3.3.2: lấy schema theo cat_id, giá trị/preset, attr_value_type, number/date và dependent_attributes | Đọc schema bắt buộc; nếu có phụ thuộc, đọc đầy đủ metadata của chính danh mục để có đúng tên, kiểu và lựa chọn. Trường phụ thuộc hiện trong bảng khi seller chọn giá trị kích hoạt. Cache model trong Catalog session tối đa 120 giây, không dùng chung giữa tài khoản. |
| §3.3.7 và bảng nhóm trạng thái: tra giấy tờ theo số/ngày | Nhận đầy đủ nhóm Green 1, 4, 6, 26–30 cho mục đích đưa hàng vào lưu thông; Yellow/Red không tự được coi là giấy tờ cho đưa hàng mới vào lưu thông. Giữ kiểm tra trạng thái/ngày, lỗi và kết quả tra cứu đang chờ. |
| product_tnved là dữ liệu đối chiếu trả về, không được công bố là phép so khớp bắt buộc của /feed | Không dùng nó để bịa một lỗi từ Catalog. Thiếu hoặc không khớp phạm vi được hiện rõ thành cảnh báo cần seller xem lại hàng/phụ lục/giấy tờ và xác nhận, không được tự chọn là phù hợp. Giá trị bao phủ vẫn được ưu tiên cho gợi ý tự động. |
| Schema và giấy tờ dùng cho đúng sản phẩm/biến thể | Không tự chuyển mã 6103109000 sang 6112 từ gợi ý danh mục/tên bài; không đổi mã để vừa giấy tờ. Màu, size/hệ size, article giữ đúng biến thể. Không coi thiếu gi lê là bằng chứng mã 6103109000 sai. |
| §3.2.2–3.2.3, §3.4: gửi duyệt, theo dõi feed, ký và đọc lại xuất bản | `moderation=1`, identified_by trade-unit, multiplier=1; giữ feed_id và báo từng trạng thái. Không phát lại yêu cầu chưa rõ kết quả. HTTP 200/feed receipt/chữ ký đã gửi chưa được tính published. |

`product_tnved` không bắt buộc trong response không có nghĩa một giấy tờ phù hợp cho mọi hàng hóa. Trường hợp không khớp phải hiện cảnh báo để seller xác nhận dữ liệu thực tế hoặc chọn giấy tờ khác; không gắn nhãn giấy tờ “đã xác minh phù hợp” chỉ vì Catalog từng duyệt thẻ khác. Luồng WB xác nhận một lần cho cùng tập giấy tờ, mã TN VED và nội dung cảnh báo trong một batch, không áp dụng xác nhận ấy cho nhánh khác. Ozon có bảng chi tiết/cảnh báo và xác nhận batch; sửa thẻ lỗi có bảng đề xuất và Áp dụng. Các điều kiện này không phải tuyên bố tính hợp pháp của một giấy tờ chỉ từ màu Catalog.

Mẫu mặc định **vncode-catalog-official-v3** được lưu trong resource dùng chung WB/Ozon. Đây là mapper/validator chạy trong app; không chỉ thay văn bản prompt và không gửi dữ liệu seller tới dịch vụ AI. Quy tắc đóng bộ đã có vẫn giữ cách sửa chỉ sau phản hồi từ chối chắc chắn, không tự gửi lại khi timeout.

## Kiểm tra và giới hạn

Linux/Xvfb: 263 ca liên quan qua trong một lượt, 2 ca checkpoint cũ bổ sung chạy riêng qua. Công cụ release: 77 ca qua. Java Windows thực thi mới ở run 39286749991/source96e206: 1924 tests, 0 failures/errors, 2 platform skips. Artifact/JAR của lượt đó được xác minh nguyên byte và app/test/dependency/packaging không đổi khi dùng trong lượt phục hồi. Java không được khai là chạy mới trong lượt phục hồi.

Lượt native cuối đã chạy lại đầy đủ và qua: khởi động/schema, DPAPI, workspace/DPI, 288 bố cục và 16 đường nâng cấp giữ dữ liệu, gồm 1.5.0. Bằng chứng tại [native Windows](2026-10-19-vncode-151-windows-native.json), [Java gốc](2026-10-19-vncode-151-full-java.json) và [Latest/download/updater](2026-10-19-vncode-151-published-latest.json). Các lượt bị chặn và nguyên nhân giữ trong hồ sơ riêng; không dùng native chưa qua hoặc lỗi Java làm kết quả qua.

Không cấp GTIN, tạo/sửa/ký thẻ hay ghi sàn thật trong đợt nghiên cứu này. API thật chỉ đọc thẻ, registry và schema. Không thay đổi thẻ xanh cũ. CryptoPro doanh nghiệp, license production và nghiệm thu ghi từ desktop vẫn chưa được xác nhận.

## Chứng cứ nguồn chính thức

SHA-256 tài liệu đã tải: `d2d0f01e96a060f73ba55f48d819292e7d5d0f9092e9129a80d1e44405130491`.

Schema thật danh mục 30696 xác nhận thuộc tính 12 «Вид товара»: chọn «НЕТ В СПРАВОЧНИКЕ» làm 23828 bắt buộc; metadata đầy đủ trả tên «Вид товара (при отсутствии в справочнике)», kiểu text. Chương trình đọc schema theo đúng danh mục, không hardcode bộ giá trị này cho danh mục khác.

API cũng xác nhận thẻ cùng tài khoản đã published/signed với 6103109000 và giấy tờ trả phạm vi 6101/6201/6202/6211. Không suy ra tính phù hợp pháp lý hay nguyên nhân kiểm duyệt thẻ đỏ từ dữ liệu này. Không đưa raw seller response hoặc API key vào repo.
