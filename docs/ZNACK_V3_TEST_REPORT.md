# Báo cáo kiểm thử V3

Nền v1.5.7 `9b580f42f3ea5b64a89a56591a598740cc7f5f45`, ngày 21/10/2026.
Môi trường Linux/Xvfb, Java 25.0.5, Node 22.23.3. Không có Windows/CryptoPro,
chứng thư hoặc seller production để nghiệm thu thao tác thật.

## Kết quả thực chạy

| Phạm vi | Kết quả | Giới hạn bằng chứng |
|---|---|---|
| Baseline `-DskipTests package` | BUILD SUCCESS | Chỉ compile/package, không tính là test |
| Java toàn bộ `verify` | 2.809 ca, 0 lỗi, 2 bỏ qua; BUILD SUCCESS, 26 phút 11 giây | Chạy trước các sửa chữa sau review |
| Java liên quan trên mã cuối `verify` | 135 ca, 0 lỗi, 0 bỏ qua; BUILD SUCCESS, 1 phút 02 giây | Gồm toàn bộ V3 và các integration/migration/recovery liên quan; không chạy lại toàn bộ suite |
| `node --test tools/*.test.mjs` | 106/106 đạt | Tooling không thay đổi sau lần chạy này |
| JavaFX V3 | Render 7 tab ở 1280×800; đã xem ảnh thực | Trạng thái không có seller, không gọi mutation |
| Khởi động JAR cuối | Cửa sổ `VN code v1.5.7`, 1300×700; ba bảng V3 tồn tại; SQLite `integrity_check=ok` | Appdata mới, riêng biệt; không chứng minh thao tác tài khoản thật |
| `git diff --check` | Đạt | Kiểm tra định dạng diff |

Hai ca bỏ qua trong lần Java toàn bộ chỉ dành cho Windows:
`WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows` và
`Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`.
Không cộng 135 vào 2.809 thành số ca độc lập: hai lượt có kiểm thử trùng nhau.

## Phạm vi hồi quy

Đã kiểm tra migration hai lần giữ dữ liệu cũ, CAS, journal trước network, worker crash,
GTIN duy nhất và pool allocation chưa rõ, owner/environment isolation, hai confirm đồng thời,
review hết hạn/thay đổi/hủy mở lại, timeout không replay, lỗi 429 chỉ retry sau duyệt mới,
feed/sign acknowledgement không đồng nghĩa publication, publication cùng GTIN nhưng nội dung
cũ bị từ chối, đối chiếu submission/signature chưa rõ, sửa thẻ bị từ chối giữ GTIN/feed receipt,
GTIN phục hồi thủ công, nhập mọi trang/size/barcode WB và cursor hỏng, conditional attributes,
preview WB tồn tại qua checkpoint, hủy/chờ/timeout/readback WB và callback UI cũ.

Một lượt review độc lập đã hoàn tất. Bốn phát hiện quan trọng đều được sửa cùng test
hồi quy RED→GREEN. Nhóm 135 ca được chạy bằng Maven trên mã cuối sau các sửa chữa đó.
Các lần runner tạm thất bại do cấu hình JUnit/classpath đã được sửa; không dùng chúng
thay cho kết quả Maven cuối.

## Bằng chứng tại workspace

Trong `/workspace/znack-v3-research/`:
- `full-verify.log`: Java toàn bộ, kết thúc 11:02:25 UTC.
- `final-maven-verify.log`: nhóm mã cuối, kết thúc 11:13:29 UTC.
- `node-tests.log`: 106 ca tooling.
- `v3-ui-smoke.png`, `v3-ui-smoke.log`: cửa sổ V3 thực.
- `app-ready-result.txt`, `app-ready.log`, `app-ready-path`: JAR hoàn tất splash và migration.

Lần smoke ban đầu chỉ thấy splash và bị kết thúc trước migration, nên không dùng làm
bằng chứng startup. Lần chạy lại nêu trên chờ đúng cửa sổ chính và đủ ba bảng V3.
Updater nền gặp proxy CONNECT 403; kiểm tra cập nhật online chưa thành công, không cản cửa sổ chính.
JAR `target/VNcode-1.5.7.jar` là artifact phát triển trên nhánh này, không phải bộ cài mới
đã phát hành. Lệnh tái chạy môi trường được ghi trong `ZNACK_V3_IMPLEMENTATION.md`.

## Chưa được chứng minh

Chỉ có bằng chứng Level 1 cục bộ. Chưa có Level 2 sandbox, Level 3 production tiếp nhận,
hoặc Level 4 công bố. MockWebServer/unit tests không thay thế các mức nghiệm thu đó.
Không thực hiện cấp GTIN, gửi thẻ, ký hay đồng bộ WB thật. WB mutation vẫn khóa;
sandbox chưa có adapter auth được nghiệm thu. Windows/CryptoPro/chứng thư và từng xác nhận
của seller cần thực hiện theo `ZNACK_V3_PRODUCTION_ACCEPTANCE.md`.
