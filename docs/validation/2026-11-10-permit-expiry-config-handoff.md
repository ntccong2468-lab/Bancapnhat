# Ngày hết hạn trong cấu hình giấy tờ

Ngày 10.11.2026 MSK. Nguồn **`80e9324c24e1d1fdee1dbbdbc56854b1e8f0149f`**, nhánh `fix/permit-expiry-20261110`, nền `202e58ab6b9c87d6568beb8a1120d71c8b958324` sau công bố 1.6.8. Người dùng báo mua KIZ cần hạn giấy tờ nhưng cửa sổ cấu hình chỉ có ngày bắt đầu.

## Nguyên nhân và thay đổi

SQLite và `ZnackModels.Settings` đã có `document_expiry_date`; cửa sổ `RegistrationDocumentConfigDialog` chưa hiển thị trường này, còn kết quả hội thoại chỉ mang loại/số/ngày cấp. Đây là cửa sổ dùng chung cho cấu hình đăng ký WB/Ozon và sửa sản phẩm.

- Thêm ô **Ngày hết hạn** đọc dữ liệu đã lưu. Hai ngày nhận `dd.MM.yyyy` hoặc `yyyy-MM-dd`, phân tích strict và lưu `dd.MM.yyyy`. Ngày không hợp lệ hoặc hạn trước ngày hiệu lực giữ hội thoại mở và báo đúng trường.
- Để trống vẫn là **chưa biết**, không tự coi giấy tờ vô thời hạn. Không tự đặt hạn hoặc sao chép hạn từ giấy tờ khác.
- Giữ bản nháp hạn theo danh tính giấy tờ (loại, số đã chuẩn hóa, ngày cấp đã chuẩn hóa). Gõ lại cùng số/ngày qua trạng thái nhập rỗng không làm mất hạn gốc hoặc hạn vừa chỉnh. Chủ động xóa hạn vẫn giữ trống; chuyển qua lại hai giấy tờ giữ hai bản nháp riêng.
- Lưu cả bốn trường giấy tờ cùng nhau; API cũ ba tham số giữ hành vi tương thích. Không migration và không thay cấu trúc `GoodsDocument`/hợp đồng API SUZ.
- Lưu giấy tờ và lưu kết nối merge với cấu hình hiện hành trong cùng giao dịch SQLite. Lưu một khu vực không ghi đè bản chỉnh sửa mới ở khu vực còn lại; giữ snapshot kết nối của pipeline KIZ đã tạo, cách kiểm chứng/chuyển chứng thư và CAS cũ.

## Kiểm chứng và giới hạn

Code Doctor: tái hiện trước sửa bằng hội thoại JavaFX thật với SQLite tạm; trường hết hạn không tồn tại, 1 ca/1 error (`expected: not <null>`). Dữ liệu kiểm thử hoàn toàn tổng hợp. Xem [biên nhận](permit-expiry-20261110/verification.json), [17 lớp/230 ca](permit-expiry-20261110/focused-tests.json) và [kiểm tra JAR](permit-expiry-20261110/local-package.json).

Lượt cuối **230/230 ca đạt trong 17 lớp, 0 failure/error/skip**, gồm 16 ca hội thoại mới và 3 ca lưu riêng từng khu vực. Các ca liên quan bao gồm sửa giấy tờ sản phẩm, hồ sơ mặc định, bằng chứng giấy tờ mua KIZ, đăng ký, snapshot pipeline, chứng thư/CAS và khám phá chứng thư trên UI. Không cộng dồn các lượt GREEN cũ vào số này. Hai finding khi review (nhập qua trạng thái rỗng làm mất hạn, lưu kết nối cũ ghi đè giấy tờ) đã sửa trước lượt cuối.

Lệnh đã chạy tại worktree trên:

```sh
PATH=/workspace/toolchains/xvfb-current/root/usr/bin:$PATH xvfb-run -a /workspace/toolchains/mvn-vncode -B -Dtest=RegistrationDocumentConfigDialogTest,RegistrationDraftCompletionDialogTest,ZnackRegistrationDocumentsTest,ZnackModuleTest,ConfiguredProductDeclarationTest,ProductBulkDialogTest,KizPurchasePermitEvidenceTest,RegistrationPermitValidatorTest,CatalogPermitEvidenceTest,DocumentDateInputTest,ZnackSettingsAreaRepositoryTest,ZnackCertificateSettingsRepositoryTest,ZnackAutomationControllerTest,ZnackAutomationInformationTest,ZnackCertificateDiscoveryUiTest,ZnackPurchaseMigrationSafetyTest,ZnackConnectionReuseTest test
/workspace/toolchains/mvn-vncode -B -DskipTests package
git diff --check
```

JDK 25/Linux/Xvfb. Wrapper chỉ cấu hình Maven cache, proxy và CA của môi trường; máy khác dùng JDK25/Maven/Xvfb tương đương. Package thành công, JAR 7.641.455 bytes, SHA-256 `de77489fea9c90ec273a6f5686ac850e1e10dcfc8d26420bc3e94a827866d93a`; đối chiếu byte 53 class liên quan và 4 locale với `target/classes`, có ô hạn mới, không đóng gói test support. Tên JAR vẫn 1.6.8 theo POM nền để kiểm build, **không phải binary 1.6.8 public** và không phải bộ cài mới. Kiểm hash 10 tệp nguồn/test khớp lượt GREEN và package; các commit tài liệu sau nguồn này không thay runtime.

Ngày đã lưu được consumer `ConfiguredProductDeclaration` đọc lại, vẫn là `UNVERIFIED`. Không đưa ngày nhập tay vào bằng chứng registry của GTIN. Kiểm tra Catalog/registry đúng sản phẩm, chủ sở hữu, trạng thái và hạn còn hiệu lực vẫn áp dụng cho mua KIZ. Lượt này không mua KIZ, ghi API seller/Catalog, ký hoặc kích hoạt license thật; không phải nghiệm thu mua đầu-cuối hoặc full suite Windows.

Latest được [đọc lại qua GitHub API](permit-expiry-20261110/latest-read.json) vẫn là **v1.6.8 / 440439895**, công bố `2026-11-10T15:34:37Z`. Sửa này **chưa phát hành**, không đổi version, tag hoặc tài sản bản 1.6.8 đã công bố.
