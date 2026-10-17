# Đối chiếu Wbcode 1.1.70 → 1.3.9 và sửa đăng ký VNCode

Đã đọc 18 bản phát hành chính thức, đối chiếu 159 đầu mục. Nguồn nguyên văn và SHA-256 ở `release-notes.json`; từng đầu mục có mã nguồn, kiểm thử, cách áp dụng và phần khác biệt ở `comparison.json`. Những kết quả live test riêng của Wbcode không được dùng làm bằng chứng cho VNCode.

Repo mã nguồn nội bộ mới `rupphi/source-wcode` trả HTTP 404 với quyền hiện tại. Mã công khai `rupphi/test-wcode` ở commit `8f7c3d1ac8598158029b8d037e2d3c128e240727` là nguồn cũ, không phải toàn bộ mã 1.3.9. Đối chiếu này không tuyên bố sao chép được mã nội bộ mới nhất.

## Sửa trong lượt này

- Feed đã Moderated nhưng thiếu good_id được tra lại bằng đúng GTIN; không mượn ID từ thẻ khác và không coi feed là đã xuất bản.
- Lỗi đọc trạng thái mạng/quota sau khi feed đã được nhận giữ PROCESSING để monitor đọc tiếp; 403 và đổi scope vẫn cần xử lý, không bị tự lặp.
- Xác minh card `published` qua API sau khi feed/sign được chấp nhận. Kết thúc feed nhưng không có thẻ, hơn 5 phút chưa tạo được thẻ và tài liệu ký tạm thời 404 có trạng thái riêng, giữ GTIN/feed để đọc lại.
- Báo cáo đăng ký thật khác báo cáo đọc nguồn; lưu ID yêu cầu, ID thẻ và kết quả xuất bản từng SKU. Thông báo lỗi không thể biến thành thành công do lỗi lưu báo cáo.
- Giữ lô đang đăng ký khi callback làm mới bảng; lấy thuộc tính mới của sàn thay cache cũ sau sửa hàng loạt. Cùng một tập giấy tờ chỉ cần xác nhận một lần trong lô.
- Kiểm tra TN VED/thương hiệu/chất liệu/giới tính trước cấp số; cho sửa cả lô, đọc lại rồi tiếp tục. Dữ liệu chưa đủ không được bịa để gửi.
- GTIN của thẻ mới đăng ký có consent theo đúng GTIN + biến thể + chủ shop; chỉ đưa vào hàng đợi sau xác minh xuất bản. Child job được xác nhận và liên kết bền vững trước khi gửi. Ozon vẫn kiểm tra SKU/tên/màu/size.
- Mẫu mặc định lấy cấu trúc một thẻ Catalog đã xuất bản thật; không sao chép GTIN hay dữ liệu seller từ thẻ mẫu. Tệp mặc định đóng gói trong app và được builder của cả WB/Ozon sử dụng.
- Bốn nút trên cùng hàng, nút Đăng ký/chọn tất cả dưới bảng; không đưa nút phụ vào dãy bốn nút. Tác vụ GTIN và sửa hàng loạt có viên thuốc tiến độ dưới bảng. Các tùy chọn phụ mở bằng chuột phải/Shift+F10 tại tiêu đề.
- Sao chép TN VED dùng clipboard thật và xác nhận đọc lại; lọc thêm chất liệu, gợi ý cuộn ngang; placeholder tĩnh và cải thiện khung media hướng dẫn.

## Bằng chứng

Đọc API thật: đủ 1.276 thẻ qua 2 trang danh sách và 52 lô chi tiết, 1.237 published + 39 draft, không lỗi/thiếu/trùng; `catalog-full-read-summary.json`. Cấu trúc thẻ approved: `catalog-approved-card-structure.json`, không chứa giá trị sản phẩm/khóa API.

Linux, mã ứng dụng `a6e9a8684e8f3fd595ce395186c32f29811c8dd9`: 1.727 kiểm thử hồi quy, 0 failure/error, 2 skip nền tảng; 153 kiểm thử tập trung và 60 kiểm thử công cụ phát hành qua. Các số này không thay thế kiểm thử bộ cài Windows hoặc ký CryptoPro thật.

Có lựa chọn GTIN mới sau khi Catalog xác nhận feed cũ bị từ chối, giữ lịch sử thẻ cũ; feed đang xử lý/không rõ kết quả không được tiêu thêm quota. GTIN đã đọc lại từ sàn cập nhật ngay vào cache hiển thị, chỉ đúng biến thể; ACK không sửa cache.

Theo xác nhận mới của người dùng, giữ Hỗ trợ/Hướng dẫn hiện tại; không thêm backend hội thoại/ảnh. Khác biệt chức năng còn ghi rõ: mẫu GS1 đóng gói sẵn (VNCode dùng mẫu doanh nghiệp nhập một lần). Không đánh dấu hoàn tất toàn bộ 159 đầu mục và không đổi các cờ nghiệm thu production thành true khi chưa có bằng chứng.

Giữ yêu cầu riêng đã duyệt: thương hiệu VNCode, license do admin cấp, kho KIZ seller theo danh mục không buộc GTIN/Znack, Liquid Glass và luồng FBS ưu tiên liên tục.

Bộ đọc sản xuất của VNCode xử lý đủ 1.276 thẻ từ snapshot API thật, 1.237 published, không lỗi. Ozon FBS đọc trực tiếp API v4 đủ 883 đơn trong 30 ngày và 255 đơn chưa hoàn tất; bộ đọc sản xuất không bỏ sót dòng hàng. File KIZ riêng của seller nhận đủ 600 mã; tiêu thụ cục bộ một mã còn 599 và nhập lại vẫn 599. Các probe dùng dữ liệu tách riêng, không chứa mã KIZ/khóa thật trong repo, không gọi ghi sàn/Catalog; license của probe là fixture, không phải nghiệm thu license production.
