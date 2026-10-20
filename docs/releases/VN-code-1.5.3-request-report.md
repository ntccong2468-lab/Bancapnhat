# Báo cáo VNCode 1.5.3

## Thay đổi đã thực hiện

- Znack WB/Ozon lưu lô đăng ký, gửi lần lượt từng thẻ và ghi tiến độ sau khi Catalog nhận feed. Bộ lọc Chưa tạo không coi dữ liệu mới xếp hàng là thẻ đã gửi; Đang xử lý giữ các thẻ chờ duyệt và trạng thái đọc lại từ Catalog.
- Trang đăng ký tiếp tục phản hồi khi chạy lô; báo cáo giữ đầy đủ lựa chọn. Gửi chưa rõ kết quả phải đối chiếu, không tự cấp thêm GTIN hoặc gửi trùng.
- Đề xuất đăng ký Ozon đọc đầy đủ các trường bắt buộc và phụ thuộc của schema Catalog. Article chọn Type «Артикул» khi xác định duy nhất; size dùng hệ Nga khi có size Nga, hoặc hệ quốc tế khi giá trị chữ và schema cho phép. Alias của trường màu, giới tính, chất liệu, danh mục và quy chuẩn được đọc từ dữ liệu sản phẩm; giá trị cố định phải khớp lựa chọn hợp lệ. Không tự đoán hệ size từ một số đơn lẻ.
- Ozon `PRE_SUBMIT_ERROR` đã chắc chắn chưa gửi được phép sửa payload từ dữ liệu/schema/giấy tờ mới và dùng lại GTIN đã cấp. Guard kiểm tra danh tính, dữ liệu đã gửi và chữ ký vẫn giữ; yêu cầu chưa rõ kết quả không được đưa vào đường thử lại này.
- WB đối chiếu lỗi GTIN với article gốc và article đã gửi sau khi trim, đúng sản phẩm và mốc thời gian; article trùng giữa nhiều sản phẩm không được dùng để gán lỗi tùy ý. Khi đọc lại xác nhận GTIN đã lên sàn, báo cáo lưu bằng chứng `marketplaceVerified` trước bước kích hoạt dữ liệu cục bộ. Lỗi kích hoạt cục bộ được giữ riêng theo thẻ, không xóa kết quả trên sàn hoặc làm mất kết quả của các thẻ khác.
- Tiến độ GTIN WB/Ozon đọc lại báo cáo cục bộ của tác vụ nền để cập nhật số đã xác minh, còn thiếu, chưa có GTIN, chờ kết quả và cần xử lý. Bộ quan sát không gửi thêm lệnh ghi lên sàn. Viên thuốc tự tắt sau kết quả cuối, dừng cập nhật khi trang đã rời hoặc shop/kết nối/thư mục dữ liệu đã đổi.
- Hàng đợi tiếp tục khi có mạng hoặc mở lại app. Đóng cửa sổ lúc đang chạy có thể giữ app trong khay hệ thống với nút Mở lại/Thoát; nếu không tạo được biểu tượng khay, cửa sổ vẫn hiện và báo lý do.
- Giảm phần kiểm tra/sao lưu lặp ở khởi động và bàn giao bộ cài, giữ kiểm tra toàn vẹn và dữ liệu SQLite/WAL. Giữ mẫu đăng ký, quy tắc phạm vi giấy tờ/TN VED và dữ liệu KIZ của 1.5.2. Nhánh FBS của WB giữ nguyên; nhánh Ozon bổ sung đối soát đóng gói đang chờ.
- Ozon FBO chuyển ba nhãn riêng của mỗi sản phẩm cần KIZ thành một nhãn ghép 58×40 mm theo mẫu đã duyệt. Lịch sử giữ cả hai mã và định danh đã xác minh để in lại khi không còn Catalog hoặc quyền in mới, không lấy thêm KIZ. Ozon FBS dùng cùng mẫu nhãn ghép và giữ nguyên các trang vận chuyển chính thức.
- Ozon Nhận tất cả gửi mỗi đơn vị hàng trong một package, kể cả nhiều đơn vị cùng SKU, để tạo shipment con thật. Lưu kế hoạch trước khi gửi; chỉ xác nhận sau khi đọc lại đúng mã shipment, đơn nguồn, kho, SKU, số lượng và trạng thái. Đồng bộ nền tiếp tục đọc lại các kết quả đang chờ theo ngân sách đọc riêng, vẫn duy trì cập nhật trạng thái FBS thông thường. KIZ đã gán được bảo vệ trước khi tách.
- Đối soát Ozon nâng cấu trúc checkpoint cũ theo cách bổ sung cột, giữ nguyên receipt đã gửi. Tổng số lượng shipment con chưa khớp được đọc mới lại ở lượt sau, tránh giữ mãi dữ liệu cache cũ sau khi Ozon đã sửa; không gửi lại POST đóng gói.
- Kịch bản Windows bổ sung nâng cấp từ bộ cài Published 1.5.2 với URL và SHA-256 cố định. Bước phát hành bắt buộc xác minh kết quả Java và mọi bằng chứng bộ cài/nâng cấp của đúng source và lượt build được khóa trong biên bản phát hành; không dùng lại build của phạm vi trước khi thêm nhãn Ozon.

## Đọc Catalog thật

Lượt đọc chỉ đọc lúc 09:35 ngày 20/10/2026, giờ Moscow, nhận HTTP 200 và 1.277 thẻ: 1.241 đã xuất bản, 34 lỗi, 2 nháp. Không thực hiện POST tạo thẻ mới trong lượt kiểm tra này. Số lượng đọc được không chứng minh nút Đăng ký đã tạo thành công một thẻ mới.

Lượt đọc lại lúc 11:30 Moscow đọc đủ 1.277 thẻ và chi tiết cả 34 thẻ lỗi; API không trả nguyên văn lý do kiểm duyệt hoặc feed ID của các thẻ đó. Không quy kết toàn bộ 34 lỗi về Type, alias hoặc giấy tờ khi chưa có lý do gốc từ feed. Lượt đối chiếu lúc 11:44 Moscow đọc đủ 388 thẻ WB, 1.982 biến thể qua bốn trang. Trong tập Catalog đã xuất bản đọc bằng API hiện tại, chỉ một biến thể WB có barcode khớp GTIN; 1.981 biến thể còn lại không khớp tập này. Đây là phép so sánh độ phủ mã trong phạm vi dữ liệu đã đọc, không chứng minh sản phẩm chưa đăng ký ở mọi tài khoản hoặc seller dùng sai tài khoản. Bằng chứng chỉ giữ tổng số, HTTP và giới hạn suy luận trong [Catalog chỉ đọc](../validation/2026-10-20-vncode-153-catalog-readonly-recheck.json) và [độ phủ WB–Catalog](../validation/2026-10-20-vncode-153-wb-catalog-coverage.json); không chứa ID thẻ, GTIN hoặc khóa API.

[Đọc lỗi WB và đối chiếu article](../validation/2026-10-20-vncode-153-wb-error-readonly-recheck.json) ghi nhận bảy thông báo khoảng trắng: năm ngày 20/10 và hai ngày 17/10. Cả bảy article có khoảng trắng đầu/cuối, không có va chạm sau trim. 61 thông báo còn lại chỉ thuộc nhóm lỗi phụ thuộc ngày 17/10, không chỉ ra thêm trường sai. Dữ liệu này không chứng minh WB cấm khoảng trắng ở giữa article. Lỗi verifier bỏ sót article gốc đã được sửa trong source cuối; chưa gửi lệnh ghi production để chứng minh WB đã chấp nhận payload trim của bản mới.

## Kiểm tra metadata

`release-version.mjs` xác nhận hai khai báo phiên bản trong `pom.xml` cùng là 1.5.3. Source a684fcd đạt 2.045 kiểm thử Linux, 0 lỗi, 2 bỏ qua theo nền tảng, thuộc 335 bộ kiểm thử; đây là mã trước các sửa Znack/GTIN bổ sung hiện tại. Phạm vi Ozon checkpoint/cache của source này cũng đạt 105 kiểm thử trọng tâm. Các kết quả đó được giữ làm bằng chứng phạm vi trước, không tính là nghiệm thu mã mới đang sửa.

Windows run 39393525107 của source a684fcd thất bại tại nhóm kiểm thử giao diện đầu: 83 kiểm thử, 1 thất bại ở `OzonBulkUiTest` dòng 161. Lượt này chưa chạy toàn bộ Java, chưa tạo bộ cài và chưa qua kiểm tra native/nâng cấp. Predicate chờ hoàn tất có giới hạn thời gian của fixture đã được sửa và bổ sung assertions; chưa ghi nhận đạt trên Windows cho tới khi chạy lại.

Các sửa Type bắt buộc nhiều lựa chọn của Catalog, alias nguồn trong đề xuất Ozon, retry giữ GTIN, bằng chứng GTIN đã xác minh/lỗi kích hoạt WB và báo cáo nền chỉ đọc đã đóng trong source `69ffc7ecb60a91c874510522c4aae3c5be069ccd`. Nhóm trọng tâm ban đầu có 226 kiểm thử với chín thất bại fixture (phản hồi HTTP m/a của trường phụ thuộc và số lượng toàn cục); các fixture đã được sửa trước khi đóng mã. Nhóm kiểm lại đích đạt 23/23: mười ca mock feed, ba ca snapshot báo cáo và mười ca viên thuốc. Hồi quy Linux `clean verify` của đúng source cuối đã đạt 2.072 kiểm thử: 2.070 đạt, không thất bại hoặc lỗi, hai bỏ qua theo nền tảng, thuộc 338 bộ kiểm thử; kết thúc lúc 09:02:42 UTC ngày 20/10/2026. Bộ công cụ phát hành đạt 85/85 kiểm thử Node. Windows [39396769729](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39396769729) đang chạy từ đúng source cuối; chưa ghi nhận bộ cài hoặc bằng chứng native đạt.

So sánh lại tại source cuối 69ffc7e xác nhận bốn đầu vào mẫu đăng ký/giấy tờ/TN VED vẫn trùng byte với 1.5.2. Toàn bộ `FbsSyncService.java` có thay đổi trong nhánh Ozon; đoạn phương thức đồng bộ WB vẫn trùng byte với bản nền. Hash và phạm vi được ghi trong báo cáo kiểm tra cục bộ.

Lượt đọc Ozon thật lúc 11:02 ngày 20/10/2026, giờ Moscow, có năm đơn chờ đóng gói và đều một đơn vị. Không có đơn nhiều đơn vị trong mẫu, không thực hiện POST đóng gói/tách shipment production. Kiểm thử mô phỏng không được ghi nhận là tách shipment thật trên tài khoản seller.

## Chưa hoàn tất

Chưa nghiệm thu thao tác đăng ký/ký thẻ mới trên tài khoản production, chữ ký CryptoPro doanh nghiệp, ghi GTIN thật qua desktop có license production, tách shipment nhiều đơn vị thật trên Ozon hoặc hành vi khay hệ thống trên Windows. Phát hành yêu cầu kiểm tra Windows mới, gồm bàn giao EXE thụ động khi nâng cấp từ 1.5.2, với bằng chứng bộ cài đi kèm.

Phát hành được ràng buộc riêng cho 1.5.3 và đúng source/lượt Windows cuối cùng trong biên bản; chỉ thay Latest sau khi toàn bộ kiểm tra tự động bắt buộc đạt. Publisher 39385466871 đã hủy khi chủ dự án bổ sung mẫu nhãn Ozon, chưa thay Latest. Cổng nghiệm thu production thông thường và kiểm tra license/chữ ký của app giữ nguyên; các phần chưa nghiệm thu ở trên vẫn có cờ false trong báo cáo.

Chủ dự án cấp quyền license không giới hạn cho phiên kiểm thử WB/Ozon. Các kiểm thử chạy bằng authority ký tạm, đủ mọi quyền và không được đóng gói trong app. Quyền kiểm thử không thay thế chứng thư/khóa CryptoPro thực của seller.

Tắt hẳn tiến trình, mất điện hoặc không có mạng không thể tiếp tục gọi API; công việc còn lại được lưu để tiếp tục khi app và kết nối hoạt động lại.
