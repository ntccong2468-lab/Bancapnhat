# Mã KIZ nhập cho Ozon — đối chiếu API thật ngày 04.11.2026

## Kết luận cho file người dùng cung cấp

File TXT có 200 dòng duy nhất, mỗi dòng 31 ký tự dạng `01` + GTIN 14 số + `21` + serial. Không có dấu phân cách GS hoặc phần mật mã. Đây là mã nhận dạng rút gọn CIS/SGTIN; không phải toàn bộ dữ liệu DataMatrix.

Một mẫu đã được gửi thật tới `POST /v5/fbs/posting/product/exemplar/validate` với đúng sản phẩm của đơn. HTTP 200 nhưng exemplar bị từ chối với hai lỗi:

- `MANDATORY_MARK_HAS_INCORRECT_CRYPTOTAIL`: phần mật mã không hợp lệ/không có trong dữ liệu đã gửi.
- `MANDATORY_MARK_IN_INCORRECT_STATUS`: trạng thái mã không phù hợp để Ozon chấp nhận ở lượt này.

Không gửi mã này vào endpoint gán exemplar, không tiêu/trừ kho mã. Kiểm tra cấu trúc áp dụng cho 200 dòng; kết quả trạng thái API chỉ áp dụng cho **một mẫu đã thử**, không suy thành cả 200 mã đều cùng trạng thái.

## Cần lấy dữ liệu nào

Với mã quần áo đang kiểm tra, yêu cầu bên cung cấp xuất **«полный код маркировки DataMatrix с криптохвостом»**, hoặc quét đầy đủ DataMatrix trên nhãn gốc. Giữ nguyên chữ hoa/thường, dấu câu và ký tự phân cách; mỗi mã một dòng trong TXT UTF-8.

Cấu trúc thường gặp với AI 91/92, chỉ minh họa các trường, không phải một mã có thể sử dụng:

```text
01<GTIN 14 số>21<serial><GS>91<khóa kiểm tra><GS>92<phần mật mã>
```

`<GS>` biểu thị ký tự ASCII 29 / U+001D, không phải chuỗi chữ phải gõ vào mã. Một số nhóm hàng dùng bố cục/AI khác như 93; không tự ép mọi mã về một độ dài cố định. App có bộ chuẩn hóa các cách biểu diễn dấu GS khi nhập, nhưng không thể tạo hoặc khôi phục phần mật mã từ chuỗi CIS 31 ký tự.

Nguồn nên là file gốc từ hệ thống phát hành mã/nhà cung cấp hoặc máy quét 2D trả đầy đủ nội dung. Không tự thêm `91`, `92`, dấu phân cách hoặc đoạn ngẫu nhiên vào mã rút gọn. Định dạng đầy đủ vẫn chưa bảo đảm được chấp nhận: phải đọc kết quả kiểm tra nghiệp vụ của Ozon, bao gồm trạng thái mã và điều kiện sản phẩm. Chưa đọc được trạng thái CRPT cụ thể của mẫu nên không kết luận mã đã sử dụng, hết hạn hay bị rút khỏi lưu thông.

## Giới hạn bằng chứng

Tài liệu web Ozon bị vòng chuyển hướng trong môi trường này; không tuyên bố đã đọc được hợp đồng API chính thức mới nhất. Kết luận từ cấu trúc file, bộ phân tích GS1 hiện có và phản hồi của API validate thật. Không kiểm thử một mã đầy đủ vì người dùng chưa cung cấp phần mật mã gốc.

Nhận thành công 22 đơn trong cùng lượt kiểm tra là kết quả riêng: API mới đọc cho phép nhận các đơn đó mà không gán mã (không có yêu cầu KIZ bắt buộc; các đơn có đánh dấu tùy chọn có hành động cho phép bỏ qua). Không dùng kết quả nhận đơn để khẳng định Ozon đã nhận KIZ trong file.
