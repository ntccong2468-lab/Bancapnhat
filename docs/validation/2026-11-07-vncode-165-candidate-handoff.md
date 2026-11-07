# VN Code 1.6.5 — gộp mã, chưa phát hành

Ngày **07.11.2026, MSK (UTC+03:00)**. Chủ dự án yêu cầu gộp toàn bộ chỉnh sửa, sau đó chỉnh lại phạm vi: **chỉ gộp vào bản mới, chưa phát hành lên Latest**. Quyết định sau cùng này thay chỉ đạo công bố trước đó.

## Nguồn và phần đã gộp

- Nhánh **`release/vn-code-1.6.5`**, nguồn ứng dụng/version **`286549c5c74c2e67c931940adfcc43a19cc481be`**, đã push. Kế thừa `bba8391` và mọi sửa hoàn thành trong PR #21; các thay đổi nghiệp vụ đến `5ad16bf` được giữ nguyên.
- `pom.xml` project/app.version cùng **1.6.5**. Lượt gộp chỉ đổi version/tài liệu, không thêm schema, sửa runtime ngoài phạm vi hoặc triển khai prototype credential.
- Gồm cách ly lỗi đồng bộ Ozon, bốn bộ lọc Znack, nhận diện Catalog và GTIN WB khác TN VED, giấy tờ mặc định Tuyên bố sự phù hợp/khử trùng, KIZ mapping WB/Ozon khác TN VED/chọn thẻ thật/báo lỗi rõ; kế thừa toàn bộ UI, FBS, PDF/KIZ và audit của 1.6.4.
- [Đối chiếu nguồn](2026-11-07-vncode-165-source-inclusion.json): tám mốc thay đổi có trong ancestry; so với 1.6.4 có 21 tệp runtime và 14 tệp test được kế thừa. Các đầu vào `src`, wrapper, build script không đổi so với nguồn nghiệp vụ `5ad16bf`; POM chỉ đổi version.
- [Ghi chú bản gộp](../releases/VN-code-1.6.5.md). README đã phân biệt Latest thực tế 1.6.4 và bản chuẩn bị 1.6.5; giữ hồ sơ 1.6.3 dưới nhãn lịch sử.

## Kiểm chứng đã chạy

[Biên nhận build và từng lớp](2026-11-07-vncode-165-candidate-build.json).

- `node --test tools/*.test.mjs`: **150/150 đạt**.
- Nhóm Java liên quan **34 lớp / 737 ca**, **736 đạt, 1 failure, 0 error/skip**. Ca `ZnackCardRegistrationControllerTest.selectedReplacementUsesReviewedDurableBatch` không thấy bước xác nhận hồ sơ sẵn sàng trong thời hạn. Không loại bỏ kết quả này để gọi cả nhóm đạt.
- Chạy riêng đúng ca lỗi: **1/1 đạt**. Chạy lại toàn bộ lớp controller và package: **38/38 đạt, BUILD SUCCESS**. Không sửa production hoặc nới assertion để có kết quả này; chưa xác định nguyên nhân lần lỗi trong lượt gộp, chưa chứng minh full suite ổn định.
- JAR **`VNcode-1.6.5.jar`**, 7.431.599 byte; hash trong JSON. Đúng `app.version=1.6.5`, đối chiếu byte **1.933 class production** với output; không bundle license fixture hoặc probe.
- Chạy chính JAR qua `com.vncode.app.Launcher` trên Linux/Xvfb: workspace 1300×700 mở sau **6.168 ms**, hồ sơ mới 0 shop, đóng và nhả khóa dữ liệu đạt. License UNVERIFIED, seller API không được cấp, không yêu cầu kích hoạt.

Lệnh Maven dùng Java 25/Maven cache của môi trường và Xvfb. Danh sách 34 lớp thật nằm trong JSON; tham số lượt gộp là `-Dtest=<các lớp được ghi trong biên nhận> package`. Lượt xác minh lại:

```sh
PATH="/workspace/toolchains/xvfb-current/root/usr/bin:$PATH" \
  /workspace/toolchains/xvfb-current/root/usr/bin/xvfb-run -a \
  /workspace/toolchains/mvn-vncode -B \
  '-Dtest=ZnackCardRegistrationControllerTest#selectedReplacementUsesReviewedDurableBatch' test

PATH="/workspace/toolchains/xvfb-current/root/usr/bin:$PATH" \
  /workspace/toolchains/xvfb-current/root/usr/bin/xvfb-run -a \
  /workspace/toolchains/mvn-vncode -B \
  '-Dtest=ZnackCardRegistrationControllerTest' package
```

## Gói nội bộ và giới hạn

Gói nội bộ tại `/workspace/artifacts/VN-code-1.6.5-consolidated-286549c/`: JAR, source ZIP đúng commit, build-info, release notes và SHA256SUMS. **Đây không phải bộ cài Windows hoặc portable runtime hoàn chỉnh.** Chưa dựng/kiểm Windows installer cho 1.6.5 trong lượt chỉ gộp này; không dùng số liệu Windows 1.6.4 làm bằng chứng thay thế.

Không tạo release/tag 1.6.5, không gọi publisher hoặc thay Latest. Latest phải giữ **v1.6.4 / `86d76d8`**. Không có nhận đơn, gán/mua KIZ, ghi Catalog/GTIN hoặc kích hoạt license thật mới trong các lượt build/kiểm thử này. Các test API/PDF ở đây dùng dữ liệu tổng hợp, không phải nghiệm thu Ozon thật.

## Công việc tiếp theo theo thứ tự người dùng yêu cầu

Sau khi hoàn tất phần gộp trên: dùng Code Doctor/soi code kiểm và sửa PDF Ozon không có KIZ kết hợp; kiểm quy tắc mapping Ozon và đề xuất cho cùng sản phẩm/cùng size nhưng tên/SKU khác cách ghi giữa Catalog và Ozon, dùng chung cho mua KIZ, đồng bộ và đẩy GTIN. Các sửa tiếp theo sẽ được lưu tiếp vào **1.6.5 chưa phát hành**, có nguồn/kiểm chứng riêng; không đổi gói đã ghi hash ở commit `286549c` thành bằng chứng cho mã mới.
