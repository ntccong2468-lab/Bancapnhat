# Tải VNCode 1.4.5 — cấu hình API Catalog và sửa license

[Tải bộ cài Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38625269821/artifacts/11923647521)
(đăng nhập GitHub nếu được yêu cầu).

Giải nén ZIP artifact, mở `out`, đóng VNCode và chạy
`VN-code-1.4.5-License-Diagnostics-Windows-x64.exe`.
SHA-256 đã đối chiếu sau khi tải về:

`be5343d8104973fe7e22882e5f5474a055434ebefcb9742d2b66b15f94efd625`

Build source `7b90774f8a8575850c9b72c1fb8a801fb82eedae`;
[run thành công](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38625269821).
159 kiểm thử Windows: 0 lỗi, 0 thất bại, 0 bỏ qua. 49 hợp đồng đóng gói Node
và 56 kiểm thử Portal đều qua. Khởi động Windows, giữ lịch sử, migration schema
3 → 5, snapshot rollback và DPAPI riêng cho khóa Catalog đều qua.

Trong **Cấu hình Znack → Cài đặt**, chọn cửa hàng và chứng thư, lưu các thay đổi,
kiểm tra chữ ký, nhập API KEY từ **Профиль → Данные участника** của National
Catalog, rồi bấm **Kiểm tra và lưu API KEY**. Chỉ thành công khi khóa khớp phiên
Catalog đăng nhập bằng CryptoPro và API chỉ đọc trả dữ liệu hợp lệ. Khi thành
công mới chuyển sang đăng ký thẻ/đồng bộ GTIN.

[Hướng dẫn và sơ đồ kết nối](../znack-catalog-api-configuration.md).
Nếu vẫn gặp lỗi sở hữu, bản này hiển thị `CATALOG_OWNER_...` để xác định điều
kiện chứng thư/INN/thời gian chưa đạt; thêm API KEY không bỏ qua điều kiện này.

Bản này còn sửa việc từ chối license vừa ký khi giờ Windows chậm vài giây,
giữ hạn và kiểm tra chữ ký. Windows có thể dùng múi giờ Việt Nam hoặc Nga;
thời điểm thực phải đúng, dùng đồng bộ giờ tự động. Không cần đổi múi giờ sang
Nga để chạy VNCode.

Portal 0.3.5 và khóa ký vncode-2 giữ nguyên, không cần triển khai lại website.
Đây là bộ cài kiểm tra, chưa phát hành stable. Chưa chạy toàn bộ ma trận nâng
cấp/workspace hoặc kết nối Catalog thật của seller. Các kiểm thử thông qua dữ
liệu giả lập không xác nhận kết quả trên tài khoản thực.

[Bằng chứng Windows và checksum](../validation/evidence/2026-10-15-vncode-145-catalog-config-ci.json).
