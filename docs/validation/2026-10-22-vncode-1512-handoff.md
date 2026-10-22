# Bàn giao VNCode 1.5.12 — 22.10.2026

## Trạng thái cuối — hoàn tất

**1.5.12 đã lên Latest**, Release [v1.5.12](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.12), công bố `2026-10-22T21:52:20Z`, ID `421236216`, bất biến. [Tải EXE Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.12/VN-code-1.5.12-Windows-x64.exe).

- Mã ứng dụng/tag: `2f9f194d26a9d840e9ab1097f51d91811abc3bbf`. Các commit sau đó chỉ sửa quy trình công bố/tài liệu; diff `src/main`, pom và build script so với tag rỗng.
- [Windows build](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39851955943) đạt: **2.923/2.925 ca, 2 ca bỏ qua theo OS, 0 thất bại/lỗi**. Native launch, migration, DPAPI, cài/gỡ và nâng cấp từ Latest 1.5.11 giữ dữ liệu đều đạt. Hai ca bỏ qua: quyền POSIX và kiểm tra fallback khi không ở Windows.
- [Công bố/xác minh](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/39860902344) đạt; tất cả tài sản bắt buộc đã tải công khai và đối chiếu byte/digest/checksum/tag. Bộ cài EXE 147.192.320 byte, SHA256 `7d01b89677d59b29527d01a519d6b06bef6ca1689b1aec88e91d9665168699a6`.
- Kiểm tra JAR thật: version 1.5.12, CSS/FXML/logo/Inter Bold mới và lớp CatalogVersionSource có trong runtime. Bộ cập nhật của đúng JAR này phân tích phản hồi Latest thật, chọn đúng MSI và khớp digest/kích thước. Chưa quan sát trực tiếp thông báo trên thiết bị seller.
- Upload từ Codex báo 401 riêng ở uploads.github.com. Đã dùng Actions để upload nguyên artifact đã kiểm tra. GitHub từ chối thêm biên nhận sau Publish vì Release bất biến (422); đã sửa workflow giữ biên nhận trong repo/Actions và chạy lại thành công, không thay bộ cài hay hạ Latest.
- Không xóa Release cũ. Bộ nhớ/quyết định/kinh nghiệm đã cập nhật ở [PROJECT_MEMORY](../PROJECT_MEMORY.md). Không thực hiện giao dịch mua có phí hoặc ghi tài khoản seller production.

Bằng chứng: [biên nhận Latest](2026-10-22-vncode-1512-published-latest.json), [tải công khai](2026-10-22-vncode-1512-public-verification.json), [Windows build-info](2026-10-22-vncode-1512-windows-build-info.json), [đối chiếu artifact/runtime](2026-10-22-vncode-1512-candidate-verification.json), [bộ cập nhật](2026-10-22-vncode-1512-updater-metadata.json), [nâng cấp](2026-10-22-vncode-1512-upgrade-smoke.json).

Phần dưới lưu trạng thái trước đóng gói tại commit nguồn. Bốn bước phát hành được liệt kê ở đó đã hoàn tất.

## Trạng thái trước đóng gói

Hoàn tất luồng Znack/đồng bộ trên nền 1.5.11 trước khi làm giao diện. PR #12 và commit `017412f` chứa phần luồng; commit `b27d799` chứa giao diện tham chiếu và 5 tab. Nhánh phát hành: `release/vn-code-1.5.12`.

Chủ dự án yêu cầu hoàn tất giao diện, kiểm tra lại mua KIZ/GTIN/đăng ký, tự sửa lỗi, sau đó build Windows và đưa lên Latest, lưu bộ nhớ và push. Không có yêu cầu xóa các Release cũ hoặc thực hiện một giao dịch mua KIZ có phí mới.

## Đã thực hiện

- Giao diện đen/nút xám/tím/logo VN đen chữ trắng cho các màn hình đã giao; giữ ảnh đúng tỉ lệ, báo cáo chi tiết và chức năng hiện có. Cấu hình Znack có 5 tab, Kho KIZ nhập file dùng kho hiện hữu.
- Kiểm tra luồng mua: UUID hợp lệ, kiểm kho trước khi mua thiếu, giữ idempotency khi mất phản hồi, đối soát đơn/mã và lưu thông; không mua lại yêu cầu đang chờ. WB/Ozon tiếp tục dùng adapter và phạm vi shop riêng.
- Kiểm tra lại đăng ký, Catalog và GTIN, gồm 10.000 GTIN WB/Ozon qua planner/adapter/SQLite thực và API giả lập. WB ghi 4 lô, Ozon 100 lô; mỗi lô 10.000 được đọc lại xác nhận đủ trong mô phỏng.
- Sửa nhãn Barcode WB/GTIN chưa dịch theo locale, giữ dòng phản hồi cấu hình khi có nội dung và chiều cao tối thiểu thẻ trang chủ. Cập nhật kiểm thử thiếu giấy tờ để mở đúng hộp Cấu hình giấy tờ được đọc bất đồng bộ, đóng bằng đúng nút Hủy đã tạo.
- Sửa khóa YAML bị lặp trong hai workflow hiện có; không chạy kiểm tra bản Wbcode lịch sử. Kiểm tra cú pháp tất cả YAML workflow và công cụ phát hành.
- Lưu quyết định và kinh nghiệm ở [PROJECT_MEMORY.md](../PROJECT_MEMORY.md), thêm liên kết từ README/CLAUDE để phiên sau tìm lại.

## Bằng chứng trước build

**1.475 ca / 148 lớp Java đạt**, là kết quả mới nhất của mỗi lớp sau hồi quy và các lượt chạy lại có mục tiêu; **117 ca công cụ Node đạt**. FXML smoke: 55 ca đạt. Bố cục Java/FXML qua nhiều locale/kích thước: không phát hiện nút cố định vượt viewport. Xem [kết quả máy đọc](2026-10-22-vncode-1512-prebuild.json) và [ảnh/kiểm tra UI](../fixes/2026-10-22-dark-reference-ui.md).

Các lỗi ban đầu đã được sửa và các lớp liên quan chạy lại đạt; không tính một lượt thất bại là thành công. Đây chưa phải bằng chứng toàn bộ ứng dụng chạy một lượt trên Windows. Workflow `publish-vncode-baseline.yml` sẽ chạy `clean verify`, đóng gói và kiểm tra native/nâng cấp từ Latest đã pin trước khi được công bố.

Không thực hiện ghi Catalog/WB/Ozon production, ký bằng chứng thư seller hoặc mua KIZ có phí trong phiên này. Các cờ live trong build-info phải tiếp tục phản ánh giới hạn này.

## Việc phát hành còn lại tại mốc commit này

1. Push SHA nguồn và chạy workflow bản nền đầy đủ trên Windows, sửa mọi kiểm tra thất bại nếu có.
2. Kiểm tra đúng SHA, version, toàn bộ kết quả Java, native smoke, nâng cấp và checksum trong artifact cuối.
3. Tạo Release v1.5.12, tải đầy đủ EXE/MSI/runtime/source/history/verification lên và đặt Latest; không chạy `prune`.
4. Đọc lại API Latest, tải tài sản công khai để kiểm tra digest, đối chiếu manifest/tag, lưu biên nhận và cập nhật README/bộ nhớ/bàn giao rồi commit/push tài liệu cuối.

Bản đã build phải gắn với tag/SHA nguồn bất biến. Commit bàn giao sau công bố chỉ cập nhật bằng chứng/tài liệu, không thay binary đã kiểm tra.
