# VN code 1.2.1 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.1-preview.1/VN-code-1.2.1-preview.1-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.2.1-preview.1)

## Thay đổi trong phiên bản này

- Thêm Hướng dẫn VN code và sửa điều hướng tới tin tức, TN VED và trợ giúp.
- Cải thiện Dashboard, thẻ tin và bảng supply ở cửa sổ nhỏ, hỗ trợ giao diện sáng/tối.
- Giữ cột WB/GTIN và giá truy cập được khi mở kho GTIN; sửa trạng thái KIZ và ngữ cảnh shop.
- Tải TN VED ở nền giữ đúng truy vấn và trang kết quả người dùng đã chọn.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `VN-code-1.2.1-preview.1-Windows-x64.exe`. Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 601 kiểm thử Java/JavaFX và 29 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37980840932); mã bộ cài: `b5034df00a1e898124210437c522c92448ead5b3`.
- SHA-256: `2159133f48de2fcaaa2d39e5983157d9c713929c10479d0007f30463394d483f`; kích thước: 141094400 byte.

## Giới hạn

- Ozon: delivering contract found for third-party rFBS only; verify standard-FBS behavior and implement selection/label reminder
- Znack: automatic GTIN reallocation is not enabled solely on a section change; verified business rule/recovery remains required
- TN VED: catalog/import/search/backup core implemented; product mappings, AI provider, marking rules, Yandex and integrated restore remain incomplete
- GS1 RUS membership/mail/invoices/application autofill and per-certificate signing changes have no verified public source/API contract; not implemented in this preview
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Bancapnhat/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.
