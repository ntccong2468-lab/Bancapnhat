# VN code 1.1.34 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Vncode/releases/download/v1.1.34/VN-code-1.1.34-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.34)

## Thay đổi trong phiên bản này

- Thống nhất tên VN code trong cửa sổ, điều hướng, thông báo và bộ cài Windows.
- Bản cá nhân miễn phí; danh sách shop mới bắt đầu trống.
- Định danh bộ cài riêng, chương trình VNcodeApp và dữ liệu VNcodeData.
- Giữ chức năng WB/Ozon, in nhãn, kho KIZ, mapping GTIN và phục hồi đăng ký.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `VN-code-1.1.34-Windows-x64.exe`. Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 551 kiểm thử Java/JavaFX và 23 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Vncode/actions/runs/37845191448); mã bộ cài: `bfa5a689a0329b9f770f30aeefa62f071ba9d86c`.
- SHA-256: `d3970ee5ca88f72a810bbedb807c84cdb7201b96c0d2ef132d0a7a01cea6ac79`; kích thước: 140996096 byte.

## Giới hạn

- Chưa nghiệm thu GS1/CryptoPro, tài khoản seller và máy in thật.
- Thêm/thay GTIN production WB/Ozon vẫn bị khóa chờ xác minh hợp đồng API chính thức.
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Vncode/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.
