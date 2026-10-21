# Đối chiếu nhánh TN VED — 19/10/2026

Đọc biểu thuế trên trang EEC hiện hành, chương 61 và 62. Các PDF được trang chính thức liên kết tại thời điểm đọc. Không suy ra mã đúng cho hàng thực tế chỉ từ tên bài hoặc một ảnh.

| Nhóm trên giấy tờ đã tra trước | Mã con 10 số trong biểu | Ví dụ |
| --- | ---: | --- |
| 6101 | 6 | 6101201000, 6101309000 |
| 6201 | 4 | 6201300000, 6201400000 |
| 6202 | 6 | 6202300000, 6202400009 |
| 6211 | 26 | 6211323100, 6211333100 |

42 mã con này khớp hoàn toàn với dữ liệu tham chiếu hiện có trong ứng dụng. Đọc từng dòng mã đủ mười số trong hai PDF và so sánh tập hợp; không đếm dòng tiêu đề trung gian. Danh sách dành cho bốn nhóm cụ thể này, không phải mọi giấy tờ hay mọi tài khoản Catalog.

## 6103109000 và gi lê

`61 → 6103 → 610310 → 6103109000`. Mã này tồn tại: «Костюмы мужские или для мальчиков из прочих текстильных материалов», hàng dệt kim. Nó không thuộc nhánh 6101, 6201, 6202 hoặc 6211. 6203 và 6103 cũng là hai nhóm riêng, không thay thế cho nhau.

Chú giải 3(a), chương 61: «костюм» có thể gồm hai hoặc ba món. Áo gi lê là thành phần bổ sung, không phải điều kiện bắt buộc. Tuy nhiên, chú giải còn quy định kết cấu áo пиджак/жакет (mặt ngoài không kể tay có từ bốn mảnh), quần/món phần thân dưới, cùng vật liệu, cấu trúc, màu và thành phần, cỡ tương thích. Không phải mọi bộ áo và quần đều đáp ứng định nghĩa này.

Nhánh «комплекты» có 6103220000 (cotton), 6103230000 (sợi tổng hợp), 6103290001/0009 (khác). Bộ thể thao dệt kim thuộc nhánh riêng 6112, với 6112110000/6112120000/6112190000 tùy vật liệu. Chú giải 3(b) không gộp bộ thể thao 6112 vào «комплект». Chọn giữa các nhánh phải theo cấu tạo, thành phần và hàng thực tế; không chỉ dựa vào từ «bộ», danh mục WB hoặc có/không gi lê.

## Duyệt xanh và phạm vi giấy tờ

Thẻ đã published là bằng chứng trạng thái Catalog, không phải dữ liệu xác nhận đầy đủ cách phân loại hải quan hay phạm vi mọi giấy tờ. Có thẻ published trong dữ liệu đã đọc mang 6103 với giấy tờ mà registry không liệt kê 6103. Vì thế không gọi mã 6103109000 là mã không tồn tại, không đổi trạng thái published thành lỗi, cũng không kết luận sai phạm vi giấy tờ là nguyên nhân kiểm duyệt duy nhất. Phải xem đúng thẻ/bản nháp, giấy tờ, phụ lục phạm vi và lỗi của feed gốc.

Đợt này không sửa mã hoặc giấy tờ trên bất kỳ thẻ thật nào. Kiểm tra bao phủ theo tiền tố tiếp tục phân biệt các nhóm; không đổi TN VED chỉ để vừa giấy tờ và không ép mọi bộ sang 6112.

## Nguồn

- https://eec.eaeunion.org/comission/department/catr/ett/
- https://eec.eaeunion.org/upload/files/catr/ett/ru.61_2022_25.04.2022.pdf — SHA-256 `55a77a8d647f87b85055da92e88bb47ce49ca90e184c5f78b96a08cef441b9a0`.
- https://eec.eaeunion.org/upload/files/catr/ett/ru.62_2022.pdf — SHA-256 `8dc3e2e4d8f9b8a2f95a6d20ffc0edbca767a11690a196a52e519d6675e25f21`.

## Đọc lại tài khoản bốn nhóm

Sau khi chủ repo xác nhận giấy tờ bốn nhóm 6101/6201/6202/6211, đã đọc lại đủ 1.277 thẻ: 1.193 published thuộc 6103. Đọc chi tiết bốn thẻ mẫu, trong đó hai thẻ 6103109000, xác nhận signed/published và cùng giấy tờ đang hiệu lực có phạm vi bốn nhóm trên. Đây là bằng chứng Catalog đã chấp nhận các mẫu đó, chưa phải lời giải thích vì sao các lượt khác bị từ chối. Không kết luận phạm vi giấy tờ không khớp là nguyên nhân kiểm duyệt duy nhất; không thay đổi thẻ published hoặc bỏ kiểm tra phạm vi phía ứng dụng. Báo cáo có định danh seller được lưu riêng tại `/workspace/reports/catalog-the-xanh-6103-giay-to-4-nhom-2026-10-19.md`, raw không commit.

## Sau khi đối chiếu API công bố

Theo yêu cầu tiếp theo của chủ repo, đã đọc API 5.68 và đổi phép chặn cứng theo `product_tnved` thành cảnh báo cần xác nhận rõ. Điều này thay thế mô tả về điều kiện chặn phía app ở phần ghi nhận trước đó. Ngày/trạng thái đỏ/lỗi định dạng hoặc tra cứu chưa rõ vẫn không tự hợp lệ. Xem `2026-10-19-catalog-official-rules.md` để biết quy tắc thực thi cuối cùng.
