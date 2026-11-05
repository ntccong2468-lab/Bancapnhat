# Bàn giao nhận diện Catalog, GTIN WB và giao diện đăng ký — 05.11.2026

Nhánh: `fix/znack-state-wb-gtin-20261105`. Base `d9af0b9`, kế thừa bản phát hành 1.6.3. Không đổi số phiên bản, tag, installer hoặc Latest trong tác vụ này.

## Phạm vi đã hoàn thành

- Đối chiếu WBCode công khai mới nhất 1.3.18 với VN Code, xác minh hash installer/JAR; tự triển khai sửa trong VN Code. [Báo cáo có bốn sơ đồ](../research/2026-11-05-wbcode-1318-vncode-znack.md).
- Hiển thị quan sát Catalog có danh tính và mới hơn thay checkpoint cũ khi phù hợp; giữ xử lý riêng cho feed đang gửi/chưa rõ kết quả. Chờ ký không phải Catalog từ chối. Lỗi remote vẫn được giữ khi thông tin biến thể còn cần kiểm tra.
- Đọc bổ sung các thẻ đã biết `good_id`, kể cả đang xử lý/chưa ký và chưa xuất hiện trong danh sách chính. Chỉ có GTIN vừa cấp không được tính là thẻ đã tạo.
- Chung quy tắc màu giữa index và adapter; không bỏ sắc độ/size/article. Size Nga có nguồn rõ ràng vẫn dùng được khi thiếu size gốc. Màu WB trả dạng chuỗi không bị mất khi đọc kho sản phẩm.
- Lọc owner/kind trước giới hạn 100 báo cáo. Mặc định báo cáo thao tác seller, bảy cột chính; dữ liệu kỹ thuật, nhật ký đồng bộ nền, báo cáo cũ theo ID và CSV còn nguyên. Phản hồi xuất file/đối chiếu vẫn hiện ngay khi ẩn nhật ký.
- WB/Ozon dùng một vùng tiến độ dưới bảng, không phủ dòng sản phẩm. Nút Thao tác khác mở kết nối, nguồn dữ liệu, lịch sử, tùy chọn tự ký và điều khiển hàng đợi. Giữ theme sáng/tối đã duyệt.
- Không đổi schema SQLite; `updated_at` Ozon đã có sẵn, nay được truyền tới bộ tính trạng thái.

## Kiểm thử và bằng chứng

**PASS:** 500 ca trong 26 báo cáo JUnit cuối, 0 failure, 0 error, 0 skip. [Danh sách từng lớp](2026-11-05-znack-tests.json). Đây là Linux/Java 25/JavaFX/Xvfb, không phải bộ cài Windows.

Bao gồm các lớp nhận diện/cache/phân trang, SQL báo cáo theo chủ sở hữu, UI hai sàn, workflow đăng ký WB/Ozon, lỗi Catalog và sửa đúng thẻ, HTTP feed/readback, mất kết nối/khởi động lại, WB giá/GTIN/429, và hàng đợi nền. Năm ca nhỏ của `GtinBulkPipelineTest` (các size chung nmID, mất phản hồi, đọc lại trễ, 429 so với 503, phản hồi từ chối WB) cũng đã đạt ở lượt trước; XML của lớp sau đó được thay bằng ca 10.000 nên không cộng thêm vào tổng 500.

**PASS — 10.000 WB, mô phỏng:** 4 lượt ghi, tối đa 3.000 nmID/lượt; payload lớn nhất 1.396.381 byte; 214 lượt đọc, 20 lượt kiểm tra giá; 10.000 barcode được xác minh bằng readback. Mở lại tác vụ hoàn tất không gửi lại. Khoảng nghỉ 15 giây được mô phỏng và kiểm chứng; thời gian test 203.404 ms không là cam kết tốc độ mạng thật. [JSON](2026-11-05-znack-wb-10000-mock.json).

**PASS — API thật, chỉ đọc:** WB 388 bài / 1.982 biến thể; Catalog 1.380 thẻ. Nhận diện duy nhất tăng 248 → 263 nhờ 15 biến thể khác cách viết xanh lá. 117 thẻ Catalog là `notsigned`, 2 nháp thuần và 1 thẻ lỗi. Trong 229 biến thể khớp thẻ công bố, 204 có nhóm TN VED khác WB; không gửi GTIN khi danh tính/hồ sơ chưa hợp lệ. [Số đếm](2026-11-05-znack-readonly-identity-counts.json).

**PASS — UI native:** ảnh sáng/tối, màn có tiến độ và report; giữ vùng bảng lớn, không lặp tiến độ, các hành động cũ còn truy cập được. TASTE/Impeccable được áp dụng theo app native. Impeccable chỉ hỗ trợ regex cho Java/FXML; kết quả detector không thay thế kiểm tra render native.

**Chưa chạy:** ghi/ký Catalog thật, ghi giá/GTIN thật trong tác vụ này; cài/khởi chạy Windows; full suite Windows; phát hành. Không sử dụng authorization của một thẻ thử trước đây để sửa hàng loạt cửa hàng thật.

## Các lệnh chính để lặp lại

Chạy trong môi trường có Java 25/Maven và display JavaFX. `mvn-vncode` ở phiên này là wrapper Maven/proxy của môi trường; máy phát triển có thể dùng Maven tương đương.

```bash
mvn -q -Dtest=GtinCatalogIndexTest,ProductRepositoryTest,RegistrationCardIndexTest,SyncRunRepositoryMetricsTest,ZnackAutoSyncServiceTest test
mvn -q -Dtest=RegistrationSyncReportDialogTest,ZnackCardRegistrationControllerTest,ZnackRegistrationQueryRepositoryTest,OzonRegistrationPaneTest,RegistrationTableTextTest test
mvn -q -Dtest=RegisteredCatalogOwnershipTest,RegisteredGtinSourceTest,CatalogCardStateTest,OzonCardRegistrationWorkflowTest test
mvn -q -Dtest=ZnackCardRegistrationWorkflowTest,ZnackRegistrationBatchHttpIntegrationTest,RegistrationBatchReopenFeedTest,CatalogErrorRepairServiceTest test
mvn -q -Dtest=WbGtinAdapterTest,WbPriceApiRateLimiterTest,WbPriceTransportRateLimitTest,ZnackGtinBackgroundServiceTest test
mvn -q '-Dtest=GtinBulkPipelineTest#wbTenThousandGtinUseFourWholeCardWritesAndReadBackEveryMember' test
mvn -q '-Dtest=ApprovedWorkspaceAcceptanceTest#approvedChromeRetainsShopActionsAndNativePagesInBothThemes' test
```

## Quyết định và kinh nghiệm

1. Trạng thái card là quan sát nguồn; checkpoint là quá trình thao tác. Không dùng một trường thay thế cả hai. So thời điểm và danh tính trước khi cập nhật giao diện, nhưng không dùng một card cũ để tự replay feed còn chưa rõ.
2. Quy tắc ghép và quy tắc ghi phải thống nhất. Bảng tương đương màu có giới hạn giúp sửa lỗi chính tả; không ghép bài bằng tên gần giống hay bỏ qua màu/size/TN VED.
3. Có thẻ Catalog chưa đủ để ghi WB. 25 trường hợp cùng nhóm TN VED trong tập đọc vẫn cần kiểm tra mã đầy đủ, quyền, giá và chứng từ ở bước thực thi.
4. Giảm báo cáo bằng lọc và mở chi tiết khi cần; giữ audit trong cơ sở dữ liệu. Lọc sau LIMIT khiến lượt đồng bộ nền che mất thao tác seller.
5. Cache cũ phục vụ hiển thị; ghi mới vẫn phải xác minh nguồn và phạm vi hiện tại. Không xóa kết quả đã đồng bộ vì một lần đọc bị lỗi.
6. Xvfb của môi trường nằm trong `/workspace/toolchains/xvfb-current/root/usr/bin/Xvfb`, không ở `/usr/bin`; phải kiểm tra socket display trước khi chạy test FX. Lượt chạy thiếu display được dừng, rồi chạy lại đạt; không tính lỗi môi trường đó là bằng chứng sản phẩm.
7. Không sao chép mã WBCode. Giữ provenance binary/hash, giới hạn phân tích tĩnh và ghi rõ phần chưa kiểm chứng động.

Khi tiếp tục phát hành, dùng commit của nhánh sửa này, thực hiện Windows build/upgrade gates và tạo phiên bản mới theo yêu cầu seller; không sửa lại các asset 1.6.3 đã công bố.
