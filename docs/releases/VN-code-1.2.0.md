# VN code 1.2.0 — mô tả bản cập nhật đang phát triển

Cập nhật mô tả ngày **09/10/2026**, giờ Moscow.

Tham khảo [WCode/Wbcode v1.2.0](https://github.com/rupphi/relatest-wcode/releases/tag/v1.2.0), bản phát hành mới nhất kiểm tra qua GitHub API ngày 09/10/2026. Bản upstream công bố ngày 08/10/2026 và chỉ cung cấp mô tả cùng bộ cài; không có mã nguồn tương ứng để xác nhận VN code giống 100%.

VN code giữ tên **VN code** và là bản cập nhật của ứng dụng VN code Windows hiện có. Không đổi thành Wbcode, không tạo thêm ứng dụng, không cài đè WCode. Giữ định danh bộ cài, thư mục dữ liệu, shop, mẫu tem, KIZ và lịch sử VN code. Repository này chứa bản cập nhật; repository Vncode gốc được giữ nguyên.

## Những thay đổi tham khảo từ bản mới nhất

| Nội dung | Trạng thái VN code |
| --- | --- |
| Bảng màu và thành phần giao diện đồng bộ, dễ đọc | Đã có điều chỉnh nền/chữ/nút; chưa xác nhận tương đương toàn bộ giao diện upstream |
| Dashboard giới thiệu tính năng, khung tin tức và nhãn tin mới | Đã triển khai trong mã nguồn |
| Trang tin tức tải dần, nội dung định dạng và nút quay lại | Đã triển khai; đã sửa giữ vị trí cuộn khi quay lại |
| Chuông đếm tin chưa đọc; tải tin sau khi Dashboard hiện | Đã triển khai trong mã nguồn |
| WB FBS: cách giao, ngày giao, thành phố và điểm nhận | Đã xác minh OpenAPI chính thức; biểu mẫu, lưu lựa chọn theo shop và đối soát thao tác ghi chưa hoàn tất |
| Điểm nhận WB: tìm tiếng Nga không phân biệt hoa/thường, ё/е và tải dần | Đã có lớp kiểm tra/tìm kiếm/phân trang; chưa kết nối đầy đủ vào biểu mẫu |
| WB Đang giao: 20 supply mỗi lần, bỏ supply rỗng | Đã phân trang dữ liệu SQLite; đồng bộ từ API theo yêu cầu 20 supply còn cần hoàn thiện |
| Ảnh WB FBS tải nền, tỉ lệ 3:4 | Có sẵn trong nền mã hiện tại; không coi đây là thay đổi mới đã chứng minh tương đương upstream |
| Ozon: chọn từng đơn/tất cả, chuyển giao và nhắc in nhãn | Chưa hoàn tất; API chuyển trạng thái tìm được giới hạn cho vận chuyển bên thứ ba, không áp dụng chung cho FBS thông thường |
| FBO: nút xuất nhãn bên phải, số lượng và biểu tượng đúng màu | Một phần có sẵn; cần nghiệm thu giao diện Windows |
| Mẫu tem mặc định FBS/FBO cho cài mới | Đã điều chỉnh; không tự ghi đè mẫu đã lưu |
| Lịch sử in cao hơn, checkbox Znack không bị che | Đã điều chỉnh trong mã nguồn |
| Không tự gửi chẩn đoán tới máy chủ WCode | Tiếp tục giữ VN code độc lập; không tích hợp chẩn đoán của nhà cung cấp WCode |
| Lỗi rõ hơn, có mã lỗi và thử lại khi lỗi tạm thời | Đã thêm mã lỗi bản địa hóa và retry có giới hạn cho thao tác đọc WB; không tự lặp thao tác ghi |
| Znack: cấp lại GTIN khi TN VED cấp 1 đổi, xử lý feed từ chối, giữ trạng thái ký | Giữ quy trình bền vững hiện có; chưa bật tự cấp lại GTIN theo thay đổi phần TN VED khi chưa xác minh quy tắc nghiệp vụ |

## Bổ sung riêng của VN code: TN VED EAEU

Người dùng đã duyệt [thiết kế module TN VED](../superpowers/specs/2026-10-09-tnved-eaeu-design.md). Module dự kiến có 21 phần I–XXI, nhập danh mục có nguồn/phiên bản/ngày hiệu lực, tra cứu Việt/Nga offline, liên kết sản phẩm, AI đề xuất có kiểm tra và đánh giá KIZ theo quy định. Đây là yêu cầu riêng của VN code, không phải chức năng đã được chứng minh có trong WCode 1.2.0.

Không tạo mã chi tiết giả; chương 77 không được tạo như chương đang có hiệu lực. AI không tự xác nhận mã hoặc nghĩa vụ pháp lý. Phân loại TN VED, đăng ký sản phẩm, cấp GTIN và mua/gán KIZ là các thao tác khác nhau. Thiết kế được duyệt chưa có nghĩa module đã được triển khai.

## Bộ cài và dữ liệu

- Mục tiêu nâng cấp tại chỗ từ VN code 1.1.34, giữ cùng UpgradeCode và dữ liệu VN code.
- Giữ các mẫu tem người dùng đã lưu; chỉ áp dụng mẫu mặc định mới cho dữ liệu trống.
- Bản cập nhật VN code đầy đủ chưa được phát hành. Không công bố trạng thái ký manifest hoặc Authenticode của WCode như trạng thái của VN code.
- Module TN VED đề xuất database riêng trong thư mục VN code; không thay schema các bảng đơn hàng/KIZ chính. Snapshot và phục hồi module vẫn phải được triển khai và kiểm thử trước khi phát hành.

## Bằng chứng kiểm tra và giới hạn

Commit mã `1ab72ef` đã chạy đạt **565 kiểm thử Java và 25 kiểm thử Node** trong môi trường Linux. Windows CI của commit này chưa đạt toàn bộ: kiểm tra cài song song dừng vì phát hiện thư mục `VNcodeData` đã tồn tại trước probe. Vì vậy chưa coi bộ cài mới nhất đã đạt kiểm tra nâng cấp Windows, dù bản commit trước đã có lần kiểm tra nâng cấp thành công.

Xem [bằng chứng API](VN-code-1.2.0-api-evidence.md) và [trạng thái chức năng](VN-code-1.2.0-status.json). Mô tả này phản ánh tiến độ thực tế; không phải thông báo đã phát hành hoặc cam kết giống 100% mã nguồn upstream.

Bước cập nhật tài liệu ngày 09/10/2026 chỉ sửa mô tả trên GitHub; không sửa mã ứng dụng, không tạo tag/release và không thay bộ cài.
