# Ozon FBS: nhận đơn và xuất PDF theo lô — 30/10/2026

- Đối chiếu tĩnh WBCode 1.3.14, bộ cài khớp SHA-256 công bố. WBCode phân trang và chuẩn bị KIZ trước khi nhận; một lỗi có thể dừng các đơn còn lại. Không sao chép mã ứng dụng đó.
- VN Code giữ lựa chọn đơn qua trang, tiếp tục xử lý từng đơn khi một đơn bị lỗi và báo riêng đơn hoàn tất, chờ, lỗi và chưa xử lý. Khi được chọn nguồn mua, đề xuất bổ sung KIZ theo cửa sổ tối đa 1.000 đơn và chờ seller xác nhận.
- Nguồn KIZ của lượt tách mới được lưu trong receipt; receipt cũ không có nguồn giữ hành vi cũ. Các đơn con của lượt tách chưa rõ kết quả được giữ chờ đối soát. Không gửi lặp hoặc tự mua cho receipt chưa đủ bằng chứng.
- Nhãn Ozon dùng tác vụ lô tối đa 1.000 đơn. Các trang phải được nhận dạng chắc chắn theo mã đơn/vận đơn; nếu chưa xác minh được thì không suy đoán theo thứ tự trang. Tối ưu tra cứu định danh, tải ảnh trước và tái sử dụng ảnh trong danh sách lấy hàng.
- API thật chỉ đọc ngày 30/10/2026: bốn trang HTTP 200 (100/100/100/25), tổng 325 đơn duy nhất trong cửa sổ ±180 ngày; 16 chờ đóng gói, một chờ giao, 308 đang giao. Cả 16 đơn chờ đọc chi tiết được và Ozon trả action ship. Không nhận đơn, mua/gán KIZ hay tạo nhãn thật.
- Benchmark `OzonBatchPdfPerformanceTest`: 100 đơn 817 ms; 1.000 đơn 7.498 ms. Đo `exportAll` với Ozon mô phỏng trên localhost, gồm tạo task, poll, tải PDF giả, đối chiếu trang, ghép PDF và tạo danh sách lấy hàng. Không bao gồm dữ liệu thật, mạng Ozon, ảnh hay KIZ. Không phải SLA hoặc phép so sánh tốc độ với WB.
- Hồi quy đầy đủ 193 ca: 192 đạt, một test giao diện hủy ban đầu không mở xác nhận do chưa chờ nguồn tải; sau sửa, hồi quy tập trung 15/15 đạt, gồm 8 ca UI và hai benchmark. Không cộng trùng các ca chạy lại. Kiểm thử mô phỏng 101 đơn đã gán 101 KIZ và nhận 101/101 qua MockWebServer; không chứng minh giao dịch thật.
- Chưa build bộ cài, chưa cập nhật Latest và chưa nghiệm thu bằng kho KIZ, dữ liệu seller hoặc Ozon thật. Những trường hợp Ozon từ chối, thiếu mapping, thiếu KIZ hoặc kết quả chưa rõ vẫn phải được báo và đối soát; không thể cam kết 100% đơn thật được nhận.

## Chẩn đoán khi seller báo 30 đơn mất 3 phút

- Seller cho biết dùng bản mới nhất; Latest công khai xác nhận là 1.5.19. Bản này chưa chứa các tối ưu đang nằm trong workspace.
- Đọc chi tiết 20 đơn thật bằng API Ozon ở chế độ chỉ đọc: 20/20 HTTP 200, hai worker, giãn cách tối thiểu 120 ms, tổng 5,73 giây; không tạo nhãn hay thay đổi đơn. Đây là mạng môi trường kiểm thử, không đại diện máy seller.
- Thêm báo cáo thời gian kiểm tra trước khi chọn file, thời gian sau chọn file, chờ KIZ/kho, chuẩn bị đơn, chờ và tải nhãn, đối chiếu trang, ghép PDF, danh sách lấy hàng và số lượt tải nhóm/riêng. Không ghi mã đơn, KIZ, token hay đường dẫn vào dữ liệu thời gian. Lưu ý các giai đoạn có thể không bao trùm toàn bộ tổng, và tổng sau chọn file bao gồm thời gian seller xác nhận mua.
- Hồi quy phần báo cáo, lô và PDF: 63/63 đạt; sau khi hoàn chỉnh chuỗi hiển thị chạy lại UI/timing 11/11 đạt. Benchmark mock mới: 100 đơn 0,770 giây; 1.000 đơn 7,076 giây, không gồm mạng Ozon, ảnh hay KIZ thật.
- Chưa có báo cáo theo công đoạn của lượt 30 đơn thực. Tải nhãn riêng vì không nhận dạng được trang, chờ API/giới hạn và chờ KIZ là các khả năng cần kiểm tra; không xác nhận một nguyên nhân cụ thể hay cam kết thời gian thật.

## Cải thiện độ mượt và nhãn trang lớn

- Trong lúc nhận hoặc xuất lô dài, giao diện cho phép xem, cuộn và chuyển tab; các nút nhận, in, chọn lại và thay KIZ được khóa để bảo vệ lô đang xử lý.
- Đơn nhiều sản phẩm sau tách được đọc và kiểm tra tối đa hai đơn con song song; kiểm đủ danh tính và số lượng trước khi gán KIZ hoặc nhận. Giữ ngân sách HTTP chung, checkpoint và đối soát.
- Trang nhãn lớn vượt trần raster 203 DPI được thử nhận diện barcode ở độ phân giải thấp hơn trong cùng giới hạn bộ nhớ. Mã không xác định, mơ hồ, trùng hoặc đa định danh vẫn phải xử lý riêng; không dựa vào thứ tự trang.
- Mô phỏng 30 trang A4 đảo thứ tự: 2,651 giây cho xử lý nhãn lô, 1 create + 1 poll + 1 download qua máy chủ mock; không gồm mạng Ozon hoặc KIZ thật. Đây chỉ là trường hợp nhãn lớn chứa barcode đọc được, chưa chứng minh nguyên nhân ba phút của seller.
- Hồi quy cuối các luồng nhận, nhãn, đo thời gian và giao diện: 110/110 test đạt. Trong lần chạy này, 30 trang A4 mock mất 2,539 giây; benchmark exportAll mock 100 đơn 0,823 giây và 1.000 đơn 7,785 giây. Các số chỉ mô tả môi trường mô phỏng và không bao gồm KIZ hoặc mạng thật.
- Bản sao schema Ozon cập nhật 30/10/2026 mô tả tạo nhãn v3 tối đa 1.000 mã, nhận v4 mỗi posting và KIZ set bất đồng bộ phải đọc lại. Nguồn tham khảo không chính chủ: https://github.com/vispar-tech/ozon-seller-api-schema/blob/main/schemas/ozon-seller-api-openapi.json. Chưa đo thời gian website Ozon hay trên máy seller, nên không thể khẳng định tốc độ tương đương.
