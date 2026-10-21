# Nghiệm thu production V3

Chưa thực hiện thao tác ghi/cấp GTIN/ký/đồng bộ WB thật trong tác vụ này.
Không có bằng chứng Level 2, Level 3 hoặc Level 4.

Điều kiện tiếp tục:
1. NK 5.68 đã đối chiếu tài liệu. Còn cần quyền đọc tài liệu WB/CryptoPro hiện hành,
   đối chiếu phần chưa xác minh và chuẩn bị môi trường nghiệm thu riêng.
2. Seller cấu hình tài khoản/chứng thư an toàn trong ứng dụng trên Windows; không gửi
   PIN/private key/mật khẩu/token vào chat hoặc Git. Kiểm tra quyền, GS1 và hạn mức.
3. Seller chọn đúng shop, nmID/chrtID, GTIN, nội dung thẻ và duyệt cấp GTIN nếu cần.
4. Seller xem payload và xác nhận GỬI THẺ THẬT. Ghi feed ID/thời điểm, đọc lại trạng thái.
5. Khi server cho ký, seller xem đúng XML/hash/chứng thư và duyệt riêng thao tác ký.
6. Đọc lại đúng GTIN/good_id/môi trường và trạng thái công bố: mới được Level 4.
7. Nếu cần WB, seller duyệt thay đổi từng biến thể; đọc lại toàn bộ size/barcode để xác minh.

Level 1 cần build/unit/migration thực chạy; Level 2 cần sandbox thực; Level 3 cần
production tiếp nhận và đọc lại; Level 4 cần công bố được API production xác nhận.
MockWebServer/unit tests không được tính vào Level 2–4. Bộ cài 1.5.7 và các proof cũ
không là bằng chứng nghiệm thu V3. Không tự động merge production hoặc phát hành.
