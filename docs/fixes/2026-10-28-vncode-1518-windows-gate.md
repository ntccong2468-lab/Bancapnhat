# Hoàn tất chốt phát hành 1.5.18 — 28.10.2026

Latest vẫn 1.5.17 khi bắt đầu lượt này. Build `40561909841` đúng nguồn `9c026d8` đã chạy đủ 3.087 kiểm thử Windows: 0 failure, 1 error, 2 skip. Lỗi duy nhất là `Gs1AutomaticSessionTest.aSlowStoreDoesNotHoldTheOtherStoresSessionLock`: chờ cửa hàng thứ hai tối đa 2 giây, sau đó bỏ qua việc chờ worker kết thúc trước khi dọn thư mục. Windows ghi nhận timeout và file SQLite còn mở. Publisher `40565064080` dừng ngay tại bước chờ build; không có bộ cài này được công bố.

Đã sửa riêng fixture: dùng HTTP budget đủ dài để giữ request thứ nhất đến khi test chủ động nhả latch; kiểm tra thứ tự hoàn tất hai cửa hàng thay cho tốc độ ổ đĩa 2 giây. Giới hạn chờ thứ hai 15 giây, ngắn hơn thời gian giữ request, nên lỗi dùng chung lock vẫn làm test thất bại. Release latch trong finally, dừng và chờ worker kết thúc trước khi dọn DB. Kiểm tra deadline ngắn vẫn giữ nguyên điều kiện timeout nghiệp vụ.

Nguồn mới `5330e9931b8b7155ebe26f3b589a7a2a747e0696`, hash cây `d3510008fe93d7cfe4b523fd1d797801f01b4cb6c18781e4bd569d207db647fe`. Mã production, POM và build script giữ nguyên từng byte từ `9c026d8`, bao gồm mọi sửa sản phẩm và PDF đã kiểm chứng; không gọi ghi API seller hoặc dùng KIZ lại trong lượt đóng gói. Lượt mục tiêu **8/8** GS1 đạt, sau đó khởi động full Linux và Windows từ nguồn mới. Windows build được chốt `40634346229`.

Sửa thêm guard của publisher từ `[1,5,17]` thành `[1,5,18]`: cho phép công bố lần đầu từ 1.5.17 và kiểm tra lại chính 1.5.18, vẫn chặn hạ Latest nếu đã có 1.5.19. Đã chạy trực tiếp ba trường hợp trên đoạn kiểm tra thực và kiểm tra cú pháp các khối JavaScript. Không thay nội dung hoặc xóa release cũ.

Các kiểm chứng thật và benchmark ngày 27.10 được giữ trong biên nhận cũ; không mô tả chúng là lượt ghi mới ngày 28.10. Full suite, native và Latest nguồn mới được ghi riêng sau khi có kết quả. Không dùng artifact của build lỗi `40561909841` hoặc run đã hủy `40556964700` để phát hành.
