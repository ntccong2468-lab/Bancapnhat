Ngày kiểm tra: **14.11.2026 MSK (UTC+03:00)**. Tiêu chí cuối của người dùng; chỉ phân tích snapshot API thật, không đổi thuật toán app.

# Kết quả cuối: chỉ đúng 152NAU và size 2XL / XXL / 52

Đã tính lại từ dữ liệu API thật theo tiêu chí mới nhất: **so toàn bộ mã mẫu**, không tìm chứa và không chỉ kiểm ranh giới từ. Chuẩn hóa Unicode NFKC, hoa/thường, bỏ khoảng trắng trong mã mẫu; chỉ gỡ tối đa một đuôi size 2XL/XXL/52 có phân cách rõ. Giữ nguyên mọi tiền tố/hậu tố mẫu khác. Alias size chỉ dành cho ví dụ 152NAU.

| Kết quả | Catalog | Ozon |
| --- | ---: | ---: |
| Dòng trong snapshot API thật | 1380 | 533 |
| Đúng toàn bộ mã sau khi chỉ bỏ đuôi 2XL/XXL/52 | 5 | 1 |
| Đúng toàn bộ mã và size trong tập 2XL/XXL/52 | 1 | 1 |
| Không đạt quy tắc tách mã + size: đuôi size ngoài nhóm hoặc mẫu khác | 15 | 14 |

Ứng viên cuối gồm **1 GTIN Catalog**, **1 good_id** và **1 sản phẩm Ozon**, tạo **1 cặp**. Không tự cập nhật GTIN dù chỉ còn một ứng viên. Barcode hiện có của 0 sản phẩm Ozon đã chứa GTIN ứng viên; metadata này không dùng làm bộ lọc.

Số loại trừ Ozon ở trên được tính theo chính sách chỉ bỏ đuôi size 2XL/XXL/52. Nó bao gồm cả các SKU cùng mã nền nhưng có đuôi size khác nhóm (như L/XL), chưa được phép bỏ; **không có nghĩa tất cả các dòng bị loại đều là mẫu hàng khác**. Chỉ kết quả 1 thẻ / 1 sản phẩm / 1 cặp là ứng viên cuối. Đã kiểm riêng bằng chứng size của cặp thực tế: mọi trường size chuyên biệt đều nằm trong nhóm người dùng chọn và không liên quan xử lý ngoặc size. Đây là đối chiếu riêng, chưa phải tích hợp matcher tổng quát vào app.

Trường article/SKU rõ ràng được ưu tiên. Khi đã có article chính, tên/mô tả/ghi chú không được ghi đè danh tính. Khi thiếu article chính, chỉ dùng ghi chú/tên có marker article rõ và lấy toàn bộ span; không cắt phần mã sau khoảng trắng đầu. Vì vậy các mẫu có tiền tố/hậu tố thêm bị loại khỏi ứng viên cuối, kể cả hậu tố là một từ tách bằng dấu cách.

Không dùng thương hiệu, màu, TN VED, giới tính, giấy tờ hoặc trạng thái công bố để chặn phép tìm. Không suy quy đổi size cho mẫu khác. Catalog size35 có metadata thật `РОССИЯ`; Ozon có trường manufacturer/Russian riêng, giữ đầy đủ nguồn trong tệp riêng.

Kết quả cũ **4 Catalog / 3 Ozon / 12 cặp** dùng quy tắc tìm chứa được giữ làm lịch sử và đánh dấu **SUPERSEDED**, không còn là kết quả cuối. Các giá trị mã mẫu thực tế và lý do loại nằm trong HTML/JSON riêng, không in GTIN/ID người bán vào báo cáo công khai.

Đọc từ snapshot thật đã xác minh **1.380 thẻ Catalog và 533 sản phẩm Ozon**, cặp Ozon mới trước đó trả **21/21 HTTP 200**. Phép tính lại này **0 HTTP thêm, 0 sửa mã app, 0 ghi API, 0 mua KIZ, 0 ký, 0 license, 0 Gmail**. Có 8 ca parser dương và 9 ca âm cùng 7 kiểm tra marker/ưu tiên; đây là kiểm tra parser cục bộ, không phải giả lập nghiệm thu API.

Tệp riêng: `EXACT-152NAU-size-aliases-private.html`, JSON cùng tên và `EXACT-152NAU-size-aliases-pairs.csv`. GTIN giữ dạng chuỗi và số 0 đầu. Không commit dữ liệu này vào Git hoặc đưa vào ZIP mã nguồn.

Tệp GTIN/ID chi tiết được giao riêng trực tiếp cho người dùng, ngoài Git/ZIP mã nguồn. Báo cáo nguồn chỉ giữ tiêu chí, số đếm và hash provenance.
