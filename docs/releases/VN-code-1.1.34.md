# VN code 1.1.34 — bản thử Windows

Đổi thương hiệu ứng dụng thành **VN code** trong cửa sổ, thanh điều hướng, phần giới thiệu, thông báo RU/EN/VI/ZH và bộ cài Windows. Mã Java/FXML dùng namespace `com.vncode.app`, JAR là `VNcode-1.1.34.jar`, launcher là `VN code.exe`.

## Nâng cấp và dữ liệu

- Cài mới lưu dữ liệu ở `%LOCALAPPDATA%\VNcodeData`, chương trình ở `VNcodeApp`.
- Nếu đã có thư mục dữ liệu `WCodeData`, bản mới dùng lại chính thư mục đó: database, giấy phép, lịch sử in/GTIN, finance và snapshot. Không sao chép database đang mở, không yêu cầu đổi tên thủ công.
- Giữ upgrade UUID của bộ cài 1.1.10+, khóa `.wcode.lock`, journal khôi phục và định danh tác vụ cũ. Thuộc tính `vncode.*` được ưu tiên; `wcode.*` vẫn tương thích.
- Giữ schema 4 và luồng phục hồi từ 1.1.33; không đổi hợp đồng API hoặc khóa xác minh giấy phép.

## Chức năng và nguồn

Giữ chức năng WB/Ozon, KIZ, in nhãn, tài chính, đồng bộ GTIN và phục hồi đăng ký của bản trước. Nguồn nền là WCode 1.1.32 công khai do Nguyễn Anh Tuấn / TuanDev phát triển cùng module GTIN của fork. Luồng phục hồi được triển khai theo mô tả công khai WCode 1.1.75; chưa có mã nguồn chính xác của 1.1.75 để tích hợp.

Bản thử chưa ký Authenticode và chưa phát hành signed update manifest: tải/cài thủ công. Ghi GTIN production WB/Ozon vẫn bị khóa chờ xác minh hợp đồng API. Chưa nghiệm thu trên tài khoản GS1/Chesty Znak thật, CryptoPro, seller thật hoặc máy in của người dùng.

Bộ cài `VN-code-1.1.34-Windows-x64.exe` kèm Java, checksum và bằng chứng kiểm thử được đính kèm khi native Windows CI hoàn tất.
