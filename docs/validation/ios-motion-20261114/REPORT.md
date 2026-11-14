# VN Code 1.6.10 — kính nổi cho toàn bộ phần bên trong

Nguồn runtime/test/CI hiện hành: **`43b82cca683b29e783a33df995afd675a76aa0dd`**, nhánh `release/vn-code-1.6.10`, kế thừa nghiệp vụ `a86048ec57521bc4d32b0a27c86d2eb1ee70fcb3`. Người dùng đã yêu cầu đóng gói và phát hành 1.6.10 lên Latest. Tại checkpoint bàn giao này, bộ cài còn đang kiểm Windows; chưa có kết quả công bố.

## Những gì đã thay đổi

Yêu cầu mới thay thế hướng giữ vùng làm việc nền đục trước đó: kính nổi áp dụng bên trong trang, panel, bảng/danh sách, ô nhập, nút, menu, Cài đặt và báo cáo. Dùng Taste và Impeccable theo brief Apple đã duyệt; giữ hai nền, vùng làm việc lớn và chức năng nghiệp vụ.

- Các panel, trường nhập, nút thông thường, combo/date/spinner, bảng/danh sách/cây, toolbar, popup và báo cáo dùng material từ wallpaper app, ánh sáng cạnh và độ nổi phù hợp. Chữ, biểu tượng, focus, lựa chọn và trạng thái vẫn rõ; không giảm opacity cả cửa sổ hoặc blur dữ liệu sản phẩm/đơn.
- Bảng dùng một lớp kính chung dưới các hàng trong suốt; giữ virtualization, không tạo ảnh/blur cho từng hàng. Clip bo ngăn hàng/header tràn ra góc, có cleanup và tôn trọng clip/binding của bên gọi. Sheet/dialog bao quanh cung cấp bóng nổi vì clip cả Control cũng cắt bóng của chính Control đó.
- Cài đặt bỏ card đục lớn bao toàn màn; từng nhóm là khối kính nổi có khoảng đệm. Ô nhập bên trong DatePicker/Spinner/ComboBox không chồng nền đặc. Wrapper vốn trong suốt không bị biến thành khung kính vuông mới.
- Vùng ngoài trình thiết kế nhãn dùng kính; giấy trắng, canvas, thước và nội dung tài liệu in giữ nguyên. Hint thiết lập đã đổi đủ bốn ngôn ngữ; tắt kính có nền đặc dự phòng. Trạng thái chính/semantic, lựa chọn, disabled hoặc paint do caller sở hữu có thể giữ nền đặc khi cần.
- Dialog app đăng ký trước show dùng StageStyle.TRANSPARENT, Scene trong suốt và khung bo có chỗ cho bóng. Titlebar riêng hỗ trợ đóng/kéo/đổi kích thước ở dialog cho phép resize. Giữ DialogPane, nội dung, nút, kết quả, close veto, owner, modality và showAndWait; không đổi cửa sổ hệ thống/CryptoPro/FileChooser.
- Bổ sung hook ở dialog đăng ký Znack, giấy tờ/thương hiệu/TN VED Ozon, hủy thẻ chờ, kho KIZ, FBO preflight, sao chép bài, license reset, cấu hình dịch và cảnh báo số dư. Trong 14 đoạn bổ sung cuối, token Java ngoài lời gọi theme/chrome được giữ nguyên.
- Giữ chuyển cảnh theo quan hệ điều hướng và phản hồi nhấn/thả/keyboard cho mục, mục con, nút, tab, menu và hành động tạo động. Màn mới hoạt động ngay; không chờ animation. Không consume/refire sự kiện hoặc gọi lặp handler. Nhập liệu, thanh cuộn và canvas giữ hình học thao tác.
- Theo dõi Scene/Stage/popup app; đóng thật trả kết quả và dọn tác vụ ngay, chỉ lớp hình thoát tạm không nhận input tiếp tục chuyển động. Đổi cửa hàng hủy ảnh cũ. Báo cáo có trường nhập/mật khẩu/canvas/WebView dùng silhouette, không chụp nội dung nhập; báo cáo chỉ đọc có ảnh đệm giới hạn. Giảm chuyển động hủy transform/lớp tạm ngay.
- Sản phẩm chia bốn nhóm cột, thích ứng vùng hẹp, giữ ảnh/tìm/chọn và năm nút sửa. Tiến độ/footer đo riêng, không phủ hàng. Ở viewport 1200×720, vùng danh sách Sản phẩm cao 468px so WB 510px; ở 1480×900 là 648px so WB 690px, đạt assertion cũ mà không nới ngưỡng.

## Hai lỗi tìm thấy trong lần nghiệm thu cuối

**Focus làm chữ khó đọc trong frame cập nhật material.** InnerShadow phủ xanh cả trường nền alpha, giảm contrast xuống 2,25:1/3,06:1. Bỏ riêng InnerShadow, giữ viền focus và bóng nền. Một ca native mới tái hiện RED ở cả hai theme; lớp material sau sửa đạt 5/5, giữ nguyên bốn ca cũ và ngưỡng tương phản. Frame ổn định cũ có thể hồi phục sau deferred paint, nên đây không phải lỗi contrast vĩnh viễn.

**Trang mới thiếu kính ở chế độ Giảm chuyển động.** Scope quét nội dung logic ScrollPane trước khi native skin gắn nó vào Scene, rồi refresh bỏ Surface và listener vì Scene còn null. Trang không nhận lại kính đến khi đổi theme. Nguồn 43b giữ listener trong lúc chờ attachment, chỉ vẽ trong Scene sở hữu; vẫn dọn khi gỡ nội dung hoặc chuyển sang Scene khác. Hai ca native mới RED trên đúng nguồn 3ce rồi GREEN sau sửa; kiểm cùng Backdrop/AllSurface đạt 19/19. Lần mount cuối của cả 25 route được kiểm trước bất kỳ đổi theme nào. Không dùng thao tác đổi theme hoặc snapshot để che lỗi.

## Material và bảo toàn chức năng

BackgroundFill ImagePattern nằm dưới tint cuối, giữ radius/inset và quyền CSS/binding của bên gọi. Bốn raster 960×600 dùng chung cho hai theme và bản blur: **8,79 MiB pixel thô**, không phải tổng RAM app. Wallpaper không có dữ liệu seller; không chụp OS desktop, foreground hoặc mỗi frame animation. Không thêm JNI/FFM/dependency. Đây là material/motion JavaFX theo hướng Apple, không phải UIKit hay chứng nhận giống từng pixel iOS.

So nguồn trước UI a860: đủ 801 đường dẫn cũ, **805 runtime tổng, 773 giữ byte, 28 tệp UI/theme/locale/caller sửa, 4 helper UI thêm, 0 xóa**. **603 tệp Java ngoài ui/shared giữ nguyên byte**, gồm nghiệp vụ/model/config/integration. POM/dependency/schema/API wire không đổi trong phần UI. So nền 1.6.9: đủ 800 đường dẫn cũ, 756 giữ byte, 44 sửa, 5 thêm, 0 xóa. [Manifest](source-preservation.json); kiểm kê byte không thay thế kiểm thử hành vi.

Gói kế thừa tiếp tục đúng lô KIZ chờ ký theo hồ sơ gốc/phạm vi cửa hàng; retheme popup dùng skin cũ; ghép Ozon theo toàn bộ mã mẫu và alias size đúng biến thể, dùng chung đăng ký/GTIN/liên kết KIZ; sửa reader và chống tạo trùng khi bằng chứng ghép hết hiệu lực. Không khôi phục cách đánh dấu tải mã là đã lưu thông.

Các yêu cầu trước có tài liệu riêng:

- [Nguồn VNCode đầu và sửa KIZ](../2026-11-14-kiz-source-repair.md).
- [Ghép Ozon đã tích hợp và bằng chứng](../2026-11-14-ozon-exact-model-size.md).
- [Repo rút gọn/hồi quy chờ duyệt](../../research/2026-11-14-code-quality-proposals.md): OpenRewrite/Spoon; ArchUnit+JaCoCo và PIT phạm vi hẹp. Chưa tự cài/refactor diện rộng.
- [WBCode 1.3.25](../../research/wbcode-20261114/README.md), phân tích tĩnh, không chạy/copy code độc quyền.

## Kiểm chứng theo đúng nguồn

- Trên nguồn 43b: **197 ca/20 suite focused và verify đạt, 0 failure/error/skip**. Chỉ đếm 20 suite yêu cầu, không cộng XML incremental cũ. [Receipt](focused-result.json).
- Cùng lượt này, 1.424 input đối chiếu Git; JAR có 2.068 tệp output biên dịch đã kiểm, gồm 2.026 class, không có test-only class. JAR SHA-256 `c7ad6200217ef07294ac9286f20b02cf7b3ca5f2609d8febd5b6277fe0803a5b`.
- Nguồn 3c trước hai sửa cuối đạt **1.396 ca/188 suite, 0 failure/error/1 Linux skip** trong [receipt riêng](related-result.json). Đây là hồi quy UI/shared/products/printing/catalog-sync/GTIN liên quan trên checkpoint cũ, không phải full hoặc chứng nhận cho nguồn 43b; không cộng trùng với 197 ca.
- Lượt 830 focused đầu có hai FAIL do theme đã tự cài clip trước listener của fixture. Nguồn 3c chỉ sửa fixture: xác nhận hook đã có rồi reset bằng API công khai trước khi thử reentrancy; giữ assertion. Production/POM 830→3c nguyên byte. Không gọi hai lỗi fixture là lỗi app.
- Các kiểm thử native giữ quyền caller/binding/reentrant, bo góc, close/result/owner/resize, focus và contrast. Expectation nền đục cũ được lưu và thay công khai theo brief mới; giữ ngưỡng 4,5:1, foreground opacity1 và không blur chữ.
- Giữ bằng chứng FAIL thật của các checkpoint UI trước: opacity toàn cửa sổ, spring vượt1, scope ảnh báo cáo sau đổi Scene, footer phủ hàng/khoảng trống và Scene trắng sau dialog tối. Đã sửa production rồi kiểm lại, không hạ assertion để xanh.
- Full 4.540/569 của KIZ/theme và 2.198/225 của Ozon là lịch sử có nguồn riêng, không chứng nhận UI hiện hành.

## Ảnh và chuyển động JavaFX thật

[Gallery cuối](native-evidence/index.html) · [Báo cáo capture](native-evidence/REPORT.md) · [Receipt cuối](native-evidence/FINAL-RECEIPT.json).

Dùng production components JavaFX từ runtime 43b được sao chép/so byte, Linux/Xvfb có compositor và SQLite tổng hợp; không dùng HTML prototype thay app hoặc khởi chạy MainApplication của seller. Không gửi tác vụ marketplace/Catalog, mua/ký hay kích hoạt license trong capture; fixture ghi nhận 0 license call.

Video gốc 1440×900 dài 44,133333 giây, **3.450.574 byte**, SHA-256 `bd5243d0eed098fdb1d95be3168b62dc7eae8acad5be322c88e0ae3bab3a1b02`. Java/ffmpeg đều exit0; 18 gate đạt. Recording 30fps là định dạng ghi hình, không phải benchmark FPS app.

- Đủ **50 PNG của 25 route ở hai theme**; cả 25 lần mount được kiểm material trong cùng theme trước khi đổi theme. Mỗi ảnh kiểm mọi logical surface đủ điều kiện và năm frame hậu Scene/CSS/layout ổn định; pixel wallpaper native khớp mẫu ImagePattern với delta0. Pattern count đơn lẻ hoặc chờ số frame cố định trước CSS không được coi là đạt.
- Nhóm review đã xem thủ công **50/50 ảnh trang và 15/15 keyframe video**, gồm sáu cửa sổ báo cáo; không tìm thấy blocker cụ thể về material, khung ngoài, bố cục hoặc độ rõ trong phạm vi này. Các hash ảnh trùng receipt pixel và danh sách review.
- Sáu báo cáo dùng StageStyle.TRANSPARENT, Scene.fill trong suốt và titlebar production; Settings ngoài trong suốt. Video cuối ghi nhận kéo tiêu đề và resize bằng con trỏ native; nút X gọi đúng luồng đóng production qua `Button.fire()`. Bảng đầy/thưa có clip bo, bốn raster dùng chung, không image-load error.
- Các gallery 1ed/b00/3c/3ce và những sweep 43b trước đó được giữ làm lịch sử. Gesture resize delta0 ở video 3c và probe sửa đúng mép card 1084→1012→1054 chỉ thuộc checkpoint 3c; không dùng làm bằng chứng thay cho video 43b cuối. Những ảnh trước khi native paint hoàn tất không nằm trong bộ ảnh nghiệm thu cuối.

Đây là phạm vi native Linux tổng hợp; số ảnh không có nghĩa đã mở mọi dialog ẩn hoặc mọi trạng thái control. Không suy từ ảnh này ra Windows graphics-driver, seller/API hoặc hiệu năng app đã cài.

## Build, security và trạng thái phát hành

Người dùng đã yêu cầu **đóng gói/phát hành 1.6.10 lên Latest**. Nguồn CI hiện hành là 43b; Windows full run **43625748501** còn đang chạy tại checkpoint tài liệu này. Phải có kết quả full Java, native/install/coexist/uninstall/passive upgrade từ 1.6.9 và review artifact đúng nguồn trước khi công bố. Chưa có draft/artifact được chấp nhận hoặc kết quả Latest mới ở checkpoint này.

Security run **43625751805** đã hoàn tất: **Java gate FAIL với 8 finding cũ, gồm 1 finding bị chặn; 0 mới/0 được giải quyết so 1.6.9**. Lỗi extraction GS1 cũ còn nên coverage bị giới hạn. JavaScript và lint đạt; OpenQodex/dependency review skip theo điều kiện manual dispatch, không cung cấp coverage. Không gọi kết quả này là security toàn bộ xanh hoặc finding cũ đã được sửa.

Lịch sử CI được giữ: run 43623930708 dừng ở Node vì một link nghiên cứu upstream bị guard nhận nhầm cấu hình build cũ; chỉ sửa URL tới thư mục, giữ guard và kiểm 174/174 local đạt. Run Windows 43624250164/SOURCE5373 được hủy khi phát hiện lỗi mount thật, trước đóng gói. Không dùng các lượt fail/canceled hoặc kết quả 3ce/5373 để chứng nhận 43b.

## Hai gói nguồn và giới hạn

Gói có thêm tài liệu/ảnh bàn giao tên **`VN-code-1.6.10-source-floating-glass-20261114.zip`**. Source ZIP chính thức trong artifact Windows là **`VN-code-1.6.10-source.zip`**, archive của đúng commit CI. Gói bàn giao có thể thêm tài liệu sau build nên khác checksum; input runtime/test/build phải giữ nguyên byte. Commit tài liệu cuối không được đổi những input đã kiểm.

ZIP checkpoint cũ giữ nguyên SHA-256 `1630458672f9be335c09f8f3a624c5ec844139ce1cdc4510fccbb304877d5e4c`. Guard kiểm Git/path/mode/CRC, input, bảo toàn và dữ liệu nhạy cảm của ZIP thực; checksum/receipt chính ZIP giao ngoài để tránh tự tham chiếu. Kiểm archive không phải nghiệm thu seller.

Chưa nghiệm thu Windows của seller, CryptoPro/SUZ hoặc mua/ký/ghi GTIN thật trong pha UI/release. ZIP nguồn không cập nhật app đang cài; đối chiếu bàn giao phát hành cuối cho kết quả bộ cài, Latest và updater.
