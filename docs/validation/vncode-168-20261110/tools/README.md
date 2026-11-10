# Công cụ kiểm chứng 1.6.8 — bản lưu nguồn

Các tệp là bản sao nguyên byte công cụ đã dùng cho source38906/build42828436446/release440439895. Không phải runtime, bộ cài, khóa, JAR hay một kết quả PASS độc lập. Receipt thật ở thư mục cha.

Script giữ đường dẫn lịch sử dưới `/tmp/vncode-168-release-audit`, `/tmp/vncode-168-independent-verifier` và `/tmp/vncode-168-exact167-probe`. Một số đầu vào được pin bằng SHA ở vị trí đó; bản lưu này không chứa rawActionsZIP, bộ cài, JAR hoặc mọi snapshot thô. Không tự chạy/chỉnh pins cho nguồn khác hoặc gọi archive là gói portable tự đủ đầu vào. Xem `archive-manifest.json` và bàn giao cho lệnh/phạm vi. Chuẩn bị phiên bản công cụ mới nếu source/run/đầu vào thay đổi.

Probe dùng JAR gốc1.6.7, không override version; chỉ giả lập chọn tài sản Windows sau lượt đọc mạng trên Linux. Giữ proxy/CA và không suy ra truy cập anonymous hoặc popup máy seller. Không có license activation, ký, giao dịch seller hoặc email trong các công cụ này.
