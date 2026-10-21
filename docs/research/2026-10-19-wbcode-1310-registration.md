# Đối chiếu đăng ký Znack với Wbcode 1.3.10

Bản mới nhất được GitHub trả về ngày 19.10.2026: [Wbcode v1.3.10](https://github.com/rupphi/relatest-wcode/releases/tag/v1.3.10), phát hành **10:50:57 Moscow**. Bộ cài `WCode.exe` có SHA-256 `742c457f7948640f5a807bb9d68e6d9e78883da23650471e6d62fc4b43551b31`, khớp `checksums.sha256` chính thức.

Phương pháp: đọc ghi chú phát hành, giải nén chỉ đọc bộ cài chính thức và kiểm tra các phương thức Java được đóng gói. Không chạy ứng dụng nhà cung cấp, không đăng nhập tài khoản seller vào Wbcode, không tuyên bố đã truy cập kho mã nguồn riêng.

Các lớp tham chiếu: `ZnackNationalCatalogService`, `ZnackCardRegistrationWorkflow`, `RegistrationRunner`, `RegistrationPublication`, `ApprovedCardSigningPresentation`, `RegistrationCategoryDialog` trong package `com.tuandev.fbsbarcode`.

| Bước | Luồng Wbcode 1.3.10 đã đối chiếu | VNCode 1.5.1 |
|---|---|---|
| Chuẩn bị | TN VED, danh mục active, schema thuộc tính, nhận dạng article/GTIN | Dữ liệu đúng biến thể WB/Ozon, tự ghép danh mục, bảng sửa thiếu có Áp dụng; bổ sung đối chiếu phạm vi giấy tờ và mâu thuẫn giới tính từ dữ liệu thật đã kiểm tra |
| Cấp GTIN | Cấp mã với checkpoint; phân biệt yêu cầu cấp chưa rõ kết quả | Giữ checkpoint và GTIN cũ của lượt đang xử lý; không cấp thêm sau timeout; đổi nhóm TN VED tạo thẻ/GTIN mới theo yêu cầu seller |
| Gửi | Gửi feed, giữ `feed_id`, xử lý kết quả chưa rõ bằng đọc lại sản phẩm | Giữ feed/checkpoint; chưa rõ kết quả không tự gửi lại hoặc báo thành công |
| Trạng thái | Tách kết quả feed với `RegistrationPublication` đọc thẻ thật | **Bổ sung:** khi feed còn Received/Processing/Moderated, đọc đúng GTIN/good_id để phân biệt draft, moderation, notsigned, errors |
| Chờ ký | Chỉ ký thẻ cần ký; không đồng nhất “đã gửi” với “đã xuất bản” | Thẻ đã ký/published được đọc lại trước khi hỏi CryptoPro; không ký thêm chỉ vì feed cập nhật chậm. Bản nháp chỉnh sửa không tự chứng minh lượt sửa mới đã xuất bản |
| Lỗi | Lỗi từng thẻ/thuộc tính và trạng thái hàng đợi riêng | Đọc lỗi dạng object/array, commonError; nếu thẻ thật báo lỗi nhưng feed chưa có lý do thì nói rõ chưa có chi tiết, không dựng lý do hoặc báo thành công |
| Chạy nền | Hàng đợi và runner tiếp tục theo từng SKU | Giữ monitor, chống gửi/ký lặp và consent đúng thẻ. Một thẻ lỗi không được tính là đã đăng ký xong |
| GTIN lên sàn | Tác vụ riêng sau khi thẻ hợp lệ | Giữ hàng đợi ghi WB/Ozon đã có; chỉ tính đã cập nhật sau khi đọc đúng biến thể trên sàn; tách với kết quả đăng ký |
| Hiển thị | Trạng thái đăng ký/ký/đồng bộ riêng; hộp danh mục sửa cho Windows tăng cỡ chữ | Giữ danh mục tự động theo yêu cầu seller. Các bảng bổ sung dữ liệu dùng DialogLayout. Dòng thẻ có trạng thái, lý do, GTIN, feed_id và sao chép; không thay bằng báo cáo đồng bộ nguồn |

Các câu trạng thái bổ sung trong app:

- Catalog có thẻ nháp; chưa xác nhận kiểm duyệt/xuất bản.
- Thẻ đang được Catalog kiểm duyệt; chưa xuất bản.
- Đã gửi chữ ký; chờ Catalog xác nhận xuất bản.
- Catalog báo thẻ lỗi nhưng feed chưa có lý do chi tiết; mở báo cáo để xem GTIN/feed_id, chưa đăng ký thành công.

Không chuyển các thông tin doanh nghiệp, GTIN, giấy tờ, cài đặt hoặc dữ liệu hỗ trợ của Wbcode sang VNCode. Ghi chú 1.3.10 về KIZ Mapping và macOS là phạm vi khác với đợt đối chiếu đăng ký này.

Đối chiếu mã đóng gói và kiểm thử mô phỏng không thay thế nghiệm thu tạo/ký thẻ bằng CryptoPro Windows thật. Phạm vi API thật, kiểm thử và build được ghi trong [báo cáo 1.5.1](../validation/2026-10-19-znack-product-edit.md).
