# Rà soát và sửa lỗi VN Code trước bản phát hành kế tiếp

Nguồn đối chiếu `f42b019` trên nhánh `design/unslop-audit-20261122`. Commit `439274a` lưu mốc giao diện Unslop và mã SHA-256 của bộ 122 ảnh, gồm 64 màn, tab và hộp thoại. Bản Latest đã kiểm trực tiếp là `v1.6.13`. [Mốc giao diện](2026-11-22-unslop-release-checkpoint.md) · [Latest](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.13).

## Các lỗi đã sửa

| Luồng | Lỗi tái hiện trên mã gốc | Cách xử lý |
| --- | --- | --- |
| License | Kiểm tra trạng thái xếp sau kích hoạt có thể đọc trạng thái đăng ký cũ và ghi đè phiên vừa kích hoạt. | Chọn đường kiểm tra bằng phiên tại thời điểm worker thực thi và dùng quy trình gia hạn đã xác minh chữ ký. |
| Tải cập nhật | Bấm Hủy khi đang chờ dữ liệu HTTP để tải bộ cài không đánh thức socket đang chặn. | Hủy đúng kết nối tải, xóa tệp tạm và trả quyền thao tác cho ứng dụng. |
| Tải cập nhật đang chờ | Đóng hộp thoại trước khi worker bắt đầu có thể để tác vụ tiếp tục chạy trong nền. | Hủy và rút tác vụ khỏi hàng đợi, giữ khóa bảo vệ lúc trình cài thực sự đang chạy. |
| WB giao lô | WB đã báo giao thành công nhưng lỗi đọc trạng thái ngay sau đó làm app báo giao thất bại. | Giữ xác nhận giao lô, lên lịch đọc lại và bỏ qua thao tác giao lặp với lô đã hoàn tất. |
| WB tạo lô | Tạo lô từ màn đang dùng khóa cũ có thể gọi sàn trước khi app phát hiện chủ sở hữu đã đổi. | Xác minh dữ liệu cửa hàng hiện hành trước lệnh tạo lô. |
| Sản phẩm | Tìm kiếm vừa đổi vẫn có thể kích hoạt tải trang bằng con trỏ cũ. Sau lỗi tải, có thể chọn sản phẩm từ bộ lọc trước. | Gắn phân trang và thao tác chọn, sửa, sao chép với kết quả của truy vấn đang hiển thị. |
| Sao chép bài đăng | WB trả HTTP 200 kèm lỗi nghiệp vụ và Ozon trả HTTP 429 đều có thể bị đưa vào trạng thái đối soát không rõ kết quả. | Báo từ chối xác định của sàn, cho phép thử lại khi có phản hồi giới hạn tốc độ và giữ đối soát cho lỗi chưa biết kết quả. |
| CryptoPro | Ngày hết hạn kèm MSK, UTC hoặc GMT có thể bị đổi sang múi giờ máy. | Dùng múi giờ ghi trên chứng thư để xác định thời điểm hết hạn. |
| Hàng đợi Catalog | Tác vụ chờ đã hết hạn hoặc bị hủy vẫn có thể chiếm hết 20 vị trí. | Gỡ tác vụ khỏi hàng đợi ngay khi hủy và giải phóng bộ đếm đúng một lần. |
| Sửa lỗi thẻ Catalog | Hết thời hạn đọc báo cáo hoặc áp dụng sửa lỗi có thể giữ spinner và khóa nút. | Dọn trạng thái bận khi tác vụ kết thúc, báo nguyên nhân một lần và tải lại trạng thái nếu thao tác đã bắt đầu. |
| Tin tức | Đổi cấu hình dịch trong lúc tải tin có thể làm mất kết quả và giữ nút làm mới ở trạng thái bận. | Tách vòng đời tải tin khỏi lần dịch đang thực hiện. |
| Thiết kế mẫu in | Lưu, đổi tên hoặc khôi phục mẫu thứ hai có thể chuyển về mẫu mặc định. | Giữ mã của mẫu đang chỉnh qua mỗi lần ghi và tải lại danh sách. |
| Tài chính Ozon | Nút đồng bộ báo cáo phạt có thể bấm dù dịch vụ trong app chỉ có nguồn WB. | Vô hiệu hóa nút khi đã chọn Ozon và hiển thị trạng thái nguồn dữ liệu. |
| Tra TN VED | Nhấp đúp vùng trống của danh sách có thể kích hoạt áp dụng. | Chỉ áp dụng bằng nhấp đúp trên ô có mã hoặc bằng nút áp dụng. |

## Phạm vi rà soát

- Khởi động và sao lưu SQLite, chuyển cửa hàng, phiên license, cập nhật ứng dụng, cài đặt, và trang quản lý license. Thao tác kiểm bằng hồ sơ thử nghiệm cục bộ.
- WB và Ozon FBS/FBO, chọn nhiều đơn, lô WB, đơn nợ, phục hồi khi sàn phản hồi một phần, gán KIZ, in theo lô, file nhặt hàng, lịch sử in và thiết kế mẫu. Đối chiếu điểm vào từ controller đến dịch vụ và kiểm thử dùng HTTP giả lập khi cần.
- Danh mục sản phẩm WB/Ozon, phân trang, lọc, năm lệnh sửa sản phẩm, xác minh giấy tờ và sao chép bài. Kiểm phạm vi thao tác và phân loại phản hồi từ sàn.
- Đăng ký Znack, giấy tờ, theo dõi feed, ghép GTIN WB/Ozon, GS1 RUS, ký, mua và lưu thông KIZ, trạng thái theo từng cửa hàng. Kiểm qua fixture API, nhật ký thao tác và điều kiện chủ sở hữu.
- Tổng quan, tài chính và tiền phạt, danh sách công việc, tin tức, hướng dẫn, tra TN VED, và điều hướng JavaFX. Kiểm bố cục và hành vi nút trong các ca hồi quy liên quan.

Các bước sửa dùng Code Doctor để giữ ca tái hiện trên nguồn gốc, rồi chạy lại bằng mã đã sửa. Dùng ECC để rà ảnh hưởng giữa các module và OpenQodex để quét thông tin bí mật trong thay đổi. Thư mục chạy quét đặt ngoài repo.

Lượt hồi quy diện rộng phát hiện ba bộ kiểm thử cuối luồng Znack gọi chữ ký năm tham số của hộp thoại duyệt nguồn. Mã ở mốc giao diện đã có tham số chế độ ký thứ sáu. Đã cập nhật cách gọi của các ca WB và Ozon, giữ các kiểm tra nghiệp vụ và chạy lại qua hộp thoại thật trên JavaFX. [Tái hiện](/workspace/reports/vncode-app-audit-20261122/catalog-review-e2e-red.log) · [Chạy lại](/workspace/reports/vncode-app-audit-20261122/catalog-review-e2e-green.log).

Hai ca mở popup JavaFX chưa đạt khi ba JVM cùng dùng một màn hình ảo. Tái hiện chuyển tiêu điểm sang cửa sổ thứ hai làm popup tự đóng. Cả hai bộ kiểm thử đạt trên màn hình riêng 1920 × 1080 với chính các lớp Java đã biên dịch từ mã gộp. [Tái hiện tiêu điểm](/workspace/reports/vncode-app-audit-20261122/native-evidence-investigation/focus-probe.log) · [Menu quản lý](/workspace/reports/vncode-app-audit-20261122/native-evidence-investigation/exact-1920-rerun.log) · [Popup đổi nền](/workspace/reports/vncode-app-audit-20261122/native-evidence-investigation/theme-1920-rerun.log).

Ca cấu hình giấy tờ của Znack dùng sai đối tượng nút Hủy và để hộp thoại cha mở sau khi thất bại. Ca kiểm mục quốc gia chạy kế tiếp đã chọn nhầm hộp thoại đó. Hai lỗi có cùng kết quả trên nguồn gốc và mã gộp. Đã sửa cách chọn nút Hủy theo vai trò và dọn hộp thoại của ca kiểm thử, sau đó chạy lại cả hai nhóm trong một JVM. [Tái hiện trên nguồn gốc](/workspace/reports/vncode-app-audit-20261122/catalog-geometry-baseline.log) · [Chạy lại](/workspace/reports/vncode-app-audit-20261122/catalog-geometry-green.log).

## Tình huống lớn đã xác minh

- Ozon FBS nhận 101 đơn với 101 KIZ khác nhau trong fixture HTTP cục bộ. Các ca xuất PDF đủ 100 và 1.000 đơn, theo đúng thứ tự và có một lần yêu cầu tạo lô cho mỗi lượt. Ca nhãn kết hợp kiểm riêng PDF 100 đơn với 200 trang cho mỗi lượt xuất và không tải từng nhãn. Thời gian đo trong máy kiểm, với ảnh và KIZ tắt trong phép đo tốc độ, là 2,85 giây cho 100 đơn và 17,03–30,20 giây cho 1.000 đơn.
- Lô đăng ký Znack 300 biến thể gửi một lần cho mỗi mục qua HTTP cục bộ, phục hồi từ sổ theo dõi sau khi khởi động lại, đọc lại được 298 thẻ và ghi rõ hai mục lỗi hoặc chưa rõ kết quả được cài trong fixture. Ca 10.000 mục kiểm cố định vùng chọn, lưu hàng đợi, tạm dừng và mở lại dữ liệu đã nhận. Ca này không gửi 10.000 thẻ.
- Nhóm Công cụ kiểm 912 vị trí nút trên 16 màn hình, bốn ngôn ngữ, hai giao diện và hai kích thước cửa sổ. Phép kiểm không tìm thấy nút bị khuất.

## Kết quả và bằng chứng

| Lượt chạy | Kết quả | Nhật ký |
| --- | --- | --- |
| Hồi quy desktop trên Linux và các lượt chạy lại | 5.344 ca trong lượt chính, gồm 27 ca ban đầu chưa đạt và 2 ca chỉ chạy trên Windows. Bảy bộ chứa 27 ca chưa đạt đều đạt khi chạy lại sau khi sửa cách gọi hộp thoại và tách màn hình ảo. Cộng 11 ca GTIN chạy riêng, tổng cộng 5.353 ca khác nhau đạt và 2 ca bỏ qua. | [Lượt chính ban đầu](/workspace/reports/vncode-app-audit-20261122/final-regression.log) |
| Java 25, JavaFX/Xvfb và `mvn -o verify` với các ca hồi quy của mã đã gộp | 102 ca đạt, 0 lỗi hoặc bỏ qua. JAR kiểm nghiệm khớp 2.170 lớp và tài nguyên với đầu ra biên dịch. Manifest chỉ đúng trình khởi động và đủ 74 thư viện. | [Maven](/workspace/reports/vncode-app-audit-20261122/integrated-fixes.log) |
| WB/Ozon FBS, FBO và in lô | 244 ca đạt. | [Maven](/workspace/reports/vncode-marketplace-audit-20261122/marketplace-green.log) |
| Sản phẩm, sao chép và GTIN | 530 ca đạt, cộng 32 ca phân trang dịch vụ WB/Ozon đạt. | [Hồi quy](/workspace/scratch/vncode-products-audit-20261122/green.log) · [Phân trang](/workspace/scratch/vncode-products-audit-final-20261122/pagination.log) |
| Znack, GS1, OMS và CryptoPro | 236 ca nghiệp vụ và 33 ca Catalog đạt. | [Nghiệp vụ](/workspace/reports/whole-app-audit-20261122/znack/focused-green.log) · [Catalog](/workspace/reports/whole-app-audit-20261122/znack/catalog-cancel-green.log) |
| Duyệt nguồn WB, tạo và thay thẻ Ozon, lựa chọn chế độ ký | 38 ca đạt sau khi sửa bộ kiểm thử gọi đúng hộp thoại. | [Hồi quy](/workspace/reports/vncode-app-audit-20261122/catalog-review-e2e-green.log) |
| GTIN lô và phục hồi lỗi | 11 ca của `GtinBulkPipelineTest` đạt, gồm phản hồi một phần, mất kết nối, phản hồi 429, không gửi lại khi kết quả chưa rõ và giữ trạng thái chờ xác nhận. | [Maven](/workspace/reports/vncode-app-audit-20261122/gtin-bulk-faults.log) |
| License, khởi động, sao lưu và cập nhật | 226 ca đạt trong lượt rộng, 43 ca đạt khi kiểm lại license và cập nhật sau bản sửa cuối. | [Lượt rộng](/workspace/reports/vncode-lifecycle-audit-20261122/lifecycle-tests.log) · [Bản sửa cuối](/workspace/reports/vncode-lifecycle-audit-20261122/queued-download-green.log) |
| Tài chính, mẫu in, lịch sử, tin tức, hướng dẫn và TN VED | 128 ca đạt. | [JavaFX](/workspace/reports/vncode-full-audit-20261122/tools/expanded-independent.log) |
| Công cụ phát triển và cổng tài khoản | Node 280 ca đạt. Portal kiểm cú pháp và 72 ca đạt. | [Công cụ](/workspace/reports/vncode-app-audit-20261122/node-tools.log) · [Portal](/workspace/reports/vncode-app-audit-20261122/portal.log) |
| OpenQodex 0.16.0 | Gitleaks 8.21.2 đã quét 34 tệp chỉnh sửa và không phát hiện bí mật. Semgrep được tắt trong lượt quét ngoại tuyến. | [Log](/workspace/reports/vncode-app-audit-20261122/openqodex-verified.log) |

Các lượt theo nhóm có ca trùng nhau. Ba ca tải 10.000 GTIN của `GtinBulkPipelineTest` chưa có kết quả trong lượt kết luận này. Kiểm thử lô 10.000 ở mục Znack chỉ xác nhận luồng chọn, lưu và khôi phục hàng đợi đăng ký.

Phạm vi thay đổi gồm 30 tệp Java sản xuất và kiểm thử. Phiên bản và cấu hình phát hành không thay đổi. Dữ liệu kiểm thử là hồ sơ tạm, API cục bộ và dữ liệu tổng hợp. Hai ca chỉ chạy trên Windows kiểm bảo vệ dữ liệu DPAPI và lưu mẫu tiền tố GS1. Nghiệm thu nâng cấp MSI, thiết bị in và CryptoPro thật, và giao dịch với tài khoản seller vẫn chưa thực hiện. Mốc này được lưu để dùng cho bản kế tiếp. Chưa tạo bộ cài phát hành hoặc chuyển Latest.
