# Kiểm thử bản sửa Znack 1.5.8

Ngày 21/10/2026. Nền 1.5.7, commit `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
Phạm vi cuối: giữ nguyên màn hình/nút 1.5.7, tích hợp xử lý bên dưới; không có màn hình V3 riêng.
Môi trường Linux/Xvfb, Oracle Java 25.0.5, JavaFX 25.0.2, Maven 3.8.5, Node 22.23.3.

## Kết quả cuối

| Kiểm tra | Kết quả thực chạy |
|---|---|
| Maven `clean verify` toàn bộ trên mã cuối | **2.838 ca, 0 failures, 0 errors, 2 skipped; BUILD SUCCESS** |
| Đối chiếu Surefire XML | 2.836 đạt; 2 ca Windows bỏ qua trên Linux |
| `node --test tools/*.test.mjs` | **107/107 đạt** |
| FXML/controller đăng ký | Khớp nguyên văn tag 1.5.7; FXML smoke/controller tests đạt trong bộ đầy đủ |
| Hiển thị màn hình cũ | Render thực 1300×800, đủ các nút cũ, không có tab/màn hình V3 |
| JAR và `jpackage --type app-image` trên Linux | Đóng gói và khởi động native đạt; cửa sổ `VN code v1.5.8` đúng PID vừa mở |
| Database native mới | Đủ 5 bảng V3; `integrity_check=ok`, `foreign_key_check` không có lỗi |
| `git diff --check` | Đạt |

Lượt `clean verify` cuối chạy đủ mọi ca, không lọc lớp, trong 13 phút 15 giây.
Dùng hai Surefire JVM với user.home và Xvfb riêng cho từng fork; không dùng song song
trên cùng màn hình/clipboard. Wrapper test và hướng dẫn trong workspace
`/workspace/znack-v3-research/158-isolated-forks/`, không nằm trong runtime production.
Không cộng các lượt focused/runner vào số ca độc lập của bộ đầy đủ.

Hai ca bỏ qua:
- `WindowsSecretProtectorTest.separatesLicenseAndExistingMailEntropyOnWindows`.
- `Gs1PrefixTemplateStoreTest.refusesNtfsJunctionDirectoryAndAncestors`.

SHA-256 JAR phát triển đã kiểm tra native:
`78395e4dcc16dc416e5de487f37ae1829ad473abd3a57378525d32ca66feddc7`.
Đây là JAR/app-image Linux trên nhánh phát triển, không phải EXE Windows đã phát hành.

## Các lỗi được phát hiện và sửa

Review độc lập phát hiện receipt cấp mã chưa giữ chỗ khi crash trước khi lưu thẻ;
lượt thay thẻ bị từ chối dùng lại cache cấp mã cũ; marker allocation đặt trước GET.
Đã sửa cùng kiểm thử hồi quy về reservation, generation bền vững và boundary tại mutation.
Đường nhận thẻ hiện có cũng được đưa qua kiểm tra nội dung và đối chiếu journal chung.

Trong rà soát cuối, mở rộng reservation vào cả truy vấn claimed-GTIN của WB/Ozon.
Test thêm đã thất bại đúng chỗ trước sửa, rồi đạt 14/14 trong runner riêng; sau đó chạy
lại toàn bộ Maven trên chính mã đã sửa, kết quả cuối nêu trên.

Lượt full đầu tiên có 2.838 ca, **4 failures**, 0 errors, 2 skipped. Bốn ca thuộc
`ZnackRegistrationBatchHttpIntegrationTest`:
- `sequentialBatchPostsTheSavedPayloadsAndCountsOnlyReadBackCards`.
- `readbackRateLimitAfterAcceptedPostPreservesIdentityAndNeverReplaysPost`.
- `http200WithoutFeedIdDoesNotCountAsCreatedOrReplayTheRequest`.
- `acceptedHttpFeedsWithoutCardsRemainPendingUntilReadbackConfirmsThem`.

Nguyên nhân: fixture HTTP không seed danh tính/chứng thư như các fixture workflow khác;
điểm kiểm tra chủ thể mới chặn trước POST. Đổi setup sang fixture repository chuẩn,
giữ nguyên mọi assertion HTTP/readback. Chạy lại bốn ca đạt, rồi toàn bộ `clean verify`
cuối đạt. Không bỏ các ca lỗi, không nới kiểm tra production để cho test qua.

Các hồi quy còn bao gồm hai worker, mất phản hồi, khôi phục receipt không POST lại,
thay payload không vượt UNKNOWN, pool INN/môi trường qua đổi shop/chứng thư,
group signing nguyên tử, thẻ published nội dung cũ không hoàn tất, sửa thẻ cùng GTIN,
nhận thẻ sau mất feed cho phép duyệt ký riêng, ownership/profile và bảo toàn dữ liệu cũ.

## Nâng cấp và giới hạn bằng chứng

CI đã thêm baseline `-FromStable157`: EXE 1.5.7 ghim checksum/kích thước từ bằng chứng
phát hành của kho, giữ chuỗi probe cũ, kiểm tra passive EXE/embedded MSI và fingerprint
dữ liệu, lưu `upgrade-stable-157-smoke.json`. Node contract mới đã qua RED→GREEN.
**Chưa chạy probe này trên Windows**, chưa tự tải/xác minh lại bộ cài baseline ở tác vụ này;
CI phải xác minh byte/checksum trước khi cài. Bằng chứng nguồn được lưu trong
`docs/evidence/vncode-1.5.7-installer.json`.

Chỉ có nghiệm thu cục bộ Level 1. Không có bằng chứng sandbox Level 2, production tiếp nhận
Level 3 hay công bố Level 4. Không cấp GTIN, gửi thẻ, ký hoặc đồng bộ seller thật.
Chưa kiểm tra CryptoPro/chứng thư, DPAPI và nâng cấp native trên Windows cho 1.5.8.

`api.github.com` bị proxy CONNECT 403 cả khi không xác thực, nên quyền GitHub/Windows CI
chưa được xác minh. Bản nháp mạng thêm host này đã lưu, **chưa áp dụng**; cần review và
Save trong Environment settings rồi Publish môi trường. Không tự nới gate phát hành.

## Bằng chứng giữ trong workspace

Trong `/workspace/znack-v3-research/`:
- `1.5.8-final-full-verify.log`, `158-java-results.json`: kết quả Java cuối.
- `158-final-node.log`: 107 ca công cụ.
- `1.5.8-full-verify.log`: lượt đầu có bốn failures, giữ nguyên để đối chiếu.
- `158-http-fixture-green.log`, `158-reservation-red.log`, `158-reservation-green.log`.
- `158-upgrade-red.log`: regression baseline Windows trước sửa.
- `1.5.8-registration-ui.png`, `1.5.8-fxml-smoke.log`: màn hình cũ thực.
- `native-158-smoke.json`, `native-158-package.log`, `native-158-path`, `native-158-data`.

Hướng dẫn tích hợp/phát hành: `ZNACK_1.5.8_UPDATE.md`.
