# Báo cáo VNCode 1.5.3

## Thay đổi đã thực hiện

- Đăng ký Znack WB/Ozon lưu toàn bộ lựa chọn vào hàng đợi bền vững, sử dụng đường gọi `/v3/feed` để gửi lần lượt từng thẻ tới Catalog và chỉ tính đã gửi khi lưu được feed ID. Trang vẫn cho đổi bộ lọc, tìm kiếm và xem thẻ đang chờ duyệt. Báo cáo giữ đủ từng thẻ; viên thuốc hiện tiến độ và tự tắt khi có kết quả cuối. Yêu cầu chưa rõ kết quả được đối soát trước khi thử tiếp.
- Đề xuất đăng ký Ozon chọn Type article và hệ size theo schema Catalog, nhận alias của màu, giới tính, chất liệu, danh mục và quy chuẩn. Giá trị không xác định duy nhất vẫn cần seller bổ sung. Thẻ `PRE_SUBMIT_ERROR` chắc chắn chưa gửi được dựng lại payload với GTIN đã cấp; không xin GTIN khác hoặc gửi lại yêu cầu đang chưa rõ kết quả.
- Cập nhật GTIN WB đối chiếu lỗi theo article gốc/đã trim, đúng sản phẩm và thời điểm. Kết quả đọc lại đã có GTIN được lưu trước khi kích hoạt cục bộ; lỗi kích hoạt được báo riêng, giữ kết quả của các thẻ còn lại. Báo cáo GTIN WB/Ozon tự đọc tiến độ nền để hiện số đã xác minh, chưa cập nhật, thiếu GTIN, chờ kết quả và cần xử lý. Rời trang không hủy tác vụ hoặc để thông báo cũ ghi đè trang mới.
- Mất mạng hoặc mở lại app tiếp tục từ checkpoint. Đóng cửa sổ khi đang đăng ký có thể thu xuống khay hệ thống với Mở lại/Thoát; nếu không tạo được biểu tượng khay thì giữ cửa sổ và báo lý do. Tiến trình đã thoát hoặc máy đã tắt chỉ tiếp tục khi mở app lại.
- Ozon FBO/FBS dùng nhãn ghép 58×40 mm cho sản phẩm cần KIZ, gồm DataMatrix KIZ, barcode sản phẩm và thông tin hàng; giữ nguyên trang vận chuyển chính thức. In lại dùng dữ liệu nhãn đã lưu, không lấy thêm KIZ. Nhận tất cả đóng gói mỗi đơn vị thành một shipment con qua Ozon; app chỉ xác nhận sau khi đọc lại đúng đơn, kho, SKU, số lượng và trạng thái. Kết quả đang chờ được đối soát nền, không lặp POST.
- Giảm kiểm tra/sao lưu lặp khi khởi động và cập nhật; không báo 100% trước khi sẵn sàng bàn giao bộ cài. Giữ xác minh EXE, checksum, SQLite/WAL và phục hồi. Dữ liệu 1.5.2, mẫu đăng ký, phạm vi giấy tờ/TN VED và KIZ được giữ. Bốn đầu vào đăng ký quan trọng và phương thức FBS WB trùng byte với 1.5.2; nhánh Ozon thêm đối soát shipment đang chờ.

## Kiểm thử và phát hành

Source ứng dụng: `69ffc7ecb60a91c874510522c4aae3c5be069ccd`. Linux `clean verify` đạt 2.072 kiểm thử: 2.070 đạt, không thất bại hoặc lỗi, hai bỏ qua theo nền tảng, thuộc 338 bộ kiểm thử. Công cụ phát hành đạt 85/85 kiểm thử Node.

Windows build: [39396769729](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39396769729). Kết quả Java cuối: **2072** kiểm thử, **2070** đạt, **2** bỏ qua, **0** thất bại, **0** lỗi. Kiểm tra native, 288 bố cục và nâng cấp giữ dữ liệu: **đạt: 23 tệp bằng chứng, 288 bố cục và 18 kịch bản nâng cấp giữ dữ liệu**, bao gồm EXE `/passive /norestart` từ 1.5.2.

Phát hành: **đã phát hành Latest 1.5.3** lúc **2026-10-20T09:49:36Z**, bởi [39398012877](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39398012877). Bộ cài: **146864128** byte; SHA-256: `ec608d3677581c12901ee702e516cb2eea2757a5a9ee8fd84a2ce2438e8a3f31`. Xác minh tải công khai và updater từ 1.5.2: **đạt; bộ dò cập nhật production của app báo AVAILABLE với đúng URL, checksum và kích thước**. Việc updater tìm thấy bản mới không chứng minh thông báo đã xuất hiện trên desktop seller.

## Đọc API thật

Catalog đọc đủ 1.277 thẻ: 1.241 published, 34 lỗi và hai nháp; đọc chi tiết cả 34 thẻ lỗi không trả nguyên văn lý do kiểm duyệt hoặc feed ID. Không gán cùng một nguyên nhân cho toàn bộ thẻ lỗi khi chưa có phản hồi feed.

WB đọc đủ 388 thẻ, 1.982 biến thể qua bốn trang. Một biến thể có barcode thuộc tập GTIN Catalog published hiện đọc; 1.981 biến thể còn lại không khớp tập này. Đây là độ phủ trong dữ liệu của kết nối hiện tại, không chứng minh sản phẩm chưa đăng ký ở mọi nơi hoặc seller dùng sai tài khoản.

Bảy thông báo WB về khoảng trắng gồm năm lỗi ngày 20/10 và hai ngày 17/10; cả bảy article có khoảng trắng đầu/cuối, không có va chạm sau trim. 61 thông báo phụ thuộc nhóm chỉ thuộc ngày 17/10, không chỉ ra thêm trường sai. Không suy ra WB cấm khoảng trắng ở giữa article. Verifier đã sửa đối chiếu article gốc; chưa ghi production để xác minh payload trim của bản mới được sàn chấp nhận.

Lượt đọc WB lúc 09:27 UTC vẫn có bốn mục/68 thông báo, nhưng timestamp lỗi mới nhất đã đổi thành 08:59:30 UTC. [Bản chênh lệch chỉ đọc](../validation/2026-10-20-vncode-153-wb-readonly-delta.json) giữ tổng số và thời gian; dữ liệu không xác định ứng dụng hoặc phiên bản đã gửi yêu cầu, không chứng minh bản mới đã ghi thành công.

Bằng chứng aggregate đã che mã và khóa: [Catalog](../validation/2026-10-20-vncode-153-catalog-readonly-recheck.json), [độ phủ WB–Catalog](../validation/2026-10-20-vncode-153-wb-catalog-coverage.json), [lỗi WB](../validation/2026-10-20-vncode-153-wb-error-readonly-recheck.json). Mẫu Ozon đọc thật gồm năm đơn một đơn vị, chưa có lệnh tách shipment production.

## Chưa hoàn tất

Chưa nghiệm thu tạo/sửa/ký thẻ Catalog mới, chữ ký CryptoPro doanh nghiệp, ghi GTIN lên sàn qua desktop seller, license production, tách shipment Ozon nhiều đơn vị thật hoặc thao tác khay hệ thống trên Windows. Mọi cờ nghiệm thu production tương ứng vẫn false; kiểm thử mô phỏng không thay thế bằng chứng ghi thật.

Phát hành theo yêu cầu tiếp tục của chủ dự án với các giới hạn đã công bố; không nhận nghiệm thu production đầy đủ. License không giới hạn do chủ dự án cấp chỉ dùng trong dữ liệu kiểm thử cô lập WB/Ozon, không đổi kiểm tra license/chữ ký của app và không cung cấp chứng thư/khóa CryptoPro thật. Khi nâng từ 1.5.2, phần tải và bàn giao ban đầu vẫn chạy mã updater của 1.5.2; sửa updater mới có hiệu lực sau khi cài bản này.
