**LỊCH SỬ: tiêu chí contains đã được người dùng thay bằng exact model152NAU; các số4/3/12 không phải kết quả cuối.**

Ngày kiểm tra: **14.11.2026 MSK (UTC+03:00)**. Chỉ đọc dữ liệu thật; không đổi thuật toán app.

# Kết quả bổ sung: chọn thẻ Catalog size52

Đã thử đúng lựa chọn mới cho ví dụ **152 NAU**: Ozon size **2XL**, Catalog size **52**, không phân biệt hoa thường, bỏ các điều kiện thương hiệu/màu/TNVED/giới tính/giấy tờ/trạng thái khỏi phép tìm.

- Catalog: **4 thẻ, 4 good_id và 4 GTIN khác nhau**, không phải bốn bản trùng của một thẻ. Có 3 thẻ ghi `152 NAU`, 1 thẻ ghi `152NAU`; đều size 52. Thuộc tính 35 có `attr_value_type=РОССИЯ` thật.
- Ozon: **3 sản phẩm** đúng mẫu và size 2XL. Cả 3 cũng có Russian 52 trong thuộc tính trên **cùng sản phẩm**; đây là dữ liệu quan sát, không là bộ lọc bổ sung hoặc bảng quy đổi chung.
- Ghép chỉ bằng hai tiêu chí theo ví dụ tạo **12 cặp ứng viên**; mỗi sản phẩm Ozon còn 4 GTIN có thể. **Chưa thể tự chọn một GTIN duy nhất** nếu bỏ mọi chi tiết khác.
- Barcode hiện có của **0/3 sản phẩm Ozon** chứa một trong 4 GTIN ứng viên. Chỉ so barcode số 13/14 chữ số, không bỏ tiền tố OZN để biến barcode sàn thành GTIN. Đây là metadata riêng, không bỏ bất kỳ cặp nào trong 12 cặp.

Kết quả cũ vẫn giữ: nếu bắt Catalog ghi đúng chữ **2XL**, tìm được **0 thẻ**. Lựa chọn **52** là phạm vi người dùng chỉ định cho ví dụ này, chưa áp dụng thành quy đổi toàn cục hoặc thay mã nguồn app.

Tính trên snapshot HTTP thật đã xác minh: **1.380 thẻ Catalog và 533 sản phẩm Ozon**. Lượt Ozon dùng cặp mới có **21/21 HTTP 200**; khóa cũ 403/code 7 là lịch sử. Phép tính size 52 này không gọi API thêm. **0 ghi lên sàn, 0 mua KIZ, 0 ký, 0 kích hoạt license, 0 Gmail.**

Tệp riêng để xem 4 GTIN và 3 bài Ozon: `private-catalog52-candidates.html`, `private-catalog52-unique-candidates.json`, `private-size52-candidate-pairs.csv` và JSON cùng tên. Bảng HTML đã escape, giữ số 0 đầu; CSV nên nhập cột GTIN dạng Text. Các tệp ngoài Git và không có khóa/CIS/OMS.

Các tệp chi tiết riêng được giao trực tiếp cho người dùng, ngoài Git và ngoài ZIP mã nguồn; báo cáo trong nguồn chỉ giữ số đếm, quy tắc và provenance không định danh.
