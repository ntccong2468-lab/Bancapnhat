# Phát hành bản gộp VN Code 1.6.6

**Đã hoàn tất Latest: 08.11.2026 00:46:11 MSK (07.11.2026 21:46:11 UTC).** Release **437862752**, immutable, tag **v1.6.6** → nguồn app **`ac8ba1c939e22d0318877a3a2ba7242359cce78e`**. [Tải EXE Windows x64](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.6/VN-code-1.6.6-Windows-x64.exe), [ghi chú](../releases/VN-code-1.6.6.md).

Người dùng yêu cầu hoàn tất audit rồi gom tất cả sửa và phát hành Latest. Bản này kế thừa toàn bộ 1.6.5, gồm Code Doctor, năm nút sửa sản phẩm/giấy tờ tuyên bố, KIZ mapping khác TN VED và liên kết Catalog–Ozon đã duyệt. Thêm toàn bộ hotfix Ozon, WB, feed và audit sau đó. [17 mốc và hash 36 input runtime](2026-11-07-vncode-166-source-inclusion.json). Không merge PR hoặc đổi tag/binary cũ.

## Những sửa đã đưa vào bộ cài

- Catalog trả và app lưu feed_id: từng thẻ sang Đang xử lý ngay; tiếp tục theo dõi yêu cầu cũ, không coi HTTP 200/feed_id là công bố. Sửa shared writer tránh SYNC_STORAGE_BUSY trước gửi, append nguyên tử các thẻ đã duyệt vào lô đang chạy.
- WB đã có barcode GTIN 046/EAN-13 tương đương: Hoàn tất. Catalog công bố và WB thiếu: cập nhật nền đúng biến thể/owner và đọc lại; giữ barcode/thuộc tính/giấy tờ cũ, quy tắc giá và consent. Không dùng tiền tố 046 làm proof mua KIZ hoặc công bố Catalog. Bỏ nút Thao tác khác, giữ chức năng chi tiết ở context menu/Shift+F10.
- Ozon tự thay KIZ seller nhập đúng danh mục khi có từ chối xác định; nhường lượt sau 8 mã/45 giây rồi quay lại. Bỏ nút Thay KIZ thủ công; giữ journal/unknown mutation, KN3 readback và quy tắc riêng kho mua. WB không đổi ở phần tự thay này.
- PDF sửa snapshot barcode cũ; đọc lại sau chuẩn bị và bổ sung theo SKU batch tối đa 1.000. Giữ đúng shop/product/offer/SKU, kiểm owner sau HTTP và không ghi cursor/proof Catalog từ print repair. Giữ vận đơn Ozon gốc, thứ tự WB và từng KIZ sát đúng đơn; không thêm barcode sản phẩm đứng riêng.
- Carrier-only hiện đúng lý do, không tính thành đủ KIZ. Sửa thêm null batch context khi in riêng đơn không cần KIZ; không đọc metadata combined cho nhánh đó. Không tạo KIZ giả khi thiếu mã phù hợp.
- Báo cáo mẫu ảnh: 1.141 xác nhận + 16 chờ + 273 cần xử lý + 552 chưa ghép = 1.982. Không cộng 552 vào Bị chặn; giữ chi tiết lỗi liên kết cục bộ khi sàn đã xác nhận barcode. Observer readback không khóa làm mới danh sách.
- TASTE/IMPECCABLE kiểm native hai nền/bốn ngôn ngữ, giữ thiết kế đã duyệt và diện tích danh sách. Sửa paint thanh tải thành phẳng, thống nhất nhãn tài chính; giữ toàn bộ handler. [Chẩn đoán, hồi quy và ảnh](2026-11-07-report-pdf-app-audit.md).

## Kiểm chứng nguồn và bộ cài cuối

[Windows build 42408546769](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42408546769): **3.979 ca/527 lớp, 3.977 đạt, 2 bỏ qua theo OS, 0 failure/error**. Ba ca 10.000, hai ca guard in riêng và ba shop PDF đều có trong XML và đạt. Native launcher, migration 3→6/snapshot, DPAPI, cài/gỡ độc lập và nâng cấp thụ động từ Latest 1.6.5 giữ dữ liệu đạt. Giữ schema 6 và UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`. [Build/hash](2026-11-07-vncode-166-build.json).

Artifact **13592011248**, đủ **16 tệp**; EXE/MSI khớp chính fingerprint của smoke nâng cấp. Source ZIP **2.917 tệp** đối chiếu Git, chỉ chấp nhận chuẩn hóa CRLF cho text; JAR **1.956 class/Java 25**, đúng 1.6.6, production launcher/feed, không fixture. [Payload/XML](2026-11-07-vncode-166-package.json). Run 42407000935/source bd3175d đã hủy trước đóng gói khi soi cuối phát hiện thiếu guard; không dùng kết quả đó cho bản cuối. Nguồn app không đổi sau `ac8ba1c939e22d0318877a3a2ba7242359cce78e`; commit tooling/docs tiếp theo không phải source binary.

Node publisher **160/160**; actionlint 1.7.12 kiểm toàn bộ workflow đạt (không chạy ShellCheck/Pyflakes). Portal **67/67** và syntax đạt ở fresh security CI; không triển khai portal. Linux rộng 3.974 ca và 40 UI/version trước guard, sau guard 150 ca Ozon, mỗi lượt giữ đúng nguồn/phạm vi; không cộng các lượt giao nhau. [Biên nhận](report-pdf-app-20261107/verification.json).

## Công bố, tải công khai và cập nhật

[Publisher 42417349432](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42417349432), source tooling **`f86178fba743f07ca696a2e9a7523a98f9803c68`**, SUCCESS. Pin đúng draft **437862752**, build/source/tag và kiểm đủ 16 asset trước Publish. [Receipt CI](2026-11-07-vncode-166-publication.json). Đã tải thực đủ 16 URL; byte/hash trùng CI, digest GitHub và receipt, `verify-public` đạt. **15 release cũ/242 asset** giữ nguyên id/tag/target/id asset/name/size/digest; không prune. [Latest và tải thực](2026-11-07-vncode-166-published-latest.json). Một request bổ sung gặp 503 rồi curl retry thành công; mọi byte cuối đều được kiểm.

[Updater từ chính binary đã công bố 1.6.5](2026-11-07-vncode-166-updater.json) đọc GitHub thật → **AVAILABLE 1.6.6**, chọn đúng MSI/hash/kích thước. JAR và 74 dependency lấy từ portable 1.6.5 đã kiểm hash, dùng profile trống. Chạy Linux/JDK 25 qua proxy/CA Cloud; client không gắn Authorization nhưng proxy có thể gắn credential repo. Chỉ đổi lựa chọn OS Windows sau network; chưa quan sát popup hoặc cài trên máy seller.

## Giới hạn và tiếp tục

Bộ cài chưa ký Authenticode. [Security 42408547869](2026-11-07-vncode-166-security.json): lint/audit và JS đạt; Java gate giữ thất bại với tám finding cũ/1 high tại SHA-1 thumbprint định danh chứng thư; cả tám vị trí byte không đổi từ 1.6.5, không query/ngưỡng/gate nào bị hạ. OpenQodex/Gitleaks quét đúng 2.917 tệp, 10 candidate cũ đã triage và 19 suppression skill cũ; không AI reviewer hoặc toàn scanner xanh. [Scan](report-pdf-app-20261107/source-scan.json).

PDF ba shop/100 đơn kết hợp và 1.000 carrier dùng fixture, không phải benchmark 1.000 đơn thật. Chưa có DB/profile để nghiệm thu lại toàn bộ shop seller hoặc quy 12 nhãn thiếu của PDF cũ về cùng nguyên nhân. Không ghi seller API, mua KIZ, ký/công bố Catalog, kích hoạt license hoặc Gmail trong lượt này. Không dùng unit/native pass thay readback nghiệp vụ thật; các biên nhận API thật trước có phạm vi riêng. Prototype credential migration vẫn ngoài runtime.

Tiếp tục từ nguồn app/tag **v1.6.6** và trạng thái/journal đã lưu. Không replay giao dịch chỉ vì mất file tạm; không sửa tag/asset immutable. Giữ kết quả in và chấp nhận KIZ trên API riêng, chỉ ghép đúng danh tính và nguồn kho. Các sửa tiếp theo cần nguồn và build mới; bộ nhớ/bàn giao cập nhật ở nhánh `release/vn-code-1.6.6`.
