# Kết quả bảo mật của VN Code 1.6.11

Nguồn app `cf3d8d695f7caa3b39d934145b7ff0445c50e194`, [Security CI 44282368837](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/44282368837), attempt 1: **FAIL được giữ nguyên**. Java có 9 finding, gồm một high; JavaScript 0 finding/PASS, lint/audit PASS. OpenQodex và dependency review bị SKIPPED theo điều kiện manual có sẵn, không phải các scan đã chạy.

Tám finding giữ từ bản công bố 1.6.10; một medium chuyển quyền lock đã có từ candidate đầu của 1.6.11. Cả 9 bản ghi đầy đủ khớp ba candidate trước; không có finding mới ở nguồn cuối. Không đổi policy, ngưỡng, suppression hay waiver. 790 Java được liệt kê extracted; lỗi extraction GS1SignatureInk cũ vẫn còn, nên không gọi độ phủ tuyệt đối hoặc toàn bộ bảo mật đạt.

- High tại `CryptoProSignedCertificateReader.java:71`: SHA-1 tính thumbprint 40 hex để nhận diện chứng thư Windows, không ký hồ sơ. Code còn đối chiếu issuer/serial và signer; CryptoPro/server xác minh CMS. Chưa xác nhận đường bỏ qua kiểm chữ ký trong phạm vi đã đọc. Không đổi thẳng SHA-256 vì sẽ làm mất tương thích chọn chứng thư; finding vẫn giữ.
- Medium tại `ZnackSigningSession.java:305`: lock chuyển sang ReconfigurationScope. Ba caller ở ZnackRepository dùng try-with-resources; scope đóng trong finally, factory dọn RuntimeException. Chưa tái hiện rò lock thông thường; nhánh Error trước khi trả scope chưa chứng minh an toàn, có thể cần phục hồi tiến trình nếu xảy ra. Không coi đã sửa.
- Các finding còn lại thuộc lock, lệnh đường dẫn tương đối và tệp tạm. Chúng tiếp tục được lưu để xử lý theo phạm vi riêng, không bị xóa chỉ vì kiểm thử chức năng đạt.

Windows/native/bộ cài, public download và updater đã có nghiệm thu riêng trong [báo cáo phát hành](FINAL-REPORT.md). Kết quả đó không thay kết quả Security FAIL. Lượt phát hành không mua SUZ có phí, ký chứng thư seller, gửi thư, kích hoạt license hoặc ghi dữ liệu seller thật.

[Receipt thực tế và SHA artifact](security.json).
