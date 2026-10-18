# Cập nhật GTIN báo lỗi CryptoPro — sửa sau 1.4.19

Trạng thái: mã nguồn đã sửa và kiểm thử, **chưa phát hành**. Bản Latest công khai và bản nền vẫn là 1.4.19 (`0cee8c2`). Chưa có phiên bản ứng dụng hoặc ERROR CHAIN của lần lỗi seller vừa báo; chưa kết luận nguyên nhân CryptoPro trên máy đó.

## Các lỗi xác định được trong mã

1. `GtinBackgroundStatus` chạy cả tác vụ ghi GTIN bằng `AppTaskExecutor.executeCatalog`, có deadline ba phút tính cả thời gian chờ. Một cửa hàng lớn có thể bị hủy giữa luồng đang chạy. Khi task bị hủy mà không có exception, giao diện tạo `CancellationException` không có thông báo; `FriendlyErrorService` chuyển thông báo trống thành “ký CryptoPro thất bại”, dù chưa xác định có lỗi ký.
2. Task hủy không được xóa khỏi bản đồ tác vụ đang chạy. Bấm lại gắn listener vào task đã kết thúc; listener không nhận chuyển trạng thái mới nên có thể giữ spinner.
3. `NationalCatalogGs1Client.authenticate` bỏ nguyên nhân `CryptoProException` khi bọc thành `IOException`. Trình dịch lỗi cũng chỉ dịch CryptoPro ở lớp ngoài cùng; báo cáo mất mã lỗi cụ thể.
4. Báo cáo đọc Catalog thay bước thực sự thất bại bằng `SOURCE_READ`.

## Sửa đã thực hiện

- Dùng `SellerMutationExecutor` hiện có, tuần tự theo shop, cho tác vụ GTIN đã được chấp nhận. Không áp deadline của tác vụ đọc lên toàn bộ lần cập nhật. Timeout từng HTTP request và lần ký CryptoPro vẫn giữ nguyên.
- Giữ `MarketplaceRequestBudget.lane(true)` cho GTIN nền để phần kết nối dành cho FBS vẫn hoạt động.
- Dọn task hủy, xử lý task đã kết thúc khi mở lại màn hình và hiển thị đúng trạng thái dừng. Không tự phát lại yêu cầu ghi chưa rõ kết quả.
- Viên thuốc hiển thị bước chuẩn bị nguồn/đọc sàn/đọc Catalog/ghép GTIN trước khi có tiến độ ghi. Nếu chuẩn bị thất bại, nêu rõ **tác vụ này chưa gửi cập nhật GTIN lên sàn**.
- Giữ nguyên cause CryptoPro, dịch thông báo của cause bị bọc, lưu mã `CRYPTOPRO_*` an toàn và bước thất bại thực tế. Thêm nút chi tiết trên viên thuốc lỗi để seller chủ động mở và sao chép báo cáo đã che token/PIN/chữ ký. Không tự bật hộp thoại lỗi.
- Không bỏ kiểm tra chứng thư, sở hữu Catalog, license, trạng thái thẻ, đối chiếu biến thể/TN VED hoặc đọc lại WB. Khóa Catalog đã xác minh trên máy vẫn được tái sử dụng; máy chưa có khóa dùng được vẫn cần xác thực CryptoPro.

## Kiểm thử

Hai kiểm thử mới tái hiện lỗi mất cause và mất thông báo cụ thể trước sửa: 16 kiểm thử chạy, hai failure. Sau sửa, lần kiểm thử cuối lúc **11:09:49 UTC ngày 18/10/2026**: **153 kiểm thử, 17 lớp, 0 failure, 0 error, 0 skip**, trên Linux/Java 25.0.4.1/Xvfb. Chi tiết trong [JSON kết quả](2026-10-18-vncode-gtin-cryptopro-fix.json).

Bao gồm: lỗi ký không gửi `certlogin`; chuẩn bị nguồn lỗi không gọi ghi GTIN; báo cáo lưu đúng mã và bước; lỗi ở viên thuốc không tự mở dialog; mở lại task hủy không giữ spinner hoặc báo nhầm CryptoPro; GTIN vào hàng đợi ghi theo shop; giới hạn kết nối nền cho FBS; sở hữu Catalog, payload, readback, chống tự gửi lại khi kết quả chưa chắc chắn. Thông báo mới có đủ bốn ngôn ngữ ứng dụng.

Không gọi API ghi lên sàn, đăng ký thẻ mới, mua KIZ hoặc ký bằng khóa thật trong lần sửa này. Chưa kiểm thử nút cập nhật GTIN trên Windows có license/chứng thư production. Cần Summary/ERROR CHAIN của seller để phân biệt lỗi hủy bị báo nhầm với lỗi ký thật (chứng thư, khóa riêng, timeout hoặc công cụ ký).
