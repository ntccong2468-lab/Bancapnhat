# Đối chiếu phiên bản và cải tiến đăng ký Znack / GTIN — 30.10.2026

## Kết luận và bản nền

Ưu tiên hoàn thiện luồng đang có: sửa thẻ bị từ chối → gửi lại đúng thẻ qua National Catalog API → theo dõi duyệt → ký theo lựa chọn của seller → xác minh công bố → cập nhật đúng biến thể WB/Ozon và đọc lại kết quả.

- Latest công khai tại thời điểm kiểm tra: **v1.5.19**, công bố `2026-10-29T06:57:14Z`, tag `7e123e60e355715806c901f2f7e7838d1d5df065`.
- Nền sửa: **`release/vn-code-1.6.0`**, SHA `a8e0d9de0ffce25b71e0c8afb195fcedb22779f1`, hơn tag 1.5.19 11 commit. Tên commit “Release” không chứng minh đã có bộ cài công khai.
- Nhánh thay đổi: `fix/znack-gtin-recovery-20261030`. Không đổi số phiên bản, tag hay bộ cài đã phát hành.
- Default branch `update/vn-code-1.3.0` không phải mốc nguồn mới nhất nên không dùng làm nền.

## Phạm vi đối chiếu

Đã lập danh mục **42 nhánh remote, 38 tag, 9 release**; so lịch sử và khác biệt của các module đăng ký Catalog, đồng bộ GTIN, WB price repair và adapter WB/Ozon trên 80 ref. Lịch sử nền có 64 commit liên quan các đường dẫn này, trong đó 53 commit chạm module đăng ký. Đọc nguồn hiện tại và các ghi chú/sửa đổi liên quan để tránh đưa lại tính năng đã tích hợp. Đây là đối chiếu phần liên quan, không phải tuyên bố đọc lại từng dòng của toàn bộ ứng dụng tại mọi tag.

Danh mục SHA, quan hệ tổ tiên, số file khác biệt và commit riêng: [biên bản kiểm chứng](../validation/2026-10-30-znack-gtin-recovery.json). Số file khác biệt được tính riêng trong phạm vi module, không phải toàn repository. Tám nhánh ngoài lịch sử tổ tiên của 1.6.0 không có commit riêng chạm các module này; thay đổi độc lập của chúng không phải bản nâng cấp đăng ký/GTIN còn thiếu.

| Nhóm lịch sử | Nội dung đã có trong nền | Quyết định |
|---|---|---|
| 1.4.x | Kiểm tra chủ thể Catalog, ánh xạ biến thể, xác minh công bố, quyền ký/đồng bộ theo thẻ, phục hồi thẻ lỗi | Giữ và mở rộng, không viết lại |
| 1.5.0–1.5.7 | Đăng ký tuần tự, sửa thẻ theo lô, xác minh giấy tờ, chuẩn hóa size, bảo toàn GTIN/feed, đọc lại kết quả sàn | Giữ các điều kiện xác minh |
| 1.5.8–1.5.11 và các nhánh Znack tích hợp | Nhật ký V3, màn hình lỗi riêng, prefill sửa thẻ, báo cáo và tiếp tục công việc bền vững | Dùng nền hiện tại, không ghép lại PR cũ |
| 1.5.12–1.5.19 | Giảm đọc lặp, tiếp tục khi giới hạn tốc độ/lỗi mạng, đồng bộ thay đổi và các cải tiến marketplace | Kế thừa toàn bộ |
| 1.6.0 | Nền mới nhất cho lần sửa này; đã chứa sửa null lịch sử nhận KIZ Ozon | Không sửa lại lỗi null đã được khắc phục |
| `ci/vn-code-1.6.0-windows-2022` | Chỉ đổi workflow đóng gói sang Windows 2022; không đổi ứng dụng | Không coi là phiên bản chức năng mới |

## Những thay đổi đã làm

| Vấn đề trước sửa | Hành vi sau sửa | Kiểm chứng chính |
|---|---|---|
| Journal gọi `startInstant().orElseThrow()` khi nạp lớp; runtime thiếu metadata có thể làm luồng đăng ký không khởi tạo được | Có mã worker dự phòng; thiếu thời điểm bắt đầu không được suy ra worker còn sống đã chết | Runtime thiếu/throw metadata, PID hiện tại, worker sống không bị chiếm quyền hoặc gửi lại |
| Sửa thẻ gọi resume với `autoSign=false`, không có lựa chọn hoàn tất ký/đồng bộ ngay tại bảng sửa | Thêm hai lựa chọn độc lập, mặc định tắt: ký sau khi duyệt; đồng bộ sau khi xác minh công bố. Lưu lựa chọn theo thẻ; quyền ký gắn với payload đã duyệt | Bốn tổ hợp quyền, payload thay đổi làm mất quyền ký, thẻ đang moderation chưa được đồng bộ; click Apply/Hủy trên JavaFX |
| Tác vụ sửa giá WB có thể bị bỏ lại khi item GTIN lỗi/hủy hoặc danh sách size thay đổi | Đối soát mọi receipt đang gửi/chờ của đúng shop và credential, kể cả item không còn chạy; xác nhận `uploadID` trước khi xử lý trạng thái tác vụ | Item FAILED, receipt tách khỏi item, size thay đổi, task lạ, mất phản hồi; không gửi lại giá |
| Thay đổi size có thể chặn mãi bước đối soát giá trước GTIN | Hoàn tất đối soát receipt cũ trước kiểm tra lựa chọn mới. Tác vụ đã kết thúc nhưng cấu trúc đổi được đánh dấu superseded; không nhận GTIN là thành công | Không mở khóa khi tác vụ còn chạy/không rõ; không tăng số GTIN thành công sai |
| Ozon ship mất phản hồi có thể coi `canShip=false` hoặc đổi trạng thái bất kỳ là thành công | Chỉ đối soát thành công với trạng thái sau giao phù hợp và không có `ship_failed`; trạng thái chưa rõ tiếp tục chặn gửi lặp | `cancelled`, vẫn đóng gói nhưng mất nút ship, `ship_failed`; lần gọi sau chỉ đọc lại |
| README trỏ cứng tới bộ cài 1.5.13 | Dẫn tới Releases/latest, phân biệt mã nguồn chưa phát hành và bộ cài | Kiểm tra link và metadata Latest |

Fixture `ZnackMarketplaceActivationTest` cũng được sửa đúng trình tự READY → xác nhận → GTIN_PENDING. Trước đó fixture xác nhận trực tiếp trạng thái GTIN_PENDING, trái điều kiện của nền 1.6.0. Không nới kiểm tra production để làm test đạt.

## Ba phương án tối ưu để phát triển tiếp

Ba phương án bổ sung cho nhau. Thứ tự triển khai đề xuất: **1 → 2 → 3**, ưu tiên đăng ký thẻ thành công trước khi tối ưu số lượng GTIN.

### 1. Bộ quy tắc tìm lỗi và sửa thẻ ngay trong VNCode — ưu tiên cao nhất

Mở rộng `CatalogFeedback`, `CatalogPackingPolicy` và `CatalogErrorRepairService` thành danh mục quy tắc có mã lỗi, điều kiện áp dụng, nguồn dữ liệu, giá trị trước/sau và phiên bản quy tắc. Tự sửa lỗi định dạng hoặc lựa chọn từ điển khi có duy nhất một kết quả đã xác minh; dữ liệu thiếu hoặc nhiều cách hiểu được gom vào một bảng để seller bổ sung một lần. Sau đó kiểm tra lại schema/giấy tờ/owner, lưu checkpoint, gửi feed qua API và tiếp tục theo dõi.

**Ưu điểm:** tác động trực tiếp đến tỷ lệ đăng ký thành công; giải thích được mỗi sửa đổi; tái sử dụng phần lớn mã hiện có; kiểm thử từng loại lỗi độc lập. **Nhược điểm:** phải duy trì quy tắc theo nhóm hàng và thay đổi của Catalog; không giải quyết tự động được dữ liệu hàng hóa chưa có; cần mẫu lỗi thực đã ẩn dữ liệu nhạy cảm để mở rộng độ phủ.

**Đã có ở lần sửa này:** nối bảng sửa thẻ với lựa chọn ký và đồng bộ tiếp theo, giữ nguyên GTIN/good_id khi sửa thẻ cũ. Quy tắc tự sửa hiện tại vẫn giới hạn ở lỗi ảnh tùy chọn/đóng gói đã được server xác nhận. Chưa triển khai bộ tự sửa mọi loại lỗi.

**Tiêu chí nghiệm thu tiếp theo:** mỗi quy tắc có ca thành công, ca dữ liệu mơ hồ và ca lỗi hỗn hợp; không đổi thành phần/chủ thể/giấy tờ bằng suy đoán; payload không đổi không bị gửi lặp vô hạn; không cấp GTIN mới chỉ vì feed cũ bị từ chối. AI có thể gợi ý cách diễn giải, nhưng không tự tạo sự thật hàng hóa hay quyết định ký.

### 2. Hàng đợi đăng ký và phục hồi thống nhất — ưu tiên kế tiếp

Tiếp tục gom checkpoint, quyền thao tác và nhật ký gửi vào một mô hình trạng thái chung cho WB/Ozon: chuẩn bị → chờ bổ sung → đã gửi → đang duyệt → chờ ký → chờ công bố → chờ đồng bộ → đã đọc lại xác nhận. Lỗi mạng/429 có lịch thử đọc lại; kết quả ghi chưa rõ phải đối soát trước khi có quyền gửi tiếp. Lưu tài khoản, môi trường, GTIN, good_id/feed_id, dấu vân tay payload và lịch sử trạng thái.

**Ưu điểm:** đóng/mở app không mất tiến trình; dễ giải thích vì sao thẻ dừng; giảm gửi trùng và công việc phải làm lại; dùng chung báo cáo. **Nhược điểm:** chuyển đổi checkpoint cũ và hai workflow hiện tại cần thận trọng; nhiều tình huống cạnh tranh và khởi động lại cần kiểm thử; không làm quá trình duyệt bên Catalog nhanh hơn.

**Đã có ở lần sửa này:** journal chịu được thiếu metadata tiến trình; phục hồi receipt giá WB độc lập với trạng thái item; giữ trường hợp kết quả chưa rõ để đọc lại. Đây là gia cố các thành phần hiện có, chưa hợp nhất toàn bộ workflow.

**Tiêu chí nghiệm thu tiếp theo:** dừng tiến trình tại từng ranh giới gửi/nhận, mở hai cửa sổ cùng chạy, đổi credential, nhận phản hồi muộn; mỗi tác vụ chỉ có một lần ghi chưa đối soát. Nhánh V3 vẫn có `V3_API_CONTRACT_UNVERIFIED` cho `SYNC_WB`; không gỡ chặn này chỉ để dùng engine mới. Luồng adapter GTIN đang hoạt động vẫn được dùng và kiểm tra riêng.

### 3. Bản đồ sản phẩm–biến thể–GTIN và đồng bộ phần thay đổi — ưu tiên thứ ba

Xây tiếp trên `ProductKey`, `RegisteredGtinProof`, `OzonSkuGtinMatcher` và hàng đợi GTIN hiện có. Mỗi ánh xạ giữ shop/INN/môi trường, GTIN/good_id, WB nmID/chrtID hoặc Ozon product/offer/SKU cùng bằng chứng size, màu, TN VED. Chỉ tạo việc khi có thay đổi đã xác minh; đọc lại đúng biến thể sau ghi rồi mới báo hoàn tất.

**Ưu điểm:** giảm gán nhầm GTIN giữa các size và shop; giảm request không cần thiết; dữ liệu nguồn rõ ràng khi sửa lỗi; phù hợp lô lớn. **Nhược điểm:** sản phẩm trùng mã nội bộ hoặc thiếu size vẫn cần xử lý xung đột; phải quản lý dữ liệu cũ, xóa/ghép biến thể và thời hạn bằng chứng; cần nghiệm thu riêng hợp đồng API và giới hạn tốc độ mỗi sàn.

**Đã có ở lần sửa này:** quyền đồng bộ được lưu từ bảng sửa; chỉ tạo ứng viên khi thẻ thực sự công bố. Giữ các adapter và kiểm tra danh tính hiện có. Chưa thay thế kho ánh xạ hoặc viết một bộ đồng bộ mới.

**Tiêu chí nghiệm thu tiếp theo:** hai size cùng article, đổi màu, đổi nhóm TN VED, GTIN trùng giữa shop, GTIN đã tồn tại, Ozon trả lỗi từng item, WB yêu cầu sửa giá; không ghi đè barcode không thuộc lựa chọn và không nhận HTTP 200 là bằng chứng GTIN đã áp dụng.

## Kiểm chứng và giới hạn

- **420 ca duy nhất đạt**, 18 lớp kiểm thử liên quan; 0 failure, 0 error, 0 skip ở kết quả cuối của từng lớp. Tổng hợp từ năm nhóm chạy sau các sửa tương ứng, không cộng các lần chạy lặp hoặc các lần lỗi trước sửa. Danh sách và hash các file thay đổi nằm trong JSON đính kèm.
- Có JavaFX Apply/Hủy và hai checkbox; API giả lập, HTTP localhost, SQLite, mất phản hồi, sai danh tính và khởi động lại. Biên dịch JDK 25 / JavaFX 25.0.2 trên Linux.
- Chưa chạy toàn bộ test suite của ứng dụng, Windows/CryptoPro thật, nâng cấp bộ cài, hoặc gửi đăng ký/GTIN trên shop thật trong lượt này. Không dùng kết quả fixture để khẳng định Catalog sẽ duyệt mọi thẻ.
- Điều kiện nghiệm thu thật: kết nối National Catalog đúng chủ thể, quyền API, chứng thư ký hợp lệ trên máy Windows, giấy tờ/thuộc tính sản phẩm đủ và vài thẻ mẫu được chọn rõ. Sau đó kiểm chứng lần lượt feed → duyệt → ký → công bố → sàn → đọc lại; lưu bằng chứng đã ẩn thông tin nhạy cảm.

Nguồn tham chiếu trực tiếp: [National Catalog API](https://docs.crpt.ru/gismt/API_%D0%9D%D0%9A/) và [đặc tả WB Products/Prices](https://dev.wildberries.ru/api/swagger/yaml/en/02-items.yaml), truy xuất 30.10.2026. Schema WB có `uploadID` và trạng thái task 3/4/5/6, dùng để đối soát receipt đúng tác vụ. Chưa xác minh lại trực tiếp tài liệu Ozon hiện hành trong phiên này; kiểm thử Ozon dựa trên adapter và các trạng thái đã có trong repository.
