# Ozon 1.6.5 — một liên kết Catalog dùng cho mua KIZ và GTIN

Ngày **07.11.2026**, nguồn **`cae86ef71149a9be505b060360a7a9db8e07c5b4`**, nhánh `release/vn-code-1.6.5`, kế thừa `684f1188138bd9151e26fdc3536d24266d0614a6`. **Chỉ gom bản mới, chưa phát hành Latest.** [Đối chiếu WBCode mới nhất 1.3.20 và sơ đồ](../research/2026-11-07-wbcode-1320-ozon-variant-link.md), [biên nhận kiểm chứng](ozon-reviewed-20261107/verification.json).

## Chẩn đoán và quyết định

Catalog đã đăng ký từ WB có thể ghi tên/article khác Ozon cho cùng biến thể. VN Code có bảng liên kết thẻ Ozon nhưng chưa dùng thống nhất ở purchase proof, matcher và màn đăng ký. Fingerprint quá rộng còn coi thay đổi trình bày là đổi danh tính; một registration/barcode cũ có thể có ưu tiên hơn quyết định mới.

Giải pháp được người dùng yêu cầu thực hiện: **duyệt một cặp native Ozon–Catalog rồi dùng cùng cặp đó cho các bước**, không đổi SKU hoặc tên trên sàn. Khác TN VED được phép theo chỉ đạo mới; mua dùng mã và giấy tờ Catalog. Không tự duyệt hai sản phẩm chỉ vì cùng size hoặc tên gần giống. Quy tắc nhận đơn/KIZ mới chỉ áp dụng Ozon, giữ nguyên WB.

## Thao tác trong ứng dụng

1. Vào Đăng ký Znack của cửa hàng Ozon, chọn các biến thể, bấm **Ghép thẻ Catalog**.
2. Tìm mẫu/article/GTIN Catalog. **Đề xuất theo bộ lọc** chỉ điền cặp màu/size duy nhất trong tập đang lọc, chưa lưu hoặc gửi gì. Những trường hợp còn nhiều ứng viên cần chọn riêng.
3. Bấm **Đọc lại hai nguồn**. App kiểm thẻ published/trade-unit/chủ thẻ/scope, đúng native Ozon, màu/size tường minh và TN VED đầy đủ của Catalog. Hiển thị hai bên để seller kiểm đúng mẫu.
4. Đánh dấu xác nhận cùng một sản phẩm, màu và size thực, rồi **Lưu liên kết**. Cả nhóm chỉ được lưu khi toàn bộ review đọc lại còn giống; SQL conflict muộn rollback cả nhóm.
5. Mua KIZ dùng cùng cặp đã duyệt và giấy tờ đang hợp lệ. Việc lưu liên kết không tự mua; vẫn tính thiếu và đi qua bước seller Áp dụng.
6. **Cập nhật GTIN lên Ozon** mở luồng GTIN có preview/hàng đợi hiện có. Lưu liên kết không tự ghi barcode. HTTP 200 chưa là thành công; chỉ xác nhận sau readback đúng native SKU.

Ba bằng chứng được giữ riêng: **liên kết đã duyệt**, **đủ điều kiện mua**, **barcode đã hiện trên sàn**. Báo cáo mapping có số đã ghép nhưng còn chờ cập nhật Ozon; không đếm chúng như mapping đã readback.

## Các tệp runtime thay đổi

| Nhóm | Tệp và hành vi |
| --- | --- |
| Proof dùng chung | `OzonReviewedVariantProof`: revision `ov2:` gắn owner/scope, native ID/SKU và danh tính vật lý Catalog/Ozon; cache tối thiểu trong `app_config`. Chỉ quan sát không tạo approval. Giữ chặn khi hết hạn/thay danh tính, không rơi về broad article rule. |
| Review và lưu | `OzonExistingCardLinkService`, `OzonReviewedGtinLinks`: đọc mới/kiểm chủ, cho phép TN VED hai bên khác, duyệt batch rồi lưu nguyên tử. Refresh các liên kết hết hạn trong ngân sách tối đa 10 lượt hoặc 10 giây trước khi bắt đầu lượt đọc tiếp; không tự duyệt thay đổi. |
| Mua KIZ | `KizRegistrationIdentityRepository`, `KizCrossMarketplaceProof`: dùng đúng native pair trước registration cũ; refresh đã duyệt khi cần. Giữ lịch sử cũ, không chuyển legacy link thành proof mua mới. Bảo vệ/metadata kho mua vẫn còn; kho seller nhập không đổi. |
| Nhận diện/đồng bộ | `GtinCatalogIndex`, `RegistrationCardIndex`, `ZnackAutoSyncService`, `ZnackGtinReviewService`: một matching index native; quyết định mới ưu tiên hơn tên/article/registration/barcode cũ. Evidence ghi `OZON_REVIEWED_NATIVE_VARIANT`. |
| Ghi GTIN | `OzonGtinAdapter`, `GtinTnvedIdentity`: full fresh read phải còn đúng proof trước gửi; dùng pre-dispatch token ổn định. Chỉ cặp native đã duyệt được đi qua khác TN VED. Giữ barcode cũ, giới hạn tốc độ, conflict và readback. |
| Báo cáo KIZ | `OzonCatalogMappingService`, `KizMappingController`: phân biệt linked/mapped; quyết định mới ưu tiên hơn mã cũ; cặp hết hiệu lực báo cần đọc lại, không tái dùng barcode lịch sử. |
| UI | `OzonRegistrationPane`, `OzonCatalogVariantLinkDialog`: chọn nhiều cặp, review và xác nhận, thao tác nền, list co giãn, vô hiệu hóa review khi đổi cặp; chuỗi vi/en/ru/zh. |

Không thêm dependency hoặc schema version. Tái sử dụng bảng liên kết tùy chọn và cache có sẵn; legacy revision 64 hex tiếp tục được adapter cũ xử lý, không tự cấp quyền mua qua đường `ov2:`. Không sửa mã nhận đơn WB hoặc portal/license/DPAPI.

## Danh tính và phục hồi

- Khác cách trình bày tên/article giữa hai bên được phép. Đổi tiêu đề/article mô tả trên cùng native pair giữ approval khi dữ liệu đọc mới vẫn có cùng danh tính vật lý.
- Đổi offer ID/SKU/native product, màu, size, category, brand/thành phần/thuộc tính vật lý hoặc scope không tái sử dụng approval cũ. Không tự suy XL = 52 nếu không có metadata size đã xác minh.
- Catalog chưa published, đổi thuộc tính vật lý hoặc GTIN đã thuộc native product khác không được tái dùng cặp đã duyệt. Giấy tờ revoked/thiếu chặn proof mua; điều kiện hợp lệ để mua tách khỏi việc nhận diện thẻ đã công bố và ghi barcode. Hết hạn giữ claim để không fallback sang mã cũ.
- Cache ProductSnapshot sống mới hơn được kiểm trước mua, không chờ đồng bộ toàn danh mục mới phát hiện chỉnh thành phần. Liên kết/cache qua restart được kiểm bằng SQLite tạm.
- Quan sát, lưu liên kết và ghi marketplace là ba bước riêng; lỗi giữa gửi/readback giữ PENDING và đối soát, không biến HTTP 200 thành APPLIED.

## Kiểm chứng và giới hạn

Cập nhật tiếp theo: đã có [một lượt append GTIN/readback API thật và đối chiếu đủ 533 bài Ozon / 2.023 thẻ Catalog](2026-11-07-ozon-live-gtin-acceptance.md) bằng khóa người dùng mới cung cấp. Đó là kiểm client/parser/transport và readback/conflict adapter; chưa phải certificate-scoped approve/submit hoặc UI Windows. Giữ phân biệt này khi đọc các giới hạn tại checkpoint triển khai bên dưới.

Lệnh Maven, số ca từng lớp, source/JAR hash, scanner và startup được ghi trong [verification.json](ozon-reviewed-20261107/verification.json). **476/476 ca / 28 lớp đạt**, 0 fail/error/skip, package SUCCESS. Test gồm khác article/TN VED, legacy/cache-only không cấp approval, restart/expiry, đổi vật lý, foreign holder, revoked permit, batch rollback, giữ barcode, HTTP 200 chưa readback và UI tối/sáng. Nhóm WB GTIN (51), Ozon acceptance (42) và PDF bundle (62) được chạy lại trên cùng nguồn. Failure display trước sửa và lần sửa chưa đủ (vẫn fallback bằng article) được giữ trong biên nhận, không bỏ test.

JAR **7.495.904 byte**, SHA-256 `f1f8fa1334e64587608735176247b09d3c8f628107787cdfb46f551cd41de84b`; 1.949 class khớp output, không fixture/probe. Startup production/profile trống mở **6.528 ms**, 1280×700, 0 shop/UNVERIFIED; đóng và nhả data lock đạt.

[Ảnh giao diện với dữ liệu tổng hợp](assets/ozon-reviewed-20261107/README.md) được chụp JavaFX trên Linux/Xvfb, không phải ảnh seller/Windows đang cài. JAR production được mở bằng profile trống để kiểm startup/shutdown và data lock, không kích hoạt license hoặc gọi API seller.

**Chưa nghiệm thu giao dịch bằng liên kết mới trên API thật:** không tự chọn/duyệt một cặp seller, không mua KIZ có phí và không ghi GTIN Ozon trong lượt triển khai này. Chín đơn gilet thật ở [lượt trước](2026-11-07-ozon-165-gilet-pdf-handoff.md) chỉ chứng minh luồng nhận đơn đã ghi riêng. Chưa chạy full Java suite, Windows installer/ứng dụng đang cài, benchmark danh mục lớn hoặc giao dịch mua–restart–tiếp tục trên hệ thống thật. OpenQodex trong môi trường chỉ có Gitleaks; không gọi đó là AI review độc lập hoặc quét toàn app.

Mốc Latest vẫn là **v1.6.4**. Bản 1.6.5 chỉ là candidate nội bộ JAR/source/checksum, chưa có bộ cài Windows và chưa gửi cập nhật xuống app.
