# VNCode 1.5.14

- Mua KIZ khi xuất nhãn WB/Ozon: khi chọn nguồn Mua KIZ mà kho còn thiếu, app hiện bảng từng GTIN với số cần, sẵn dùng, thiếu và mua thêm. Nhấn Áp dụng để mua phần thiếu, chờ xác nhận từng mã đã lưu thông rồi tự xuất nhãn vào tệp đã chọn. Hủy không gửi lượt mua mới; yêu cầu đang chờ được tiếp tục, không mua trùng. Viên thuốc hiển thị tiến trình và tự ẩn khi kết thúc. Nếu đóng app lúc chờ, lượt mua vẫn được giữ; mở lại và chọn xuất nhãn để tiếp tục.
- Sửa cổng mua KIZ cho sản phẩm đã xuất bản trên Catalog nhưng GTIN chưa xuất hiện trong barcode sàn. App vẫn kiểm tra đúng cửa hàng, chủ sở hữu, article, màu, cỡ và dữ liệu Catalog hiện hành trước khi mua. Thông báo thiếu ghép GTIN có thông tin đơn/SKU, không còn dòng trống hoặc mã lỗi lặp.
- Tải bản cập nhật khi app đang có tác vụ nền. Trước khi cài, app chờ phần đang ghi hoàn tất hoặc lưu điểm tiếp tục an toàn; giữ yêu cầu KIZ đã gửi và dữ liệu hiện có. Không ép đóng giữa giao dịch.
- Lưu kết quả xác minh GTIN WB/Ozon qua các phiên. Lần cập nhật tiếp theo tập trung vào phần chưa xong; dữ liệu sàn mới hơn trái ngược sẽ mở lại đúng phần cần xử lý. Vẫn yêu cầu bằng chứng Catalog và đọc lại sàn để báo thành công.
- Ozon: chuẩn bị, gán và kiểm tra KIZ trước khi nhận/đóng gói theo yêu cầu của đơn, kể cả nhóm sản phẩm có thể yêu cầu mã. Đơn nhiều đơn vị cần KIZ được tách và đối soát các đơn con trước khi chuyển bước.
- Tăng tốc xuất PDF FBS Ozon cho lô tới 1.000 đơn bằng tác vụ nhãn theo lô, tải tệp chung một lần và ghép trang theo đúng mã đơn. Giữ hai nhãn chính: vận đơn Ozon và nhãn kết hợp barcode sản phẩm/KIZ; không thêm nhãn sản phẩm riêng. In lại một đơn chỉ lấy trang của đơn đó.
- Sửa vùng hiển thị trên toàn bộ màn hình app: nội dung dài cuộn tới nút cuối, cửa sổ thường nằm trong vùng làm việc của Windows, hạn chế bị thanh tác vụ che. Tác vụ in có hàng đợi riêng để lúc chờ KIZ không chiếm hết worker của các thao tác khác.

Giữ dữ liệu, hàng đợi, định danh cài đặt và kết nối gốc của giao dịch đang dở khi nâng cấp. Không tự xin GTIN hoặc mua mã mới chỉ vì timeout. Luồng đăng ký Catalog và các phạm vi TN VED/giấy tờ từ bản nền được giữ.

Kết quả kiểm thử toàn bộ, mở app Windows và nâng cấp giữ dữ liệu được kèm trong build-info và gói verification. Kiểm thử tự động dùng dữ liệu tổng hợp/API giả lập cho mua KIZ, gán mã và ghi GTIN; không đồng nghĩa đã thực hiện giao dịch có phí hoặc nghiệm thu tài khoản production của từng seller. Bộ cài hiện chưa có chữ ký Authenticode.
