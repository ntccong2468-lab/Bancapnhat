# Triển khai Znack V3

Nền: VNCode v1.5.7, commit `9b580f42f3ea5b64a89a56591a598740cc7f5f45`.
Nhánh: `feature/znack-production-registration-v3`; không merge, push hoặc phát hành.
Kế hoạch: `docs/superpowers/plans/2026-10-21-znack-v3.md`.

## Mã đã triển khai

Trong `integration/znack/registration/v3/`:
- `RegistrationJob`, `RegistrationState`: bản chụp không chứa credential; trạng thái riêng.
- `RegistrationStore`: ba bảng jobs/remote_operations/events, chỉ mục GTIN duy nhất,
  CAS revision, checkpoint request/ack, attempt, nhận dạng worker và khôi phục UNKNOWN.
- `WbRegistrationImport`: toàn bộ phân trang, mọi size/barcode, subject/name/ảnh/characteristics;
  cursor không tiến hoặc phản hồi thiếu không bị coi là danh mục trống.
- `RegistrationOrchestrator`: nháp, đối chiếu GTIN, validation, duyệt và gửi một lần;
  tách ký và publication, sửa thẻ bị từ chối, đọc lại sau timeout, duyệt mới khi retry rõ từ chối.
- `CatalogRegistrationGateway`: adapter thật của client 1.5.7, không có mock production.
- `RegistrationValidation`: checksum GTIN, schema, preset, kiểu giá trị và điều kiện.
- `RegistrationContractPolicy`: NK 5.68 đã đối chiếu; WB/sandbox còn khóa theo phạm vi chưa xác minh.

Trong `ui/znackregistration/`: `RegistrationV3Pane` và `RegistrationAttributeEditor`.
Mở bằng nút **Đăng ký Znack V3** ở trang Đăng ký Znack của shop WB.
Bảy bước tiếng Việt; có tìm sản phẩm, chọn nhiều dòng, đọc/chọn danh mục từ Catalog,
đề xuất thương mại từ dữ liệu WB, nhập thuộc tính phụ thuộc, xem payload/XML và lịch sử.
Thông tin pháp lý không được tạo từ đề xuất. Mỗi mutation duyệt riêng theo từng thẻ;
không tự gửi một lô chỉ vì đã chọn nhiều sản phẩm. Cửa hàng/biến thể và môi trường
được ghi rõ trong hộp xác nhận. Hủy review có thể mở lại; đóng cửa sổ bỏ callback cũ.

`Database.java` gọi migration V3 cộng thêm. Không xóa/sửa hàng đợi registration,
GTIN/KIZ hoặc bảng dữ liệu cũ. `ZnackNationalCatalogService` bổ sung so sánh nội dung
đã duyệt khi công bố/đối chiếu; giữ kiểm tra chủ thể và byte XML hiện hữu.
Màn hình/controller/FXML cũ chỉ thêm lối mở V3, không chuyển Ozon sang luồng mới.

## Những quyết định và giới hạn

- Chọn tag 1.5.7 thay checkout cũ 1.2.2: bản phát hành mới nhất tìm được; nhánh audit
  mới hơn chỉ đổi metadata/tooling. Chi phí: chưa mang thay đổi tooling chưa phát hành vào nền.
- Giữ V3 song song luồng cũ đến nghiệm thu Windows/seller: tránh làm hỏng tác vụ đang chạy.
  Chi phí: chưa thay thế mặc định toàn bộ màn hình và hàng đợi Ozon.
- Không sao chép mã từ ba kho chưa thấy LICENSE. Chi phí: chỉ tái dùng client của chính VNCode.
- Ban đầu khóa mọi write khi chưa đọc được đặc tả. Sau đó tìm được API NK 5.68 chính thức,
  bổ sung bằng chứng và test, mở riêng hợp đồng Catalog production; WB vẫn khóa.
  Chi phí: chưa thể nghiệm thu end-to-end WB trong V3 chỉ bằng test mô phỏng.
- Chỉ giải phóng worker khi PID/thời điểm bắt đầu chứng minh worker cũ đã dừng.
  Chi phí: marker cũ không xác định cần kiểm tra thủ công; không đoán đã dừng để gửi lại.
- Unknown allocation cần seller tra mã trên Catalog rồi dùng “Đối chiếu GTIN đã có”.
  Danh tính/biến thể phải được đọc lại trước gắn mã; không cấp thêm để né kết quả chưa rõ.

## Rà soát và kiểm thử

Đã có một lượt review độc lập. Bốn lỗi quan trọng đã có test hồi quy và sửa:
preview WB bị xóa ở checkpoint; WB hủy/chờ đọc lại bị kẹt; thiếu đường sửa thẻ từ chối;
thuộc tính phụ thuộc không có ô nhập. Bổ sung test hai confirm đồng thời, pool allocation
chưa rõ, lưu feed trong checkpoint, lỗi 429 và validation thất bại.
Không có lượt review độc lập thứ hai; các sửa chữa được xác minh bằng test và kiểm tra diff.
Chi tiết kết quả cuối trong `ZNACK_V3_TEST_REPORT.md`.

## Chạy ở cloud đã chuẩn bị

```bash
source /workspace/.vncode-setup/activate.sh
bash /workspace/.vncode-setup/start-display.sh
cd /workspace/Bancapnhat
./mvnw -B -s /workspace/.vncode-setup/settings.xml \
  -Dmaven.repo.local=/workspace/.vncode-setup/m2/repository \
  '-DargLine=-Duser.home=/workspace/.vncode-setup/home --enable-native-access=ALL-UNNAMED' verify
node --test tools/*.test.mjs
```

Không export JAVA_TOOL_OPTIONS vì test khóa dữ liệu kiểm tra chính xác stdout của process con.
Cloud task đã cô lập, dùng checkout hiện tại; không tạo worktree nếu người dùng chưa yêu cầu.
Bản JAR build trên nhánh là artifact phát triển, không phải bộ cài 1.5.7 chính thức đã phát hành.
Truy cập API seller thật cần môi trường/tài khoản/chứng thư đã cấu hình đúng và phê duyệt riêng.
