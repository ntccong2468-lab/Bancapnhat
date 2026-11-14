# Kiểm tra ZIP mã nguồn 1.6.10

Helper: `verify-source-zip.py`. Python 3.9+ và Git hỗ trợ `check-attr --source` (đã kiểm trên Python 3.12/Git 2.52). Chỉ đọc Git/ZIP và ghi receipt ngoài repository; không extract ZIP, chạy app/Maven/API, mua KIZ hay sửa source.

**Chỉ chạy nghiệm thu sau khi có ZIP thật từ commit tài liệu cuối.** Ví dụ sau full test và final docs commit:

```sh
git -c core.autocrlf=false -c core.eol=lf archive --format=zip \
  --prefix=VN-code-1.6.10-source/ \
  -o /tmp/vncode-kiz-repair-20261114/VN-code-1.6.10-source.zip FINAL_DOC_COMMIT

python3 /tmp/vncode-kiz-repair-20261114/verify-source-zip.py \
  --repo /workspace/vncode-kiz-original-repair-20261114 \
  --zip /tmp/vncode-kiz-repair-20261114/VN-code-1.6.10-source.zip \
  --ref FINAL_DOC_COMMIT \
  --code-ref 75691c68fa14f728856b0418fb6008083787ef7c \
  --preservation /tmp/vncode-kiz-repair-20261114/source-preservation.json \
  --receipt /tmp/vncode-kiz-repair-20261114/source-zip-verification.json
```

Các ref phải là đủ 40 ký tự, không phải nhánh mutable. `--preservation` mặc định là `source-preservation.json` cùng thư mục helper. Không ghi đè receipt cũ; nếu cần chạy lại thì giữ kết quả cũ và dùng tên output mới. ZIP comment phải là final commit do `git archive` ghi. Receipt phải nằm ngoài repo để tránh tự sửa cây nguồn hoặc tạo vòng tự tham chiếu checksum.

Kiểm tra thực hiện:

- CRC, tên file trùng, prefix duy nhất, traversal/NUL/backslash/drive/NTFS stream, tên reserved Windows và collision case/Unicode.
- So byte **tất cả Git-tracked blob** ở final ref; không nới bằng cách normalize EOL. Giữ executable wrapper/script, kiểm symlink không đi ra ngoài root, từ chối device/FIFO hoặc ghi dưới symlink.
- Kiểm attrs ở đúng ref: export-ignore có thể làm thiếu file nên được báo rõ và FAIL, không nhận là source đầy đủ; LFS pointer/submodule chưa có payload cũng FAIL. Không bỏ qua file âm thầm.
- Cấm `.git`, output `target`/`out`, `node_modules`, user profile/credential home và `.env` thực. Không cấm cả test suite hoặc xóa fixture để đạt kết quả.
- POM project version và `app.version` đều 1.6.10. Các nguồn/test/build input ở final ref phải đúng code commit đã kiểm, Maven wrapper/build/AGENTS/README/CURRENT/JavaFX resources có đủ.
- Đối chiếu `source-preservation.json` với Git object thật: baseline 1.6.9 `4ef0f1e…`, 800 runtime file giữ nguyên tập tên, 790 byte-identical và đúng 10 file sửa (6 Java, 4 locale), 0 thêm/xóa. Bổ sung `ThemeService` so với checkpoint 9 file trước. POM chỉ đổi hai trường version; không ghi đè bằng repo cũ 504 file.

## Fixture JWT có sẵn

Prescan 3.288 blob tại code commit chỉ thấy một literal JWT trong `src/test/java/com/vncode/app/ui/StartupProgressTest.java`. Đây là fixture của test `preparationFailureCopiesPhaseAndCauseTypesWithoutMessagesCredentialsOrPaths`, nhằm chứng minh chẩn đoán clipboard không lộ chuỗi nhạy cảm. Token có claim `sub` và signature ES256 không hợp lệ về độ dài (12 byte, chuẩn ES256 yêu cầu 64); không ghi giá trị token vào receipt.

Helper chỉ cho phép đúng cặp **path + pattern `jwt_literal` + SHA-256 toàn blob `3b258a7170f0a590b43ffc6c1b6d17f94eaeff02f1af3dae5a5f142024a69364`** đã review. Kết quả thành công sẽ là `PASS_WITH_CONFIRMED_TEST_FIXTURE`, có ghi fixture. Bất kỳ match khác, kể cả trong `src/test`, phải review và FAIL; không có whitelist cả thư mục test. Source fixture được giữ nguyên.

Đây là scan hữu hạn theo các dạng GitHub token/JWT/signed URL/private PEM, không chứng minh không có mọi loại credential. Xem `source-zip-secret-preflight.json` và `source-zip-secret-fixture-review.json`; không mô tả là đã mua KIZ hay nghiệm thu chức năng.

## Kiểm helper

`python3 test-source-zip-helper.py`: **21 control đạt**, gồm Git archive nhỏ giữ CRLF và execute bit; trường hợp CRC hỏng, path traversal, duplicate, Unicode path extra, NUL, case collision, source cũ 504 file, file thiếu/thừa, fixture hash sai và test-directory không được whitelist. Đây là **SELF TEST**, không phải nghiệm thu ZIP VN Code thật. Chưa có ZIP/ref thật thì không có receipt nghiệm thu thành công.

Byte/source preservation không bảo đảm mọi hành vi gián tiếp; build/test/API/Windows/CryptoPro vẫn cần bằng chứng riêng. Helper đã ghim riêng code commit `75691c68fa14f728856b0418fb6008083787ef7c` (parent `53a22c1e…`); nếu root sửa thêm code thì phải cập nhật pin, source-preservation và kiểm thử trước khi dùng lại, không đổi input chỉ để làm xanh.

Checkpoint cũ cho `53a22c1e…`/9 file cùng helper SHA `d188fa524dbc7fd15840e1cd2f1e7634a86cee6f6a65add8c1241e838f6b871a`, 21 controls và receipt nguồn đã được giữ nguyên trong `source-zip-helper-history/code-53a22c1e-scope9/`. Không dùng checkpoint này để nghiệm thu code mới. Cập nhật pin không biến lượt full test cũ thành bằng chứng cho code mới; phải chờ full rerun được root ghi nhận riêng trước ZIP thật.
