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
| WB FBS: cách giao, ngày giao, thành phố và điểm nhận | Đã nối biểu mẫu vào trang supply, lưu lựa chọn theo shop, không lưu ngày, checkpoint và đối soát sau ghi; shop phải xác định quốc gia |
| Điểm nhận WB: tìm tiếng Nga không phân biệt hoa/thường, ё/е và tải dần | Đã nối API điểm nhận và tìm kiếm/phân trang 20 dòng vào biểu mẫu; kiểm tra lại điểm đã nhớ theo thành phố/loại hàng |
| WB Đang giao: 20 supply mỗi lần, bỏ supply rỗng | Phân trang SQLite và yêu cầu API theo lô 20; bỏ supply đã xác nhận rỗng, giữ supply chưa biết số lượng |
| Ảnh WB FBS tải nền, tỉ lệ 3:4 | Có sẵn trong nền mã hiện tại; không coi đây là thay đổi mới đã chứng minh tương đương upstream |
| Ozon: chọn từng đơn/tất cả, chuyển giao và nhắc in nhãn | Chưa hoàn tất; API chuyển trạng thái tìm được giới hạn cho vận chuyển bên thứ ba, không áp dụng chung cho FBS thông thường |
| FBO: nút xuất nhãn bên phải, số lượng và biểu tượng đúng màu | Một phần có sẵn; cần nghiệm thu giao diện Windows |
| Mẫu tem mặc định FBS/FBO cho cài mới | Đã điều chỉnh; không tự ghi đè mẫu đã lưu |
| Lịch sử in cao hơn, checkbox Znack không bị che | Đã điều chỉnh trong mã nguồn |
| Không tự gửi chẩn đoán tới máy chủ WCode | Tiếp tục giữ VN code độc lập; không tích hợp chẩn đoán của nhà cung cấp WCode |
| Lỗi rõ hơn, có mã lỗi và thử lại khi lỗi tạm thời | Đã thêm mã lỗi bản địa hóa và retry có giới hạn cho thao tác đọc WB; không tự lặp thao tác ghi |
| Znack: cấp lại GTIN khi TN VED cấp 1 đổi, xử lý feed từ chối, giữ trạng thái ký | Giữ quy trình bền vững hiện có; chưa bật tự cấp lại GTIN theo thay đổi phần TN VED khi chưa xác minh quy tắc nghiệp vụ |

## Bổ sung riêng của VN code: TN VED EAEU

Người dùng đã duyệt [thiết kế module TN VED](../superpowers/specs/2026-10-09-tnved-eaeu-design.md). Module đã có 21 phần I–XXI, nhập CSV/JSON có xác nhận nguồn/phiên bản/ngày hiệu lực, tra cứu Việt/Nga offline, xem chi tiết và sao lưu. Liên kết sản phẩm, AI, đánh giá KIZ theo bộ quy tắc, Yandex và phục hồi tích hợp chưa hoàn tất. Đây là yêu cầu riêng của VN code, không phải chức năng đã được chứng minh có trong WCode 1.2.0.

Không tạo mã chi tiết giả; chương 77 không được tạo như chương đang có hiệu lực. Mã chi tiết chưa nhập hiển thị “Chưa tải dữ liệu”. Phần gốc có tên Nga theo văn bản đã đọc; ngày hiệu lực chưa xác minh không bị tự điền. AI không tự xác nhận mã hoặc nghĩa vụ pháp lý. Phân loại TN VED, đăng ký sản phẩm, cấp GTIN và mua/gán KIZ là các thao tác khác nhau.

## Bộ cài và dữ liệu

- Mục tiêu nâng cấp tại chỗ từ VN code 1.1.34, giữ cùng UpgradeCode và dữ liệu VN code.
- Giữ các mẫu tem người dùng đã lưu; chỉ áp dụng mẫu mặc định mới cho dữ liệu trống.
- Bản cập nhật VN code đầy đủ chưa được phát hành. Không công bố trạng thái ký manifest hoặc Authenticode của WCode như trạng thái của VN code.
- Module TN VED dùng database riêng trong thư mục VN code; không thay schema các bảng đơn hàng/KIZ chính. Sao lưu trước import bằng SQLite VACUUM INTO và checksum đã có; tích hợp vào quy trình phục hồi toàn ứng dụng chưa hoàn tất.

## Bằng chứng kiểm tra và giới hạn

[VN code 1.2.0 Preview 1](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.2.0-preview.1) đã phát hành ngày 09/10/2026. [Windows CI 37865887385](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37865887385) đạt tại commit `e7c4c9e1031f64d576cb696b9a7fa396fc7c86a3`: 589 kiểm thử Java/JavaFX, 27 kiểm thử Node, launcher, migration, cài song song và nâng cấp VN code 1.1.34 giữ dữ liệu. [Workflow phát hành 37866526374](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/37866526374) xác minh artifact rồi tải lên GitHub; không tạo lại bộ cài.

Bộ cài Windows x64: `VN-code-1.2.0-preview.1-Windows-x64.exe`, 141078016 byte. SHA-256: `2290088fb9e1065430f4d9c5e9b47185c5581a93996b3ad692e8bbc846756ad1`. Release có checksum, build-info, trạng thái chức năng và ba kết quả kiểm tra Windows. Bộ cài chưa ký Authenticode; chưa nghiệm thu thao tác ghi với shop thật hoặc máy in thật.

Xem [bằng chứng API](VN-code-1.2.0-api-evidence.md) và [trạng thái chức năng](VN-code-1.2.0-status.json). Đây là bản thử nghiệm của cùng ứng dụng VN code; bản đầy đủ chưa hoàn tất, chưa có cam kết giống 100% upstream. Bản hoàn thiện tiếp theo cần số phiên bản Windows cao hơn 1.2.0 để nâng cấp bản thử tại chỗ.

Bản thử nghiệm chỉ được phát hành qua `publish-vncode-preview.mjs` với tag `v1.2.0-preview.N`, CI thành công đúng commit và các kiểm tra bộ cài đạt. Phải công bố toàn bộ phần còn thiếu; không phát hành manifest hoặc đưa preview vào cập nhật tự động. Công cụ phát hành đầy đủ tiếp tục chặn trạng thái `complete: false`.
