# Bộ nhớ dự án VNCode

## Đã phát hành 1.5.13 — 23.10.2026

- Chủ dự án yêu cầu «đưa bản sửa này phát hành xuống app đang cài» sau khi được báo rõ phạm vi thử API và Windows. **1.5.13 đã lên Latest lúc 2026-10-23T19:14:18Z**, Release ID `422171061`, bất biến; tag `v1.5.13` và mã ứng dụng `7e88dc0e5e4e491025874f560c3ac069b6718644`. Nhánh bàn giao `release/vn-code-1.5.13`.
- Gói Windows đã xác minh chứa bản sửa Catalog `674eada`, country alias và hàng thu gọn. Build `40002256619` đạt toàn bộ 2.929 ca: 2.927 đạt, 2 bỏ qua theo OS, không lỗi/thất bại. Mở app, DPAPI, cài/gỡ và nâng cấp thật từ 1.5.12 giữ dữ liệu đều đạt. Công cụ Node 117/117 đạt.
- Công bố `40002567923` đạt; 16 tài sản, 11 tài sản bắt buộc đã được tải công khai/đối chiếu byte, digest, checksum và tag. Bộ cập nhật của JAR Windows đọc Latest thật, nhận 1.5.13 và chọn đúng MSI. Chưa quan sát thông báo trên thiết bị seller; có thể dùng Cài đặt → Kiểm tra phiên bản để nhận ngay. App tự kiểm tra khi mở và theo chu kỳ 45–60 phút.
- Bộ cài EXE 147.192.320 byte, SHA256 `7593e46c6eb243b5f310025c43c4e6d9fc25f7e0aed201e7cd347af8c4fea9a9`. Không đổi định danh nâng cấp hoặc xóa Release cũ. Sau tag chỉ cập nhật quy trình công bố/tài liệu; không thay binary/tag đã kiểm tra.
- Thử Catalog production một thẻ đã được chủ dự án bấm Đăng ký là bằng chứng riêng của phiên sửa, không phải giao dịch trong CI. Đọc lại lần nữa trước công bố vẫn có đúng thẻ draft/moderation; không cấp/gửi thêm, ký CryptoPro, mua KIZ có phí hoặc ghi GTIN WB/Ozon. Cờ live trong build-info phản ánh đúng giới hạn của CI, không phủ nhận biên nhận API riêng.
- Biên nhận công khai giữ trong repo/Actions vì Release khóa bất biến sau Publish. Workflow 1.5.13 chờ đúng Windows run, đối chiếu đúng source/tag/draft/assets và chặn hạ Latest. Windows Java mất khoảng 52 phút; không khẳng định hoàn tất khi mới có trạng thái running.
- [Bàn giao hoàn tất](validation/2026-10-23-vncode-1513-handoff.md), [biên nhận Latest](validation/2026-10-23-vncode-1513-published-latest.json). Quy tắc gửi/đọc lại Catalog và giới hạn nghiệm thu ký ở phần dưới tiếp tục áp dụng.

## Phiên sửa 23.10.2026 — trước đóng gói 1.5.13

- Nhánh `fix/catalog-readback-compact-rows`, nền mã ứng dụng 1.5.12. Sửa điều kiện bộ lọc Đang xử lý để cần bằng chứng đọc thẻ thật, đọc lại receipt còn chờ cuối lô, đưa tạo từng thẻ Ozon vào cùng hàng đợi và giảm hàng Sản phẩm/Đăng ký còn 104/76 px. Giữ luồng khôi phục lô Ozon cũ và bổ sung ánh xạ tên Китай chỉ khi dictionary thật xác nhận CN với tên đầy đủ tương ứng.
- 187 ca mục tiêu có kết quả cuối đạt. Lượt toàn bộ đầu phát hiện một lỗi khôi phục lô Ozon; đã sửa và chạy lại 103 ca liên quan đạt. Lượt `clean verify` cuối **BUILD SUCCESS: 2.929 ca, 2.927 đạt, 2 bỏ qua theo hệ điều hành, không lỗi/thất bại**. [Biên nhận kiểm thử](validation/2026-10-23-catalog-readback-compact-verify.json), [bàn giao](validation/2026-10-23-catalog-readback-compact-handoff.md). Ảnh hàng thu gọn đã xem từ view thật với dữ liệu tổng hợp.
- Người dùng đã bấm Đăng ký cho đúng một fixture production đã trình bày. Đã xin đúng 1 GTIN mới và POST `/v3/feed` đúng 1 lần bằng API Catalog thật; đọc lại xác nhận good_id và article/màu/size/TN VED khớp. Catalog tăng từ 1.253 lên 1.254 thẻ; thẻ mới là `draft / moderation`, chưa ký. Biên nhận công khai đã loại dữ liệu seller: [kết quả API thật](validation/2026-10-23-catalog-one-card-api-live.json). Đây là thử API của phiên, không phải nghiệm thu thao tác trên app Windows của seller.
- Payload, GTIN, feed_id và good_id thật nằm riêng trong `/tmp/vncode-live-catalog-20261023/`, không commit dữ liệu seller vào repo. `live-attempt.json` đã có và trạng thái `CATALOG_CONFIRMED`: chỉ đọc/đối soát nếu tiếp tục, không cấp GTIN hoặc gửi lại. Chưa ký CryptoPro hoặc ghi barcode lên WB/Ozon trong lượt thử này.
- Trước khi nghiệm thu tự ký/xuất bản, cần đối chiếu thêm chuẩn hóa phản hồi: lần gửi thật dùng `categories` là danh sách ID và nước là ISO, còn API đọc lại trả danh mục dạng object và tên nước chính thức. Đọc `submissionStatus`/bộ lọc đã đạt với phản hồi thật; phép so sánh nguyên JSON ở `validateReviewedBusinessFields` chưa được nghiệm thu cho luồng ký. Không suy từ thử tạo thẻ sang kết luận ký/xuất bản đã đạt.
- Latest vẫn là 1.5.12 đã công bố trước đây. Không tạo tag/bộ cài hoặc đổi Latest trong lượt sửa này.
- Chi tiết: [gửi API, đọc lại và hàng thu gọn](fixes/2026-10-23-catalog-readback-compact-rows.md).

## Trạng thái phiên 22.10.2026

- Bản công khai tại thời điểm bắt đầu: `v1.5.11`, commit `2abe716d77a0f2d4d5f08ed0ae6451bf29ea2495`.
- Luồng đăng ký/đồng bộ được hoàn tất trước giao diện: nhánh `fix/znack-workflow-parity`, PR #12, commit cuối `017412f`. Không có bộ cài riêng cho bước này.
- Giao diện nền đen và tab Kho KIZ: commit `b27d799`, nhánh `ui/wbcode-dark-reference`, đã push. 115 ca/11 lớp kiểm thử UI đạt; ảnh view thật với dữ liệu tổng hợp nằm trong `docs/fixes/images/2026-10-22-dark-reference`.
- **Đã công bố 1.5.12 lên Latest lúc 2026-10-22T21:52:20Z**, Release ID `421236216`, tag `v1.5.12`, mã ứng dụng `2f9f194d26a9d840e9ab1097f51d91811abc3bbf`. Nhánh phát triển tiếp: `release/vn-code-1.5.12`; các commit sau tag chỉ cập nhật quy trình công bố/tài liệu.
- Windows build `39851955943` thành công: 2.925 ca, 2.923 đạt, 2 bỏ qua theo hệ điều hành, không lỗi/thất bại; mở app, bảo vệ dữ liệu và nâng cấp từ 1.5.11 đều đạt. 117 ca công cụ phát hành đạt.
- Công bố/xác minh `39860902344` thành công; tải công khai và checksum đủ 11 tài sản bắt buộc đã xác minh. Release có 16 tài sản và được khóa bất biến. Bộ cập nhật trong JAR Windows đã đọc đúng Latest 1.5.12, URL MSI và digest; chưa quan sát thông báo trực tiếp trên máy seller.
- Không mua KIZ có phí, ghi Catalog/WB/Ozon production hay ký bằng chứng thư seller trong phiên này. Các bài nghiệp vụ dùng fixture/API giả lập và app Windows thật cho phần native.
- Xem [bàn giao hoàn tất](validation/2026-10-22-vncode-1512-handoff.md) và [biên nhận công bố](validation/2026-10-22-vncode-1512-published-latest.json).

## Quyết định cần giữ

1. Cấu hình Znack có đúng 5 tab chính: Thông tin, Lịch sử mua KIZ, Đã xóa, Cài đặt, Kho KIZ. Tab Kho KIZ dùng kho và luồng nhập file hiện có, không tạo kho song song.
2. Giao diện tham chiếu mới là nền đen, nút xám, thao tác chính tím, logo VN đen/chữ trắng. Không phục hồi nền xanh khi nâng cấp. Lựa chọn giao diện được áp dụng một lần khi nâng cấp, sau đó giữ lựa chọn rõ ràng của người dùng.
3. Đăng ký phải lấy TN VED từ đúng sản phẩm/biến thể trên sàn. Giấy tờ chỉ được dùng khi có phạm vi phù hợp; không thay TN VED để ép khớp giấy tờ, không gán danh tính thẻ của sản phẩm khác.
4. Gửi feed là một mốc riêng với việc đã đọc thấy thẻ trên Catalog và việc đã xuất bản. Báo cáo Lỗi chỉ nhận từ chối thực sự của Catalog; cần kiểm tra ghép thẻ không được lẫn vào nhóm này.
5. Giữ hàng đợi, GTIN, feed ID, phạm vi shop/chứng thư và kết quả chưa rõ qua khởi động lại. Đối soát trước khi gửi lại; không xin GTIN/mua mã mới chỉ vì timeout. Không thể thực thi HTTP khi tiến trình đã đóng hoàn toàn hoặc không có mạng.
6. GTIN WB: tối đa 3.000 thẻ/yêu cầu và 10 MB, nghỉ 15 giây giữa các lô, giữ barcode gốc, xác minh lại từng size. Ozon dùng endpoint barcode riêng, tối đa 100 cặp/yêu cầu. Luôn tôn trọng giới hạn/Retry-After phía dịch vụ.
7. KIZ: kiểm tra kho sẵn dùng và yêu cầu còn chờ trước khi mua phần thiếu. Mã yêu cầu mua là UUID hợp lệ được giữ trong pipeline. Kho seller tải lên và kho mua phải tiếp tục dùng đúng luồng/định danh đã có.
8. Giữ Hỗ trợ/Hướng dẫn hiện có, không thêm backend hội thoại mới. Không thay kiến trúc ứng dụng ngoài phạm vi đã được giao.
9. Phát hành bản mới không tự động xóa các Release cũ. Không chạy chế độ `prune` chỉ vì chủ dự án yêu cầu đưa bản mới lên Latest.
10. Không lưu API key, license, mật khẩu, mã KIZ thật hoặc dữ liệu tài khoản seller trong bộ nhớ/bàn giao. Không tắt license/chữ ký production để làm kiểm thử xanh.

## Kinh nghiệm kiểm tra và phát hành

- Codex Web dùng JavaFX trên Xvfb với `DISPLAY=:99`; cần truyền biến này cho từng lệnh Maven. Không suy ra lỗi ứng dụng từ việc runner chưa có DISPLAY.
- Font Inter biến thiên không bảo đảm chữ đậm ổn định trên JavaFX. Bản Inter Bold tĩnh cùng giấy phép OFL và nguồn/hash được kèm trong app.
- CSS phải bao phủ cả trường hợp view là scene root và nằm trong workspace; nếu chỉ dùng selector hậu duệ thì preview và app có thể khác nhau. Màu glass cũ không được ghi đè giao diện đen.
- Khi ẩn dòng phản hồi lúc rảnh, vẫn phải hiện lại khi có lỗi/tiến trình. Không giấu lỗi cấu hình để giống ảnh.
- Kiểm tra YAML phải phát hiện khóa lặp: hai workflow cũ có `name`, `options` hoặc `run` bị trùng, khiến GitHub từ chối trước khi thực thi.
- Lô giả lập 10.000 chạy qua planner, adapter và SQLite thật của app nhưng không phải ghi production. Giữ rõ các cờ `liveCatalogWriteVerified`, `liveMarketplaceWriteVerified`, `paidKizPurchaseVerified` trong bằng chứng.
- Bản build và tag phải trỏ cùng SHA. Build Windows dùng `publish-vncode-baseline.yml`, chạy toàn bộ Maven verify trước `jpackage`, kèm EXE/MSI/runtime/source/Git bundle/checksum/verification. Đừng chạy hai Maven mutation/compile đồng thời trong cùng workspace.
- Đóng gói `app-image` sẽ dọn `out`; phải giữ bộ cài và chứng cứ trước khi tạo runtime. Chỉ đổi Latest sau khi kiểm chứng tài sản cuối.

Nguồn chi tiết: [luồng đăng ký](fixes/2026-10-22-znack-workflow-parity.md), [giao diện](fixes/2026-10-22-dark-reference-ui.md), [bản nền](releases/BASELINE.md), [ghi chú 1.5.12](releases/VN-code-1.5.12.md).

## Kinh nghiệm công bố 1.5.12

- `api.github.com` và Git push có thể vẫn dùng được khi upload từ Codex tới `uploads.github.com` báo 401. Đợt này giải quyết bằng workflow GitHub Actions với `contents: write` và token của repository, sử dụng nguyên artifact Windows đã kiểm tra; không xin token qua chat.
- GitHub khóa Release ngay khi công bố (`immutable: true`). Phải tải hết tệp vào bản nháp, đối chiếu checksum rồi mới Publish/Latest. Không thêm hoặc ghi đè tệp, kể cả biên nhận, sau công bố; lưu bằng chứng tải công khai trong repo/Actions.
- Workflow một lần `publish-vncode-1512.yml` pin nguồn/build/release cụ thể và chặn mã ứng dụng khác với SHA đã kiểm tra, chặn hạ Latest. Lượt đầu tải công khai đã đạt nhưng lỗi thêm biên nhận sau khóa; lượt sau chỉ đọc/xác minh và lưu artifact đã đạt. Không dựng lại hoặc thay binary.
- Tệp văn bản trong JAR Windows có CRLF khác Git LF. So nội dung CSS/FXML sau chuẩn hóa xuống dòng, còn ảnh/font và tài sản phát hành đối chiếu byte/hash nguyên gốc.
- Bộ cập nhật Windows ưu tiên MSI; liên kết EXE vẫn dùng cho tải/cài thủ công. Đã dùng đúng JAR phát hành để phân tích phản hồi Latest công khai và kiểm tra version/URL/digest/size.
- Kiểm thử toàn bộ Java trên Windows ở lượt này mất khoảng 52 phút. Không khẳng định thành công trước khi có kết quả; các lượt sửa quy trình công bố/tài liệu giữ nguyên mã ứng dụng và không yêu cầu dựng lại app.
