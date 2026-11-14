# VN Code 1.6.10 — kính nổi và bản sửa Windows

Nguồn app/test/CI hiện hành: **`f3e98725ee2892abf225cb66978ed9302fd49489`**, nhánh `release/vn-code-1.6.10`. Người dùng đã cho phép tạo bộ cài và phát hành lên Latest. Tại checkpoint tài liệu này, full Windows **43639385681** đang chạy; chưa có bộ cài hay Latest 1.6.10 được nghiệm thu. [Lượt Windows hiện hành](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/43639385681).

## Phạm vi đã gộp

- Kính nổi áp dụng trong trang, panel, bảng/danh sách, ô nhập, nút, menu, Cài đặt và báo cáo của cả nền tối/sáng. Material lấy từ wallpaper app, giữ chữ và dữ liệu rõ, không blur nội dung sản phẩm/đơn hoặc chụp desktop. Bảng dùng một lớp dưới hàng trong suốt, giữ virtualization và clip bo góc; không tạo blur/image cho từng hàng.
- Báo cáo app dùng Scene trong suốt và khung bo/titlebar riêng; Cài đặt bỏ card đục lớn bao màn. Giữ DialogPane, kết quả, owner, modality, close veto và showAndWait; không đổi cửa sổ OS/CryptoPro/FileChooser. Canvas, giấy trắng và dữ liệu in giữ nguyên.
- Chuyển cảnh và phản hồi nhấn/thả/keyboard dùng chung cho trang, mục con, tab, menu, popup và báo cáo. Nội dung hoạt động ngay; không consume/refire hoặc gọi handler lặp. Nhập liệu, thanh cuộn và canvas giữ hình học thao tác. Giảm chuyển động tiếp tục theo lựa chọn app **hoặc** cài đặt trợ năng OS.
- Sản phẩm chia cột rõ, footer/tiến độ không che hàng. Sửa focus phủ xanh và trang mới thiếu kính khi material được cài trước khi nội dung ScrollPane gắn vào Scene.
- Sửa quyền nền caller bị mất sau CSS/đổi theme/dựng lại helper: lưu riêng cờ Boolean theo Region. Không giữ Scene/controller/global map. Nền inline, bound hoặc do caller sở hữu vẫn được giữ sau tháo/gắn, ẩn/hiện và đổi theme.

Bốn raster 960×600 dùng chung cho hai theme và blur: **8,79 MiB pixel thô**, không phải tổng RAM app. Đây là material/motion JavaFX theo brief Apple đã duyệt, không phải UIKit hoặc chứng nhận giống iOS từng pixel. Taste và Impeccable được áp dụng theo brief của người dùng.

## Giữ chức năng và chỉnh sửa trước UI

[Manifest hiện hành](source-preservation.json): 805 runtime, đủ 800 đường dẫn từ 1.6.9, 756 nguyên byte, 44 sửa, 5 thêm, **0 xóa**. So checkpoint nghiệp vụ trước UI `a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3`: đủ 801 đường dẫn cũ, 773 nguyên byte, 28 tệp UI/theme/locale/caller sửa, 4 helper UI thêm, **603 Java ngoài ui/shared nguyên byte**. POM/dependency/schema/API wire không đổi trong phần UI. Kiểm kê byte không thay thế kiểm thử hành vi.

Các phần đã sửa trước UI được giữ:

- Tiếp tục đúng lô KIZ dừng chờ ký bằng hồ sơ/lô/cửa hàng gốc; không mua lại hay cấp quyền cho cửa hàng/lô khác. Không đánh dấu tải mã là đã lưu thông. [Báo cáo nguồn đầu và KIZ](../2026-11-14-kiz-source-repair.md).
- Ghép Ozon bằng toàn bộ mã mẫu và size đúng biến thể, dùng chung đăng ký/GTIN/mua KIZ; xử lý reader và tránh tạo trùng khi bằng chứng hết hiệu lực. `152NAU` khớp không phân biệt hoa/thường/khoảng trắng, loại `B3152NAU` và `152NAUDASH`; 2XL/XXL/52 chỉ dùng alias có căn cứ đúng biến thể. [Báo cáo và giới hạn](../2026-11-14-ozon-exact-model-size.md).
- Retheme popup dùng skin cũ; phân tích tĩnh WBCode 1.3.25 có bằng chứng, không thực thi/copy code độc quyền. [WBCode](../../research/wbcode-20261114/README.md).
- Repo rút gọn/hồi quy đã nghiên cứu để người dùng duyệt: OpenRewrite/Spoon; ArchUnit+JaCoCo trước, PIT phạm vi hẹp sau. Chưa tự cài hoặc refactor diện rộng. [Đề xuất](../../research/2026-11-14-code-quality-proposals.md).

## Lỗi Windows đã xác định và sửa

Full Windows cũ **43625748501**, nguồn43b, **FAIL: 4.697 ca/585 suite, 1 failure +19 error, 2 skip**. Giữ [tổng JUnit thật](windows-f3e/failed-43b-actual-junit-summary.json); không dùng làm chứng nhận hoặc dự đoán tổng lượt mới.

| Nhóm | Bằng chứng và sửa |
| --- | --- |
| 17 ca chuyển động | Windows Server worker tắt client-area animation; JavaFX công khai báo reducedMotion=true. App tôn trọng OS là đúng. Không sửa production để bỏ trợ năng. Chỉ chuẩn hóa tạm worker CI, probe JVM mới cả bật/tắt và luôn khôi phục OS ban đầu. |
| Quyền nền caller | Tái hiện RED với CSS trước rescan; helper cũ quên quyền sở hữu khi dispose/rebuild. Sửa cờ Boolean theo Region, giữ nguyên 12 test cũ, thêm regression24dòng. Hai suite liên quan20/20 GREEN. [Receipt](windows-f3e/caller-background-red-green.json). |
| Kề nhau ô shop/Quản lý | Body gap8px, shadow visual overlap3px. Fixture cũ so vùng shadow; sửa dùng layoutBounds, giữ ngưỡng0..20, kiểm âm overlap1px/gap21px. App/FXML không đổi vì phép đo này. |
| Contrast native | Desktop Robot lấy trúng console Windows foreground, ratio1,279 đúng cặp màu console/chữ; Scene render đạt. Fixture đưa Stage thật lên trước, fit màn hình và xác nhận focus trước capture; giữ contrast4,5/focus3/opacity1, không retry để săn kết quả xanh. |

Commit50edad sửa caller ownership và phép đo header; 86b sửa fixture foreground. f3e chỉ thêm setup worker/probe CI và tăng preflight21→41 suite. Giữ nguyên full unfiltered clean verify, native, DPAPI, install/coexist/uninstall và passive upgrade. [Workflow validation](windows-f3e/ci-workflow-validation.json), [patch receipt](windows-f3e/ci-worker-motion-patch.json).

## Kiểm thử hiện hành và giới hạn

- Local86b: **200 ca/21 suite, 0 failure/error/skip**, verify đạt. 1.424 input kiểm Git; JAR 2.026 class/2.068 output biên dịch, không test class. [Receipt đầy đủ](windows-f3e/local-focused-86b-result.json).
- Windows thật86b, run **43638439643**: 198 ca/20 suite và NativeGlass2 ca/1 suite đều đạt; **200 ca phân biệt/21 suite**. Material observed/unobserved1 ca trùng tập không cộng thêm. Normal/reduced public probes và khôi phục OS đạt. Baseline giảm chuyển động có17 lỗi assertion animation được giữ làm đối chứng; không gọi toàn diagnostic gốc là full app đạt. [Summary](windows-f3e/windows-focused-86b-summary.json).
- **Main/test/POM/wrapper f3e nguyên byte so86b**; f3e package-only đạt, không giả nhận đã chạy lại200 ca local trên commitf3e. [Alias input](windows-f3e/input-alias.json).
- Full Windows mới43639385681 phải tự đạt toàn bộ app và bộ cài trên đúngf3e. Không ghép chứng nhận full cũ hoặc dùng tổng4697 làm dự báo. Chưa có kết quả full/package tại checkpoint này.
- Security mới43639388384 đã xác minh artifact thật: **8 finding Java cũ, 0 mới/0 giải quyết**, một finding7,5 làm high gate FAIL; JS0/lint đạt. Toàn bộ record finding/codeflow/location/fingerprint bằng1.6.9/43b. Error/warning extraction GS1 giữ nguyên và giới hạn coverage; informational/note có khác nên không khẳng định mọi notification giống nhau. [Báo cáo và digest thật](windows-f3e/security/REPORT.md). Không gọi security toàn bộ xanh. OpenQodex/dependency review skip theo điều kiện manual không cung cấp coverage.
- 43b focused197/20, 3c related1396/188 và full KIZ4540/569 là lịch sử có nguồn riêng; không dùng chúng thay nghiệm thu hiện hành. [Báo cáo43b nguyên bản](history/ui-checkpoints/REPORT-43b-historical.md).

## Ảnh và chuyển động

Bộ f3e mới đang được capture từ runtime sao chép/so byte, input đối chiếu86b rõ ràng; dùng JavaFX production components, Linux/Xvfb/compositor và SQLite tổng hợp. Không gửi API marketplace/Catalog, mua/ký hoặc kích hoạt license. Bộ43b [gallery lịch sử](native-evidence/index.html) và ZIP đã giao được giữ nguyên; không đổi tên nguồn ảnh thànhf3e.

Nghiệm thu ảnh mới yêu cầu50PNG/25route×2theme, tất cả mount kiểm ngay trong cùng theme, mọi logical surface hợp lệ sẵn sàng sau Scene/CSS/layout, pixel wallpaper desktop khớp ImagePattern qua5frame ổn định. Tôn trọng persistent callerBackground opt-out. Không dùng Pattern count đơn lẻ để chứng nhận native paint. Video giữ phạm vi real-pointer drag/resize; Button.fire cho nútX phải ghi đúng là programmatic productionaction. Recording30fps không là benchmark FPS app. Receipt/gallery mới sẽ bổ sung khi thực tế hoàn tất.

## Source ZIP và phát hành

Gói mới dùng tên **VN-code-1.6.10-source-floating-glass-windowsfix-20261114.zip**; archive Windows chính thức vẫn tên VN-code-1.6.10-source.zip đúng commitCI. Docs sau build có thể làm checksum/sourceZIP khác, nhưng input runtime/test/build phải bằng nguồn đã kiểm. ZIP mới cần guard chạy trên chính archive thật và docs-only gate; receipt/hash của ZIP để ngoài tránh tự tham chiếu.

Hai ZIP đã giao giữ nguyên: đầu1630458672f9be335c09f8f3a624c5ec844139ce1cdc4510fccbb304877d5e4c và43b15ab17ed6c867ff81aebe15773bdf403ece2420b8a4b616eec3f407e72a1defb. Không ghi đè chúng bằng bản sửa Windows.

Trước công bố cần full Windows thành công, kiểm actualartifact/digest/16asset, EXE/MSI/portable/source/native/DPAPI/migration/upgrade, publisher pin đúng source/run/draft và retention bản cũ. Sau công bố phải tải lại đủ16file công khai, xác minh checksum/manifest và chạy updater gốc1.6.9 với metadata thật. Latest tại checkpoint này vẫn1.6.9; không coi ZIP nguồn hoặc preview là cập nhật app. Không nghiệm thu CryptoPro/SUZ/mua KIZ hay seller API ghi thật trong pha UI/release này.
