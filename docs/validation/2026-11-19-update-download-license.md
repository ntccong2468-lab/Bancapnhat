# Tải bản cập nhật và xác minh license sau nâng cấp

Ngày 19.11.2026, giờ Moskva. Mã gốc 1.6.12 ở `0a7ee7cd9005d13f35d43005812687d7127efacd`. Latest đang trỏ tới release immutable `451181790`.

## Tải bộ cài

Đã tải đủ 149.153.033 byte của MSI 1.6.12 và đối chiếu SHA-256 `6bfdd72b1561a2babea6160ea5a963500af9ea0e99d3827f222bf61633bbf35d`. Lượt HTTPS trực tiếp mất 3,233 giây. Hàm `UpdateInstallerService.downloadInstaller` từ JAR công khai 1.6.11 tải và xác minh cùng MSI trong 5,875 giây, qua HTTP 302 của GitHub rồi HTTP 200 của CDN với TLS 1.3. Lượt đo dùng Linux và tuyến mạng của môi trường kiểm thử. Quá trình cài, chuẩn bị hồ sơ và tuyến mạng trên máy người dùng nằm ngoài hai phép đo này.

Đã tái hiện trường hợp đọc metadata chạy nền chiếm luồng duy nhất, làm lượt tải đã xác nhận vẫn xếp hàng trong hộp cập nhật. Ngoài ra, phản hồi chunked thiếu `Content-Length` làm tiến trình vô định dù metadata đã có số byte chính xác. Bản sửa dùng luồng riêng để tải, hiển thị tiến độ dựa trên số byte trong metadata khi cần, và hiển thị thông điệp của pha hiện tại khi chưa có tỷ lệ phần trăm. Kiểm SHA-256, giới hạn kích thước và việc chờ tác vụ đạt điểm lưu an toàn trước sao lưu vẫn được thực hiện.

## License

Portal trước đây cấp phiên thiết bị 30 ngày. Các lần xác minh thành công trong thời gian này cấp quyền dùng đã ký nhưng không kéo dài phiên. Khi phiên hết hạn, challenge trả 401 và app có thể báo tài khoản bị chặn dù thuê bao còn hạn. Một lỗi xác minh tạm thời đã lưu cũng có thể hiển thị là bị chặn sau khi mở lại, rồi chờ đến lượt kiểm định kỳ mới thử phục hồi.

Desktop tự đối chiếu nền khi lỗi đã lưu cần xác minh lại. Quyền chỉ được khôi phục sau khi chấp nhận quyền dùng đã ký mới của đúng tài khoản và thiết bị. Mất mạng tiếp theo giữ nguyên trạng thái thu hồi đã xác nhận. Phiên hết hạn hiển thị hướng dẫn dùng lại khóa đã cấp. Kích hoạt lại hợp lệ giữ khóa nhận diện thiết bị cũ.

Portal kéo dài phiên đang hiệu lực khi còn tối đa bảy ngày và có lượt xác minh chữ ký thành công. Lần gia hạn giữ token hiện tại, thời hạn thuê bao, quyền và giới hạn thiết bị. Phiên đã hết hạn, thiết bị bị thu hồi và quyền thuê bao hết hạn vẫn bị từ chối. Gia hạn phiên cần triển khai thay đổi Portal mới có hiệu lực.

## Kiểm thử

- Regression desktop trước sửa tái hiện ba lỗi license, một lỗi hàng đợi và hai lỗi hiển thị. Bản mã cuối đạt 200/200 ca trong 30 bộ kiểm thử Java 25/Linux/Xvfb và tạo được JAR với `-Dtest=*License*Test,PortalClientContractTest,Update*Test,LocalData*Test verify`. Dừng lượt toàn bộ khi bản mã đó đã được thay thế. Chưa chạy toàn bộ desktop trên bản mã cuối.
- Portal `npm run check` và `npm test` đạt 72/72 ca. Phạm vi có xác minh đồng thời, hết hạn hoặc xóa phiên trong khi ký, thu hồi thiết bị, tập khách đồng thời và thay đổi chính sách. Wrangler đóng gói Worker bằng chế độ `--dry-run`, thoát mã 0.
- Bằng chứng cục bộ ở `/workspace/reports/update-license-1612-20261119`. Chưa dùng license thật, bộ cài Windows mới hoặc hồ sơ Windows của người dùng để xác minh. Bản sửa desktop chưa phát hành và Portal chưa triển khai.
