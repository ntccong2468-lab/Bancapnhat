# Bàn giao kiểm tra VN Code 1.6.0 — 31.10.2026

- Mã ứng viên ứng dụng: `354c0e7f4d127db5750f2f4b23f6e6666aa3065e` trên nhánh `release/vn-code-1.6.0`.
- Lượt Windows kiểm thử và tạo bộ cài từ commit này: [GitHub Actions 41242700851](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41242700851). Lượt này chưa hoàn tất tại thời điểm ghi bàn giao; không coi bộ cài hoặc nâng cấp là đã xác minh.
- Lượt hồi quy Linux trên mã ứng viên, không gồm lớp GTIN khối lượng lớn chạy riêng: 3.605 ca, không thất bại, 2 ca bỏ qua vì chỉ chạy trên Windows. Lớp GTIN khối lượng lớn đạt 14/14 trên cùng mã nghiệp vụ, trước bản sửa kiểm tra quyền đọc chi tiết Ozon.
- Lượt mô phỏng 10.000 GTIN Ozon xác minh 10.000 dòng, mất khoảng 11 phút 58 giây sau khi dựng dữ liệu, với 60.000 lượt đọc giấy tờ giả lập. Kết quả không bao gồm thời gian mạng hoặc giới hạn API thật; hiệu năng danh mục lớn vẫn cần cải thiện.
- Các kiểm thử Catalog, ký, GTIN, FBS và PDF trong lượt này dùng dữ liệu/API giả lập. Chưa nghiệm thu một thẻ công bố thật, sửa thẻ thật, ký CryptoPro thật hoặc ghi GTIN đúng biến thể trên sàn thật.
- Lượt kiểm thử tổng trước sửa có một ca khởi động không ổn định; ca này đạt trong lượt tích hợp sau sửa và trong một nhóm 477 ca độc lập. Nguyên nhân ban đầu chưa được chứng minh.
- Công cụ tạo manifest đã được kiểm thử với khóa giả và MSI versioned. Gói portable 1.5.19 công khai đã được kiểm tra SHA-256 và `app.properties` trong JAR không ghim khóa manifest; kết quả này không chứng minh cấu hình của mọi bản cài khác. Chưa có bằng chứng workflow Windows tạo manifest ký bằng khóa production hoặc khóa đó khớp các app đang cài; kiểm thử nâng cấp EXE không chứng minh đường cập nhật MSI trong app.
- Chỉ công bố latest sau khi kiểm tra thành công Windows, đối chiếu đúng commit và checksum các tài sản, xác minh đường cập nhật phù hợp với app đang cài và xử lý các giới hạn nghiệm thu thật. Không dùng `prune` vì thao tác đó xóa bản phát hành cũ.
