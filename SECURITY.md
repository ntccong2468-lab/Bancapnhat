# Bảo mật — VN code

VN code là ứng dụng cá nhân miễn phí theo lựa chọn của chủ dự án. App không kích hoạt thuê bao,
không tạo giấy phép WCode và không kết nối máy chủ giấy phép hoặc báo cáo lỗi của WCode.
Các báo cáo lỗi chỉ hiển thị trong dialog có nút sao chép để người dùng tự xem và chia sẻ.

- WB/Ozon và GS1/Chesty Znak vẫn yêu cầu tài khoản, quyền truy cập và chứng thư hợp lệ của người dùng.
- Giữ xác nhận mutation, kiểm tra shop/marketplace, idempotency và đối soát sau timeout.
- Adapter đồng bộ GTIN production vẫn khóa ghi khi hợp đồng API chưa được xác minh.
- Database và cache nằm trong thư mục VN code riêng; không đọc hoặc sửa dữ liệu/giấy phép WCode.
- Không ghi credential, raw KIZ hoặc thông tin cá nhân vào log/báo cáo.
- Update phải qua kiểm tra chữ ký và checksum hiện có. Không coi gói chưa ký là bản cập nhật đã tin cậy.

Bản thử Windows hiện chưa ký Authenticode và chưa có signed update manifest; cài thủ công từ
GitHub của chủ dự án. Profile obfuscation chỉ được dùng sau khi đã nghiệm thu với Java 25/FXML.
