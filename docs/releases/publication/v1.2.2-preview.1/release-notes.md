# VN code 1.2.2 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.2-preview.1/VN-code-1.2.2-preview.1-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.2.2-preview.1)

## Thay đổi trong phiên bản này

- Cho yêu cầu in FBS trong khi lô đang tải; chỉ in sau khi đồng bộ thành công.
- Chống nhấp trùng; hủy yêu cầu in khi tải lỗi, đổi shop/lô hoặc đóng màn hình.
- Kiểm tra lại shop, lô và phiên tải trước hộp thoại; không dùng dữ liệu cache sau lỗi.
- Giữ bố cục Dashboard, bảng supply, hướng dẫn và hỗ trợ ngôn ngữ hiện có.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `VN-code-1.2.2-preview.1-Windows-x64.exe`. Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 607 kiểm thử Java/JavaFX và 31 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37984754861); mã bộ cài: `d17ced942afd4231ca6821013c34e0e1ee11e11c`.
- SHA-256: `4ef4b3881337f1414d684d0be0ced9b8543be6bf114031ec31e5c83227d93541`; kích thước: 141094400 byte.

## Giới hạn

- Ozon: delivering contract found for third-party rFBS only; verify standard-FBS behavior and implement selection/label reminder
- Znack: automatic GTIN reallocation is not enabled solely on a section change; verified business rule/recovery remains required
- TN VED: catalog/import/search/backup core implemented; product mappings, AI provider, marking rules, Yandex and integrated restore remain incomplete
- GS1 RUS membership/mail/invoices/application autofill and per-certificate signing changes have no verified public source/API contract; not implemented in this preview
- FBO printing only WB-card GTIN, missing-GTIN list/column and KIZ purchase-stage error report have not been implemented or verified; existing FBO barcode behavior is retained in this preview
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Bancapnhat/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.
