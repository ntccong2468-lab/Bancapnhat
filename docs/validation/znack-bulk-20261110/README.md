# Bằng chứng đăng ký Znack lô lớn

Các JSON API là **tóm tắt số liệu thực tế**, không phải response chứa dữ liệu seller. Tên cửa hàng được thay bằng `REPORTED_WB_STORE`; định danh sản phẩm, khóa API, mã KIZ và hồ sơ thật không được đưa vào Git. [Provenance các bản che định danh](archive-redactions.json) giữ SHA-256 của bản riêng tư và bản tóm tắt.

`LiveWbBulkReadProbe.java.txt` giữ logic probe đọc nguồn bằng production reader/review, endpoint WB chính thức và profile tạm; chỉ nhãn chẩn đoán tên cửa hàng được tổng quát hóa trong bản lưu. Không dùng fixture license hoặc ghi Catalog/sàn. Profile tạm sau đo đã xóa. Đây không phải kiểm thử giao diện hay tạo thẻ Catalog thật.

[Receipt tập trung283ca](focused-final-receipt.json) kết hợp16lớp theo kết quả mới nhất, không cộng trùng. Lượt đầu151ca có2 lỗi kỳ vọng fixture và bị dừng; lịch sử giữ riêng. [Hai ca RED](baseline-red.json) chạy đúng mã phát hành1.6.7.

[Chi tiết, lệnh và đề xuất](../2026-11-10-znack-bulk-preflight.md). Chưa phải bản phát hành mới; Latest vẫn1.6.7.

Nguồn kết hợp cuối3990072: [Windows502/38đạt](final-windows.json), [Linux45/7đạt](final-scaled-linux.json), [package sạch](package-final.json), [kết luận có lệnh/phạm vi/giới hạn](acceptance-summary.json). Giữ riêng [Windows core lần đầu FAIL](first-windows-failure.json) và [candidate dừng108ca](first-keepalive-candidate-stopped.json); không xóa lịch sử thất bại hoặc dùng chúng làm nghiệm thu cuối. Test300 giữ nguyên toàn file/hash/600s/assertion. Bản sửa KIZ [ở bàn giao riêng](../2026-11-10-kiz-exhausted-recovery.md);16lớp247ca KIZ là tập con của502Windows, không cộng thêm.
