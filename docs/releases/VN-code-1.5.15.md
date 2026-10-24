# VNCode 1.5.15

- Đăng ký Znack WB/Ozon tiếp tục xử lý từng thẻ đến hết lô. Lỗi mạng, giới hạn API hoặc Catalog trả kết quả chậm không còn làm hàng đợi tự tạm dừng sau năm lần kiểm tra. App chờ đúng thời hạn rồi tự tiếp tục; giữ lựa chọn Tạm dừng/Hủy của seller.
- Tự khôi phục các lô bị bản cũ tạm dừng vì giới hạn số lần thử, giữ nguyên GTIN, yêu cầu đã gửi và tiến trình. Các thẻ WB chưa gửi được ưu tiên trước những yêu cầu cũ đang chờ Catalog trả kết quả.
- Sửa trường hợp Catalog trả HTTP 429 ngay lúc cấp GTIN hoặc gửi thẻ: app đợi rồi thử lại đúng yêu cầu, giữ GTIN/payload đã có. Ozon lưu cả thời gian chờ dài hơn mà dịch vụ yêu cầu. Không xin thêm GTIN hoặc gửi lặp yêu cầu đã được nhận để vượt lỗi.
- Tăng tốc đường gửi từng thẻ bằng tái sử dụng kết nối, chuyển phiên Catalog vừa chuẩn bị sang bước gửi và giảm các lần đọc metadata/quota bị lặp. Vẫn kiểm tra danh tính cửa hàng, kết nối, hạn mức hiện hành, dữ liệu và giấy tờ trước khi gửi.
- Giữ cách báo cáo riêng số yêu cầu Catalog đã nhận và số thẻ đã đọc lại xác nhận. Thẻ có kết quả ghi chưa rõ phải được đối soát; thẻ bị từ chối dữ liệu báo lý do để xử lý, không báo thành công từ HTTP 200 hoặc receipt đơn thuần.

Kế thừa toàn bộ chức năng và dữ liệu từ 1.5.14, gồm mua KIZ khi xuất nhãn, các tab đơn Ozon, nhãn kết hợp barcode/KIZ, kết quả đồng bộ GTIN và vùng hiển thị app. Giữ định danh cài đặt và các giao dịch đang dở khi nâng cấp.

Gửi liên tục vẫn tuân thủ thời gian chờ của Catalog. Nếu app thoát hoàn toàn hoặc máy mất mạng, hàng đợi được giữ và tiếp tục khi app/mạng trở lại. Sai dữ liệu, giấy tờ, quyền truy cập hoặc hết hạn mức cần được xử lý theo thông báo.

Kiểm thử nghiệp vụ dùng dữ liệu tổng hợp và API giả lập; có thử lô 117 thẻ qua nhiều lần giới hạn/đọc chậm, không gửi lại feed đã được nhận. Kết quả toàn bộ kiểm thử, mở app Windows và nâng cấp giữ dữ liệu nằm trong build-info và gói verification. Không coi kiểm thử tự động là nghiệm thu giao dịch Catalog/marketplace production của từng seller. Bộ cài hiện chưa có chữ ký Authenticode.
