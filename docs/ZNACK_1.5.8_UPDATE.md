# Bản sửa đăng ký Znack 1.5.8

Yêu cầu đã chốt: giữ toàn bộ màn hình/nút và cách thao tác của 1.5.7, thay xử lý bên dưới;
không đưa người dùng sang màn hình bảy bước hay cửa sổ V3 khác.
Nền `v1.5.7` / `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
Nhánh cập nhật local: `update/vn-code-1.5.8`; chưa push hoặc phát hành.

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
giữ mọi baseline cũ và thu proof mới vào artifact. 107 Node contracts đã qua; probe
Windows chưa chạy. Nguồn pin nằm trong `docs/evidence/vncode-1.5.7-installer.json`.
Quy trình Windows hiện hữu trong `.github/workflows/build-java.yml` cần build đầy đủ để tạo
artifact cài đặt, với bằng chứng native/upgrade đúng commit. Bước công bố trong các workflow
`publish-vncode-stable.yml` / `publish-vncode-preview.yml` còn yêu cầu hồ sơ nghiệm thu phù hợp
phiên bản. Publisher Windows cũ dành riêng 1.2.0 không dùng để phát hành 1.5.8. Không thêm
ngoại lệ 1.5.8, không dùng artifact 1.5.7 hoặc bằng chứng cũ, không nới gate license/production.

Cloud hiện tại là Linux. Proxy trả CONNECT 403 cho `api.github.com` cả khi gọi không xác thực.
GitHub CLI báo GH_TOKEN không hợp lệ nhưng chưa thể kết luận về token khi API bị chặn; quyền
GitHub chưa được xác minh. Chưa thể gọi Windows CI/công bố bộ cài qua API từ môi trường này.
Không in/đổi token, không bỏ qua proxy hoặc gate phát hành.
Các thay đổi vẫn được chuẩn bị, kiểm thử và commit local để tiếp tục phát hành khi có quyền.
