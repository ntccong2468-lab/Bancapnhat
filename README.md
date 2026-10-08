# VN code

VN code là ứng dụng desktop JavaFX cho người bán **Wildberries** và **Ozon FBS**.

- Quản lý nhiều shop theo marketplace, không dùng nhầm credential giữa WB và Ozon.
- Đồng bộ supply/order Wildberries và posting Ozon FBS.
- Quản lý kho KIZ/Znack, mapping GTIN, gán và gửi KIZ lên marketplace.
- In nhãn, barcode, trang KIZ và phiếu nhặt hàng PDF.
- Chuẩn bị đơn, ship và tải nhãn vận chuyển chính thức cho Ozon FBS Standard.
- Lưu lịch sử in, template và dữ liệu cục bộ trong SQLite.

Dự án: https://github.com/ntccong2468-lab/Vncode.
Mã nguồn gốc WCode do Nguyễn Anh Tuấn / TuanDev phát triển; VN code là bản fork tùy chỉnh.

## Công nghệ

| Thành phần | Công nghệ |
|---|---|
| Desktop UI | JavaFX 25, FXML, MaterialFX, Ikonli |
| Core | Java 25, Maven |
| Dữ liệu | SQLite |
| Marketplace API | OkHttp, Gson |
| PDF và barcode | iText 8, PDFBox, ZXing |
| Excel | Apache POI |

## Tải bản Windows

Bản **VN code 1.1.34** là ứng dụng riêng, cài song song với WCode, giữ các chức năng đã phát triển.
[Tải bộ cài Windows x64](https://github.com/ntccong2468-lab/Vncode/releases/download/v1.1.34/VN-code-1.1.34-Windows-x64.exe).
Java được đóng gói kèm; tải EXE rồi chạy để cài đặt. Đây là prerelease chưa ký Authenticode.
Xem [mô tả và giới hạn](docs/releases/VN-code-1.1.34.md).
Cập nhật GTIN thật lên WB/Ozon trong module đồng bộ vẫn bị khóa chờ xác minh API.

VN code cài ở `%LOCALAPPDATA%\VNcodeApp`, dùng dữ liệu riêng ở `%LOCALAPPDATA%\VNcodeData` và bắt đầu với danh sách shop trống. VN code là bản cá nhân miễn phí, không cần giấy phép WCode; thiết lập tài khoản shop và chứng thư riêng trong app mới. Cài hoặc gỡ VN code không thay thế WCode và không tự nhập dữ liệu WCode.

VN code chỉ dùng thuộc tính `vncode.appdata.dir` và `vncode.data.profile`; các thiết lập `wcode.*` không tác động đến ứng dụng mới. Cả kênh cập nhật chính và bản thử đều nằm trong repository này.

Bản portable có `check-portable.bat` để thu thập cấu trúc package và startup log khi cần hỗ trợ.

## Phát triển

Yêu cầu JDK 25. Node.js 22 chỉ cần cho các contract test của pipeline phát hành.

```bash
# Chạy toàn bộ Java/FXML test
./mvnw clean verify

# Chạy app JavaFX với app-data thật
./mvnw javafx:run

# Chạy app với dữ liệu thử cô lập
./mvnw -Dvncode.appdata.dir=/tmp/vncode-smoke javafx:run

# Kiểm tra contract build/release
node --test tools/*.test.mjs
```

Đóng gói local:

```bash
build.bat app-image   # Windows portable
build.bat exe         # Windows EXE chưa ký
build.bat msi         # Windows MSI chưa ký
./build.sh app-image  # macOS/Linux app-image
./build.sh dmg        # macOS DMG chưa ký/notarize
./build.sh deb        # Linux DEB
```

Bản phát hành chính thức lấy từ workflow [release.yml](.github/workflows/release.yml).
Bộ cài thử nhánh GTIN ở trên lấy từ workflow [build-java.yml](.github/workflows/build-java.yml).
Workflow phát hành kiểm tra cài và gỡ VN code bên cạnh WCode `1.1.75` thật trên runner Windows tạm; xác minh chương trình, dữ liệu và registration WCode giữ nguyên. VN code có installer identity riêng, không chạy migration từ dữ liệu WCode. Authenticode và signed update manifest được
bật khi các secret tương ứng đã cấu hình đầy đủ; thiếu cả nhóm secret không chặn build, nhưng cấu
hình dở dang sẽ bị từ chối. Version duy nhất nằm trong `pom.xml`; tag phát hành phải khớp
`vMAJOR.MINOR.PATCH`.

Xem [runbook phát hành JavaFX](docs/javafx-release-runbook.md) và
[báo cáo triển khai Ozon](docs/ozon-marketplace-expansion-report.md).

## Nhánh ứng dụng Windows với module GTIN

Nhánh [feat/windows-gtin-sync-20261006](https://github.com/ntccong2468-lab/Vncode/tree/feat/windows-gtin-sync-20261006)
bổ sung màn hình **GTIN WB / Ozon** trong ứng dụng
JavaFX hiện có. Giữ các luồng VN code, thêm đọc GTIN đăng ký từ National Catalog,
đối chiếu article/màu/size, ánh xạ nội bộ, xem trước thêm/thay, lịch sử CSV/XLSX
và hàng đợi SQLite có tạm dừng, hủy dòng chưa gửi, phục hồi và đối soát.

**Chưa bật cập nhật GTIN thật trên WB hoặc Ozon.** Tài liệu API chính thức bị
HTTP 403 trong môi trường cloud, nên adapter production chặn cả thêm lẫn thay.
Xem trước không gửi dữ liệu; nút xác nhận bị khóa khi hợp đồng API chưa được
xác minh. Kiểm thử hàng đợi dùng adapter fixture, không chứng minh cập nhật
thành công trên tài khoản seller. Dữ liệu thiếu thuộc tính hoặc trạng thái đăng
ký chắc chắn không được ghép tự động.

Kiểm tra ngày 06/10/2026 trên runner Windows với JDK 25: `clean verify` qua
**518 kiểm thử**, Node qua **18 kiểm thử**, đóng gói EXE thành công.
EXE đã tải xuống và đối chiếu SHA-256; cài đặt và chạy thử trên máy người dùng
đang chờ nghiệm thu. Xem [báo cáo bộ cài thử](docs/validation/2026-10-06-windows-test-installer.md),
[báo cáo chức năng](docs/validation/vn-code-feature-parity.md),
[hướng dẫn Windows và trạng thái nghiệm thu](docs/validation/windows-gtin-acceptance.md)
và [trở ngại hợp đồng API](docs/gtin-marketplace-api-contracts.md).
Lượt kiểm tra trước khi đẩy mã đã sửa lỗi xóa shop làm mất tác vụ GTIN chưa có
kết quả chắc chắn; [báo cáo kiểm tra lại](docs/validation/2026-10-06-pre-push-check.md)
ghi rõ kiểm thử và phạm vi chưa xác minh.

GTIN luôn được giữ dạng chuỗi trong SQLite và XLSX. CSV dành cho Excel có BOM
UTF-8, escape công thức và thêm dấu nháy đơn trước giá trị toàn chữ số để giữ
số 0 đầu; bộ đọc CSV thông thường sẽ thấy dấu nháy đơn đó. Dùng XLSX khi cần
GTIN nguyên văn trong ô kiểu chuỗi.

## An toàn dữ liệu khi nâng cấp

- Trên Windows, `1.1.10` sao chép/migrate dữ liệu legacy từ `%LOCALAPPDATA%\VN code` sang thư mục
  dữ liệu riêng `%LOCALAPPDATA%\VNcodeData`; binary nằm tại `%LOCALAPPDATA%\VNcodeApp`.
- Migration schema chạy tăng dần và snapshot SQLite được tạo, verify trước khi ghi.
- Binary cũ fail closed khi gặp schema mới hơn; không tự hạ schema hoặc xóa dữ liệu.
- Marketplace của shop là bất biến; credential Ozon không bao giờ được gửi tới endpoint WB và ngược lại.
- Không đưa database, WAL, API key hay KIZ thật vào artifact/repository.

## Phạm vi Ozon hiện tại

Hỗ trợ Ozon FBS Standard, một package chứa đủ các item/quantity của posting. Chưa hỗ trợ rFBS, FBO,
partial package, multibox, price/stock và carriage/act. Các trường hợp ngoài phạm vi bị chặn thay vì
gửi mutation không chắc chắn.

## License

Phần mềm thương mại © TuanDev.
