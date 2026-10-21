# Bản sửa đăng ký Znack 1.5.8

Yêu cầu đã chốt: giữ toàn bộ màn hình/nút và cách thao tác của 1.5.7, thay xử lý bên dưới;
không đưa người dùng sang màn hình bảy bước hay cửa sổ V3 khác.
Nền `v1.5.7` / `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
Nhánh cập nhật: `update/vn-code-1.5.8`; đã push. Chủ dự án yêu cầu phát hành lên Latest.

## Điểm tích hợp thực tế

`HomeController` vẫn nạp FXML/controller đăng ký cũ. Hai file giao diện/controller đã được
đối chiếu nguyên văn với tag 1.5.7. Xóa `RegistrationV3Pane`, `RegistrationAttributeEditor`
và test của màn hình thử nghiệm khỏi bản cập nhật; không còn nút mở V3.

`ZnackCardRegistrationWorkflow` vẫn là điểm gọi chung của đăng ký đơn/lô, monitor và duyệt
ký. Các bước cấp GTIN, gửi thẻ thường/thẻ nháp đã duyệt và gửi chữ ký đơn/nhóm đi qua
`v3.RegistrationCommandJournal`. Nhật ký này là bộ phận V3 tích hợp production; orchestrator/
gateway V3 độc lập ở lượt trước không được gắn vào một màn hình mới để thay thao tác của seller.
Các kiểm tra giấy tờ, schema, owner, review và consent hiện hữu vẫn chạy trước gửi.

Bảng `znack_v3_dispatches` giữ hash nội dung, resource nmID/chrtID, chủ thể và môi trường,
lượt thử, worker, trạng thái và receipt thực. Không lưu credential hay chữ ký vào nhật ký này.
ACKNOWLEDGED có thể khôi phục receipt mà không gửi lại. UNKNOWN chặn cả payload mới hay
nút khác trên cùng resource; allocation UNKNOWN chặn pool cùng INN/môi trường kể cả đổi shop
hoặc chứng thư. Không có timer tự giải phóng kết quả chưa rõ.

Receipt cấp GTIN tham gia tập mã đã giữ chỗ dùng chung của WB/Ozon ngay khi acknowledgement được lưu;
chỉ mục duy nhất so sánh GTIN có/không có số 0 đầu. Bảng `znack_v3_allocation_generations`
đổi generation trong cùng transaction lưu trữ identity cũ khi seller xác nhận thay thẻ;
status/restart/sửa nháp không tự đổi generation. Callback trước cấp mã đặt tại API mutation,
không đặt trước GET lấy danh sách mã có sẵn.

`observedProgress` và đường nhận thẻ hiện có dùng chung kiểm tra nội dung đã duyệt.
Thẻ công bố cùng GTIN nhưng khác nội dung không được báo hoàn tất. Feed bị từ chối phải
khớp receipt đã nhận trước khi cho lần gửi khác. Kết quả mất feed được đối chiếu bằng đúng
identity và nội dung, đánh dấu OBSERVED và không tạo feed ID giả hoặc gửi lại.

Migration chỉ cộng bảng/chỉ mục; không đổi cấu trúc dữ liệu cũ, không đổi schema-version
chung, không xóa hàng đợi cũ. Các đường Ozon, đồng bộ WB có preview/readback và danh tính
bộ cài Windows hiện tại được giữ. Những giới hạn policy của gateway V3 độc lập không được
áp đặt sang nút đồng bộ WB hiện hữu.

## Rà soát

Một reviewer độc lập rà soát tích hợp. Các phát hiện đã đối chiếu với mã và có hồi quy:
receipt cấp mã không giữ chỗ; cache trả mã của lượt thay thẻ bị từ chối trước đó; đặt marker
cấp mã quá sớm. Đường nhận thẻ hiện có bỏ qua đối chiếu cũng được sửa. Kết quả kiểm thử
cuối và những phần chưa được nghiệm thu ghi trong `ZNACK_V3_TEST_REPORT.md`.

## Đóng gói và phát hành

POM và app.version cùng là 1.5.8. Release notes: `releases/VN-code-1.5.8.md`.
Giữ `WINDOWS_UPGRADE_UUID`, install-dir và cơ chế bảo toàn dữ liệu của 1.5.7.
Thêm probe nâng cấp trực tiếp `-FromStable157`, ghim checksum/kích thước EXE đã công bố;
giữ mọi baseline cũ và thu proof mới vào artifact. Nguồn pin nằm trong
`docs/evidence/vncode-1.5.7-installer.json`.

GitHub API và quyền push đã được xác minh lại. Windows build đầy đủ đang chạy từ
`06408f3a35fddd2952b64374f75e5761d0b43502`, run `39641010813`; không dùng lại Java/artifact 1.5.7.
Publisher Windows cũ dành riêng 1.2.0 không dùng cho bản này.

Yêu cầu trực tiếp mới: “tạo phát hành bản cập nhật mới lên latest để thông báo xuống ứng dụng”.
Hồ sơ `validation/2026-10-21-vncode-158-owner-publication.json` ghi quyền công bố đúng 1.5.8,
commit và run trên. Mode `owner-requested-158` chỉ nhận bộ cài vượt toàn bộ kiểm tra Windows,
native, lịch sử nâng cấp và passive EXE từ đúng checksum 1.5.7. Gate `publish` thông thường
vẫn yêu cầu nghiệm thu production đầy đủ; quyền phát hành riêng này không phải nghiệm thu
license production, CryptoPro hay thao tác seller thật. Những cờ đó vẫn false.

Publisher kiểm tra Latest còn là 1.5.7 trước tạo tag và trước công bố, không thay tag đã tồn tại,
đối chiếu hash/kích thước asset rồi tải lại bộ cài công khai. Ứng dụng nhận 1.5.8 từ GitHub
Latest ở lần kiểm tra cập nhật tiếp theo; chưa có bằng chứng thông báo đã hiện trên máy seller.
