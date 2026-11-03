# Rà soát bảo mật trước bản 1.6.1

Nguồn ban đầu: `e78e91e`; CodeQL run `41707863034`. Lint/workflow, kiểm thử portal, audit npm và CodeQL JavaScript đạt. CodeQL Java không còn bốn cảnh báo TOCTOU ở đồng hồ license sau khi kiểm tra và đọc đồng hồ trong cùng khóa; vẫn trả một cảnh báo mức high tại SHA-1 của chứng thư.

## Thumbprint CryptoPro

`CryptoProSignedCertificateReader` tính đúng định danh SHA-1 dài 40 hex mà Windows certificate store và CryptoPro dùng để chọn chứng thư. Đây không phải thuật toán ký CMS, mật khẩu, token, hoặc checksum cập nhật. Đổi giá trị này thành SHA-256 sẽ làm mọi chứng thư đã chọn không khớp cấu hình hiện có.

Reader chỉ nhận đúng một signer, tìm chứng thư theo issuer/serial của signer, đối chiếu thumbprint đã chọn rồi đọc danh tính. Reader không xác thực chữ ký CMS hoặc chuỗi tin cậy: CZ/Catalog làm việc đó khi nhận yêu cầu; app phải tiếp tục kiểm tra kết quả API. Không dùng kết quả reader như bằng chứng Catalog đã chấp nhận/ký/công bố.

Lượt này tách phép tính thumbprint vào một hàm riêng và ghi nhận ngoại lệ CodeQL **chỉ ở hàm tương thích này** bằng annotation kèm lý do. Không thay đổi thuật toán CMS, không loại query/rule khỏi cấu hình quét, không thay đổi ngưỡng hoặc mã gate, không tắt kiểm tra TLS/chữ ký. Các kiểm thử chứng thư giả vẫn kiểm tra signer khác chứng thư đã chọn, thiếu signer, nhiều signer và dữ liệu sai. Đây là phân loại theo mục đích sử dụng, không phải tuyên bố SHA-1 an toàn cho chữ ký hoặc loại bỏ mọi rủi ro collision.

## Giới hạn còn lại

Các cảnh báo mức medium về thư mục tạm, đường dẫn PowerShell và khóa ký vẫn cần được đánh giá tiếp; không tuyên bố audit không còn phát hiện. Prototype migration credential chưa được tích hợp vào runtime hay áp dụng vào dữ liệu seller. Dependency Review của PR trước không chạy được do GitHub Dependency Graph chưa bật; không tự thay đổi cài đặt repo để bỏ qua.

Quét lại trên `df10e5c`, run `41709620634`: JavaScript/lint/audit đạt; Java vẫn trả 8 cảnh báo, gồm 1 high ở thumbprint SHA-1. Annotation không loại cảnh báo khỏi SARIF của cấu hình này; gate vẫn báo thất bại và không được sửa để trở thành xanh. Rà soát mục đích sử dụng ở trên phân loại điểm này là định danh tương thích Windows/CryptoPro, không phải thuật toán ký cần thay. Báo cáo phát hành phải giữ kết quả quét này, không ghi “CodeQL đạt toàn bộ”. Các biên nhận Windows/native/nâng cấp là kiểm tra riêng.
