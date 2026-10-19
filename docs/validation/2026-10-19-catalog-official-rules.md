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

Lượt kiểm thử liên quan trên Linux/Xvfb: **263 ca qua** trong toàn bộ nhóm registration, UI đăng ký, parser giấy tờ và TN VED. Hai ca bổ sung kiểm tra checkpoint WB/Ozon từ bản cũ được chạy riêng và qua cả hai (0 failures/errors). Công cụ release: **74 ca qua**. Bản JAR đã package thành công; kiểm thử bộ cài Windows mới được ghi riêng khi hoàn tất.

Run Windows cũ 39268181948, nguồn 85fd0e060ad75031d21b1ed54fb6a4821d46d9e7, đã chạy đủ **1913 tests, 0 failures, 0 errors, 2 platform skips**; đã dừng trước khi phát hành vì chủ repo yêu cầu áp dụng quy tắc công bố vào mã mới. Workflow publish 39268835904 cũng đã hủy trước khi tạo tag/release. Kết quả đó không thay thế lượt Windows mới của bản sửa này.

Không cấp GTIN, tạo/sửa/ký thẻ hay ghi sàn thật trong lượt nghiên cứu/kiểm thử này. API thật chỉ đọc thẻ, registry và schema. Không thay đổi trạng thái các thẻ xanh cũ. CryptoPro doanh nghiệp và nghiệm thu ghi từ desktop/license production vẫn chưa được xác nhận.

## Chứng cứ

SHA-256 trang tài liệu đã tải: `d2d0f01e96a060f73ba55f48d819292e7d5d0f9092e9129a80d1e44405130491`.

Schema thật danh mục 30696 xác nhận: thuộc tính 12 «Вид товара» có điều kiện «НЕТ В СПРАВОЧНИКЕ» làm thuộc tính 23828 bắt buộc. Metadata đầy đủ trả tên «Вид товара (при отсутствии в справочнике)», kiểu text. Không hardcode bộ giá trị của danh mục này vào danh mục khác; chương trình đọc schema hiện tại.

Đã xác nhận bằng API những thẻ cùng tài khoản đã published/signed với 6103109000 và giấy tờ trả phạm vi 6101/6201/6202/6211. Điều này được ghi trong báo cáo riêng có định danh seller ở `/workspace/reports/catalog-the-xanh-6103-giay-to-4-nhom-2026-10-19.md`; không suy ra nguyên nhân từ chối của các thẻ đỏ khi chưa có feed_id/lý do gốc. Không đưa raw seller response hay API key vào repo.

Windows 39277937972 của nguồn quy tắc mới đã qua 1.924 ca (0 failures/errors, 2 platform skips), 288 bố cục và 15 đường nâng cấp. Đường 1.5.0 bị chặn trước cài vì script thiếu nhánh URL và tải nhầm bộ cài 1.1.34; checksum đúng của 1.5.0 đã ngăn dùng file đó. Đã sửa URL, giữ nguyên checksum; nguồn `96e20614882488a2d60319fc29f4ca62b6cb9b81` đang chạy lại đầy đủ ở run `39286749991`. Chưa dùng kết quả một build thất bại để phát hành.
