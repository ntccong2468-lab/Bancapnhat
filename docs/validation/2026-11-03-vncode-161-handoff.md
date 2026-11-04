# Bàn giao bản gộp VN Code 1.6.1 — 03.11.2026

<!-- PUBLICATION_START -->
**Đã phát hành [VN Code 1.6.1](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.1) lên Latest lúc 00:27:59 UTC ngày 04.11.2026.** Mã ứng dụng bất biến `1786c2c30e38c975523f9b678c4e77d203b5a7cb`. [Biên nhận phát hành và kiểm tra công khai](2026-11-04-vncode-161-published-latest.json).

Đủ 16 tệp đã được tải công khai và kiểm tra SHA-256/kích thước; bộ cập nhật lấy từ binary 1.6.0 nhận đúng 1.6.1 và đúng MSI. Chưa quan sát popup trên máy seller. Các bản phát hành cũ được giữ lại. Bản gộp dùng số 1.6.1 vì 1.6.0 đã công bố.
<!-- PUBLICATION_END -->

## Phạm vi đã gom

Tất cả nhánh sửa liên quan đều là tổ tiên của mã ứng dụng chốt; không còn thay đổi ứng dụng chưa commit trong các worktree tương ứng:

| Nhóm | Mã/tài liệu |
| --- | --- |
| Nền ứng dụng đã phát hành 1.6.0 | `07e2b7d` |
| Audit độ ổn định và OpenQodex | `042b6d9`, `43907af` |
| FBS Ozon, phiếu nhặt hàng, KIZ seller theo danh mục/kho mua theo GTIN, Tổng quan giao dịch phạt | `b46a674`, `c200200` |
| Năm nút sửa sản phẩm, phiên bản dữ liệu Ozon, đăng ký Catalog và nghiệm thu ghi thật | `4b3cfa7`, `f69f125`, `78c9b19`; [biên bản API](2026-11-03-product-edit-catalog-live-acceptance.md) |
| Đọc Ozon theo nhóm, PDF task theo lô, tách phần KIZ đã xác minh, cache ảnh nhặt hàng | `6d9b277`, `6f302cf`, `8b28133`, `30afa28`; [báo cáo PDF](2026-11-03-ozon-batch-pdf-audit.md) |
| UI UX Pro Max toàn app, nền tối/sáng, danh sách rộng, progress dành chỗ riêng, accessibility và cột giá Ozon | `a250e2f`; [92 ảnh và báo cáo giao diện](2026-11-03-ui-ux-pro-max/README.md) |
| Phiên bản 1.6.1, khóa đồng hồ license, fixture nhận đơn seller và FXML dùng cùng loader i18n của app | `e78e91e`, `df10e5c`, `24f9761` |

[Ghi chú đầy đủ theo tính năng](../releases/VN-code-1.6.1.md). Giữ toàn bộ thao tác hiện có; không chuyển desktop sang web và không thay adapter nghiệp vụ khi đổi theme. Prototype migration mã hóa credentials vẫn là tài liệu thử nghiệm ngoài runtime. Source portal đi kèm trong Git; lượt phát hành desktop không triển khai website.

## Xác minh

- Linux/Xvfb trên đúng `24f9761`: `clean verify` **3.813 ca, 0 thất bại, 0 lỗi, 2 bỏ qua theo OS**, 518 lớp, 35 phút 03 giây. Hai ca bỏ qua yêu cầu Windows DPAPI/NTFS junction; [biên nhận](2026-11-03-vncode-161-linux.json).
- Linux trên `01b87d3`: 11/11 ca mục tiêu cho hai fixture Windows đạt; không chạy lại full Linux vì `src/main`, POM, wrapper và build script không đổi. Full Windows đã chạy lại toàn bộ trên nguồn chốt `1786c2c`. [Chi tiết nguyên nhân và sửa](2026-11-03-windows-release-fixtures.md).
- Kiểm tra bổ sung fixture trên nhánh xuất bản: Retry-After 120 giây trong ngân sách transport, worker phải nhả trong 30 giây, giữ deadline và chống lặp; 11/11 Linux đạt. Windows bổ sung [41736451413](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41736451413) đạt 11/11 trên nguồn kiểm thử `4fa8e6a`, ca cooldown 7,008 giây; CI bổ sung bắt buộc runtime/build inputs giống nguồn ứng viên01b. [Biên nhận](2026-11-03-vncode-161-cooldown-windows.json). Kết quả này là bằng chứng bổ sung lịch sử; nguồn phát hành1786 đã chứa fixture120 giây và đạt full Windows riêng. [Phân biệt hai nhánh cooldown](2026-11-03-windows-release-fixtures.md).
- Linux sau sửa thiết lập khóa SQLite: **58/58** kiểm thử FXML đạt, không lỗi/bỏ qua; 38,607 giây gồm Maven. Giữ giới hạn 300 ms cho thao tác giao diện dưới khóa.
- Công cụ phát hành Node **138/138**, Python search skill **39/39**. Các kiểm thử mục tiêu UI trước full suite: 143 ca riêng biệt, 26 view/pane × bốn ngôn ngữ × hai theme × hai cỡ, thêm năm danh sách có dữ liệu ở ba cỡ. Không cộng các nhóm mục tiêu này vào tổng full suite vì có giao nhau.
- 92 ảnh là view JavaFX thật dùng dữ liệu tổng hợp; không phải chứng cứ đã ghi API thành công. Các danh sách tăng đúng chiều cao vùng làm việc; kiểm tra giá Ozon, focus, giữ dữ liệu/lựa chọn khi tiến trình hiện/ẩn.
<!-- WINDOWS_START -->
- Windows trên đúng `1786c2c`: **3.813 ca, 0 thất bại, 0 lỗi, 2 bỏ qua theo OS**, 518 lớp, 59 phút 10 giây; preflight 69/69. Ca khóa SQLite đã sửa đạt, ca cooldown 120 giây nhả worker trong 3,975 giây. [Biên nhận Windows](2026-11-04-vncode-161-windows.json).
- Native launcher, migration/snapshot, bảo vệ dữ liệu Windows, cài/gỡ độc lập và nâng cấp thụ động bằng đúng EXE từ **1.6.0** đều đạt; xác minh MSI nhúng và dữ liệu trước/sau nâng cấp. [Build 41738169431](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41738169431).
- Đối chiếu 2.626 tệp source ZIP với nguồn Git: 2.401 tệp văn bản chỉ khác CRLF của Windows, không khác nội dung; tệp nhị phân trùng byte. JAR đúng 1.6.1, có lớp/CSS mới, không còn hai CSS xung đột. Không gọi đây là toàn bộ archive trùng byte. [Biên nhận đóng gói](2026-11-04-vncode-161-package.json).
- [16 tệp tải công khai](2026-11-04-vncode-161-public-assets.json) khớp biên nhận CI và checksum. [Bộ cập nhật của 1.6.0](2026-11-04-vncode-161-updater.json) đọc metadata công khai, báo có bản 1.6.1, chọn MSI và khớp hash/kích thước.
- Publisher [41746710521, lần 2](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/41746710521) đạt. Lần đầu dừng ở nhận diện draft/tag, chưa upload; sửa nhãn bản nháp, bỏ đúng một draft trùng rỗng do lượt đó tạo và chạy lại riêng publisher. Không dựng lại hoặc thay byte bộ cài đã kiểm chứng.
<!-- WINDOWS_END -->

## Nghiệm thu thật và giới hạn

- Đã ghi API thật 10/10 lượt cho năm trường WB/Ozon và đọc lại xác nhận, bằng cách gửi lại các giá trị đang có. Chưa thử mọi danh mục, giấy tờ mới hoặc mọi giá trị kinh doanh khác.
- Đã xin đúng một GTIN và gửi đúng một feed Catalog; đọc lại đúng thẻ ở `draft / moderation`. Chưa ký/công bố thẻ này, chưa đẩy GTIN mới lên sàn. Không chạy lại allocation/feed chỉ vì đọc 404 ban đầu hoặc mất tệp journal.
- PDF Ozon thật: một đơn, hai trang, một tác vụ, không tải riêng; tạo mới 6,111 giây. In lại cùng tác vụ ở tiến trình mới có cache 4,989 giây. Lô 100/1.000 là HTTP/PDF giả lập, không phải cam kết tốc độ trên sàn thật.
- Nhãn vận đơn được Ozon cấp vẫn được xuất khi thiếu KIZ/mapping, nhưng không bịa KIZ, không ghép KIZ theo thứ tự trang và không báo đơn đã nhận/in nếu API từ chối. Quyết định trạng thái/điều kiện đơn vẫn thuộc Ozon. Chỉ ghi nhận kết quả thực và phần còn thiếu.
- Lượt đóng gói dùng dữ liệu tạm, không kích hoạt license thật, mua/tiêu KIZ, ký Catalog hoặc nhận đơn khách. Chưa quan sát popup cập nhật trên máy seller, mọi DPI/máy in hay screen reader Windows.
- [Rà soát bảo mật](2026-11-03-release-security-review.md): CodeQL Java vẫn báo 1 high ở định danh SHA-1 của chứng thư Windows/CryptoPro và 7 medium. High này đã được rà theo mục đích thumbprint, không phải thuật toán ký CMS; gate vẫn báo thất bại, không sửa rule/ngưỡng để biến thành xanh. Lint, audit npm và CodeQL JavaScript đạt. Không ghi “mọi kiểm tra bảo mật đã đạt”. Bộ cài giữ trạng thái Authenticode như các bản trước.

## Kinh nghiệm và tiếp tục

- Lượt full ban đầu chỉ ra hai fixture không còn đúng đường chạy: seller-only test chưa gắn danh mục seller; FXML test bỏ qua loader i18n của production. Sửa fixture theo dữ liệu/loader thật của app, giữ các assert thiếu kho, snapshot/resume và thao tác UI. Các ca liên quan đạt trước khi chạy lại full; không xóa test hoặc bỏ điều kiện API/KIZ.
- Full suite có bài 10.000 GTIN chạy lâu; log có thể dừng ở tên lớp trong khi SQLite/API giả lập vẫn hoạt động. Cần xem thời gian, kết quả từng giai đoạn và process, không suy ra app bị treo hay hủy build đủ điều kiện chỉ vì log tạm im.
- Chuyển journal SQLite trong fixture phải chờ tất cả executor sở hữu kết nối, gồm FinanceExecutor. Retry có hạn chỉ áp dụng bước dựng điều kiện khóa; giữ phép đo FX 300 ms. Không tăng timeout thao tác UI để che lỗi.
- Khi chuẩn bị draft trên GitHub, đối chiếu cả `tag_name`, `target_commitish` và ID sau cập nhật; không chỉ dựa tên hiển thị của release. Chỉ bỏ draft trùng rỗng do chính lượt xuất bản tạo, giữ các release công khai.
- Một mã ứng dụng bất biến đi cùng build-info, checksum, Git bundle, native và upgrade proof. Công cụ publisher có nhánh riêng; thay tài liệu/công cụ xuất bản không được làm lệch nguồn ứng dụng đã kiểm chứng.
- Upload asset trực tiếp từ workspace gặp HTTP 401 của uploads.github.com; dùng workflow publisher với token Actions được cấp sẵn. Không sao chép credential vào CI hoặc nới quyền bảo mật để vượt lỗi.
- Phiên sau lấy SHA trong build-info làm nền, giữ UUID nâng cấp `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, schema/dữ liệu và phạm vi shop. Không dùng `prune`, không xóa bản cũ hoặc thay binary/tag đã công bố. Đọc biên nhận và journal trước bất kỳ thao tác live nào có thể bị gửi lặp.
