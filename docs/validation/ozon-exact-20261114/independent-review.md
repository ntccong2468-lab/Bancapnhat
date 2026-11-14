# Review tích hợp Ozon exact — phát hiện và trạng thái

Ngày14/11/2026. Review static trên worktree `vncode-ozon-exact-gtin-20261114`; parent đang chạy focused tests. Người review không chạy Maven, app hoặc HTTP. Baseline `e277d30208d12778ce9239dcde4be16f5ddc9438`; thay đổi mới chưa được gán commit tại thời điểm viết.

## Phát hiện thực đã xử lý

1. **Refresh exact proof bị gọi thêm API giá/giấy tờ Ozon.** `OzonExistingCardLinkService.readExact` lúc đầu đi qua registration reader. Agent proof đã đổi nhánh exact sang `readForGtinSync`, giữ manual reader cũ. Đã đọc lại source xác nhận phân nhánh; focused test do parent/agent chịu trách nhiệm.
2. **Lookup nhiều exact claims quét toàn Catalog cho từng dòng.** `matchingIndex → claims → matches → requireExactPair` ban đầu là N×M parse JSON. Agent proof đã thêm `ExactCatalogRead` dùng một index cho toàn call `claims`, có kiểm owner/source/read revision trước trả kết quả. Đã đọc lại nhánh tái sử dụng; không gọi đây là tối ưu toàn bộ mint/dispatch (xem phần giới hạn).
3. **Attribute article cũ có thể che model mới trong cache Ozon.** Matcher mới ưu tiên explicit article attribute; `currentBasic(exact)` ban đầu dựng snapshot mới nhưng giữ attributes cũ. Với article cache đổi `Q47NAU → B3Q47NAU`, SKU/size giữ nguyên và updated_at rỗng, proof vẫn có thể dùng model cũ. Agent proof đã đổi sang dựng `ProductRow` từ article/size/native IDs hiện có trong `ozon_products` và so typed identity, không mang attribute cũ sang. Đã đọc lại sửa; agent có regression `anOldExplicitArticleAttributeCannotHideACachedNativeModelChange`.
4. **Invalid saved claim có thể bị coi nhầm là chưa có thẻ, dẫn tới đăng ký trùng.** Registration lookup mới dùng reviewed map; map giữ key nhưng GTIN rỗng khi proof hết hạn. `GtinCatalogIndex.ozon` trả rỗng cho key đó, trước đây `lookup` có thể trả Optional.empty dù vừa tải được thẻ Catalog khớp. Đã sửa riêng `RegistrationExistingCards`: saved claim không usable trả lỗi theo dòng `CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED` trước nhánh Catalog rỗng. Regression `anExpiredOzonNativeClaimNeverBecomesPermissionToCreateADuplicate` bao gồm Catalog có đúng thẻ và Catalog rỗng, chứng minh dòng độc lập vẫn dùng cùng snapshot và không tạo checkpoint mới. **Parent cần chạy lại class sau thay đổi này.**

## Ranh giới quyền ghi đã đọc lại

- Index, RegistrationCardIndex và RegistrationExistingCards chỉ lookup/local read; không mint proof hoặc POST barcode.
- `OzonGtinAdapter.prepareIdentity` mới được tạo exact binding trong bước chuẩn bị plan; `validateIdentity`, hasBarcode và verify không mint binding. Existing barcode/current/readback vẫn phải xác thực proof đã có.
- `OzonCatalogMappingService.synchronize` được gọi từ thao tác Sync của người dùng; chỉ tạo native local proof, không gửi barcode tại source refresh. Thêm barcode vẫn đi queue đã xác nhận và readback.
- Exact proof dùng `oe1`, manual native pair vẫn `ov2`. Các nhánh bỏ màu/TN VED của exact có kiểm model/size/proof tương ứng; không sửa helper WB chung. Manual pair khác article vẫn đi chính sách cũ.
- Publication, trade unit, owner/source, native SKU, legal permit và conflict GTIN vẫn tách khỏi matcher thuần. Có actual barcode mới báo observed/applied; direct proof mua KIZ có thể tồn tại trước barcode.
- Size manufacturer/Russian có vai trò riêng; không thấy phép quy đổi size toàn cục hoặc bỏ model prefix/suffix trong implementation mới đã đọc.

Không tìm thêm lỗi authority hoặc hidden POST cụ thể trong adapter/reader/evidence paths đã đọc sau các sửa trên. Đây là kết luận static có phạm vi, không thay nghiệm thu Seller API thật.

## Giới hạn còn lại đã báo parent

Mint/dispatch nhiều target còn có các lượt đọc toàn Catalog tại `bindExactUnique`, `observeExact` hoặc adapter validation khi không truyền operation index. Phần `claims` đã hết N×M, nhưng **chưa có bằng chứng toàn bộ lô10.000 target chạy nhanh**. Parent chọn giữ điều này như giới hạn hiệu năng và không công bố claim10k performance. Không nới kiểm ambiguity để giảm chi phí.

Các lần focused test trước thay đổi guard cuối không tự chứng minh toàn bộ trạng thái cuối đã pass. Cần receipt focused cho guard mới và kiểm cuối do parent tổ chức. Không có thay đổi source ngoài hai file registration/index và hai test được parent giao; guard cuối chỉ sửa RegistrationExistingCards và test tương ứng.
