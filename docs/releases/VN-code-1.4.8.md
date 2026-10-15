# VNCode 1.4.8

Sửa lỗi tải bản cập nhật đến 100% nhưng không mở được bộ cài.

- Hoàn tất tải và chuyển sang bước cài đặt đúng cách, sửa lỗi “Task must only be used from the FX Application Thread”.
- Lỗi chuẩn bị/cài đặt được báo riêng với lỗi tải. Nếu không mở được bộ cài, app giữ hoạt động và hiện chi tiết để kiểm tra.
- Giữ kiểm tra SHA-256, sao lưu trước khi cài và chờ công việc nghiệp vụ hoàn tất. Giữ lại nguyên nhân lỗi sao lưu trong báo cáo.
- Giữ giao diện Ozon FBS, Catalog và các nâng cấp từ 1.4.7.

**Cách cài lần này:** app 1.4.4–1.4.7 vẫn có lỗi chuyển từ tải sang cài. Chọn **Chỉ tải bộ cài**, hoặc tải EXE từ mục Assets trên trang release, đóng VNCode rồi chạy EXE để nâng cấp. Không gỡ/xóa dữ liệu trước. Bản 1.4.8 sửa bước chuyển này cho các lần cập nhật tiếp theo.

Bộ cài Windows x64 có Java đi kèm. Website Portal 0.3.5 và license key ID vncode-2 giữ nguyên; không cần triển khai website lại.

Kiểm thử giao diện dùng bộ cài mô phỏng để xác nhận đúng luồng tải → chuẩn bị/cài → yêu cầu thoát. Cài đặt/nâng cấp native được kiểm tra riêng trên Windows. Không dùng kết quả fixture để xác nhận toàn bộ nghiệm thu tài khoản Ozon/Catalog thực hay license production nhiều máy/offline/thu hồi.
