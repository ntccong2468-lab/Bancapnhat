# Báo cáo yêu cầu VNCode 1.5.1

Đã phát hành Latest tại https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.1, nguồn `f9da70ca98734b8c5daed0a6ef49b38df9723f54`.

Đã viết luồng báo cáo/sửa thẻ Znack lỗi WB/Ozon: chọn tất cả, xem lỗi, Chỉnh sửa/Hủy, đề xuất trước/sau, bổ sung trường và giấy tờ, Áp dụng. Bảng đóng trước khi chạy nền; viên thuốc dưới giữa bảng theo dõi từng thẻ. Giữ GTIN và đúng good_id; không coi receipt là thẻ đã xuất bản.

Mẫu mặc định `vncode-catalog-official-v3` áp dụng API National Catalog 5.68: giữ TN VED hợp lệ; node bốn/mười số; đúng nhóm Green; schema có trường phụ thuộc và kiểu dữ liệu. Phạm vi giấy tờ không khớp được báo rõ để seller xác nhận hoặc thay giấy tờ, không bịa thành lý do từ chối của Catalog. Giữ đúng biến thể/size và checkpoint cũ; bảo vệ thẻ xanh đã xuất bản. Giữ các thay đổi GTIN theo lô WB/Ozon, KN3/thay KIZ đỏ, sửa năm trường sản phẩm, logo VN Liquid Glass, schema 6 và ưu tiên FBS.

Java đã được thực thi mới đầy đủ ở run **39286749991**, nguồn `96e20614882488a2d60319fc29f4ca62b6cb9b81`: **1924 tests, 0 failures, 0 errors, 2 platform skips**. Lượt native `39294782641` dùng lại đúng archive/JAR đã qua, xác minh SHA-256 và app/Java test/dependency/packaging không đổi. **Java không chạy mới trong lượt phục hồi**; biên nhận chứa nguồn thực thi và hash nguyên bản. Toàn bộ native, DPAPI, 288 bố cục và 16 đường nâng cấp giữ dữ liệu chạy lại và qua, gồm 1.5.0 → 1.5.1. 77 ca công cụ release qua; nhóm Linux liên quan 263 ca và 2 ca checkpoint bổ sung chạy riêng đều qua.

Đã tải hết EXE công khai và đối chiếu SHA-256/size với GitHub/CI. Bộ cập nhật production thực sự đọc endpoint Latest và trả AVAILABLE cho phiên bản cài đặt kiểm tra 1.5.0. Request chạy từ Linux với proxy môi trường, kiểm tra riêng bộ chọn asset Windows; chưa quan sát thông báo trên desktop seller thực tế.

**Chưa nghiệm thu production:** tạo/sửa/ký thẻ thật bằng CryptoPro doanh nghiệp, license production và lượt ghi WB/Ozon thật từ desktop bản này. API Catalog/sàn trong đợt nghiên cứu chỉ đọc; kiểm thử ghi dùng máy chủ giả lập. Các cờ nghiệm thu này giữ false. Lỗi URL nền và lỗi dịch vụ GitHub trước đó được giữ trong hồ sơ `2026-10-19-vncode-151-upgrade-url-fix.json` và `2026-10-19-vncode-151-native-recovery.json`.
