# VN code 1.1.34 — bản thử Windows

Đổi thương hiệu ứng dụng thành **VN code** trong cửa sổ, thanh điều hướng, phần giới thiệu, thông báo RU/EN/VI/ZH và bộ cài Windows. Mã Java/FXML dùng namespace `com.vncode.app`, JAR là `VNcode-1.1.34.jar`, launcher là `VN code.exe`.

## Cài riêng và dữ liệu

- Đây là ứng dụng độc lập, cài song song với WCode. Tên shortcut và launcher là **VN code**.
- Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`; danh sách shop bắt đầu trống theo lựa chọn của người dùng.
- Có Windows installer upgrade UUID riêng. Cài/gỡ VN code không thay thế registration hoặc chương trình WCode.
- Không tự nhập database, lịch sử, giấy phép hay bản sao lưu WCode. Cache/backup và cả hai kênh cập nhật của VN code cũng độc lập.
- Chỉ nhận thuộc tính `vncode.appdata.dir` và `vncode.data.profile`; bỏ qua `wcode.*`. Giữ schema 4 và các chức năng phục hồi trong dữ liệu của chính VN code.

## Bản cá nhân miễn phí

Không cần giấy phép WCode, không gọi máy chủ kích hoạt hoặc gửi báo cáo lỗi về WCode. Thanh điều hướng hiển thị bản cá nhân miễn phí bằng RU/EN/VI/ZH. Chi tiết lỗi có nút sao chép để chủ app tự xem và chia sẻ. Tài khoản WB/Ozon, GS1/Chesty Znak và chứng thư CryptoPro vẫn cần thiết cho nghiệp vụ tương ứng.

## Chức năng và nguồn

Giữ chức năng WB/Ozon, KIZ, in nhãn, tài chính, đồng bộ GTIN và phục hồi đăng ký của bản trước. Nguồn nền là WCode 1.1.32 công khai do Nguyễn Anh Tuấn / TuanDev phát triển cùng module GTIN của fork. Luồng phục hồi được triển khai theo mô tả công khai WCode 1.1.75; chưa có mã nguồn chính xác của 1.1.75 để tích hợp.

Bản thử chưa ký Authenticode và chưa phát hành signed update manifest: tải/cài thủ công. Ghi GTIN production WB/Ozon vẫn bị khóa chờ xác minh hợp đồng API. Chưa nghiệm thu trên tài khoản GS1/Chesty Znak thật, CryptoPro, seller thật hoặc máy in của người dùng.

Bộ cài `VN-code-1.1.34-Windows-x64.exe` kèm Java, checksum và bằng chứng kiểm thử được đính kèm khi native Windows CI và kiểm tra cài/gỡ song song hoàn tất.
