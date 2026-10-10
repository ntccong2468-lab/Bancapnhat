# VN code 1.2.0 — Windows

**VN code là dự án do Nguyễn Thành Công phát triển và quản lý**, hỗ trợ công việc bán hàng trên Wildberries và Ozon.

[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.2.0-preview.1/VN-code-1.2.0-preview.1-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.2.0-preview.1)

## Thay đổi trong phiên bản này

- Dashboard có sáu thẻ chức năng, tin tức, thông báo chưa đọc và nội dung tin có định dạng.
- WB FBS có biểu mẫu giao hàng, tìm điểm nhận, lưu lựa chọn theo shop và đối soát kết quả.
- Phân trang supply và tải ảnh nền; cải thiện bảng, lịch sử và mẫu tem mặc định.
- TN VED có danh mục gốc, tra cứu, nhập CSV/JSON và sao lưu; phần tích hợp sâu còn đang phát triển.

## Cài đặt và dữ liệu

Java được đóng gói kèm. Đóng ứng dụng trước khi chạy `VN-code-1.2.0-preview.1-Windows-x64.exe`. Chương trình ở `%LOCALAPPDATA%\VNcodeApp`, dữ liệu ở `%LOCALAPPDATA%\VNcodeData`. Danh sách shop và tài khoản nghiệp vụ do người dùng thiết lập.

## Kiểm chứng bộ cài

- 589 kiểm thử Java/JavaFX và 27 kiểm thử công cụ đạt trên Windows.
- Launcher và migration giữ lịch sử đã được kiểm tra trên runner Windows.
- [CI Windows](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37865887385); mã bộ cài: `e7c4c9e1031f64d576cb696b9a7fa396fc7c86a3`.
- SHA-256: `2290088fb9e1065430f4d9c5e9b47185c5581a93996b3ad692e8bbc846756ad1`; kích thước: 141078016 byte.

## Giới hạn

- Ozon: delivering contract found for third-party rFBS only; verify standard-FBS behavior and implement selection/label reminder
- Znack: automatic GTIN reallocation is not enabled solely on a section change; verified business rule/recovery remains required
- TN VED: catalog/import/search/backup core implemented; product mappings, AI provider, marking rules, Yandex and integrated restore remain incomplete
- Bộ cài chưa ký Authenticode; tải và cài thủ công, chưa có manifest tự cập nhật.
- Kiểm thử fixture và Windows không thay thế nghiệm thu bằng chứng thư, hộp thư, shop hoặc máy in thật.

Ghi nhận bản quyền thành phần: [NOTICE](https://github.com/ntccong2468-lab/Bancapnhat/blob/HEAD/NOTICE.md). Ngày 10/10/2026 chỉ cập nhật thông tin công bố; bộ cài, mã commit và kết quả kiểm thử được giữ nguyên.
