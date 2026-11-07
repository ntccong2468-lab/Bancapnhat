# Ozon — tự thay KIZ và giữ nhãn kết hợp, 07.11.2026

Nguồn nền `61f5ae5dd1a11472b135dc3f36f77865c7e72bee`, nhánh `fix/ozon-fbs-acceptance-pause`. Đây là sửa sau Latest 1.6.5, **chưa đóng gói/phát hành**, chưa ghi API seller thật trong lượt này. WB runtime không đổi. [Biên nhận kiểm thử](ozon-auto-kiz-20261107/verification.json).

## Chẩn đoán có bằng chứng

- Nút **Thay KIZ** vẫn bật trong lúc nhận đơn nhưng handler trả ngay khi `busy`: click không có tác dụng. Baseline JavaFX tái hiện đúng lỗi, sau đó yêu cầu mới của seller là bỏ nút và tự thay.
- Một đơn có thể giữ worker trong vòng tối đa 1.000 mã; UI chỉ báo số đã xong, không báo pha HTTP. Đây là đường gây chờ lâu có bằng chứng từ mã, chưa chứng minh thời lượng cụ thể trên máy seller ở mốc 10/15.
- Sau thay mã, dữ liệu KN3 đỏ cũ chưa được cập nhật bằng chính readback của luồng nhận; thao tác thay thủ công có thể giữ target cũ rồi gặp `OZON_KN3_CHANGED`. Giữ guard đối chiếu mã/product/exemplar; không bỏ guard để che lỗi.
- Hai regression PDF tái hiện lỗi: preflight cũ đặt carrier-only trước khi có job làm app bỏ luôn bước chuẩn bị KIZ hiện tại; mỗi trường hợp xuất 0 nhãn kết hợp thay vì 1.
- PDF seller gửi có **17 trang vận đơn + 5 trang KIZ kết hợp = 22**. Chưa có DB/profile và nhật ký export tương ứng để quy tất cả 12 trang thiếu về cùng một nguyên nhân. PDF/serial/dữ liệu seller nằm ngoài Git.

## Thay đổi

- `OzonDashboardController`/FXML: bỏ nút Thay KIZ, handler và entry mở dialog thủ công riêng Ozon, bỏ query đỏ toàn bộ danh sách chỉ để bật nút. WB vẫn có luồng/nút cũ. Dùng một luồng `Task.updateMessage` cho tiến độ, tránh counter cũ ghi đè pha mới.
- `OzonAcceptanceKizService`, `OzonKizProgress`, `OzonKizReplacementDeferredException`: mỗi lượt tối đa 8 lần thử hoặc ngân sách 45 giây **kiểm tra giữa các lần từ chối**; không hủy mù yêu cầu đang ghi. Khi hết lượt, giữ journal, không dành trước mã thứ chín; đưa đơn xuống cuối hàng để các đơn khác chạy rồi tự quay lại. Không có giới hạn tổng 8 mã cho một đơn trong lượt nhận của UI.
- Chỉ phản hồi từ chối có danh tính khớp mới đổi mã. Pending/timeout/HTTP mơ hồ vẫn đối soát trước, không gửi lặp. Mã seller nhập dùng đúng danh mục đã gắn; không lấy pool tùy ý và không tự mua KIZ. Quy tắc GTIN của kho mua giữ nguyên.
- Mỗi readback KN3 khớp đúng binding cập nhật cache và settle intent. Trạng thái nhận đơn, trạng thái KIZ và việc in được phân biệt rõ. Mã đỏ được loại khỏi kho khả dụng, vẫn giữ binding để in đúng đơn khi Ozon cho nhận dù từ chối mã.
- `OzonExemplarService.PrintBatch`/`OzonPrintBundleService`: tự sửa KN3 đỏ/intent còn dở trước in, kể cả đơn đã nhận không còn danh sách optional-mark. Đọc trạng thái mới, giữ đúng slot và thuộc tính khác, bỏ kết quả prepare cũ sau khi thay. Không tự POST chỉ vì chưa có job hoặc chưa có quan sát đỏ. Nếu thiếu mã phù hợp thì giữ kết quả từ chối riêng; không tự tạo mã hoặc ghép theo vị trí trang.
- `OzonExemplarJobRepository`: nếu mã từng bị loại nay được readback chấp nhận đúng job, chỉ settle khi có đủ quan sát mới khớp mã/product/exemplar, cache accepted đúng owner, tombstone cùng owner và không gắn job khác. Đường API cũ không có proof vẫn bị từ chối; không trả mã về AVAILABLE.
- Preflight PDF production là ước tính, luôn cho chuẩn bị lại theo chi tiết/kho hiện tại; tải vận đơn theo lô, giữ vận đơn gốc và bỏ barcode sản phẩm riêng như trước. Nhãn KIZ phải khớp đúng đơn; các lỗi thực còn lại vẫn báo carrier-only riêng.
- Có bản dịch tiến độ Việt/Anh/Nga/Trung. Không migration, dependency, license hoặc updater mới.

## Kiểm thử

- Baseline click: 1 lỗi tái hiện. Baseline PDF: 6 ca, 2 thất bại đúng 0/1 nhãn; 4 ca khác đạt.
- Hồi quy đầu: **139/139** đạt. Hồi quy cuối: **237/237, 19 lớp, 0 bỏ qua**; lệnh chính xác và danh sách lớp trong JSON.
- Có JavaFX 15 đơn chậm ở đơn thứ 11 vẫn báo đúng pha, tự nhường/tiếp tục không click, 101 đơn nhận, fixture PDF 1.000 đơn giữ tải theo lô, đọc DataMatrix, in lại, kho nguồn, restart/ý định ghi, HTTP 429, owner, unknown mutation và WB KN3 cũ.
- Các API trong test là **MockWebServer**, SQLite tạm và fixture license của bộ kiểm thử. Không phải nghiệm thu nhận đơn/KIZ thật, benchmark sàn thật hoặc Windows. Đã review diff và `git diff --check` đạt. Không quét độc lập toàn app/không dùng agent reviewer.

## Giới hạn và việc tiếp theo

- Chưa phát hành thay thế 1.6.5 immutable. Nếu seller yêu cầu phát hành tiếp, tạo version/tag mới; không thay asset của 1.6.5. Chưa tạo bộ cài Windows của nguồn này.
- Thay tự động hiện thực cho mã seller nhập đã phân loại; không nới mapping/GTIN của kho mua hoặc bịa mã khi hết kho. Tự sửa chạy trong nhận đơn và chuẩn bị in, không biến vòng đọc FBS thành vòng ghi KIZ chặn đồng bộ.
- Yêu cầu mới đang chờ sau phần Ozon: Code Doctor luồng Znack WB, `SYNC_STORAGE_BUSY`, trạng thái hoàn tất khi WB đã có GTIN 046, cập nhật nền mã còn thiếu, gửi thẻ Catalog thật, bỏ “Thao tác khác”. Không lấy quyền thay đổi WB từ tác vụ Ozon; yêu cầu mới đã cấp scope WB riêng.
