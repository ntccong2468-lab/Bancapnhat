# Kiểm tra lại trước khi đẩy nhánh Vncode

Ngày 06/10/2026. Mã nền trước lượt kiểm tra: `52f4f7f` trong checkout phát triển
`/workspace/test-wcode`. Repository bàn giao: `ntccong2468-lab/Vncode`; nhánh mới
`feat/windows-gtin-sync-20261006`. Repository đích chưa có commit khi kiểm tra;
bàn giao snapshot mã nguồn đầy đủ, không đưa database, credential, cache hoặc
artifact build vào Git. Giữ thông tin nguồn ứng dụng tham chiếu và license hiện có.

## Lỗi được tìm và sửa

`ShopRepository.delete()` đã chặn job exemplar Ozon chưa kết thúc, nhưng chưa
chặn job GTIN. Vì các bảng GTIN dùng foreign-key `ON DELETE CASCADE`, xóa shop
khi còn dòng đang đợi, đang gửi hoặc cần đối soát sẽ mất shop/credential/history
cần để xác minh kết quả cập nhật. UI tracker của shop không bao gồm GTIN queue.

Đã bổ sung kiểm tra các dòng GTIN chưa ở trạng thái kết thúc trong cùng
transaction xóa shop. Thao tác bị chặn bằng thông báo RU/EN/VI/ZH; sau khi hủy
an toàn hoặc đối soát xong vẫn xóa được shop như trước. Không thay đổi mutation
API hoặc tự gửi lại tác vụ có kết quả chưa rõ.

Hai kiểm thử hồi quy tái hiện lỗi trước sửa: **2 assertion failures**, không
có lỗi môi trường. Sau sửa:

- `queuedJobMustBeCancelledBeforeDeletingItsShop`: bảo toàn shop/job QUEUED;
  sau cancel xóa shop được.
- `unknownRemoteOutcomeKeepsShopAndHistoryUntilReconciled`: bảo toàn shop và
  RECONCILE_REQUIRED sau timeout; sau đối soát APPLIED xóa shop được.

## Kết quả xác minh

| Lệnh | Kết quả thực tế |
| --- | --- |
| Maven targeted: `GtinSyncCoordinatorTest,ShopOperationCoordinatorTest,GtinTranslationTest` | 14 tests, 0 failures/errors/skipped; exit 0 |
| `DISPLAY=:99 /workspace/wcode-setup/mvn clean verify` | 518 tests, 0 failures/errors/skipped; BUILD SUCCESS, exit 0 |
| `node --test tools/*.test.mjs` | 17 pass, 0 fail; exit 0 |
| `git diff --check` | exit 0 |

Môi trường: Linux x64, JDK 25.0.4.1, Maven 3.8.5, Xorg dummy cho JavaFX/FXML.
Không khẳng định ứng dụng hết mọi lỗi chỉ dựa trên bộ kiểm thử này.

## Các giới hạn còn lại

- Production ADD/REPLACE trên WB/Ozon vẫn bị khóa vì hợp đồng API chưa xác minh;
  kết quả fixture không chứng minh cập nhật trên seller thật.
- Chưa chạy installer/portable Windows, hiển thị 100%/125%, DPAPI, máy in thật
  hoặc CryptoPro với chứng thư thật. Workflow native hiện có chỉ tự chạy cho
  push vào `dev`/`main` hoặc pull request tương ứng; push nhánh mới này không
  phải bằng chứng CI Windows đã chạy.
- Không tạo release, thay đích phát hành/updater hoặc dùng dữ liệu seller thật.
  Xem [trạng thái nghiệm thu](windows-gtin-acceptance.md).
