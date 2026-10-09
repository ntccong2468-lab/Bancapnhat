# Đối chiếu Wbcode 1.3.1 cho VN code

Người dùng yêu cầu đọc và chạy bản WCode mới nhất rồi áp dụng cho cùng VN code Windows. Release 1.3.1 xuất hiện lúc 19:17 UTC ngày 09/10/2026, trong lúc VN code 1.2.1 đang đóng gói. Tiếp tục thực hiện theo phê duyệt hiện có.

- Chạy bộ cài 1.3.1 chính thức trên runner Windows tạm, đối chiếu SHA-256 `2632e9a06db84990476d97f1f962b61f5dffcec40ccc2c90c7bb7b304b4a0533`; quan sát lần mở đầu, không nhập shop/kích hoạt license.
- FBS: nút xuất nhãn hiện và có thể yêu cầu in ngay khi mở supply. Khi đồng bộ đang chạy, chỉ ghi nhận một yêu cầu; in sau khi đồng bộ thành công. Thất bại, đổi shop/supply hoặc xóa ngữ cảnh phải hủy yêu cầu. Không in dữ liệu cache sau khi đồng bộ thất bại.
- Giữ layout 1.2.0 đã duyệt, dữ liệu riêng, UUID bộ cài `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, app cá nhân miễn phí. Nâng phiên bản Windows lên 1.2.2 nếu bổ sung hành vi sau bản 1.2.1 đã phát hành.
- FBO mới yêu cầu GTIN trên thẻ WB: chưa xác minh được quy tắc nhận diện GTIN so với barcode từ mô tả. Tài liệu WB đọc lại trả HTTP 498. Không đổi barcode thành GTIN chỉ dựa trên chuỗi số hoặc mapping cục bộ. Ghi rõ phần này chưa hoàn tất trong Preview.
- Quy trình ký của VN code hiện theo từng feed/thẻ, không có scanner báo “Catalog changed during scan”; không thêm vòng lặp mutation suy đoán. GS1/mail/thanh toán chưa có module/API được xác minh. Các sửa license không áp dụng vì VN code cá nhân không dùng giấy phép WCode.
- Kiểm tra nâng cấp từ 1.1.34, 1.2.0 Preview và bản 1.2.1 Preview vừa phát hành; EXE chưa ký, cài thủ công và không đưa vào tự cập nhật.

Nguồn mô tả: https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.1. Nguồn công khai test-wcode vẫn là commit `8f7c3d1ac8598158029b8d037e2d3c128e240727`, phiên bản 1.1.32.
