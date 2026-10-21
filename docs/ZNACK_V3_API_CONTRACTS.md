# Hợp đồng API Znack V3

Đối chiếu ngày 21/10/2026 với [tài liệu chính thức National Catalog API 5.68,
ngày 28/09/2026](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/), đọc thành công HTTP 200.
Bằng chứng URL, phiên bản, thời điểm lấy và SHA-256 nằm trong
[evidence/znack-v3-catalog-contracts.json](evidence/znack-v3-catalog-contracts.json).
Bản HTML đầy đủ giữ ngoài checkout tại `/workspace/znack-v3-research/official-api-nk.html`.
Không coi việc đọc tài liệu là bằng chứng đã thực hiện giao dịch seller thật.

Các trang `dev.national-catalog.ru`, `docs.cryptopro.ru`, `dev.wildberries.ru`
đã trả HTTP 403 trong lần kiểm tra. Đã lưu draft bổ sung host tài liệu;
lưu draft chưa chứng minh cấu hình runtime đã áp dụng. Không yêu cầu GitHub token:
Git read-only của các kho nguồn đã hoạt động qua xác thực nền tảng.

## Hệ thống và xác thực

| Hệ thống | Host và phạm vi |
|---|---|
| Catalog production | `https://апи.национальный-каталог.рф`, khớp host IDNA trong ZnackModels |
| Catalog sandbox | `https://api.nk.sandbox.crptech.ru` — xác nhận địa chỉ, chưa có phiên sandbox được nghiệm thu |
| True API / ГИС МТ | Xác thực và nghiệp vụ hệ thống маркировка; không thay Catalog tạo thẻ |
| SUZ | Đặt/nhận mã маркировка; không dùng thay đăng ký thẻ |
| WB Content | Đọc/cập nhật sản phẩm của đúng shop, là giao dịch riêng với Catalog |

API NK chấp nhận `apikey` hoặc `Authorization: Bearer <token ГИС МТ>`.
Theo tài liệu, API key được quản trị viên lấy ở Hồ sơ → Dữ liệu người tham gia.
`CatalogConnectionService` hiện hữu kiểm tra khóa, phạm vi chủ thể, chứng thư và
DPAPI trên Windows; V3 dùng client này, không sao chép khóa vào journal.
Không sử dụng host tùy ý hoặc suy ra môi trường từ chuỗi con URL.

## Hợp đồng đã xác minh từ nguồn chính thức

Base cho các dòng dưới là host Catalog production/sandbox ở trên; version API là 3.
Auth chung như trên, JSON UTF-8. Các schema thuộc tính phải lấy theo danh mục hiện hành,
không thay bằng một schema quần áo cố định.

| Nghiệp vụ / method / path | Request | Response và lỗi | Tài liệu |
|---|---|---|---|
| Cấp GTIN GET `/v3/generate-gtins` | `quantity` để cấp; `exist=true` chỉ đọc các mã đã cấp; `supplier_key` theo quyền | `result.monthly-limit.limit/usage`, `result.drafts[].gtin`; đọc exist tối đa 10.000 bản ghi, không coi danh sách bị giới hạn là bằng chứng tuyệt đối chưa từng cấp | §3.2.1 |
| Tạo/cập nhật POST `/v3/feed` | Entry gồm GTIN, tên, TN VED, brand, categories, identified_by, good_attrs, ảnh; có `good_id` khi sửa; `moderation=1` khi gửi kiểm duyệt. Ví dụ JSON một entry trong tài liệu là object | `result.feed_id`; HTTP 400 sai schema, 413 vượt giới hạn; giới hạn tài liệu 25 MB, 500 hàng, 1.000 thành phần bộ; feed lưu một năm | §3.2.2 |
| Đọc feed GET `/v3/feed-status` | `feed_id`, `verbose=true` để lấy lỗi chi tiết | `feed_id`, `status`, `status_id`, lỗi từng hàng/thuộc tính; Received, Processing, Moderated, Signed, Rejected | §3.2.3 |
| Đọc thẻ riêng GET `/v3/feed-product` | Một nhóm `gtin/good_id` hoặc `gtins/good_ids`; tối đa 25 vị trí khi dùng danh sách | `identified_by`, `good_id`, trạng thái và thuộc tính thẻ thuộc tài khoản hoặc được chủ thể cấp quyền; phải đối chiếu cả GTIN lẫn good_id | §3.1.1 |
| Lấy XML POST `/v3/feed-product-document` | `goodIds`, `gtins`, `publicationAgreement`; tối đa 10 thẻ | `result.xmls[].goodId/xml`, `errors`; không ký khi thiếu/sai danh tính | §3.4.1 |
| Gửi chữ ký POST `/v3/feed-product-sign-pkcs` | Tối đa 10 phần tử `{goodId,base64Xml,signature}`; XML nguyên bản và chữ ký tách rời mã hóa Base64 riêng | `result.signed[]`, `errors[].goodId/message`; chữ ký **CAdES PKCS#7 detached**. V3 vẫn đọc lại thẻ và nội dung trước xác nhận công bố | §3.4.3 |
| Danh mục GET `/v3/categories` | TN VED theo API và quyền | Danh mục do server trả; seller chọn, không tự suy đoán danh mục pháp lý | Phần thuộc tính/danh mục |
| Thuộc tính GET `/v3/attributes` | `cat_id`, `attr_type` | Loại trường, preset, value types, điều kiện phụ thuộc; điều kiện phát sinh phải có ô nhập tương ứng | Phần thuộc tính/danh mục |

Quy tắc TN VED trong §3.2.2 phân biệt nhóm bốn số và mã đủ mười số/thuộc tính 13933.
V3 giữ mã đủ do seller xác nhận và dùng bộ dựng/kiểm tra payload hiện hữu đã có xử lý quy tắc này.
Không tự tạo GTIN ngẫu nhiên, không tự tạo giấy tờ hay quốc gia sản xuất.

### Rate limit và retry

Giới hạn chung và riêng theo tổ chức/method; phản hồi có `API-Usage-Limit` và
`API-Method-Usage-Limit`. Tài liệu quy định chu kỳ mới sau năm phút kể từ request
đầu của chu kỳ. Không suy diễn một hạn mức số chung cho mọi tài khoản từ ví dụ.
Tái sử dụng request budget/cooldown của client hiện hữu; tôn trọng Retry-After.
Read có retry giới hạn của client; **không tự replay write**, kể cả GET cấp GTIN.
Timeout, mất ack và 5xx giữ UNKNOWN. HTTP 400/413/401/403/429 được ghi nhận là
phản hồi từ chối rõ ràng; chỉ thử lại sau sửa/xác thực và một lần duyệt mới, có bộ đếm attempt.
GTIN cấp chưa rõ kết quả khóa pool của cùng chủ thể đến khi đối chiếu.

### Chứng minh công bố

`good_status=published`, chữ ký hợp lệ theo dữ liệu trả về, danh tính đúng,
không có lỗi và nội dung đúng phiên bản đã duyệt mới tạo PUBLISHED_VERIFIED.
Không lấy thẻ cũ cùng GTIN làm bằng chứng cho bản sửa mới. Cần đọc mới sau giao dịch;
HTTP 200, feed Signed hay ack gửi chữ ký không tự đủ.

## Hợp đồng chưa xác minh / khả năng chưa nghiệm thu

- **WB ghi: UNVERIFIED** trong đợt này. Endpoint quan sát trong client là
  POST `/content/v2/cards/update`; đọc POST `/content/v2/get/cards/list` bằng cursor
  updatedAt/nmID. Không chứng minh hợp đồng hiện hành chỉ bằng source cũ.
  `RegistrationContractPolicy` tiếp tục chặn `SYNC_WB`; không có checkbox override.
- Sandbox: đã xác minh host, nhưng `productionCatalog` hiện hữu yêu cầu phạm vi production.
  V3 không cho thao tác ghi sandbox giả danh một phiên đã được xác minh.
- CryptoPro: định dạng detached được xác minh từ hợp đồng NK; lời gọi nhà cung cấp
  và chứng thư thật trên Windows chưa nghiệm thu ở Linux. Không lưu PIN/khóa riêng.
- Tài liệu True API, CryptoPro vendor và WB hiện hành vẫn cần đọc/đối chiếu thêm.
  Client V3 Catalog dùng khóa Catalog đã ràng buộc, không tự đổi sang một route True API khác.

Các quyền seller, GS1/hạn mức, chủ thể ký và xác nhận từng thao tác vẫn là điều kiện runtime,
ngay cả với endpoint đã xác minh tài liệu. Không có bằng chứng Level 2–4 trong tác vụ này.
