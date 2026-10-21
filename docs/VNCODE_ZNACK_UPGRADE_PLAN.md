# Kế hoạch và kết quả nâng cấp Znack/GTIN/KIZ

Ngày 21.10.2026, Europe/Moscow. Repository `/workspace/Bancapnhat`; nhánh `upgrade/znack-source-audit-20261021`; commit gốc `b4672258090b51d6e40f1266cc180c6d1c4946f7`; nguồn 1.5.7, Java 25/JavaFX/Maven, SQLite schema 6.

## Trình tự thực hiện

1. Khảo sát toàn repository và chuỗi gọi, ghi nhận nền: đã xong.
2. Nghiên cứu ba repository bắt buộc và `test-wcode/znack-registration-test`, ghim commit, kiểm tra giấy phép và API chính thức: đã xong. Không sao chép mã từ các kho tham khảo.
3. Lập kiểm toán trước khi sửa: đã xong; các vị trí dòng trong phần khảo sát trỏ vào commit gốc, không phải số dòng sau sửa.
4. Sửa hẹp trên service/repository/workflow hiện có theo P0 → P1 → P2: đã xong các mục dưới, gồm rà soát và sửa xuất nhãn đồng thời, Draft và phản hồi CIS mã 0.
5. Kiểm thử nhóm, hồi quy phần còn lại, build JAR và kiểm tra khởi động cục bộ: đã đạt trong phạm vi Linux/mock; các ca Windows ghi rõ chưa chạy. Kết quả chính thức nằm trong [báo cáo kiểm thử](VNCODE_ZNACK_TEST_REPORT.md).
6. Lưu thay đổi trên nhánh riêng để review; không merge, không push, không tạo bộ cài Windows, không phát hành hoặc đổi Latest.

## Thay đổi và tiêu chí kiểm chứng

| ID | Mức | Sửa trên thành phần hiện có | Điều kiện chấp nhận |
| --- | --- | --- | --- |
| KIZ-01 | P0 | `ZnackSchemaSupport` giữ order ID, request key và checkpoint đã gửi/chưa rõ; index/trigger chỉ chặn yêu cầu mua mới, không ép giao dịch đã gửi về QUEUED. `ZnackPurchaseCoordinator` đọc lại giao dịch cũ có order ID. | Database cũ với GTIN 13/14 số tương đương, init lặp không mất receipt/GTIN/KIZ và không POST mua lại. |
| KIZ-02 | P0 | `ZnackKizOrderService.reconcile` kiểm tra cấu trúc, định danh, fingerprint và lỗi của toàn phản hồi SUZ; dữ liệu thiếu/sai là chưa xác minh. | Không chuyển phản hồi lỗi thành NOT_FOUND để mở một lượt mua khác. |
| KIZ-03 | P0 | `FboVerifiedGtinRepository` và `KizPurchaseMappingProof` tái sử dụng proof Catalog: ID, chủ thể, source, trạng thái gốc, độ mới, đơn vị thương mại và biến thể native. | Barcode có checksum đúng không đủ quyền mua; sai article/màu/size/chủ thể hoặc proof cũ phải chặn. |
| KIZ-04 | P1 | `FboPurchaseService` tạo UUID chuẩn, riêng cho từng GTIN trong một ý định đã xác nhận. | Lô nhiều GTIN đi vào hàng đợi hiện có; không còn lỗi khóa do định dạng `fbo:...`. |
| SIG-01 | P1 | `CryptoProCommandRunner` đặt deadline cho cả vòng đời/đầu ra, giới hạn bộ đệm, dọn tiến trình đã quan sát khi timeout/hủy; giữ interrupt. | Process Java cục bộ mô phỏng stdout/stderr lớn, writer liên tục, child giữ pipe, timeout và hủy; không treo vô hạn. |
| SIG-02 | P1 | `WindowsCadesSignatureProvider` giữ nguyên mã TIMEOUT/CANCELLED và nguyên nhân khi kiểm tra CAdESCOM. | Hủy không bị đổi thành thiếu CAdESCOM và không tiếp tục thử host ký khác. |
| WB-01 | P1 | `WbContentApiRateLimiter` dùng quota riêng cho `cards/error/list`: 10/phút, khoảng 6 giây, burst 5; giữ các lane khác và Retry-After. | Clock mô phỏng xác minh quota, cách ly đọc/ghi và bảo toàn ưu tiên FBS. |
| WB-02 | P1 | `WbProductSyncService` kiểm tra mảng cards và cursor.total khớp số dòng trước persist/READY, chống cursor lặp. | Trang 20/20 hợp lệ; 20/80 không được báo hoàn tất hay xóa cache tốt. |
| PRINT-01 | P1 | `PrintHistory*`, `ZnackGtinInventoryService`, `ZnackKizExportService`, `OrderExportWorkflow` lưu snapshot nhãn/KIZ và chuyển prepared → pdf_ready cùng transaction tiêu thụ mã. | Lỗi xuất file sau tiêu thụ, xuất một phần, đóng/mở lại và in lại phải giữ đúng mã, không mua/tiêu thụ thêm. Ngăn hai lượt xuất trùng phạm vi chạy đồng thời. |
| CIS-01 | P1 | `ZnackCisReadback` dùng chung cho readiness/lưu thông: xác minh đúng tập CIS, không lặp/ngoại lai, GTIN và lỗi của từng dòng. | Đếm đủ trạng thái không thay được danh tính; dữ liệu thiếu không được xác nhận INTRODUCED. |
| CIS-02 | P1 | `ZnackErrorMessages.hasCisLookupError` dùng chung cho đối soát CIS và xác minh kho KIZ, chấp nhận mã thành công số `0` hoặc chuỗi `"0"`. | Không coi mã 0 là lỗi; vẫn chặn mã khác 0, kiểu sai và thông báo lỗi; giữ đối chiếu CIS/GTIN/chủ thể/trạng thái. Bằng chứng hình dạng phản hồi từ nguồn tham khảo, chưa phải schema True API chính thức đã xác minh. |
| CAT-01 | P2 | `ZnackNationalCatalogService` đọc thêm chẩn đoán feed `result.result`, giữ scope từng dòng và che dữ liệu nhạy cảm. | Có lý do lỗi theo hợp đồng cũ; không gán lỗi size khác và không dùng việc bổ sung parser để khẳng định mọi lỗi tạo thẻ đã hết. |
| UI-01 | P2 | Bảng WB/Ozon hiện riêng Size RU, trạng thái đăng ký, kiểm duyệt Catalog và GTIN trên sàn; nhãn mới tiếng Việt, giữ raw state/thời điểm đọc. | Không suy đổi L → 52; không coi accepted feed/published/local updated là đã ghi marketplace; renderer không truy vấn DB/API từng ô. |
| CAT-02 / HS-02 | P1 | Draft đã có được giữ GTIN/good_id, mở lại review đầy đủ rồi cập nhật qua feed `moderation=1`; không cần thêm endpoint `feed-moderation`. | Dữ liệu phải được seller duyệt, kiểm tra lại Draft/chủ thể/danh tính/TN VED sát dispatch; checkpoint trước POST, mất phản hồi phải đối soát và không gửi lặp. Không tự cấp GTIN hoặc ký/ghi marketplace. |

## Những quyết định giữ nguyên

- Dùng các hàng đợi native WB/Ozon, `features/gtinsync`, `ZnackPurchaseCoordinator` và lịch sử in hiện có; không dựng database hoặc scheduler song song. Schema vẫn là 6; không xóa dữ liệu seller.
- Giữ kiểm tra GTIN/checksum, identity native, giấy tờ theo sản phẩm và TN VED, phê duyệt seller, checkpoint trước mutation và readback sau ghi. Không dùng tên sản phẩm hoặc thứ tự size làm khóa.
- Không ép batch 50: tài liệu WB hiện hành công bố endpoint cập nhật thẻ tới 3.000 thẻ/10 MB cùng các quota liên quan. Planner hiện có kiểm tra giới hạn và chỉ xác nhận khi đọc lại.
- Không thêm HTTP ETag cache chỉ vì kho tham khảo có; không dùng cache cũ làm bằng chứng không tồn tại thẻ. Giữ phân biệt UNKNOWN với NOT_FOUND và giới hạn đọc Catalog được báo rõ.
- Không thay bộ ký/CMS đang hoạt động, không lưu PIN plaintext, không đổi license hoặc kéo theo CryptoPro binary/pycades.
- Yandex Market chưa có trong mã nguồn hiện tại; Yandex Translate không phải tích hợp seller Market. Không tự xây sàn mới trong bản sửa Znack/WB/Ozon.

## Giới hạn nghiệm thu

Kiểm thử cục bộ/mock không chứng minh tạo thẻ, cấp GTIN, mua KIZ, ký pháp lý hay ghi WB/Ozon production thành công. Windows/CryptoPro/chứng thư thật, license production, máy in thật và hiệu năng với quota production cần nghiệm thu riêng trong môi trường được phép. ProcessHandle dọn tiến trình theo khả năng quan sát, chưa có bảo đảm như Windows Job Object cho tiến trình con đã tách trước khi được quan sát. Các giới hạn này được giữ rõ trong báo cáo, không thay bằng trạng thái thành công giả.
