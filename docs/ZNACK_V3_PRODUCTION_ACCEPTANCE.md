# Nghiệm thu bản sửa đăng ký Znack 1.5.8

Luồng mới chạy bên dưới màn hình/nút 1.5.7. Không có màn hình V3 riêng để nghiệm thu.
Đã có bằng chứng Level 1 cục bộ trong `ZNACK_V3_TEST_REPORT.md`; chưa thực hiện thao tác
cấp GTIN/gửi thẻ/ký/đồng bộ seller thật, không có bằng chứng Level 2, 3 hoặc 4.

Điều kiện tiếp tục:
1. Mạng GitHub/quyền đã xác minh; Windows build đang chạy trên đúng commit;
   giữ fresh Java execution, native smoke, DPAPI và probe nâng cấp trực tiếp `-FromStable157`.
   So sánh fingerprint dữ liệu, registration, shortcut và hash EXE/embedded MSI.
2. Seller cấu hình tài khoản/chứng thư an toàn trên Windows, kiểm tra quyền, GS1, hạn mức.
   Không đưa PIN/private key/token vào chat hoặc Git; xác minh CryptoPro thực.
3. Dùng các nút/hộp duyệt hiện có để chọn đúng shop, nmID/chrtID, GTIN, giấy tờ và nội dung.
   Xác nhận riêng việc cấp mã/gửi/ký hoặc đồng bộ khi ứng dụng yêu cầu.
4. Sau gửi, giữ feed ID/thời điểm và đọc lại đúng thẻ. Feed được tiếp nhận hoặc chữ ký
   được nhận không phải bằng chứng công bố.
5. Đọc lại đúng GTIN/good_id/môi trường/nội dung đã duyệt và trạng thái công bố trước Level 4.
   Kết quả chưa rõ chỉ được đối chiếu, không cấp thêm/gửi lại để né trạng thái UNKNOWN.
6. Kiểm tra đồng bộ WB bằng preview/consent/readback hiện hữu, giữ toàn bộ size/barcode.
   Kiểm tra hồi quy Ozon và các tác vụ cũ đang chờ trong cùng database.
7. Nghiệm thu production đầy đủ cần hồ sơ đúng 1.5.8. Quyền phát hành Latest trực tiếp của
   chủ dự án được ghi riêng, giới hạn đúng commit/run 1.5.8 và vẫn cần toàn bộ Windows/native/upgrade
   thật; không dùng proof 1.5.7 thay kết quả mới hoặc gọi quyền phát hành là nghiệm thu seller.

Level 2 cần sandbox thực; Level 3 cần production tiếp nhận và đọc lại; Level 4 cần API
production xác nhận công bố đúng nội dung. Mock/unit tests và app-image Linux không thay
các mức này hoặc bộ cài Windows. Gateway V3 độc lập ở lượt thử nghiệm vẫn có policy riêng;
nó không là đường của nút đồng bộ WB cũ trong bản sửa giữ nguyên giao diện.
