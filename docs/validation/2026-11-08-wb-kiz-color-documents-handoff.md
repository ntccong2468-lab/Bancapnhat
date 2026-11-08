# WB KIZ mapping: màu và giấy tờ — 08.11.2026

Áp dụng [Code Doctor](../../.agents/skills/code-doctor/SKILL.md) và đối chiếu mã từ đồng bộ sản phẩm → Catalog/registry → mapping → kiểm tra trước mua. Người dùng xác nhận lỗi ở WB. Bản sửa trên nhánh `fix/kiz-mapping-color-documents-20261108`, nền `9599a5784e7125211a11de2c9ed51d3a551316ab`. Nguồn chức năng và kết quả cuối được ghi trong `verification.json`.

## Chẩn đoán

Đã tái hiện tám assertion thất bại trước sửa. Projection WB chỉ lấy màu đầu tiên, còn kiểm tra mua yêu cầu một màu duy nhất nên cùng dữ liệu nhiều màu có thể được báo ghép nhưng không đủ proof mua. Bộ so màu chưa thống nhất một số cách ghi như grey/gray/xám/серый và thứ tự của cùng bộ màu.

Thông báo `GTIN_DOCUMENT_MISSING` trước đây gộp giấy tờ hết hạn/bị đình chỉ, ngày dạng `dd.MM.yyyy` và sổ giấy tờ chưa xác minh. Khi Catalog trả mã 18/19, parser còn xóa giấy tờ đã quan sát. Khi registry không truy cập được, mapping có thể báo đạt dựa trên giấy tờ cũ rồi thất bại ở guard sau. Ngày hết hạn `to_date` từ registry chưa được lấy, có thể khiến dùng hạn cũ hoặc không thấy hạn.

Đây là lỗi mã đã tái hiện; chưa xác định chính xác SKU lỗi trong hồ sơ app seller. Dữ liệu hai API key môi trường chỉ có một GTIN trùng, không đủ kết luận tình trạng toàn cửa hàng đang cài.

## Mã đã sửa

| Nhóm tệp | Thay đổi |
| --- | --- |
| `GtinColorIdentity`, `GtinMatchingService` | Dùng bộ màu đầy đủ, chuẩn hóa hữu hạn cách viết/ngôn ngữ, Ё/Е, dấu gạch và thứ tự. Giữ sắc độ, màu thứ hai và giá trị lạ; không fuzzy-match hoặc dùng giao của hai bộ màu. |
| `ProductRepository`, `WbGtinProductReader`, `WbProductSnapshotReader` | Projection và các lần đọc WB giữ đủ màu, thay vì lấy phần tử đầu hoặc trả rỗng cho mọi bộ nhiều màu. |
| `FboVerifiedGtinRepository`, `KizRegistrationIdentityRepository` | Dùng cùng cách đọc màu trong proof trước mua và hồ sơ đăng ký. Sai/thiếu màu hoặc size vẫn không cấp proof. |
| `NationalCatalogGtinSource`, `RegistrationExistingCards`, các projection quan sát Ozon | Giữ đủ màu Catalog khi đọc lại/lưu observation, tránh refresh ghi đè màu thành rỗng. Không đổi nhận đơn Ozon. |
| `PermitDocumentDate`, `CatalogProductData.Document`, `ZnackPermitDocumentParser` | Chuẩn hóa ISO hoặc `dd.MM.yyyy` bằng parser strict. Ngày không tồn tại/không rõ dạng vẫn bị từ chối. Không suy đoán hạn vô thời hạn. |
| `CatalogProductDataParser` | Giữ giấy tờ quan sát khi registry đang xử lý, gắn cờ chưa xác minh; dùng ngày hết hạn registry của đúng loại/số giấy tờ; phân biệt registry rỗng và registry có giấy tờ chưa đủ xác minh. |
| `CatalogPermitEvidence`, WB/Ozon mapping và helper permit dùng chung | Phân biệt thiếu, hết hạn, bị đình chỉ, chưa hiệu lực, ngày sai, thiếu trường và chưa xác minh. Pending/unavailable không được báo mapping thành công. |
| `KizMappingController`, bốn locale | Hiện màu WB và Catalog trong dòng lỗi màu; tách lý do giấy tờ để biết cần bổ sung hay chỉ đọc lại trạng thái. |

Không đổi schema/version/dependency, không tự sửa giấy tờ hay màu thật trên sàn, không lấy giấy tờ cấu hình gắn cho thẻ khác. Owner/chứng thư/publication/trade-unit/size/trùng GTIN và guard hàng đợi mua vẫn áp dụng. Khác TN VED vẫn được phép mapping theo quyết định trước; metadata mua lấy từ Catalog. Kho seller nhập giữ quy tắc riêng.

## Hiểu thông báo sau sửa

- **Sai màu:** bộ màu đầy đủ hai nguồn khác nhau; báo kèm cả hai giá trị. **Thiếu màu:** chưa có dữ liệu đọc đủ, không kết luận là sai màu.
- **Thiếu giấy tờ:** không có giấy tờ trong thẻ/sổ đã đọc; giấy tờ lưu trong cấu hình app không tự chứng minh đã có trên thẻ Catalog.
- **Đang xác minh / không đọc được registry:** giữ giấy tờ cũ để xem, đọc lại sau; không yêu cầu khai giấy tờ mới chỉ vì timeout hoặc mã 18/19.
- **Hết hạn, đình chỉ, ngày sai, thiếu trường:** nêu riêng nguyên nhân; giấy tờ không hợp lệ không tự thành hợp lệ vì có cờ vô thời hạn cũ.

## Kiểm chứng

Nguồn chức năng **`ae2d48a2e397428869d553b7e008402f469a3566`**. Lượt Linux rộng: **4.010 ca / 530 lớp, 0 fail/error, 2 OS skip**; không chạy ba stress 10.000 GTIN. Sau lượt biên dịch đó đã bổ sung một guard màu chỉ gồm dấu phân cách và ca tái hiện; **217/217 ca / 18 lớp trên nguồn cuối đạt, 0 skip**, rồi package SUCCESS. Manifest ghi riêng nguồn lượt rộng và hai tệp của guard cuối, không cộng các lượt trùng nhau. JAR local 1.960 class Java 25 khớp byte output; không fixture/probe, chưa là bộ cài Windows.

Kết quả/lệnh và hash log cuối: [verification.json](wb-kiz-color-documents-20261108/verification.json). Dữ liệu thử trong JUnit là tổng hợp/SQLite tạm/mock HTTP; không tính thành giao dịch API thật. Có regression bộ màu đầy đủ/partial, giữ sắc độ, ngày không tồn tại, hết hạn dù cờ endless, pending/unavailable, proof trước mua và báo cáo JavaFX bốn ngôn ngữ.

API thật chỉ đọc: **388 bài WB / 1.982 biến thể**, danh sách **1.380 thẻ Catalog**, một thẻ chi tiết + một registry, **8/8 HTTP 200**. Có 19 bài hai màu và hai bài ba màu. Bộ đọc production chạy trên chính raw response thật cho **1.982/1.982 projection màu nhất quán**; một giấy tờ registry có hạn đã đọc được và phân loại đủ điều kiện dữ liệu. Đây không phải proof chủ sở hữu hoặc mapping đã lưu. [Biên nhận đã bỏ dữ liệu seller](wb-kiz-color-documents-20261108/live-read.json).

Raw response/credential/GTIN/số giấy tờ/profile thật giữ ngoài Git. Không gửi chỉnh sửa seller, mua có phí, ký CryptoPro, kích hoạt license hoặc Gmail. Môi trường chưa có hồ sơ OMS/chứng thư để nghiệm thu mua/lưu thông đầu-cuối. Chưa nghiệm thu trên app Windows đang cài hoặc tất cả GTIN của cửa hàng.

OpenQodex 0.16.0 chỉ chạy **Gitleaks 8.21.2** theo diff nền; không AI reviewer và không chứng nhận mọi scanner/security toàn app. Xem receipt trong verification. Không lấy kết quả Windows của 1.6.6 để chứng nhận nguồn sửa mới.

## Trạng thái và bàn giao

Bản sửa chưa phát hành; POM vẫn 1.6.6 chỉ là mã candidate cục bộ. **Latest immutable v1.6.6 / release 437862752 / app source ac8ba1c939e22d0318877a3a2ba7242359cce78e giữ nguyên.** Khi có yêu cầu phát hành tiếp theo cần version/tag và Windows build mới; không thay asset/tag 1.6.6.

Khi kiểm mua thật về sau, đọc journal/profile có OMS/chứng thư hợp lệ; chọn đúng biến thể và đối soát giao dịch cũ trước khi gửi. Không replay giao dịch chỉ vì thư mục `/tmp` của phiên trước mất. Sau cập nhật nên đồng bộ lại WB/Catalog để lấy đủ màu và trạng thái registry mới; không xóa cache/dữ liệu seller.
