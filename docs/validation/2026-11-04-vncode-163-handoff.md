# VN Code 1.6.3 — bàn giao ứng viên phát hành

Người dùng đã duyệt bản xem trước và yêu cầu áp dụng cùng sửa nhận đơn FBS Ozon. Phiên bản ứng dụng đã tăng lên **1.6.3**, kế thừa Latest 1.6.2 và sửa Ozon `ccd7fc1`. **Tại thời điểm ghi hồ sơ này chưa công bố Latest mới**; phải qua Windows full/native/upgrade rồi mới xuất bản.

Ứng viên ban đầu `6de9c73f34c429bfac0ba2af5ac4ea91538c8af1` / build `41895958691` **đã hủy**, vì người dùng yêu cầu bổ sung ghép PDF/KIZ trước phát hành. Không được xuất bản run này; pin nguồn/build mới sau commit. Tool publisher kiểm144/144; Latest vẫn1.6.2.

Nguồn ứng dụng cuối đã commit/push: `14a10498951e50ffb3d2603e88f5bfb8d6c15b68`; Windows full build `41902237002` bắt đầu 19:59:24 UTC. Publisher đã repin đúng commit/run này; chỉ được chạy sau khi toàn bộ gate thành công.

Bổ sung sau chốt UI: sort vận đơn across batch theo WB, đặt KIZ kết hợp ngay sau đúng vận đơn, không thay content/dimension carrier. KIZ bị từ chối vẫn in từ reservation đúng đơn theo yêu cầu mới; giữ API rejection riêng. Nhận không KIZ chỉ khi fresh Ozon cho phép và không có mandatory mark; pending/foreign/429 giữ nguyên cơ chế đối soát. Child bị từ chối không chặn child hợp lệ.

[PDF thật mới](2026-11-04-vncode-163-ozon-paired-pdf.json): 25 vận đơn + 13 KIZ gilet, 38 trang, 2 trang picking;2native task/0individual;6.999s export/9.401s end-to-end. 13DataMatrix khớp reservation độc lập,25carrierbyte-identical,sort/picking/scanit đạt. 12đơn khác danh mục không ghép mã gilet. File/user raw KIZ và API data nằm riêng ngoàiGit; không đưa vào source ZIP. Không claim Ozon đã chấp nhận13KIZ mới; đây là local combined print của đơn đã nhận.

## Những phần đã hoàn tất

- Áp dụng giao diện đã duyệt bằng JavaFX thật: hai theme, thanh điều hướng 196px, header58px, quản lý/thêm/sửa và thùng rác cạnh selector. Diagnostics vẫn ở overflow; giữ các handler, phạm vi cửa hàng và xác nhận xóa.
- Tổng quan bỏ ba KPI và chiều cao trống. Danh sách sản phẩm/Ozon76px, Catalog71px, đơn nhiều mặt hàng tăng chiều cao để giữ đủ thông tin. Hành động hàng loạt có vùng riêng, không phủ danh sách.
- Drawer chi tiết chia nhóm, Enter/Escape và trả focus; bản nháp cấu hình giữ riêng theo đủ danh tính shop. Chuyển động ngắn có thể ngắt, reduced motion hệ điều hành/tùy chọn. Không animation từng dòng, không tạo delay cho API.
- Sửa tương phản template editor do nested `root`, trạng thái danh sách trống bản địa hóa, giữ chức năng chỉnh độ trong suốt theme sáng.
- Ozon đối soát receipt sau khi gửi cả tập đã chọn, tối đa3vòng đọc1/2/4giây và2reader; giữ yêu cầu chưa rõ cho nền tiếp tục, không ship lại. Phiếu nhặt hàng sort theo cùng cấu hìnhWB và giữ mã vận đơn theo đúng posting. Nhận diện trang barcode sản phẩm bằng mọi barcode của đúng SKU, bảo vệ trang vận chuyển.

[30 ảnh native](../design/native/2026-11-04/README.md), [review ship](../design/native/2026-11-04/REVIEW.md), [ghi chú bản cập nhật](../releases/VN-code-1.6.3.md).

## Bằng chứng và giới hạn

- [Prebuild](2026-11-04-vncode-163-prebuild.json):244ca riêng biệt/23lớp mục tiêu,0lỗi/bỏ qua; Node144/144. Tổng hợp XML thành công mới nhất mỗi lớp, không cộng trùng các lượt có giao nhau. Không phải full Linux.
- Layout kiểm26màn với4ngôn ngữ/2theme, vùng nút cố định và danh sách co giãn. 30ảnh synthetic gồm12màn×2theme, Ozon1366×768/1920×1080 và drawer. Ít nhất7dòng đơn đầy đủ ở1440×900. Acceptance4/4 kiểm hành động cửa hàng, scope draft, focus/selection và mở lại trong lúc đóng.
- Lượt UI tổng hợp trên Linux ghi warning nền khi fixture FXML xóa profile tạm; JUnit không thất bại, lượt native acceptance tách riêng đạt. Không dùng warning này để kết luận lỗi dữ liệu seller; full Windows vẫn là gate bắt buộc.
- [API thật trước đóng gói](2026-11-04-ozon-accept-picking-verified.json):22/22đơn đọc lại đã nhận; snapshot cuối18:00:05UTC,19nhãn trong1task, xuất lại task có sẵn8.009giây. Không phải benchmark1000đơn hay thao tác trên Windows seller. File KIZ31ký tự bị từ chối crypto/trạng thái; không gán/tiêu/mua KIZ. Không lặp mutation thật trong bước phát hành.
- Sau yêu cầu kiểm tra thật bổ sung, quét26trang lịch sử/4trang hàng đợi tìm2đơn mới; gửi2yêu cầu ship và xác nhận2/2 `awaiting_deliver`. Cả2trả trạng thái chậm ở lần đọc đầu, được bước đối soát production mới giải quyết không gửi trùng. Quét cuối không còn đơn chờ trong cửa sổunfulfilled±180ngày. [Biên nhận](2026-11-04-vncode-163-ozon-live-recheck.json). Không bắt buộcKIZ trên2đơn này; không coi đó là bằng chứng fileKIZ đã hợp lệ.
- Theo yêu cầu mới đã xuất PDF thật cho25đơn:25trangvậnđơn+2trangnhặthàng,0thấtbại;2nhómtask,0requestnhãnlẻ;15,426giây cả đối chiếu dữ liệu,11,458giây riêngexport. Kiểm độc lập số in trên nhãn khớp đúng25/25dòng phiếu và sortWB. [Biên nhậnPDF](2026-11-04-vncode-163-ozon-pdf-live.json). Không có nhãnKIZ; file thật/ảnh ở artifactprivate khôngcommit. Đây là serviceproduction trongprofileisolated, không phải clickWindows hoặc benchmark1000đơn/cachelạnh.
- Không đổi schema6, UpgradeCode, dữ liệu license/shop/GTIN/history; không triển khai portal hoặc prototype credential migration. Installer chưa ký Authenticode; giới hạn securityreview1.6.1 vẫn còn.

## Bước phát hành bắt buộc

Pin SHA ứng dụng và build Windows `publish-vncode-baseline.yml`, chạy full suite/native/DPAPI/coexistence/passiveEXEupgrade từ1.6.2. Publisher pin đúng nguồn/run, đủ16asset; không sửa tag/binary cũ hoặc prune. Sau công bố tải/đối chiếu hash toàn bộ asset, sourceZIP/JAR và bộ đọc updater từbinary1.6.2. Ghi receipt/source/run trong hồ sơ phát hành bổ sung; chưa khẳng định popup/cài trên máy người dùng.
