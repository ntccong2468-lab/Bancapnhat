# Nghiên cứu nguồn Znack V3

Ngày 21/10/2026; đọc mã, không chạy ứng dụng hay dùng dữ liệu seller của bên khác.
Nền cũ checkout: Bancapnhat `f45cd4b4a15a7010143ad2677da34cc16b2d7791`, app 1.2.2.
Nền chọn mới: tag v1.5.7 `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
`origin/upgrade/znack-source-audit-20261021` ở `15d062e89fe7c5ede4a34afb63694d2eef7b1f16`
có bằng chứng phát hành mới hơn nhưng diff ứng dụng so tag rỗng; chỉ metadata/tooling.
Kho https://github.com/ntccong2468-lab/Vncode ở `c01c5cac03a65310500109613a15032b65bb1d12`
vẫn là 1.1.34, không dùng làm nền mới nhất.

| Kho / commit đã đọc | License tìm thấy | Ngôn ngữ / bằng chứng | Tái sử dụng và rủi ro |
|---|---|---|---|
| https://github.com/equescodebelike/honest-sign / `fc2f30b48913fa2fddb9a70c9c277693d3db7753` | Không thấy LICENSE | Python `hs-cli.py:73,92,162,169`: product-list, feed-product, POST feed, feed-status | Chỉ quan sát; mặc định sandbox, API key query; không chứng minh production |
| https://github.com/Chenuss/National_cat_CHZ / `1c237dd2e25517016b2105990fba0dd8c2dcf93e` | Không thấy LICENSE | Python `nk_client.py:192,238,367,546`: giới hạn batch, manual retry, 429; `docs/api/nk_api_openapi.yaml` | Tài liệu bên thứ ba, không thay hợp đồng chính thức; không sao chép code/schema |
| https://github.com/akdengi/sklad-cz / `c6a72c642ade6066dcc9b42dc480a1f59ecb9760` | MIT; LICENSE còn lưu ý pycades không khai báo license | Python `app/cz_api.py:52,80,115,124`: Store/CPSigner/SignCades; đọc docs/15-api-chestny-znak.md | Tham khảo attached/detached theo từng API, không bê format chung sang Catalog; không sao chép |
| https://github.com/rupphi/test-wcode nhánh znack-registration-test / `8f7c3d1ac8598158029b8d037e2d3c128e240727` | Không thấy LICENSE | Java package com.tuandev.fbsbarcode: workflow/service/repository/schema/mapper, ZnackApiClient, GTIN/KIZ, WindowsCadesSignatureProvider, WbApiClient đều có | Khác namespace và phiên bản; không mặc định đây là VNCode production; không sao chép |

## Nền 1.5.7 đã kiểm toán

Java 25/JavaFX 25/FXML, Maven wrapper 3.8.5, SQLite qua Database/AppPaths.
Client `ZnackApiClient` đã phân biệt Catalog/True API/SUZ và không replay nhiều
mutation; `ZnackNationalCatalogService` có validate schema/giấy tờ, đọc card thật,
review XML và batch signature. `ZnackRegistrationScope` ràng buộc chứng thư/INN/
môi trường; `productionCatalog` lắp CryptoProSignatureProvider với signer kiểm tra
chủ thể. `WbGtinAdapter` giữ toàn bộ size/barcode và xác minh đọc lại, không hỗ trợ
replace barcode. Không tạo lại các client này.

Workflow/controller cũ trên 1.000 dòng, có status tổng hợp PUBLISHED và các consent
riêng. V3 tách journal/state rõ hơn, không xóa registrations/history/KIZ/GTIN cũ.

## Tìm thêm nguồn

Đã thực hiện GitHub repository search cho đủ mười từ khóa trong yêu cầu: Честный
ЗНАК API, Национальный каталог API, National Catalog CRPT, Честный ЗНАК CryptoPro,
CAdESCOM Java Windows, National Catalog GTIN registration, feed-product-sign-pkcs,
Честный ЗНАК маркировка, СУЗ API, Wildberries GTIN update. HTML/metadata tìm kiếm
không cho kết quả repository bổ sung có thể xác minh ổn định trong lần truy cập này;
không suy ra kho nguồn từ tên hay bịa thêm danh sách. Kết quả truy vấn giữ tại
`/workspace/znack-v3-research/additional-searches.json`.

Nguồn chính thức đọc thêm thành công: docs.crpt.ru → phần cho nhà phát triển → API НК.
API 5.68 ngày 28/09/2026 xác minh trực tiếp các endpoint và detached CAdES; xem
`ZNACK_V3_API_CONTRACTS.md`. Không dựa vào OpenAPI của repo tham khảo để tuyên bố
đã xác minh hợp đồng production.
