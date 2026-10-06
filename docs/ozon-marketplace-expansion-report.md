# Báo cáo mở rộng WCode sang Ozon

> Ngày nghiên cứu: 2026-08-18
> Phạm vi: WCode desktop, Wildberries FBS hiện tại và Ozon Seller API
> Trạng thái: Ozon FBS Standard đã có production UI JavaFX; local/mock gate và live Java-core gate đã pass qua KIZ, ship và nhãn; Seller UI, Windows native và canary vẫn chờ xác nhận riêng

## 1. Kết luận

WCode có thể mở rộng sang Ozon, nhưng đây không phải thay đổi chỉ gồm thêm Client-Id vào form cửa hàng. Mã hiện tại gắn trực tiếp Shop, Order, Supply, sticker, KIZ và đồng bộ vào mô hình Wildberries. Để thêm Ozon an toàn cần bốn thay đổi nền tảng:

1. Thêm marketplace vào danh tính cửa hàng và bắt buộc mọi command/workflow kiểm tra đúng sàn.
2. Giữ adapter, DTO, bảng SQLite và sync state riêng cho WB và Ozon; chỉ dùng chung những mô hình thực sự trùng lặp như cửa hàng, tài nguyên in và KIZ inventory.
3. Đổi biên giới nghiệp vụ từ WB supply -> WB order sang fulfillment posting -> items; Ozon dùng posting_number dạng chuỗi và một posting có thể có nhiều sản phẩm/số lượng.
4. Xây luồng KIZ Ozon riêng. WB gắn SGTIN vào order metadata; Ozon quản lý từng exemplar và phải validate/set/check mã trước khi đóng gói và giao hàng.

MVP được đề xuất là **Ozon FBS Standard**: tạo cửa hàng, kiểm tra credential, đồng bộ catalog/posting, xem chi tiết, phân bổ KIZ, xác thực exemplar, xác nhận đóng gói, tải nhãn chính thức và in. Không gồm Ozon FBO, rFBS, FBP, tạo kho, cập nhật tồn kho/giá hoặc toàn bộ carriage/act trong đợt đầu.

JavaFX entrypoint, controller, FXML và theme nằm trực tiếp trong `src/main`. Maven là build system
duy nhất, tạo runnable JAR cùng runtime dependencies cho `jpackage`. Core Ozon, schema v2 và bằng
chứng live được giữ nguyên khi production UI quay lại JavaFX. Rollback dữ liệu dùng snapshot đã
verify; Windows 1.1.10 dùng installer identity và thư mục dữ liệu mới để không kích hoạt uninstaller
legacy vốn đặt binary chung với dữ liệu.

### 1.1 Trạng thái triển khai ngày 2026-08-18

| Phase | Trạng thái | Bằng chứng trong bản triển khai |
| --- | --- | --- |
| Compatibility và migration | Hoàn tất local gate | `Marketplace`, Shop/CRUD hai sàn, schema v2, snapshot trước migration, backfill WB, JavaFX marketplace guard, test migration giữ ID/FK/token và SQLite integrity |
| Ozon read-only | Hoàn tất code + mock/local gate | Client có redaction/rate-limit/retry, `/v1/roles`, `/v2/warehouse/list`, catalog cursor, posting v4 cursor + rolling overlap, repository transaction và dashboard JavaFX; cursor thiếu/lặp không advance sync state |
| KIZ tự động | Hoàn tất code + mock/local gate | Mapping SKU→GTIN, durable reservation, state machine, create/get→validate→set→status, read-after-timeout, restart và duplicate-click tests; validation/status chỉ pass khi đủ từng exemplar/mark và không có lỗi |
| Ship | Hoàn tất code + mock/local gate | Explicit confirmation, requirement/package guard, `/v4/posting/fbs/ship`, timeout readback và action log atomic chặn mọi lần gửi lại khi kết quả trước chưa được reconcile |
| Nhãn | Hoàn tất code + mock/local gate | Tạo job nhãn v2, chọn rõ task `big_label`, poll trạng thái v1, tải PDF chính thức chỉ từ HTTPS Ozon không kèm API key và atomic publish; kết quả create không rõ bị giữ ở `RECONCILE_REQUIRED`, không lưu URL/raw response/đường dẫn local |
| Rollout | Chưa chạy cohort | JavaFX local/mock verification hoàn tất. Chưa có internal/canary/production hoặc Windows signed-installer rehearsal cho Ozon |

Giới hạn cố ý của MVP: chỉ Ozon FBS Standard và một kiện chứa toàn bộ item/quantity hiện tại. rFBS, FBO, partial package, multibox, price/stock và carriage/act đều fail closed hoặc chưa được expose.

## 2. Phạm vi đã kiểm tra

Đã đọc các nhóm mã:

- Khởi động, SQLite và migration: Database, WbSchemaSupport, ShopCredentialSchema.
- Cửa hàng: Shop, ShopRepository, ShopWorkflow và JavaFX ShopDialogController/ShopSidebarController.
- Wildberries: WbApiClient, product/order/supply sync, repositories, sticker, token inspector và rate limiter.
- Nghiệp vụ: HomeController, SupplyLoadWorkflow, PackingWorkflow, OrderExportWorkflow, KizAttachmentCoordinator, dashboard, FBO barcode và print history.
- Znack: inventory GTIN/KIZ, purchase pipeline, mapping và introduction.
- Phát hành: Maven JavaFX verification, runnable JAR và native packaging qua `jpackage`.

Không có mã Ozon sẵn trong repo tại thời điểm nghiên cứu.

## 3. Logic nghiệp vụ hiện tại

### 3.1 Luồng chính

1. Người dùng tạo Shop(name, apiKey); token WB được lưu tại shops.api_key.
2. WbSyncWorkflow đồng bộ product cards, supplies, new orders, lịch sử order và status vào các bảng wb_*.
3. Người dùng chọn supply. WbSupplyWorkflow đọc orders, bổ sung product, ảnh, sticker và metadata KIZ.
4. PackingWorkflow quản lý order mới, supply đang chuẩn bị, thêm order vào supply và deliver supply.
5. OrderExportWorkflow đặt chỗ KIZ, tạo PDF nhãn/chi tiết và lưu print history.
6. Sau khi publish file in, KizAttachmentCoordinator gửi SGTIN vào WB order metadata trong background.
7. Znack là module độc lập theo shop: đồng bộ GTIN, mua KIZ, tải code và đưa code vào lưu thông.

### 3.2 Điểm gắn cứng với Wildberries

| Vị trí | Hiện trạng | Hệ quả khi thêm Ozon |
| --- | --- | --- |
| models/Shop.java | Chỉ có id, name, apiKey | Không phân biệt sàn; không có Client-Id |
| Database.java | shops(name, api_key) | Mọi row mặc định bị coi là WB |
| WbSchemaSupport.java | Cursor WB nằm trên shops; dữ liệu ở wb_* | Không được tái sử dụng cursor cho Ozon |
| Credential mirror | Một secret/token cho mỗi shop | Cần namespace theo marketplace |
| Form shop JavaFX/React | Chỉ nhập token WB | Cần chọn sàn và field theo sàn |
| HomeController/commands | Sync/token state mặc định là WB | Cần dispatch theo Marketplace |
| WbSupplySummary | Supply là aggregate trung tâm | Ozon dùng posting/carriage |
| Order.id | Long | posting_number là chuỗi |
| Order | Gần với một sản phẩm WB | Ozon posting có nhiều item/quantity |
| Print history | order_id INTEGER NOT NULL | Cần external ID dạng TEXT |
| KizAttachmentCoordinator | Gửi KIZ sau khi in | Ozon cần exemplar trước ship |
| Dashboard/FBO catalog | Query trực tiếp wb_* | Cần repository theo marketplace |

Không nên biến các lớp Wb* thành lớp tổng quát chứa nhiều nhánh if (marketplace). Giữ implementation WB hiện tại, thêm adapter Ozon và lớp dispatch mỏng ở biên command/workflow.

## 4. Khác biệt WB và Ozon

| Năng lực | Wildberries | Ozon |
| --- | --- | --- |
| Xác thực | Authorization: Bearer token | Client-Id + Api-Key |
| Fulfillment ID | Numeric order ID, gom vào supply | posting_number dạng chuỗi, chứa items |
| Catalog | nmID/chrtID/SKU | product_id/offer_id/sku |
| Việc cần xử lý | New orders + supply orders | Postings theo time/status/cutoff |
| Gom lô | Create/add/deliver supply | Ship posting; carriage/act là luồng riêng |
| Nhãn kiện | WB PNG 58x40 | PDF/label chính thức Ozon |
| KIZ | SGTIN metadata trên order | Exemplar ID, validate/set/status |
| Thời điểm KIZ | Hiện gửi sau publish PDF | Phải hoàn tất trước ship |
| Status | supplierStatus, wbStatus, done | status, substatus, available_actions, requirements |
| Đồng bộ | Cursor WB và order window | Product last_id; posting overlap window |

Không tạo “Ozon supply” giả để vừa UI WB. Màn hình Ozon nên hiện postings theo status/cutoff/kho và chỉ gom carriage/act khi triển khai đúng nghiệp vụ của Ozon.

## 5. Ozon Seller API cần dùng

Base URL: https://api-seller.ozon.ru. Request dùng Content-Type: application/json, Client-Id và Api-Key.

### 5.1 Credential và quyền

| Mục đích | Endpoint | Ghi chú |
| --- | --- | --- |
| Quyền API key | POST /v1/roles | Trả roles, methods và expires_at |
| Thông tin seller | POST /v1/seller/info | Read-only; không đưa payload raw qua UI/log |
| Danh sách kho | POST /v2/warehouse/list | Pagination limit/cursor; v1 đã bị tắt |

### 5.2 Catalog

| Mục đích | Endpoint | Cách dùng |
| --- | --- | --- |
| Liệt kê product | POST /v3/product/list | Cursor last_id, limit bounded, upsert product_id |
| Chi tiết hàng loạt | POST /v3/product/info/list | Tên, ảnh, barcode, SKU, stock/status |
| Thuộc tính | POST /v4/product/info/attributes | Dimensions, attributes, images, barcodes |

### 5.3 FBS posting

| Mục đích | Endpoint | Cách dùng |
| --- | --- | --- |
| Danh sách posting | POST /v4/posting/fbs/list | Cửa sổ since/to, cursor và limit tối đa 100; v3 sẽ bị tắt 31-08-2026 |
| Posting cần xử lý | POST /v4/posting/fbs/unfulfilled/list | Cursor; filter cutoff/delivery phải đúng cặp |
| Chi tiết posting | POST /v3/posting/fbs/get | Bật with.product_exemplars khi cần KIZ |
| Xác nhận đóng gói | POST /v4/posting/fbs/ship | Mutation sau preflight và confirmation |
| Đóng gói một phần | POST /v4/posting/fbs/ship/package | Ngoài MVP ban đầu |

### 5.4 Nhãn và chứng từ

| Mục đích | Endpoint | Cách dùng |
| --- | --- | --- |
| Tạo job nhãn | POST /v2/posting/fbs/package-label/create | Lưu task ID loại `big_label`; v1 create đã deprecated |
| Poll job nhãn | POST /v1/posting/fbs/package-label/get | Đọc status/file_url, không lưu raw response |
| Lấy file PDF | HTTPS URL do Ozon trả | Chỉ host Ozon, không gửi Client-Id/Api-Key, kiểm tra PDF và atomic publish |
| Tải trực tiếp | POST /v2/posting/fbs/package-label | Chỉ cho batch nhỏ; async là mặc định |
| Act | /v2/posting/fbs/act/* | Để phase carriage/acceptance sau |

Nhãn Ozon là tài liệu vận chuyển chính thức. Quy định packaging của Ozon nêu kích thước nhãn barcode 12 x 7,5 cm. Không resize nhãn Ozon thành template WB 58x40. WCode có thể tạo trang thông tin/KIZ riêng, nhưng phải giữ nguyên PDF/tỷ lệ barcode vận chuyển do Ozon cấp.

### 5.5 KIZ/exemplar

| Bước | Endpoint | Quy tắc |
| --- | --- | --- |
| Đọc requirement | /v4/posting/fbs/list hoặc /v3/posting/fbs/get | Dùng requirements.products_requiring_mandatory_mark |
| Tạo/lấy exemplar | POST /v6/fbs/posting/product/exemplar/create-or-get | Persist exemplar ID trước mutation sau |
| Validate | POST /v5/fbs/posting/product/exemplar/validate | Validation error không retry mù |
| Lưu exemplar | POST /v6/fbs/posting/product/exemplar/set | Persisted idempotency và safe audit |
| Kiểm tra | POST /v5/fbs/posting/product/exemplar/status | Poll bounded đến terminal state |
| Sửa exemplar | POST /v1/fbs/posting/product/exemplar/update | Chỉ cho correction flow |

Ozon còn có IMEI, JW UIN, RNPT, GTD, country và weight requirements. MVP phải fail closed nếu posting có requirement WCode chưa hỗ trợ; không cho ship chỉ vì KIZ đã đủ.

## 6. Smoke test API thật trong giai đoạn nghiên cứu

Các kết quả dưới đây là bằng chứng nghiên cứu có trước bản triển khai hiện tại. Chúng không thay thế live acceptance gate theo fixture chỉ định cho binary/schema v2.

Credential được đọc từ .env chỉ trong process test. Không in Client-Id, API key, tên kho, product ID, posting number, tên sản phẩm hay dữ liệu người mua.

| API | Kết quả 2026-08-18 | Bằng chứng |
| --- | --- | --- |
| /v1/warehouse/list | HTTP 400 | obsolete method cannot be used |
| /v2/warehouse/list | HTTP 200 | Có warehouses, cursor, has_next |
| /v3/product/list | HTTP 200 | Có items, last_id, total |
| /v3/product/info/list | HTTP 200 | Có detail, images, barcodes, SKU, status |
| /v4/product/info/attributes | HTTP 200 | Có attributes, dimensions, images |
| /v3/posting/fbs/list | HTTP 200 | Có items, actions, requirements, barcode |
| /v3/posting/fbs/get | HTTP 200 | product_exemplars và requirements hợp lệ |
| /v1/seller/info | HTTP 200 | Credential thuộc seller hợp lệ |
| /v1/roles | HTTP 200 | 41 roles, có expires_at và đủ methods dự kiến |

Request unfulfilled/list với filter rỗng trả HTTP 400, thông báo the mismatch between cutoff & delivery date. Cần DTO/validator riêng theo endpoint, không dùng một filter tổng quát.

Trong đợt smoke ban đầu không gọi endpoint mutation: exemplar set/update, ship, label job, act, carriage, cancel, stock hoặc price. Xem mục 16 cho live test exemplar được chủ cửa hàng yêu cầu riêng sau đó.

## 7. Thiết kế đề xuất

### 7.1 Domain cửa hàng

~~~java
public enum Marketplace {
    WILDBERRIES,
    OZON
}
~~~

Mở rộng Shop:

~~~text
id
name
marketplace
clientId       # null cho WB, bắt buộc cho Ozon
apiKey         # write-only qua UI/bridge
~~~

marketplace không cho sửa sau khi tạo. Đổi sàn trên cùng shop_id làm mất ý nghĩa FK, sync state, print history, KIZ ownership và audit. Nếu chọn sai, người dùng tạo shop mới; chỉ xóa shop cũ khi không có job chạy.

### 7.2 Migration SQLite

~~~sql
ALTER TABLE shops ADD COLUMN marketplace TEXT NOT NULL DEFAULT 'WILDBERRIES';
ALTER TABLE shops ADD COLUMN client_id TEXT;
CREATE INDEX IF NOT EXISTS idx_shops_marketplace ON shops(marketplace, id);
~~~

Yêu cầu:

- Tăng Database.CURRENT_SCHEMA_VERSION; không chỉ thêm cột rồi giữ version 1.
- Backfill mọi row cũ thành WILDBERRIES, giữ nguyên ID/FK/token.
- Java validation bắt buộc marketplace thuộc enum; client_id chỉ bắt buộc cho Ozon.
- Gọi OzonSchemaSupport.initialize(conn) từ Database.initDatabase().
- Chạy migration trong snapshot/recovery gate và test từ database version cũ.
- Không đổi tên/xóa bảng wb_* trong đợt Ozon.

Không đặt cursor Ozon trên shops. Tạo bảng riêng:

~~~text
ozon_sync_state(
  shop_id PK/FK,
  products_last_id,
  products_last_synced_at,
  postings_changed_since,
  postings_last_synced_at,
  last_error
)
~~~

Các bảng tối thiểu:

~~~text
ozon_products(shop_id, product_id, offer_id, sku, name, primary_image_url,
              archived, updated_at, synced_at)
ozon_product_barcodes(shop_id, product_id, barcode)
ozon_postings(shop_id, posting_number, order_id, order_number, status, substatus,
              warehouse_id, shipment_at, in_process_at, lower_barcode, upper_barcode,
              requirements_json, optional_json, synced_at)
ozon_posting_items(shop_id, posting_number, item_index, product_id, sku, offer_id,
                   name, quantity, currency_code, price)
ozon_exemplars(shop_id, posting_number, product_id, exemplar_id, exemplar_index,
               kiz_id, check_status, weight, updated_at)
ozon_action_log(id, shop_id, action_type, posting_number, status,
                safe_error_code, created_at)
~~~

Nguyên tắc lưu trữ:

- Dùng TEXT cho posting_number, offer_id, SKU/barcode bên ngoài; không ép sang Long.
- Không lưu customer, addressee, phone, PIN, legal info hoặc raw response nếu UI không cần.
- requirements_json chỉ lưu product ID/flag cần thiết.
- KIZ code có một source-of-truth trong Znack inventory; ozon_exemplars tham chiếu kiz_id, không nhân bản raw code.
- Mỗi bảng có FK cascade theo shop_id và index cho status/cutoff/posting.

Print history cần migration additive:

~~~text
print_jobs.marketplace TEXT DEFAULT 'WILDBERRIES'
print_job_items.external_order_id TEXT
print_job_items.external_item_id TEXT
~~~

Giữ order_id INTEGER trong cửa sổ rollback, nhưng code mới không dùng nó làm danh tính duy nhất cho Ozon.

### 7.3 Credential

- Client-Id là account identifier, có thể lưu trong SQLite.
- Ozon Api-Key là secret: không log, không trả qua bridge, không prefill khi edit.
- Tổng quát tokenConfigured thành credentialConfigured ở domain/command; UI hiện nhãn theo sàn.
- Namespace credential mirror theo marketplace.
- Trong rollback window, shops.api_key vẫn là source-of-truth plaintext. Chưa được tuyên bố secret-at-rest hoàn tất trước khi Windows Credential Manager, macOS Keychain, read-back và rollback rehearsal đều pass.
- .env chỉ dùng cho research/dev smoke, không phải config production. Repo đã được thêm rule ignore .env và .env.*.

### 7.4 Package và adapter

~~~text
integration/marketplace/Marketplace.java
integration/marketplace/MarketplaceGateway.java
integration/marketplace/MarketplaceGatewayRegistry.java
integration/ozon/OzonApiClient.java
integration/ozon/OzonApiException.java
integration/ozon/OzonCredentials.java
integration/ozon/OzonSchemaSupport.java
integration/ozon/Ozon*Dto.java
integration/ozon/Ozon*Repository.java
integration/ozon/OzonCatalogSyncService.java
integration/ozon/OzonPostingSyncService.java
integration/ozon/OzonExemplarService.java
integration/ozon/OzonLabelService.java
integration/ozon/OzonFbsWorkflow.java
~~~

MarketplaceGateway chỉ chứa use case giao nhau ở UI: credential check, overview sync, fulfillment list/detail và print preparation. Mutation khác nhau như WB deliverSupply và Ozon shipPosting ở workflow riêng, không ép vào một method tổng quát mơ hồ.

Registry phải fail closed nếu marketplace không có adapter. Mỗi command nhận shopId phải load shop và kiểm tra marketplace trước khi truy cập repository/API.

### 7.5 HTTP client Ozon

- OkHttp/Gson riêng, timeout bounded như WB.
- Interceptor thêm Client-Id, Api-Key, Content-Type; toString() của credential phải redact.
- Phân loại lỗi theo HTTP và code/message/details; chỉ đưa safe code/thông điệp đã lọc lên UI.
- Retry có jitter cho 429, 502, 503, 504 và network timeout; tôn trọng Retry-After.
- Không retry mutation khi kết quả không rõ. Sau timeout exemplar/ship, đọc posting/exemplar status trước khi gửi lại.
- Rate limiter tách theo shopId + endpoint family; không dùng chung với WB.
- Không log request/response body, posting/customer identifier hoặc secret.

## 8. Luồng Ozon MVP

### 8.1 Tạo cửa hàng

1. Chọn Wildberries hoặc Ozon bằng segmented control.
2. Nhập tên cửa hàng.
3. WB: nhập API token.
4. Ozon: nhập Client ID và API key.
5. Nút Kiểm tra kết nối gọi /v1/roles rồi /v2/warehouse/list với limit nhỏ.
6. Backend kiểm tra method tối thiểu cho read/ship/label/exemplar và đọc expires_at.
7. Chỉ save khi hợp lệ; response chỉ trả credentialConfigured, marketplace và metadata an toàn.

Khi edit, marketplace disabled; API key để trống nghĩa là giữ key cũ. Shop picker hiện badge WB/Ozon và không hiện bất kỳ phần nào của credential.

### 8.2 Đồng bộ

1. Đồng bộ /v3/product/list bằng last_id; batch detail qua /v3/product/info/list.
2. Chỉ gọi attributes cho product mới, thay đổi hoặc thiếu thông tin in.
3. Đồng bộ posting theo rolling overlap window vì status bản ghi cũ có thể thay đổi.
4. Upsert posting/items trong một transaction; chỉ advance sync state sau commit.
5. Khi mở detail, refresh /v3/posting/fbs/get và product_exemplars nếu có mark/weight/IMEI requirement.
6. Hiện tab Ozon theo status/cutoff thay cho supply WB.

### 8.3 KIZ và ship

1. Preflight đọc requirements và available_actions mới nhất.
2. Nếu có requirement chưa hỗ trợ, chặn mutation và hiện lý do.
3. Gọi create-or-get và persist exemplar ID.
4. Reserve KIZ theo GTIN/shop; transaction local ghi posting/product/exemplar.
5. Validate marks. Validation failure xác định thì release reservation; timeout giữ reservation để reconciliation.
6. Persist request fingerprint và stage trước khi set exemplars.
7. Poll status bounded; chỉ terminal accepted mới cho ship.
8. Gọi /v4/posting/fbs/ship sau explicit confirmation, với item/quantity mới nhất.
9. Refresh đến awaiting_deliver hoặc status cho phép label.
10. Tạo/poll label job, publish PDF atomically, rồi tạo trang WCode product/KIZ riêng nếu được chọn.

Khác WB, WCode phải tạo/lấy và validate exemplar trước khi in. Theo yêu cầu UX cập nhật, file có
thể được publish/mở khi durable job ở `VALIDATED`, sau đó `set` và poll `status` chạy nền. Lệnh ship
vẫn bắt buộc phụ thuộc persisted exemplar state `ACCEPTED`; job nền không được cấp thêm KIZ hoặc
retry mutation mù.

### 8.4 In ấn

- Dùng nguyên PDF Ozon; không tái tạo barcode nếu đã có file chính thức.
- Tách logic sinh nhãn: trang shipping label của Ozon được copy nguyên MediaBox/content stream, còn trang WCode KIZ là 58x40. Khi người dùng in, hai loại trang được ghép vào cùng một bundle PDF hỗ trợ mixed page size; PDF nhặt hàng A4 vẫn là file riêng.
- Một posting nhiều item vẫn chỉ có shipping label theo posting/package; trang product/KIZ theo exemplar/item.
- Print history lưu marketplace, posting number, item/exemplar linkage và template snapshot; không lưu PII.

## 9. Thay đổi UI

### JavaFX production

- Shop dialog/FXML thêm marketplace choice và conditional Client ID.
- HomeController không chạy WbTokenInspector/WbSyncWorkflow cho Ozon.
- Shop picker/sidebar có badge sàn; marketplace bất biến khi edit và secret là write-only.
- Màn hình chỉ hỗ trợ WB ẩn/disable rõ ràng cho shop Ozon; Ozon có posting/detail/mapping riêng.
- Copy và validation thuộc catalog RU/EN/VI/ZH.

**Rủi ro rollback bắt buộc:** binary WCode cũ đọc mọi row từ shops và coi là WB. Cần phát hành một compatibility release biết cột marketplace và ẩn shop không hỗ trợ trước khi cho phép tạo row Ozon. Sau khi N-1 an toàn nằm trong cửa sổ rollback mới bật feature flag tạo Ozon. Nếu bỏ qua, rollback có thể gửi Ozon API key đến endpoint Wildberries.

## 10. Test plan

### Unit

- Marketplace validation, credential redaction và command authorization.
- Request DTO cho posting window, product cursor, label và exemplar.
- Parsing unknown/null fields và 64-bit/string identifiers.
- Error mapping, retry/jitter/rate limiting và timeout reconciliation.
- Multi-item, quantity > 1 và multibox guards.
- KIZ state machine, duplicate click, crash/restart và ambiguous mutation response.

### Database/integration

- Migrate DB v1 có WB data, giữ count/ID/FK và integrity_check=ok.
- Ozon cascade không chạm wb_*/Znack/print history của shop khác.
- Pagination restart idempotent; lỗi giữa batch không advance cursor.
- Credential mirror fault injection.
- Snapshot/recovery và rollback với compatibility release.

### UI/command

- WB chỉ cần token; Ozon cần Client ID + API key.
- Đổi marketplace reset field không liên quan; secret bị xóa khi close/error.
- Edit để trống key giữ credential cũ; response/DOM/console không có secret.
- Navigation không hiện action sai sàn.
- Posting list/detail có loading, empty, error, pagination và cancellation.
- JavaFX controller/FXML smoke và navigation theo marketplace.

### Live gates

Read-only tự động trên shop test:

~~~text
/v1/roles
/v2/warehouse/list
/v3/product/list
/v3/product/info/list
/v4/product/info/attributes
/v4/posting/fbs/list
/v3/posting/fbs/get
~~~

Mutation phải chạy thủ công trên fixture được phép:

~~~text
create/get -> validate -> set -> status exemplar
ship posting
create/get label
act/carriage khi vào phase tương ứng
~~~

Không dùng production order ngẫu nhiên. Fixture phải có posting/GTIN/KIZ chuẩn bị trước, rollback nghiệp vụ và người chịu trách nhiệm.

## 11. Thứ tự triển khai

### Phase 0 - Credential và compatibility

- Ignore .env; thêm secret scanning CI.
- Marketplace, schema version mới, migration tests.
- Cập nhật JavaFX để non-WB row không đi vào WB workflow.
- Phát hành compatibility release; giữ feature flag ozon.shop.create=false.

### Phase 1 - Ozon read-only

- Client, credential check, roles/expiry warning.
- Form create/edit/picker theo marketplace.
- Ozon schema, catalog và posting sync/detail.
- Dashboard/list read-only; chưa ship/KIZ mutation.

### Phase 2 - Print label

- Label service bất đồng bộ, atomic publish, external IDs trong print history.
- Giữ nguyên khổ PDF do Ozon phát hành cho warehouse (120x75 hoặc compact 58x40), tách khỏi custom label sản phẩm/KIZ của WCode.
- Native print rehearsal Windows/macOS.

### Phase 3 - KIZ exemplar

- Persisted state machine, Znack inventory linkage, validate/set/status.
- Crash recovery và ambiguous response reconciliation.
- Live fixture được phép cho mandatory marking.

### Phase 4 - Ship

- Preflight requirement coverage, package builder, explicit confirmation.
- /v4/posting/fbs/ship, refresh status và label readiness.
- Chỉ thêm partial package, carriage và act sau khi luồng chính ổn định.

### Phase 5 - Rollout

- Internal -> canary -> mở rộng theo release gate hiện có.
- Theo dõi 401/403, 429, 5xx, sync lag, duplicate attempt, KIZ mismatch và label failure theo marketplace.
- Không xóa cột/token legacy hoặc bảng WB trong ít nhất một rollback window đã rehearsal.

## 12. Điều kiện chấp nhận MVP

- CRUD/chọn được shop WB và Ozon; marketplace bất biến; secret không quay lại UI/log.
- Shop WB cũ migrate tự động, hành vi không đổi.
- Ozon catalog/posting sync idempotent, restart được và không lưu PII thừa.
- ID chuỗi/64-bit không bị truncation.
- Requirement chưa hỗ trợ chặn ship.
- KIZ liên kết đúng exemplar, không cấp trùng và khôi phục sau crash.
- Ship chỉ sau confirmation và exemplar terminal-success.
- Label Ozon giữ đúng kích thước/tỷ lệ; custom label không thay shipping label.
- Maven verify, JavaFX FXML smoke, packaging contract và migration tests pass.
- Live read-only smoke pass; mutation smoke có fixture được phép.
- Rollback không bao giờ coi Ozon shop là WB shop.

## 13. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
| --- | --- | --- |
| Binary cũ coi Ozon shop là WB | Critical | Compatibility release trước feature flag |
| Gửi KIZ/ship lặp sau timeout | Critical | Persisted stage + fingerprint + read-after-uncertain |
| In sai kích thước nhãn | High | Giữ nguyên PDF chính thức và verify MediaBox theo format warehouse; không ép mọi shop về 120x75 |
| Flatten multi-item posting | High | Posting/item/exemplar hierarchy, string ID |
| Secret rò qua bridge/log | High | Write-only DTO, redaction tests, không raw body |
| API Ozon deprecate nhanh | High | Version inventory, roles check, review official news |
| API key hết hạn | Medium/High | Đọc expires_at, cảnh báo sớm |
| Ép Ozon vào WbSupplySummary | High | Ozon workflow/repository/view riêng |
| Requirement ngoài KIZ | High | Generic requirement gate, fail closed |

Quyết định: **không refactor toàn bộ WB trước**. Thêm marketplace boundary, giữ WB code hiện tại, xây Ozon vertical slice riêng; chỉ trích xuất abstraction khi hai implementation chứng minh cùng semantics.

## 14. Tài liệu tham chiếu

- Ozon Seller API: <https://docs.ozon.ru/api/seller/>
- Kênh thay đổi Seller API chính thức: <https://t.me/s/OzonEnSellerAPI>
- Thông báo chính thức về việc `/v3/posting/fbs/list` bị tắt ngày 31-08-2026 và chuyển sang v4: <https://t.me/s/OzonSellerAPI?before=673>
- Thông báo chính thức chuyển label create từ v1 sang v2: <https://t.me/s/OzonEnSellerAPI/157>
- Cập nhật API key/expires_at tháng 2-2026: <https://t.me/s/OzonSellerAPI/619>
- Ozon for dev về tránh bị chặn API: <https://dev.ozon.ru/start/298-Seller-API-kak-izbezhat-blokirovok/>
- Quy định packaging/label: <https://docs.ozon.ru/legal/en/partners/logistics/contract/>
- Nội bộ: `docs/javafx-release-runbook.md`, `docs/ozon-print-bundle-spec.md`, `docs/znack-standalone-module.md`.

## 15. Backlog file-level khởi đầu

1. Database, Shop, ShopRepository, ShopCredentialSchema, JavaFX shop workflow/controller và tests.
2. JavaFX shop forms, FXML, i18n và UI tests.
3. integration/ozon client/DTO/error/credential tests, chỉ read-only.
4. OzonSchemaSupport, repositories và migration compatibility tests.
5. Catalog/posting sync services + JavaFX Ozon controller/view.
6. Print model external IDs + Ozon official label service.
7. Exemplar/KIZ persisted workflow.
8. Ship preflight/mutation, live fixture, native print và rollout evidence.

Mỗi pull request nên là một vertical slice có migration/test đầy đủ; không gom schema, shop UI, toàn bộ sync, KIZ và ship vào một thay đổi lớn.

## 16. Bằng chứng live KIZ lịch sử ngày 2026-08-18

Đây là lần kiểm chứng thủ công trong giai đoạn nghiên cứu, không phải lần chạy nghiệm thu của bản triển khai hiện tại. KIZ ở dòng 1 đã được dùng và được xem là **retired**; mọi live gate tiếp theo tuyệt đối không được dùng lại mã này.

Phạm vi được chủ cửa hàng yêu cầu: gắn một KIZ thật vào một đơn Ozon phù hợp để kiểm chứng trường `Дополнительная информация`; không ship, cancel, đổi tồn kho, giá hoặc tạo nhãn.

### Đối tượng thử nghiệm

- Catalog không có đúng chuỗi `BOdai/176`; Ozon đang hiển thị chuỗi ghép từ article/biến thể. Posting được chọn có `offer_id=bodai-den-176`, tên `Спортивный костюм мужской худи с принтом и джоггеры`, SKU `5340693583`, số lượng 1.
- Posting là ứng viên duy nhất của biến thể trên ở trạng thái `awaiting_packaging`, substatus `posting_created`.
- `requirements.products_requiring_mandatory_mark` rỗng, nhưng SKU nằm trong `optional.products_with_possible_mandatory_mark`. `create-or-get` xác nhận `is_mandatory_mark_needed=false`, `is_mandatory_mark_possible=true` và có đúng một exemplar trống.
- `kiz.txt` có 255 dòng, cùng GTIN `04645588781154`. Live test dùng **dòng 1**, fingerprint SHA-256 rút gọn `acab07e4d777`.

### Kết quả API

| Bước | Endpoint | Kết quả đã làm sạch |
| --- | --- | --- |
| Đọc posting | `/v3/posting/fbs/get` | HTTP 200; đúng SKU, quantity 1, exemplar trống |
| Lấy exemplar | `/v6/fbs/posting/product/exemplar/create-or-get` | HTTP 200; một exemplar, cho phép mandatory mark |
| Kiểm tra KIZ | `/v5/fbs/posting/product/exemplar/validate` | HTTP 200; product, exemplar và mark đều `valid=true`, không lỗi |
| Lưu KIZ | `/v6/fbs/posting/product/exemplar/set` | HTTP 200; task được chấp nhận |
| Poll kết quả | `/v5/fbs/posting/product/exemplar/status` | `ship_available`; mark `check_status=passed`, không có error code |
| Đọc lại | `/v6/fbs/posting/product/exemplar/create-or-get` | Có đúng một `mandatory_mark`; hash khớp chính xác KIZ đã gửi |

Posting vẫn ở `awaiting_packaging`; live test không gọi `/v4/posting/fbs/ship`. Sau khi refresh Seller UI, exemplar tương ứng phải hiện đã có mã маркировки.

**Kiểm soát sử dụng lại:** dòng 1 của `kiz.txt` đã được dùng trên Ozon và không được cấp cho đơn khác. File nguồn chưa bị sửa tự động; implementation production phải reserve/consume KIZ bằng transaction trong inventory thay vì đọc trực tiếp file.

### Vướng mắc được xác nhận qua live test

1. Catalog của SKU chỉ trả barcode nội bộ `OZN...`, không trả GTIN `04645588781154`. Ozon vẫn validate KIZ thành công, nhưng WCode production cần mapping SKU/offer -> GTIN do người dùng quản lý hoặc lấy từ nguồn Znack; không thể suy ra GTIN từ barcode Ozon.
2. Chuỗi article trên UI có thể khác `offer_id` API. Không tìm posting bằng exact text `BOdai/176`; phải dùng SKU/offer mapping đã đồng bộ.
3. HTTP 200 từ `set` chỉ tạo tác vụ. Workflow bắt buộc poll `status` đến `ship_available` và kiểm tra lỗi từng mark trước khi cho ship.
4. Sản phẩm hiện chỉ “có thể cần” маркировка, không bắt buộc. UI WCode phải cho phép thêm KIZ tự nguyện nhưng không được diễn giải cờ optional thành mandatory.

## 17. Migration evidence của bản triển khai

Migration gate hiện tạo snapshot SQLite đã verify trước khi `Database.initDatabase()` nâng `PRAGMA user_version` từ 1 lên 2. Test fixture v1 xác nhận:

- shop WB giữ nguyên `id=41`, tên và token;
- FK từ dữ liệu legacy vẫn trỏ đúng shop;
- external ID lớn vẫn là `TEXT`, không bị ép/truncate;
- toàn bộ shop cũ được backfill `WILDBERRIES`;
- `PRAGMA foreign_key_check` không có dòng lỗi và `PRAGMA integrity_check` trả `ok`;
- snapshot có checksum và bản database trong thư mục rollback;
- binary gặp schema lớn hơn version nó hỗ trợ sẽ dừng trước khi ghi.

Các bảng Ozon được tạo additive, có FK cascade theo shop và không đổi/xóa bảng `wb_*`. Print history đã có `marketplace`, `external_order_id` và `external_item_id` mà vẫn giữ constructor/đường đọc WB cũ.

## 18. Verification và live acceptance gate

Local verification của bản triển khai:

- `./mvnw -B verify`: pass 260/260 test sau live fix, gồm migration, JavaFX FXML, parser/guard, state machine, safe upstream error telemetry và MockWebServer Ozon;
- JavaFX controllers/FXML được load trong test toolkit với app-data cô lập;
- `git diff --check`: pass;
- credential-pattern scan giới hạn ở source/UI/docs và loại trừ `.env`, `kiz.txt`, database/build artifacts: không phát hiện credential literal.

Live acceptance gate của bản triển khai này: **đã chạy hết prepare/KIZ, ship và tải nhãn chính thức** trong app-data cô lập `/Users/rupphi/WCode-live-acceptance`. Posting đang ở `awaiting_deliver`; PDF được publish atomically và render kiểm tra ở mục 19. Chi tiết đã làm sạch nằm trong thư mục `evidence` của app-data live.

Checklist cho lần live gate được ủy quyền:

- [x] API `/v1/roles` và warehouse xác nhận credential thuộc account Client ID có suffix `3361`; Seller UI còn chờ kết nối browser để đối chiếu trực quan;
- [x] chọn posting FBS Standard alias `ba56657eeb22`, SKU suffix `3583`, mapping SKU→GTIN đã live-verify và một KIZ `AVAILABLE` trong kho Znack cô lập của đúng shop;
- [x] dùng dòng 2, fingerprint rút gọn `c5c71ccd10bf`; runner chặn cứng dòng 1/fingerprint retired;
- [x] chạy prepare và duplicate-call; readback khớp fingerprint, mark `passed` và `ship_available`;
- [ ] xác minh Seller cabinet hiện dấu tích marking đúng account;
- [x] local KIZ chuyển `CONSUMED`, một liên kết duy nhất, không có duplicate; SQLite integrity/FK pass;
- [x] ship chỉ sau confirmation alias riêng; readback hậu-ship là `awaiting_deliver`, sau đó tạo/poll và kiểm tra PDF nhãn chính thức;
- [x] live run không có 401/403, 429 hoặc 5xx; mismatch cuối = 0, duplicate-call không reserve thêm; một lỗi parser label local đã được sửa và job cuối `READY`.

Rollout chỉ được chuyển internal → canary → production sau khi checklist trên pass. Carriage/act, partial/multibox và mọi workflow ngoài scope vẫn giữ trạng thái bị chặn.

## 19. Live acceptance của implementation ngày 2026-08-18

### Phạm vi và cô lập dữ liệu

- Chủ shop đã yêu cầu live test flow từ đơn mới đến tick KIZ. Test dùng Seller API thật nhưng tạo app-data riêng tại `/Users/rupphi/WCode-live-acceptance`; database production `~/WCode/database.db` không bị migrate hoặc ghi.
- Credential được parser allowlist đọc từ `.env`, không `source` shell, không in hoặc ghi vào evidence. App-data live có quyền owner-only; report chỉ dùng Client ID đã mask.
- Read-only discovery đồng bộ 102 catalog product và 351 posting trong cửa sổ ban đầu; account có 41 role, một warehouse và quyền exemplar/ship/label. Có bốn posting optional-mark quantity 1; chỉ alias `ba56657eeb22`, SKU suffix `3583` khớp mapping đã có bằng chứng với GTIN của file KIZ.
- Runner đọc dòng 2, không sửa `kiz.txt`, không đưa raw KIZ vào log/evidence. Fingerprint rút gọn là `c5c71ccd10bf`; dòng 1 và fingerprint retired `acab07e4d777` bị chặn cứng.

### Sự cố live và thay đổi contract

Lần `/set` đầu tiên trả HTTP 400. Durable job chuyển `RECONCILE_REQUIRED`; KIZ vẫn `RESERVED` với `reservation_recoverable=0`, lần gọi prepare thứ hai không tạo job hoặc cấp KIZ mới. Hai readback (`status` và `create-or-get`) cùng xác nhận remote còn exemplar trống, nên không release hoặc gửi lại mù.

Client được bổ sung telemetry chỉ giữ `code/error_code` hoặc diagnostic tag từ vocabulary allowlist, tuyệt đối không giữ response message/body. Retry reconcile sau readback phân loại lỗi thành `ERR_MULTIBOX_EXEMPLAR_INVALID`. Root cause là payload Standard gửi `multi_box_qty=0` dù đây không phải multibox; trường này không nằm trong nhóm bắt buộc hiện tại. Payload đã được sửa để không gửi `multi_box_qty`, `is_gtd_absent` hoặc `is_rnpt_absent` cho MVP Standard đã fail-closed các requirement đó. Regression tests xác nhận ba field vắng mặt.

### Kết quả cuối đã làm sạch

| Gate | Kết quả |
| --- | --- |
| Posting | `awaiting_deliver / posting_transferring_to_delivery`, alias `ba56657eeb22` |
| Exemplar job | `ACCEPTED` |
| Ozon status | `ship_available=true`, mọi mark `passed` |
| Readback | đúng một mark, fingerprint khớp KIZ đã reserve |
| Local KIZ | `CONSUMED`, đúng một linked ID, không duplicate |
| Double-click | cùng job/cùng KIZ, không có lần reserve hoặc `/set` thứ hai từ duplicate-call |
| SQLite | `integrity_check=ok`, `foreign_key_check=0` |
| Ship/action | Ozon trả HTTP 200; action cuối `success`, readback xác nhận không còn ship-available |
| Label | `READY`; PDF Ozon 73.422 byte, hai trang compact khoảng 58x40 mm, render không cắt/mờ |
| Seller UI | chưa xác minh vì Chrome extension chưa bắt tay được dù browser/extension/native host đều hiện diện |

Evidence JSON đã redacted được ghi atomically theo phase tại `/Users/rupphi/WCode-live-acceptance/evidence`; bản mới nhất là `ozon-live-latest.json` với phase `label`. PDF chính thức nằm tại `output/pdf/OZON-ba56657eeb22.pdf`; bản copy có cùng SHA-256 với file atomic export của live app-data.

### Ship và label sau confirmation ngày 2026-08-19

Lần ship đầu nhận HTTP 4xx xác định và readback chứng minh posting vẫn `awaiting_packaging`. Root cause là request `/v4/posting/fbs/ship` thiếu `packages[].products[]`; implementation trước đó chỉ gửi `posting_number` và `with`. Package builder đã được sửa để gom toàn bộ item/quantity hiện tại vào đúng một package, serialize `product_id` numeric tại API edge nhưng vẫn lưu external ID dạng `TEXT`, và fail closed với product ID không hợp lệ. Action log nay ghi `rejected` cho lỗi mutation xác định để không để lại `pending` giả; timeout/5xx mơ hồ vẫn bị chặn retry như cũ. Regression test bao phủ deterministic rejection -> corrected retry, duplicate product row aggregation và success-response/readback-failure.

Sau retry theo cùng confirmation token, Ozon trả HTTP 200. Readback tức thời còn `awaiting_packaging` do độ trễ chuyển trạng thái; không gửi lại mutation. Lần poll read-only kế tiếp xác nhận `awaiting_deliver / posting_transferring_to_delivery`, `ship_available=false` và action cuối `success`.

Lần tạo label đầu trả HTTP 200 nhưng parser chỉ hiểu schema cũ `result.tasks[].task_type=big_label`, trong khi live v2 trả `result.task_id` trực tiếp. Parser đã hỗ trợ cả hai schema và test regression pass. Job tài liệu đầu không có task ID local được đánh dấu `FAILED/unparsed_task_id`; tạo lại một job tài liệu không thay đổi posting/KIZ, rồi poll đúng task đến `READY` và tải PDF chính thức.

PDF cuối có SHA-256 `c77b9187b32619cbf41a26e12e2575edd48e0119e549e89cdc4918b3badd7777`, PDF 1.7, không mã hóa, không form/JavaScript, hai trang có MediaBox xấp xỉ `164.25 x 113.25 pt` và `164.40 x 113.38 pt` (compact 58x40 mm). Poppler render 300 DPI cho thấy trang QR/mã tuyến và trang barcode OZN/mô tả đều sắc nét, nằm trong biên, không overlap hoặc clipping. Đây là định dạng compact chính thức Ozon trả theo cấu hình warehouse; WCode giữ nguyên byte và không tái tạo barcode vận chuyển.

### Bundle in và phiếu nhặt hàng ngày 2026-08-19

Sau khi xác nhận yêu cầu vận hành giống WB, JavaFX Ozon có một action in bundle. Action kiểm tra durable exemplar job trước: job `ACCEPTED` được tái sử dụng và không gọi prepare/set lần nữa; job cần marking nhưng chưa accepted sẽ chạy prepare idempotent và fail closed nếu chưa đạt `ACCEPTED`. Action in không tự ship.

Đầu ra cho posting quantity 1 gồm ba trang theo đúng thứ tự:

1. trang QR/mã tuyến chính thức của Ozon;
2. trang barcode OZN/mô tả chính thức của Ozon;
3. trang KIZ DataMatrix 58x40 của đúng exemplar đã `passed`.

Posting nhiều item/quantity không cố định ba trang: số trang là `số trang Ozon chính thức + số exemplar KIZ ACCEPTED`. Một PDF nhặt hàng A4 riêng liệt kê posting, SKU, offer ID, tên, quantity và số KIZ accepted theo item; không chứa raw KIZ hoặc PII người mua.

Live artifact được dựng offline từ official PDF đã tải và durable job của app-data cô lập, nên không gọi lại API Ozon, không push KIZ và không ship. Kết quả:

- `output/pdf/OZON-ba56657eeb22-bundle.pdf`: ba trang, PDF 1.7, hai trang đầu giữ 58x40 mm và trang KIZ 58x40 mm;
- `output/pdf/OZON-ba56657eeb22-picking.pdf`: một trang A4;
- render SHA-256 của trang 1/2 trong bundle trùng từng byte PNG với render 300 DPI của hai trang official tương ứng;
- test giải mã GS1 DataMatrix từ trang KIZ, xác nhận symbology identifier `]d2` và so sánh trong process với đúng raw code persisted; raw KIZ không xuất hiện trong log/evidence/PDF nhặt hàng;
- SHA-256 bundle `016f7056ec6980c8e3e0fce3bb17d97284b823c96cc878d900d951e95143dc92`; picking PDF `d18fa72188a6965a2e6b4991ec87501a2d2e7140b3e6bc2ad531eb34c87535aa`;
- `./mvnw -Dtest='Ozon*Test,FxmlSmokeTest' test`: pass 52/52, gồm bundle 3 trang, nhiều exemplar, reprint không prepare lại, mandatory marking không bị downgrade, fail closed khi chưa accepted và FXML smoke.
- gate cuối tại thời điểm live: `./mvnw test` pass 264/264; `git diff --check`, SQLite integrity/FK và scan credential/KIZ literal đều pass.

## 20. Khôi phục JavaFX production và gate nâng cấp 1.1.10 ngày 2026-08-23

JavaFX hiện là UI/runtime production duy nhất. Bộ controller/FXML mới nhất, gồm Ozon dashboard,
SKU→GTIN mapping, prepare KIZ, ship, label bundle và picking PDF, đã được chuyển nguyên vẹn về
`src/main`. Java core, schema v2, durable exemplar job, print history và evidence live ở mục 19
không bị thay thế hoặc hạ cấp.

Build/release contract hiện yêu cầu:

- `Launcher` → `MainApplication` là production entrypoint và FXML Ozon nằm trong main resources;
- Maven compile/test/package JavaFX, tạo runnable JAR và `target/lib` cho `jpackage`;
- không còn source set desktop khác, frontend web, bridge binding hoặc Gradle wrapper;
- script local và workflow đều package `com.tuandev.fbsbarcode.Launcher`;
- Windows EXE/MSI dùng Upgrade UUID data-safe của 1.1.10+, giữ registration 1.1.9 trong lần chuyển
  tiếp để không xóa `%LOCALAPPDATA%\WCode`, rồi app tự migrate sang `%LOCALAPPDATA%\WCodeData`;
  Authenticode và signed update manifest được bật khi repo có đủ secret tương ứng;
- migration 1.1.9 → 1.1.10 vẫn khóa app-data, snapshot/verify trước ghi, giữ ID/token/FK/WB data,
  backfill `WILDBERRIES`, thêm schema Ozon và fail closed khi gặp schema mới hơn.

Rollout production vẫn cần clean upgrade rehearsal trên máy Windows thật, in vật lý
58×40/CryptoPro, xác nhận Seller cabinet đúng account và internal → canary. Nếu chưa có chứng thư
Authenticode, release notes phải nêu rõ bộ cài Windows chưa ký; app-image local chỉ dùng để kiểm thử.

Gate khôi phục JavaFX ngày 2026-08-23:

- `./mvnw -B clean verify`: pass 294/294 test, 0 failure/error/skip;
- Node production/release contracts: pass 12/12; workflow YAML parse thành công;
- macOS arm64 `./build.sh app-image`: tạo `out/WCode.app`, launcher config trỏ đúng
  `com.tuandev.fbsbarcode.Launcher`, bundle signature/layout verify thành công;
- packaged app chạy 12 giây với `wcode.appdata.dir` cô lập, tạo schema v2, verified snapshot và
  marker `javafx-1.1.10.ready`; `integrity_check=ok`, không có FK error;
- source, tests, docs, workflow và package không còn dependency/reference của desktop stack đã loại;
- database/WAL, `kiz.txt` và output live của operator không được mở hoặc sửa trong gate này.
