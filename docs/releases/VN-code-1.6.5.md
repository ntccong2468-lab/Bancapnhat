# VN Code 1.6.5 — bản gộp, chưa phát hành

**Trạng thái: chuẩn bị trên nhánh `release/vn-code-1.6.5`; chưa công bố, chưa đưa lên Latest.** Theo chỉ đạo cuối của chủ dự án ngày 07.11.2026, chỉ gộp chỉnh sửa vào bản mới. Latest vẫn là **1.6.4**, app chưa nhận thông báo cập nhật 1.6.5.

Kế thừa đầy đủ nguồn 1.6.4 và các sửa trên PR #21 đến `bba83919094e6e0f713302a365f9fa87a340c6a6`; nguồn thay đổi nghiệp vụ cuối `5ad16bf3f7ed7d4d3911fd44ab39f42c9f24483c`. Không ghi đè tag hoặc bộ cài cũ.

## Đồng bộ FBS Ozon

- Một đơn trả 404 hoặc thiếu dữ liệu không chặn việc đọc/lưu những đơn hợp lệ phía sau, ở cả thao tác thủ công, trạng thái nền và hàng đợi lấy đơn mới.
- Giữ dữ liệu cũ của đơn chưa đọc được, checkpoint và cảnh báo qua các trang; không báo quét đủ khi còn dữ liệu chưa xác minh.
- Đọc trang kế tiếp theo nhịp 1 giây; thời gian chờ thử lại lỗi áp sau lượt, cùng giới hạn API. Không coi lỗi quyền, quota hoặc mạng là đơn đã được xử lý thành công.

## Znack và GTIN WB

- Rút bộ lọc còn **Chưa tạo / Đang xử lý / Hoàn tất / Lỗi**, cộng Tất cả. Lỗi dành cho thẻ bị Catalog từ chối; việc ghép cần đối chiếu nằm trong Đang xử lý.
- Ưu tiên thẻ khớp article/màu/size hiện tại; không để GTIN hoặc checkpoint cũ che quan sát mới. Hiển thị trạng thái công bố tách khỏi kết quả ghép sản phẩm.
- Nút cập nhật GTIN có thể bắt đầu từ snapshot lưu; worker đọc xác minh lại nguồn hết hạn trước ghi.
- **Khác TN VED không chặn append GTIN lên WB.** Giữ điều kiện đúng biến thể/chủ sở hữu và barcode, thuộc tính, giấy tờ đang có. Khi tạo thẻ Catalog mới vẫn lấy TN VED từ sản phẩm trên sàn.

## Sửa thông tin sản phẩm

- Sửa giấy tờ mặc định dùng **Tuyên bố sự phù hợp đã cấu hình cho cửa hàng đang chọn**; bỏ danh sách và hai radio loại giấy tờ cũ trong form sửa sản phẩm.
- Chuẩn hóa alias Declaration của WB để không gửi trùng giấy tờ cùng số; giữ ID của giấy tờ đang gắn trên bài đích vừa đọc.
- Không tự biến Certificate thành Declaration hoặc coi thiếu ngày hết hạn là vô thời hạn. Nhãn tuyên bố được hiển thị bằng ngôn ngữ người dùng.
- Giữ đủ năm nút sửa thương hiệu, chất liệu, giới tính, TN VED và giấy tờ cùng quy trình đọc lại sau gửi.

## Mapping để mua KIZ

- WB chọn theo các thẻ Catalog thực sự đủ điều kiện thay vì báo mơ hồ chỉ do có nhiều barcode hợp checksum.
- **Cho phép TN VED marketplace khác TN VED Catalog ở cả mapping WB và Ozon.** Đơn mua dùng metadata TN VED/giấy tờ của thẻ Catalog; giữ điều kiện chứng thư, chủ sở hữu, publication, biến thể và nguồn dữ liệu.
- Báo riêng trường hợp không tìm thấy thẻ; không đổi lỗi quyền/mạng thành mapping đã xác minh.
- Giữ chính sách kho seller nhập theo danh mục và kho mua theo mapping đúng biến thể.

## Các sửa được kế thừa

Giữ toàn bộ giao diện tối/sáng đã duyệt, khung danh sách lớn, năm tab Znack/Kho KIZ, WB Đơn nợ, Ozon Đơn mới/Đang giao hàng; nhận đơn và tải nhãn theo lô; vận đơn Ozon nguyên bản và KIZ đúng đơn; số vận đơn/thứ tự phiếu nhặt hàng; cache/receipt/checkpoint, license kiểm tra nền và cập nhật an toàn. Các sửa audit PDF/KIZ, updater, log, dependency và công cụ cũng được giữ.

Không đổi schema 6, UpgradeCode hoặc thư mục dữ liệu. Prototype mã hóa credential chưa đưa vào runtime; không triển khai portal.

## Giới hạn nghiệm thu

Đây chưa phải bản được phát hành hoặc chứng nhận hoàn tất mọi luồng thật. Năm nút WB đã kiểm bằng gửi lại giá trị hiện có và đọc lại; Ozon đang trả 403/code 7 với credential trong môi trường. Mapping KIZ đã kiểm chọn dữ liệu thật nhưng chưa mua có phí; chuỗi Catalog ký/công bố/GTIN mới, benchmark 1.000 đơn thật và stress 10.000 GTIN còn giới hạn ghi trong bàn giao. Báo cáo phạt Ozon, tự xuất PDF chờ mua sau restart và các phần chưa hoàn tất không được mô tả là tính năng mới đã xong.

[Danh mục toàn bộ sửa và phần còn thiếu](../validation/2026-11-07-change-inventory.md), [Code Doctor](../validation/2026-11-06-code-doctor.md), [Znack/GTIN](../validation/2026-11-06-znack-four-states-wb-gtin.md), [năm nút sản phẩm](../validation/2026-11-06-products-five-audit.md), [KIZ mapping](../validation/2026-11-07-kiz-mapping-live-audit.md).
