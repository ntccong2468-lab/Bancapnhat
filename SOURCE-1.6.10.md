# VN Code 1.6.10 — mã nguồn đã phát hành

**1.6.10 đã lên Latest lúc13:40:34 ngày15.11.2026 Moscow (UTC+03:00)**, Release445977967/tagv1.6.10, bất biến. Updater1.6.9 công khai nguyên bản đã báoAVAILABLE1.6.10 và chọn đúng MSI. [Bản phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.10) · [Bàn giao/kiểm chứng đầy đủ](docs/validation/2026-11-15-vncode-1610-release-handoff.md).

Mã runtime/test/build đã kiểm là **`b74b5edfd46636e8779c94f467eef01c03c50a14`**. Gói **VN-code-1.6.10-source-floating-glass-windowsfix-20261114.zip** bổ sung tài liệu/ảnh/QA cuối sau build, giữ nguyên byte các input app/test/POM/wrapper của b74. Commit tài liệu nằm trong ZIP comment và receipt bên ngoài; không đưa checksum chính ZIP vào trong nó. Source ZIP chính thức ở16assets là archive đúng mốcCIb74, nên tài liệu/checksum có thể khác gói bổ sung. Không đổi asset đã công bố.

Gộp toàn bộ chức năng nền1.6.9 và các sửa mới: tiếp tục đúng lô KIZ chờ ký bằng hồ sơ gốc, giữ owner/cửa hàng/chống mua lặp; Ozon ghép toàn bộ model và size, không phân biệt hoa/thường/khoảng trắng, tránhB3152NAU/152NAUDASH, chỉ dùng alias Nga52 khi biến thể xác nhận; kính nổi toàn bộ phần bên trong, khung báo cáo bo tròn, Cài đặt từng nhóm, danh sách lớn, chuyển động theo ngữ cảnh/Giảm chuyển động. Sửa focus che chữ, logical attachment, quyền nền caller và viền1px Finance/FBO. Giữ giấy trắng/canvas, handler, dữ liệu và hành vi hiện có; không dependency/schema/APIwire mới trong UI.

Kiểm chứng Windows đúng nguồn: **4.700ca/585suite,4.698đạt,0failure/0error,2skip đúngHĐH**; cài/gỡ/cài song song/DPAPI/nâng cấp từ1.6.9 đạt. Đủ16public khớp byte/hash CI độc lập;19release/306asset/48tag cũ giữ nguyên. Source805runtime/0xóa;603Java nghiệp vụ nguyên byte từ mốc trướcUIa860. Kiểm byte không thay thế nghiệm thu mọi tình huống thực tế.

Security còn8finding Java cũ,1mức cao7,5 và lỗi extraction GS1; không có finding mới trong kết quả đã so1.6.9. Không gọi toàn bộ security xanh. Không mua/ký có phí hoặc ghi API seller thật trong pha này. Xem phạm vi, giới hạn và hashes trong bàn giao.

- [Nguồn VNCode đầu/KIZ](docs/validation/2026-11-14-kiz-source-repair.md).
- [Ghép GTIN Ozon và bằng chứng](docs/validation/2026-11-14-ozon-exact-model-size.md).
- [OpenRewrite/Spoon và ArchUnit/JaCoCo/PIT để duyệt; chưa cài/refactor rộng](docs/research/2026-11-14-code-quality-proposals.md).
- [WBCode1.3.25 phân tích tĩnh](docs/research/wbcode-20261114/README.md).
- [UI/ảnh/video native](docs/validation/ios-motion-20261114/REPORT.md).
- [Điểm tiếp tục AI](docs/memory/CURRENT.md).

Build cần **JDK25**, dùng `mvnw.cmd -B clean verify` trênWindows; Linux cần display hoặc `xvfb-run -a ./mvnw -B clean verify`. Không cần dữ liệu/API seller để build. Mã nguồn không kèm khóa API, license hay chứng thư riêng tư.

Giữ nguyên hai ZIP đã giao: đầu SHA-256 `1630458672f9be335c09f8f3a624c5ec844139ce1cdc4510fccbb304877d5e4c`; checkpoint43b SHA-256 `15ab17ed6c867ff81aebe15773bdf403ece2420b8a4b616eec3f407e72a1defb`. Chỉ gói cóhậu tốwindowsfix chứa các sửa Windows cuối và tài liệu phát hành này.
