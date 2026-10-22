# KIZ khi Catalog đang duyệt — VNCode 1.5.11

Giữ nguyên màn hình và lựa chọn nguồn KIZ. Đơn WB/Ozon thiếu GTIN trên sàn được dùng GTIN của đúng thẻ đăng ký đã gửi và đang duyệt, khi đủ bằng chứng sở hữu và định danh. Không yêu cầu thẻ đã xuất bản để giải quyết việc thiếu GTIN tạm thời này.

## Điều kiện và luồng

1. Giữ mã đã áp dụng trên đơn theo luồng hiện có; đơn không cần KIZ tiếp tục bình thường. Mã từ kho seller vẫn được ưu tiên khi seller chọn nguồn đó.
2. Với phần cần mã mua, ghép đúng shop, sản phẩm, biến thể và SKU/barcode. Thẻ chờ duyệt phải có feed đã được nhận hoặc good ID, GTIN thật hợp lệ, payload trùng GTIN và article/màu/size hiện tại. Kiểm tra chứng thư, INN và môi trường production hiện tại; thẻ bị từ chối, dữ liệu thay đổi hoặc alias mơ hồ không được rơi xuống quy tắc sản phẩm chung rồi lấy mã sai size.
3. Có mã sẵn dùng đúng GTIN thì gán cho đơn. Chỉ mã AVAILABLE và IN_CIRCULATION được dùng; mã vừa tải về ở trạng thái RECEIVED tiếp tục chờ đối soát.
4. Khi thiếu mã, dùng luồng mua và UUID yêu cầu bền vững hiện có. Metadata hợp lệ từ feed cung cấp tên, TN VED, loại UNIT/BUNDLE và giấy tờ nếu có. Những điều kiện khác như license, omsId, kết nối SUZ, chữ ký và giấy tờ cần thiết vẫn được kiểm tra. Timeout sau gửi tiếp tục đối soát yêu cầu cũ, không tự mua lại.
5. Lưu hàng đợi cần bản ghi khóa ngoại nội bộ. Bản ghi `pending_registration` này bị ẩn khỏi danh sách sản phẩm Catalog, không có cờ lưu thông/cho phép đánh dấu giả; mỗi lần mua phải kiểm tra lại thẻ đăng ký. Khi Catalog xuất bản thật, bản ghi được kích hoạt và giữ nguyên pipeline mua trước đó.

Định danh chờ duyệt dành cho KIZ không thay đổi quy tắc FBO: FBO vẫn cần bằng chứng Catalog xuất bản theo hợp đồng riêng.

## Bằng chứng

Kiểm thử `KizPendingIdentityTest`, `OrderExportWorkflowKizPlanningTest` cùng các bộ mapping, stock replenisher, purchase readback, reconciliation và attachment dùng dữ liệu SQLite thật cùng dịch vụ/ký giả lập. Phạm vi gồm WB/Ozon, sai SKU/sản phẩm/size/chủ sở hữu/môi trường, feed chưa được nhận hoặc bị từ chối, gán kho sẵn dùng, không lấy mã RECEIVED, hàng đợi bền vững và chuyển từ chờ duyệt sang xuất bản. Lượt Linux cuối của nhóm KIZ đạt **76/76**, không lỗi hoặc thất bại. Hai ca sandbox WB/Ozon và ca giữ mã đã áp dụng/đơn không cần KIZ/kho nhập đã được chạy đỏ trước khi sửa, sau đó chạy xanh. Toàn ứng dụng trước hai sửa này đạt 2.876 ca, không lỗi/thất bại, 2 ca chỉ dành cho Windows được bỏ qua; Node đạt 117/117. Bản Windows sẽ chạy lại toàn bộ từ commit phát hành; số cuối nằm trong báo cáo Surefire và `build-info.json` của gói phát hành.

Môi trường cloud không có dữ liệu/tài khoản Mpp fashion. Không có yêu cầu mua KIZ trả phí, chữ ký CryptoPro thực hay ghi seller production trong lượt xác minh này. Kiểm thử chứng minh logic ứng dụng; chưa chứng minh một giao dịch mua thật của shop đã thành công.
