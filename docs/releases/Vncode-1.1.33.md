# VN code 1.1.33 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Vncode/releases/download/v1.1.33/Vncode-1.1.33-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Vncode/releases/tag/v1.1.33)

## Thay đổi trong phiên bản này

- Chọn từng sản phẩm hoặc nhiều sản phẩm để đăng ký và phục hồi GTIN.
- Lưu checkpoint trước cấp GTIN; giữ GTIN/feed/good ID và kết quả đã có khi tiếp tục tác vụ.
- Hiển thị yêu cầu kiểm tra kết quả khi mất phản hồi; chặn tự cấp lại hoặc gửi trùng.
- Migration schema 3 → 4 có snapshot và kiểm tra toàn vẹn dữ liệu.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `Vncode-1.1.33-Windows-x64.exe`. Các bản từ 1.1.34 dùng `%LOCALAPPDATA%\VNcodeApp` và `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 553 kiểm thử Java/JavaFX và 18 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Vncode/actions/runs/37677001156); mã bộ cài: `3b5c725ec8b481556cb6635859b08dcdb66ab053`.
- SHA-256: `4214ed4a5f0f230389a69f87089404ad8ef200ba4f05dea75d4230e5eeee2731`; kích thước: 141037056 byte.

## Giới hạn

- Chưa nghiệm thu GS1/CryptoPro, tài khoản seller và máy in thật.
- Thêm/thay GTIN production WB/Ozon vẫn bị khóa chờ xác minh hợp đồng API chính thức.
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.
- Đây là bản thử lịch sử. Dùng VN code 1.1.34 hoặc bản cập nhật mới hơn để có định danh cài đặt và dữ liệu riêng hiện tại.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Vncode/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.
