# Điều tra và sửa lỗi

1. Xác định phiên bản, cửa hàng/sàn, thao tác, trạng thái trước và sau, cùng phạm vi ảnh hưởng. Đọc log liên quan và đối chiếu mã nguồn; che thông tin nhận dạng, token, mã KIZ và dữ liệu seller trước khi chia sẻ.
2. Tái hiện bằng dữ liệu giả hoặc bản sao dữ liệu được phép. Cô lập thư mục ứng dụng bằng thuộc tính Java `vncode.appdata.dir` trong môi trường thử; không trỏ kiểm thử vào dữ liệu seller đang dùng.
3. Viết kiểm thử tái hiện khi phù hợp, xác nhận nguyên nhân và thay đổi nhỏ nhất; kiểm tra cả các trường hợp lỗi, timeout và khởi động lại. Chạy lại kiểm thử liên quan sau khi sửa.
4. Với đồng bộ hoặc yêu cầu đã gửi, phân biệt chưa gửi, đã nhận và kết quả chưa rõ. Đọc lại trạng thái trước khi thử lại để tránh tạo thẻ, mua KIZ hoặc ghi sàn trùng.
5. Với khởi động hoặc nâng cấp, xem `Launcher`, `LocalDataMigrationGate`, `AppDataLock`, `LocalDataSnapshotService` và `Database`. Kiểm tra khóa dữ liệu, snapshot, khôi phục và tương thích dữ liệu cũ trước khi kết luận.

Trên Windows, log khởi động mặc định ở `%LOCALAPPDATA%\VNcodeData\logs\startup.log`. Trong PowerShell, đọc bằng `Get-Content -LiteralPath "$env:LOCALAPPDATA\VNcodeData\logs\startup.log" -Tail 100`; không dán trực tiếp đường dẫn làm lệnh. Log có thể chứa đường dẫn cá nhân và thông tin kỹ thuật cần che.

Nếu không tái hiện được, báo rõ điều kiện, bằng chứng và giả thuyết; không tuyên bố đã sửa nguyên nhân chưa được xác định.
