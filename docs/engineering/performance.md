# Hiệu năng

Đo trước khi tối ưu. Ghi thao tác, số dòng/đơn, dữ liệu giả hay thật, hệ điều hành, môi trường, số lần chạy và chỉ số thích hợp như thời gian trung vị/p95, bộ nhớ hoặc số yêu cầu. Không suy diễn số đo trên Xvfb/Linux hoặc API giả thành thời gian thực trên Windows/sàn.

Ưu tiên đường người dùng cảm nhận: khởi động, chuyển màn, bộ lọc và cuộn danh sách, ảnh thu nhỏ, đồng bộ, nhận đơn và in PDF. Không chạy I/O mạng hoặc SQLite kéo dài trên JavaFX Application Thread; giữ giao diện có trạng thái chờ và không xóa dữ liệu đã hiển thị khi không cần thiết.

Kiểm tra số lần đọc và kích thước lô, phân trang, giới hạn API, retry và khả năng tiếp tục sau lỗi. Cache phải có giới hạn và không thay thế việc đọc mới khi cần xác minh trước ghi hay sau khi sàn xác nhận. Đảm bảo tối ưu không làm sai thứ tự, vùng seller, lựa chọn, KIZ hoặc trạng thái hàng đợi.

`SellerWorkspacePerformanceTest` và `tools/windows-seller-workspace-benchmark.ps1` là điểm bắt đầu cho đo cục bộ/Windows; chỉ chạy với fixture và điều kiện phù hợp. Lặp lại phép đo trước/sau cùng dữ liệu và báo cả kết quả không cải thiện, giới hạn mẫu và test hồi quy.
