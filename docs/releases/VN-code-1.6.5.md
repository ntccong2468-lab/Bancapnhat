# VN Code 1.6.5 — bản gộp, chưa phát hành

**Trạng thái: chuẩn bị trên nhánh `release/vn-code-1.6.5`; chưa công bố, chưa đưa lên Latest.** Theo chỉ đạo cuối của chủ dự án ngày 07.11.2026, chỉ gộp chỉnh sửa vào bản mới. Latest vẫn là **1.6.4**, app chưa nhận thông báo cập nhật 1.6.5.

Kế thừa đầy đủ nguồn 1.6.4 và các sửa trên PR #21 đến `bba83919094e6e0f713302a365f9fa87a340c6a6`; nguồn gộp ban đầu đến `5ad16bf3f7ed7d4d3911fd44ab39f42c9f24483c`. Sau phần gộp, bổ sung sửa PDF và nhận đơn Ozon theo các yêu cầu tiếp theo; nguồn và nghiệm thu mới được ghi riêng trong bàn giao Ozon ngày 07.11. Nguồn bổ sung Ozon: `3c865b6d6c15d616d083c65502a02778d77de6f2`, kế thừa PDF `8845573`. Không ghi đè tag hoặc bộ cài cũ.

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

## PDF và nhận đơn Ozon — bổ sung sau phần gộp

- Các job đang giữ KIZ ở trạng thái RESERVED/SET_PENDING/VERIFYING/RECONCILE_REQUIRED được đối soát lại trước khi hạ xuống chỉ in vận đơn; giữ đúng mã của đơn và proof nguồn kho. Không coi job chưa được đối soát là đã hoàn thành.
- Vận đơn Ozon giữ nguyên nội dung gốc; nhãn KIZ kết hợp được tạo cạnh vận đơn theo thứ tự đơn. Giữ tải nhãn theo lô, không khôi phục trang barcode Ozon riêng từng đơn.
- **Chỉ Ozon:** tự thay mã seller nhập bị từ chối bằng mã tiếp theo trong đúng danh mục của sản phẩm. Áp dụng ở cả bước validation và kết quả sau gửi; không yêu cầu cùng GTIN cho kho seller nhập. Không lấy mã thuộc danh mục khác hoặc chuyển sang kho mua khi chỉ chọn kho seller.
- Lưu journal thay mã, slot, fingerprint và ranh giới trước HTTP; mã đã bị từ chối bị loại khỏi lần cấp tiếp theo. Kết quả đang chờ/không rõ phải đọc lại, không gửi lặp hoặc bỏ tiếp mã mới.
- Lỗi chứng từ, xác thực hoặc phản hồi sai danh tính không bị biến thành KIZ hỏng. Nếu hết mã và Ozon cho phép nhận không cần KIZ bắt buộc, vẫn nhận theo xác nhận mới từ Ozon; trường hợp khác giữ rõ đơn chưa xử lý.
- Giữ báo cáo đủ từng đơn và đối soát sau lô; một đơn lỗi không làm mất các đơn phía sau. Không đổi quy tắc nhận đơn/gán KIZ/in nhãn của WB.

## Ghép Catalog–Ozon khác tên/article — bổ sung theo đối chiếu WBCode 1.3.20

Nguồn bổ sung `cae86ef71149a9be505b060360a7a9db8e07c5b4`, kế thừa PDF/nhận/thay KIZ Ozon `3c865b6`. Hồi quy cuối 476/476 ca / 28 lớp và package đạt; chưa nghiệm thu mua/ghi GTIN thật bằng liên kết mới.

- Thêm **Ghép thẻ Catalog** cho những biến thể Ozon đã chọn: tìm mẫu, đề xuất màu/size rõ ràng, đọc mới hai nguồn, seller duyệt rồi lưu cả nhóm. Tên/article hai bên được khác; không gộp chỉ theo tên gần giống hoặc cùng size.
- Dùng một liên kết native đã duyệt cho nhận diện thẻ, mua KIZ và GTIN. Cho phép khác TN VED ở đường này; mua lấy mã/giấy tờ Catalog. Registration hoặc barcode cũ không được thay quyết định mới.
- Lưu qua restart; đổi cách trình bày tên không mất liên kết sau đọc lại, đổi danh tính vật lý/scope cần xem lại. Báo riêng đã ghép và GTIN đã đọc thấy trên Ozon; giữ barcode cũ và chỉ xác nhận ghi sau readback.
- Không tự mua KIZ hoặc ghi barcode khi chỉ lưu liên kết. Legacy link không tự thành proof mua mới. WB giữ nguyên quy tắc.
- [So sánh WBCode 1.3.20, nguồn và sơ đồ](../research/2026-11-07-wbcode-1320-ozon-variant-link.md), [bàn giao và giới hạn kiểm chứng](../validation/2026-11-07-ozon-reviewed-variant-handoff.md).

## Các sửa được kế thừa

Giữ toàn bộ giao diện tối/sáng đã duyệt, khung danh sách lớn, năm tab Znack/Kho KIZ, WB Đơn nợ, Ozon Đơn mới/Đang giao hàng; nhận đơn và tải nhãn theo lô; vận đơn Ozon nguyên bản và KIZ đúng đơn; số vận đơn/thứ tự phiếu nhặt hàng; cache/receipt/checkpoint, license kiểm tra nền và cập nhật an toàn. Các sửa audit PDF/KIZ, updater, log, dependency và công cụ cũng được giữ.

Giữ số schema 6, UpgradeCode và thư mục dữ liệu; thêm bảng journal nhận đơn Ozon theo cách bổ sung, không sửa/xóa bảng cũ. Prototype mã hóa credential chưa đưa vào runtime; không triển khai portal.

## Giới hạn nghiệm thu

Đây chưa phải bản được phát hành hoặc chứng nhận hoàn tất mọi luồng thật. Năm nút WB đã kiểm bằng gửi lại giá trị hiện có và đọc lại; Cặp Ozon mới đã kiểm API thật: 9 đơn gilet và 9 mã KIZ được xác nhận, các đơn chuyển chờ giao; credential Secrets cũ trước đó bị 403 không đại diện cho kết quả mới. Không dùng kiểm nhận đơn này làm bằng chứng đã sửa thông tin bài Ozon hoặc đồng bộ GTIN thật. Mapping KIZ đã kiểm chọn dữ liệu thật nhưng chưa mua có phí; chuỗi Catalog ký/công bố/GTIN mới, benchmark 1.000 đơn thật và stress 10.000 GTIN còn giới hạn ghi trong bàn giao. Báo cáo phạt Ozon, tự xuất PDF chờ mua sau restart và các phần chưa hoàn tất không được mô tả là tính năng mới đã xong.

[Danh mục toàn bộ sửa và phần còn thiếu](../validation/2026-11-07-change-inventory.md), [Code Doctor](../validation/2026-11-06-code-doctor.md), [Znack/GTIN](../validation/2026-11-06-znack-four-states-wb-gtin.md), [năm nút sản phẩm](../validation/2026-11-06-products-five-audit.md), [KIZ mapping](../validation/2026-11-07-kiz-mapping-live-audit.md).

[Bàn giao PDF/nhận đơn Ozon, 9 đơn thật](../validation/2026-11-07-ozon-165-gilet-pdf-handoff.md), [209 ca và build](../validation/ozon-165-20261107/verification.json), [đề xuất mapping khác tên/SKU](../research/2026-11-07-ozon-catalog-variant-link-proposal.md).
