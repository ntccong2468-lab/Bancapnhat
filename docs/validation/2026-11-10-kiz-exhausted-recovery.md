# KIZ: phục hồi bộ đệm EXHAUSTED — 10.11.2026

**Bản nháp nghiệm thu; lượt hồi quy cuối vẫn PENDING.** Đã tái hiện lỗi 3390 trên mã phát hành 1.6.7, sửa đường phục hồi và review độc lập. Người dùng đã cung cấp thông tin OMS nhưng môi trường chưa có phiên SUZ/chữ ký để xác minh đơn mua KIZ thật. Không coi tài liệu hợp đồng hoặc test tổng hợp là nghiệm thu mua/cấp mã thật.

Phạm vi nhánh: `fix/znack-preflight-bulk-20261110`, worktree `/workspace/vncode-znack-bulk-20261110`. Latest vẫn là 1.6.7, nguồn app `d0231cd6913ce3a1764067bad0901b32c535de41`; công việc này chưa tạo bộ cài hoặc phát hành. Phần sửa đăng ký Znack 300/10.000 trước đó được bàn giao riêng tại [Znack bulk preflight](2026-11-10-znack-bulk-preflight.md).

## Chẩn đoán đã có bằng chứng

Thông báo `HTTP 400`, mã nghiệp vụ `3390`, trạng thái buffer `EXHAUSTED` có nghĩa SUZ từ chối cấp tiếp bằng đường lấy mã thông thường vì buffer không còn `ACTIVE`. Theo tài liệu chính thức, `EXHAUSTED` là buffer và các pool của phương thức phát hành không còn mã. Đây không phải bằng chứng đơn mua thất bại, không phải trạng thái `CLOSED`, cũng không chứng minh mã đã lưu ở app hoặc đã lưu thông.

Mã phát hành xử lý `3390/PENDING` và đơn đóng, nhưng chưa đưa `3390/EXHAUSTED` vào đường đối soát các khối đã cấp. Một đáp ứng cấp mã đã tới SUZ nhưng app chưa lưu đủ có thể khiến lần gọi tiếp nhận `EXHAUSTED`; đây là tình huống cần phục hồi lịch sử cấp mã, thay vì cấp lại hoặc mua đơn mới. Chưa đọc được OMS của seller nên chưa khẳng định đây chính xác là lịch sử của đơn trong ảnh.

Test hồi quy dùng đáp ứng SUZ tổng hợp đi qua coordinator của đúng nguồn phát hành đã thất bại với lỗi 3390 này: [baseline-red.json](kiz-exhausted-20261110/baseline-red.json). Đây là tái hiện lỗi mã nguồn, không phải một giao dịch thử trên dịch vụ thật.

**Đính chính kết luận review ban đầu:** catch hiện hành giữ nguyên stage đang hoạt động `POLLING_ORDER` hoặc `DOWNLOADING_CODES` cùng lỗi. Không có bằng chứng đường 3390 được báo đã chuyển pipeline thành `FAILED`. Bản sửa không bổ sung cơ chế tự kích hoạt lại các job `FAILED` cũ.

## Hợp đồng SUZ được đối chiếu

Nguồn là «СУЗ-ОБЛАКО 5.0. Руководство программиста», phiên bản 5.03, API 3.0.40, lần biên soạn 67, phê duyệt 24.09.2026. Các bản tài liệu công khai đã được tải HTTP 200; bằng chứng URL, SHA-256, số trang và phạm vi xác minh nằm trong [official-contract.json](kiz-exhausted-20261110/official-contract.json).

- [API SUZ chính thức, PDF tiếng Nga](https://docs.crpt.ru/gismt/files/pdf/API_%D0%A1%D0%A3%D0%97_3.0.pdf): §5.3.1.6, bảng 267, trang PDF 456 phân biệt `EXHAUSTED` và `CLOSED`.
- [Danh mục lỗi SUZ chính thức](https://docs.crpt.ru/gismt/%D0%98%D0%BD%D1%81%D1%82%D1%80%D1%83%D0%BA%D1%86%D0%B8%D1%8F_%D0%BF%D0%BE_%D0%B2%D0%BE%D0%B7%D0%BD%D0%B8%D0%BA%D0%B0%D1%8E%D1%89%D0%B8%D0%BC_%D0%BE%D1%88%D0%B8%D0%B1%D0%BA%D0%B0%D0%BC_%D0%A1%D0%A3%D0%97/): lỗi 3390 xảy ra khi `bufferStatus` khác `ACTIVE`; chỉ số 3390 không đủ để gọi mọi trường hợp là chờ `PENDING`.

| Đường API | Ý nghĩa và giới hạn theo tài liệu |
| --- | --- |
| `GET /api/v3/codes` | Cấp mã từ đơn. Dù là GET, đây là thao tác tiêu thụ lượt cấp; không bật tự gửi lại HTTP một cách trong suốt. §4.4.4, trang 174–176. |
| `GET /api/v3/order/codes/blocks?omsId&orderId&gtin` | Liệt kê các khối đã cấp của đúng đơn/GTIN. Yêu cầu suborder chưa đóng và lần yêu cầu in đầu tiên thực hiện qua API. §4.4.5, trang 177–180. |
| `GET /api/v3/order/codes/retry?omsId&blockId` | Lấy lại các mã đã cấp trong khối khi mất liên lạc, không tạo đơn mua mới. Yêu cầu mã từng được yêu cầu qua API, đơn chưa đóng, phương thức phát hành/nhóm TN VED chưa đóng hoặc bị khóa. §4.4.6, trang 181–183. |

Hợp đồng hiện hành yêu cầu `omsId`, `orderId`, `gtin`, `blocks` trong danh sách; mỗi khối có `blockId`, `blockDateTime`, `quantity`. Đáp ứng retry có `omsId`, `codes`, `blockId`. Parser vẫn giữ khả năng đọc một số đáp ứng cũ thiếu trường; mọi danh tính được cung cấp đều phải khớp. Thiếu trường ở định dạng tương thích cũ không được mô tả là bằng chứng danh tính mới theo hợp đồng hiện hành.

## Thay đổi mã nguồn

| Thành phần | Hành vi sau sửa |
| --- | --- |
| `ZnackErrorMessages` | Phân biệt marker đã lưu hoặc đúng lỗi 3390 kèm token buffer/`EXHAUSTED`; không gom mọi 3390, token số gần giống hoặc `NOT EXHAUSTED` vào phục hồi. Ẩn chi tiết kỹ thuật dự kiến khi pipeline đang đối soát, nhưng giữ lỗi sai danh tính có thể nhìn thấy. |
| `ZnackPurchaseCoordinator` | Nhận diện `EXHAUSTED` từ đọc trạng thái, lỗi cấp mã và dữ liệu đã lưu sau khởi động lại. Giữ trạng thái từ xa là `EXHAUSTED`, không gán giả thành `CLOSED`. Lưu checkpoint này trước khi gọi API phục hồi để lỗi mạng, auth hoặc danh tính ở bước sau không đưa lần chạy kế tiếp về `/codes`. |
| `ZnackKizCodeService` | Đối soát khối đã cấp trong đúng đơn mua hiện có. Một mã đã lưu không chứng minh cả khối đã đủ. Chỉ bỏ qua khối khi số lượng metadata dương khớp số mã địa phương; khối thiếu một phần hoặc chưa có bằng chứng số lượng được lấy lại khi đơn còn thiếu. |
| `FriendlyErrorService`, bốn locale | Lỗi `SUZ_CODE_BLOCK_RECOVERY_IDENTITY_INVALID` có thông báo rõ về đơn/OMS/GTIN không khớp, giữ nguyên đơn và mã đã lưu. Không đưa nội dung wrapper chứa thông tin riêng tư vào thông báo này. |

Các ràng buộc quan trọng:

1. Giữ nguyên cửa hàng, cấu hình gắn với pipeline, OMS, external order ID, GTIN, số lượng và request key của đơn đã mua. Đơn không có external ID đã xác nhận, sai GTIN hoặc sai số lượng bị chặn đối soát.
2. Trước khi dùng tổng số mã làm bằng chứng đã nhận đủ, kiểm tra mã đã lưu thuộc đúng order ID/metadata GTIN; mã thô phân tích được cũng phải có GTIN đúng. Tập mã đủ số lượng nhưng khác danh tính không được coi là hoàn tất.
3. Kiểm tra danh tính được cung cấp trong toàn bộ danh sách khối trước khi retry khối đầu tiên. Đáp ứng retry phải khớp `blockId` đã yêu cầu và các OMS/order/GTIN được cung cấp. Chuẩn hóa độ dài GTIN và chữ hoa/thường của UUID; không ghép gần đúng danh tính.
4. Số mã thô khác nhau trả lại không được vượt số lượng khối đã khai báo. Khối hợp lệ được lưu trước khi lỗi ở khối sau vẫn tồn tại để lần tiếp theo tiếp tục.
5. Lưu lại bằng `INSERT OR IGNORE` hiện có: mã trùng nguyên văn không được thêm lần nữa; trạng thái đã giữ chỗ, đã dùng và trạng thái pháp lý cũ không bị đặt lại. Nếu dữ liệu địa phương đã đủ và đúng danh tính, không gọi auth/API phục hồi.
6. Không tạo đơn mua mới, không mua bù tự động, không đặt lại đơn về hàng đợi tạo đơn và không tự kích hoạt lại mọi pipeline `FAILED`. Khi chưa đối soát đủ, giữ đơn hiện có ở trạng thái chờ đối soát với nhịp chờ phù hợp.

Không có migration/schema mới, sửa luồng license hoặc thay đổi quy trình WB/Ozon trong bản sửa KIZ này.

## Kiểm thử và review — giữ cả lịch sử thất bại

| Lượt | Kết quả có thể xác nhận |
| --- | --- |
| Baseline mã phát hành `d0231cd…` | 1 ca, 1 failure, 0 error/skip; đúng lỗi SUZ tổng hợp 3390/EXHAUSTED. [Receipt](kiz-exhausted-20261110/baseline-red.json). |
| Focused đầu tiên sau sửa | 247 ca/16 lớp: 2 failure, 0 error/skip. Không phải nghiệm thu cuối. [Receipt đầy đủ](kiz-exhausted-20261110/first-focused-failure.json). |
| Sửa hai kỳ vọng fixture | Fixture lỗi danh tính vẫn kỳ vọng mã thô từ hàm hiển thị sau khi thông báo đã được dịch; đã tách kiểm tra lỗi lưu thô và thông báo UI. Fixture đồng thời cũ thiếu external order ID đã được chấp nhận; bổ sung ID tổng hợp, giữ kiểm tra khóa/concurrency và ràng buộc đơn. Không nới guard sản xuất để làm test xanh. |
| Rerun kết hợp sau chỉnh fixture | Dừng có chủ đích sau 108 ca/6 lớp đạt khi trace phát hiện báo cáo vẫn mở/đóng SQLite trước keepalive và đếm toàn lô. Lớp tải300 chưa hoàn tất; không coi lượt dừng là PASS. Bản sửa truy vấn và keepalive theo toàn bộ một item đang chạy lượt mới. |
| Review độc lập | [static-review.json](kiz-exhausted-20261110/static-review.json) và [localized-review.json](kiz-exhausted-20261110/localized-review.json) không còn finding chặn trong phạm vi đã đọc. Đây là review mã, không phải test OMS thật hoặc review toàn ứng dụng. |

Lệnh tái hiện baseline đã chạy:

```sh
/workspace/toolchains/mvn-vncode -B -Dtest=ZnackExhaustedBufferWorkflowRegressionTest test
```

Lệnh rerun kết hợp đã dừng, không phải nghiệm thu cuối:

```sh
PATH=/workspace/toolchains/xvfb-current/root/usr/bin:$PATH xvfb-run -a /workspace/toolchains/mvn-vncode -B -Dtest=ZnackGtinWorkflowTest,ZnackExhaustedBufferRecoveryTest,ZnackRegistrationBatchServiceTest,ZnackRegistrationBatchHttpIntegrationTest,RegistrationLargeBatchDurabilityTest,DatabaseBatchSessionTest,RegistrationBatchConnectionLifetimeTest,RegistrationBackgroundLifetimeTest,UpdateSnapshotHandoffTest test > /tmp/vncode-znack-bulk-audit/combined-final.log 2>&1
```

Các test mới bao phủ đường 3390 trên coordinator, trạng thái `EXHAUSTED` trực tiếp, restart, khối lưu thiếu một phần, lỗi ở khối sau, sai danh tính ở danh sách/retry/mã thô/mã đã lưu, tập mã đủ nhưng thuộc GTIN khác, hoàn tất cục bộ không gọi API, cùng việc giữ trạng thái mã đã dùng/lưu thông. Test locale kiểm tra thông báo ở mọi ngôn ngữ app và wrapper riêng tư không xuất hiện.

Manifest ban đầu và review có phạm vi riêng: [source-manifest.json](kiz-exhausted-20261110/source-manifest.json) là ảnh chụp trước chỉnh fixture cuối; [localized-review.json](kiz-exhausted-20261110/localized-review.json) ghi rõ hash thực tế đã review và delta ở fixture. Không dùng ảnh chụp cũ để chứng nhận byte mã/test thay đổi sau đó. Rerun kết hợp phải được đối chiếu manifest/receipt cuối trước khi chốt nghiệm thu.

## Giới hạn và việc còn phải nghiệm thu

- Người dùng đã cung cấp thông tin OMS và yêu cầu mua thật, sau đó chỉ đạo tiếp tục sửa mã nguồn. [Kiểm tra mạng thật](kiz-exhausted-20261110/suz-live-access-preflight.json): endpoint đúng `auth/key` trả200 và challenge ký; `order/list` thiếu clientToken trả400/1090. Probe đầu dùng nhầm đường `auth/cert/key` trả404 được giữ trong lịch sử. Chưa đăng nhập SUZ hoặc đọc được dữ liệu đơn; số mua/cấp mã/ký/ghi dữ liệu seller đều0. Không lưu thông tin OMS/token/challenge thật trong Git.
- `omsConnection` là mã kết nối, token WB là quyền API WB; chúng không thay thế clientToken SUZ. Kể cả có clientToken để đọc, tạo đơn mới trong luồng hiện hành còn cần chữ ký CMS `X-Signature` trên đúng payload. Môi trường không có CryptoPro/chứng thư riêng hoặc phiên SUZ; không lấy token WB/Catalog thay quyền SUZ, không giả ký/license để mua.
- Chưa chứng minh khả năng phục hồi đơn thực tế của seller; cần đọc đúng trạng thái và lịch sử khối bằng cấu hình của chính đơn mua, không tạo đơn thay thế để thử.
- Không bảo đảm endpoint retry dùng được cho đơn đã đóng, lần in đầu từ giao diện web, phương thức phát hành/nhóm hàng bị khóa hoặc hết quyền truy cập. Khi SUZ không cho lấy lại, app giữ nguyên đơn/dữ liệu và yêu cầu đối soát; không tự báo đã nhận đủ.
- Giữ tương thích mã thô cũ không phân tích được GTIN. Trường hợp đó không có bằng chứng từ nội dung mã để xác nhận danh tính hoặc tính hợp pháp; đây là giới hạn rõ ràng, không phải bỏ qua sai GTIN đọc được.
- Khử trùng hiện có dựa trên chuỗi mã thô nguyên văn. Không tuyên bố mọi cách mã hóa khác nhau của cùng một CIS đã được hợp nhất.
- Có đủ số lượng mã chỉ chứng minh đã nhận đủ dữ liệu sau các kiểm tra tương ứng. Không tự chứng minh mã đã đưa vào lưu thông hoặc được Ozon/WB chấp nhận; các bước pháp lý, ký và gán mã vẫn giữ kiểm tra cũ.
- Chưa chạy toàn bộ bộ test ứng dụng hoặc kiểm CryptoPro/OMS trên Windows thật cho phần sửa này. Cần receipt rerun cuối và kiểm nghiệm môi trường thật trong phạm vi được cho phép trước khi xác nhận đầu-cuối.
