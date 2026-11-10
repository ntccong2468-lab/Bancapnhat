# VN Code 1.6.8 — Latest đã phát hành và kiểm chứng

Công bố **10.11.2026 18:34:37 MSK /15:34:37 UTC**. [EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.8/VN-code-1.6.8-Windows-x64.exe) · [Release](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.8) · [Receipt cuối](vncode-168-20261110/final-release.json).

Nguồn ứng dụng **38906a2108501029d9609ed1d071ee987c8b2637**, nhánh `release/vn-code-1.6.8`; source này kế thừa73619d3/runtime3990072/core8fed8f7 và toàn bộ bản1.6.7d023. Publisher riêng **1a193778c1ba1aab22975200282d1062585542c9**, nhánh `release/vn-code-1.6.8-publication`; commit tài liệu sau đó không sửa binary hoặc tag.

## Những sửa đã đưa vào bộ cài

- Đọc đầy đủ thẻ WB theo cursor, đối chiếu danh sách mới khi tìm theo nmID trả rỗng; lỗi chỉ rõ thẻ/biến thể/trường thiếu. API WB thật trước release đã chứng minh3/11 tìm kiếm rỗng dù thẻ còn trên trang mới; production source review đọc đủ300/300 bằng4HTTP/15,696s. Đây là bước đọc nguồn, không phải300 thẻ tạo thật.
- Preflight báo đầy đủ tập chọn, chỉ tiếp tục tập hợp lệ sau xác nhận; không dùng cache cũ hoặc tự loại thẻ lỗi. Giữ owner, exactvariant, source revision và consent. Chọn tối đa10.000 biến thể mỗi lượt, trang hiển thị300, deadline chuẩn bị10phút; hàng đợi gửi lưu từng mục/feed_id và phục hồi qua restart.
- KIZ `3390/EXHAUSTED`: chuyển sang đối soát/lấy lại gói đã cấp đúng OMS/order/GTIN/block, giữ marker qua restart, bổ sung phần khối chưa lưu đủ. Kiểm cả danh tính mã đã lưu trước khi dùng số lượng làm bằng chứng hoàn tất. Không reset mã đã dùng/giữ chỗ, không mua lại mù hoặc tự bật mọi job FAILED.
- Báo cáo đăng ký dùng lookup chính xác và indexed existence thay đếm toàn lô tại mỗi chuyển trạng thái; giữ receipt/event ngay và keepalive SQLite theo từng item, đóng trước checkpoint updater vòng gửi.
- Kế thừa toàn bộ1.6.7: chứng thư CryptoPro/confirmation, giấy tờ Latin/Cyrillic, KIZ mapping/màu/giấy tờ, GTIN WB/Ozon, FBS/nhãn PDF, sản phẩm, Đơn nợ, license kiểm nền, UI native đã duyệt và kho/lịch sử/công cụ. Không đổi dependency/schema6/UpgradeCode hoặc đưa prototype mã hóa credential PR-only vào runtime.

[Source inclusion](vncode-168-20261110/source-inclusion.json):14 Java runtime/4 resource locale/14 test/POM thay so với d023; source gốc là ancestor. [Bàn giao Znack](2026-11-10-znack-bulk-preflight.md), [KIZ](2026-11-10-kiz-exhausted-recovery.md) giữ lịch sử chẩn đoán.

## Build, kiểm thử và bộ cài

[Windows42828436446](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42828436446) SUCCESS trên đúng38906. **4.254ca/554lớp,4.252đạt,0failure/error,2OSskip**. Bảy lớp mới đều có;554=547 lớp nền+7 mới. Hai skip đúng `Gs1PrefixTemplateStoreTest#privatePermissionsAreAppliedWhereSupported` và `WindowsSecretProtectorTest#neverFallsBackToPlaintextOffWindows`.

Ba bài GTIN10.000 và hai bài hàng đợi300/10.000 đã qua. Bài300 giữ nguyên file/hash/timeout600s/assertion, đạt256,316s trong full run này; đây là300 POST tới localhost, không phải benchmark Catalog thật. Queue10.000 đạt14,465s,0network. Không cộng các lượt502Windows/45Linux hoặc283core lịch sử vào4.254ca này.

Node tools tại source ứng dụng166/166 và publisher174/174 đều đạt ở phạm vi riêng, có trùng nên không cộng tổng. Native startup/migration3→6/snapshot, DPAPI Windows, cài/gỡ/coexistence và passive EXE upgrade từ1.6.7 giữ dữ liệu đều đạt. RawActionsZIP529.803.075bytes/digest `d599bd42bf085da27e5790ca368195a9e71ec71878f2376148596941e696d9fd` đã tải nguyên vẹn trước giải nén16tệp.

[Package review độc lập](vncode-168-20261110/package-review.json):3.086tệp sourceZIP khớp Git,2.760 khác biệt chỉ UTF8LF/CRLF. JAR production1.994class, đủ18runtime thay đổi, không fixture/test profile. MSI đọc độc lập từ resource PE của EXE khớp chính xác artifact và Windows smoke: [proof](vncode-168-20261110/embedded-msi-review.json). Không gọi đó là biên dịch độc lập từ source; liên hệ class/source còn dựa provenance CI đã kiểm.

| Tệp | Byte | SHA-256 |
| --- | ---: | --- |
| EXE |149408256|`bce16b0cb2eadc155f89c9bafcecd802cf8463272b02aae6e68bf9f105b90707`|
| MSI |148739333|`8dbfd5e6eb91c4635a1f535640e77b147254944cb11fe0998a367348f76a41cf`|
| Portable ZIP |148326903|`5e4a8ac20439df64a61b847571f9cd8581b817f661749481d29fd6c76126e141`|

## Công bố và updater

Publisher **42845466246 SUCCESS**, immutable release **440439895/tag v1.6.8**, đúng source38906. Module mới chỉ cho bản168 này, pin build/draft/tổng4254/4252/2, giữ sàn4188; không sửa tools167 hoặc dùng gate partial/focused thay full. [Metadata run](vncode-168-20261110/publisher-run.json).

[16tệp public được tải thật](vncode-168-20261110/public-downloads.json), mọi byte/hash khớp gói đã review. [Preservation PASS](vncode-168-20261110/public-preservation.json):giữ nguyên **17release/274asset/46tag cũ**, sau công bố18release/290asset/47tag. Không prune/ghi đè/thêm asset sau Publish.

[Binary1.6.7 gốc](vncode-168-20261110/updater-verification.json) trước công bố trảCURRENT1.6.7; sau công bố trả **AVAILABLE1.6.8**, chọn đúng MSI URL/hash/148739333bytes. Đối chiếu lại75JAR và probe không đổi; không override version. Probe chạy Linux JVM với JAR Windows gốc, chỉ giả lập chọn tài sản Windows sau đọc mạng; không quan sát popup/cài đặt trên máy seller. Transport giữ Cloud proxy/CA, có thể gắn repository credential; không chứng minh anonymous độc lập. `manifestPublicKeyPinned=false`/Authenticode chưa ký là trạng thái nền giữ nguyên.

## Lệnh và lịch sử bằng chứng

Workflow full trên Windows dùng `mvnw.cmd -B clean verify`, `node --test tools/*.test.mjs`, rồi `build.bat exe` và `build.bat app-image` tái sử dụng Java đã kiểm. MSI/EXE/proof được giữ trước khi app-image dọn out. Nguồn/workflow/build/sourceZIP/JAR và run được đối chiếu; không dùng JAR nội bộ mang1.6.7 từ lượt trước làm bộ cài mới.

Kiểm tra độc lập đã chạy trên16file artifact thật:

```sh
python3 /tmp/vncode-168-independent-verifier/verify.py --repo /workspace/vncode-168-release --artifacts /tmp/vncode-168-release-audit/reviewed-artifacts --run-metadata /tmp/vncode-168-release-audit/windows-run-final.json --run-id 42828436446 --workflow-id 392732536 --artifact-metadata /tmp/vncode-168-release-audit/artifact-metadata.json --artifact-id 13778000178 --artifact-zip /tmp/vncode-168-release-audit/complete-baseline-actions.zip --expected-tests 4254 --expected-suites 554 --receipt /tmp/vncode-168-release-audit/package-independent-review.json
python3 /tmp/vncode-168-release-audit/verify-public.py --after-releases /tmp/vncode-168-release-audit/releases-after.json --after-tags /tmp/vncode-168-release-audit/tags-after.json --latest /tmp/vncode-168-release-audit/latest-after.json --public-dir /tmp/vncode-168-release-audit/public-assets --reviewed-dir /tmp/vncode-168-release-audit/reviewed-artifacts --review-receipt /tmp/vncode-168-release-audit/package-independent-review.json --updater-after /tmp/vncode-168-exact167-probe/after.json
```

[Công cụ nguồn được lưu](vncode-168-20261110/tools/README.md) giữ hash và giới hạn; không phải gói tự đủ đầu vào. Checkpoint “đang chạy” trước đó là lịch sử, không phải PASS. Lịch sử coreWindows170/171 FAIL/timeout600s, candidate dừng108ca, KIZ247ca với2fixture lỗi vẫn giữ ở bàn giao trước; không xóa hoặc diễn giải thành lượt đạt.

## Giới hạn và tiếp tục

Security run42828869332 đúng38906: [so sánh SARIF](vncode-168-20261110/security-comparison.json) giữ8finding Java cũ/1high gate/0mới, **Java gate FAIL**, JS0finding/lint-audit đạt. Có extraction error GS1 và giới hạn coverage; dispatch bỏ OpenQodex/dependency-review theo thiết kế. Không hạ query/gate/suppression hoặc gọi toàn bộ security xanh.

Không seller API write, nhận đơn/mua KIZ, ký thật, license activation, Gmail hoặc portal deploy trong lượt phát hành. Chưa nghiệm thu OMS/CryptoPro seller, tạo/ký/công bố thẻ Catalog thật, hoặc10.000 thẻ gửi thật. EXHAUSTED retry còn chịu điều kiện API-first/đơn chưa đóng/quyền của SUZ; không tự mua lại khi không lấy được. Preflight vẫn10phút/128MiB/chưa checkpoint qua restart; vòng readback thứ hai chưa checkpoint updater giữa từng item. Các giới hạn PDF1.000đơn thật, Narrator/DPI và các mục tồn tại trước giữ scope riêng.

Sau cập nhật chạy **Đồng bộ Znack** một lần; giữ profile/feed/jobs/journal, không tạo thẻ/đơn mua thay yêu cầu chưa rõ kết quả. Source/tag/assets168 đã immutable; sửa tiếp phải là source/phiên bản mới. Commit bàn giao sau công bố chỉ đổi tài liệu và không thay binary.
