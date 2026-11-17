# KIZ — không để lô mới che lô mua đang chờ xác nhận ký

Phạm vi riêng được giao: `KizMappingRepository.java` và lớp test mới `KizSigningSummaryRecoveryTest.java`. Không sửa source khác, kho, schema, dependency hoặc API. Giữ các thay đổi ECC/Ozon trước nhiệm vụ; baseline là ZIP 3.767 tệp đã được parent kiểm chứng.

## Chẩn đoán và nguyên nhân

Cả danh sách tổng hợp all/purchased và truy vấn phân trang dùng ba subquery chọn pipeline `ORDER BY updated_at DESC LIMIT 1`. Một intent `QUEUED`, lô mới đang `VALIDATING` hoặc lô terminal mới hơn có thể che lô đã mua đang chờ chữ ký. UI nhận sai pipeline/stage/error nên mất thao tác tiếp tục đúng lô hoặc trình bày mua mới.

Đã tái hiện bằng `ZnackRepository.enqueuePipeline` thật trên SQLite tổng hợp: khi lô cũ `DOWNLOADING_CODES` bận thì lô sau thành `QUEUED`; khi lô cũ `WAITING_INTRODUCTION_READINESS` thì enqueue sau có thể thành `VALIDATING`. Không khẳng định mọi readiness đều chặn FIFO: `findActivePipeline` chỉ xét nhóm mutation hẹp. Các test chứng minh đúng hai hành vi này, cho cả shop WB và Ozon.

RED: 15 ca chạy thực trên bản sao baseline + test mới, **10 assertion failure, 0 error/skip**. Nguyên nhân thất bại đúng là chọn pipeline2 thay vì lô1 chờ ký và tie timestamp chọn sai ID. Log Maven ghi BUILD FAILURE, XML được lưu. Lần launch RED dùng subprocess tách rời nên không lưu trực tiếp exit code; không đoán exit code từ log hoặc gọi lỗi compile/môi trường là RED.

## Sửa nhỏ nhất

- Giữ nguyên query tồn kho, filter all/purchased, mapping rule, sắp xếp/phân trang sản phẩm và latest order status.
- Sau aggregate, đọc pipeline của đúng shop và tập GTIN vừa trả về theo batch tối đa900 GTIN; không N+1. Chỉ ưu tiên pipeline thực sự đạt guard `ZnackPurchaseCoordinator.isSigningConfirmationPending(shopId,id,stage,error)`, gồm persisted marker đúng định dạng và trạng thái pause runtime. Không tạo parser LIKE/prefix riêng, không ưu tiên mọi active cũ.
- Chỉ thay ba trường pipelineId/stage/error trong projection khi có lô cần xác nhận ký; giữ nguyên các số kho và thông tin khác. Khi không có pause đủ điều kiện, giữ semantics chọn lô mới nhất.
- Thêm `id DESC` khi `updated_at` bằng nhau, đồng nhất các subquery và chọn giữa các pause đủ điều kiện.
- Reader không cấp consent, không enqueue/mua, không gọi signer hay API, không chuyển trạng thái DB. Chỉ giúp UI hiện lại đúng lô để cơ chế tiếp tục hiện có tự kiểm tra trạng thái/quyền mới nhất khi người dùng thao tác.

## Kiểm thử

15 ca mới kiểm cả WB/Ozon, queue thật sau download, newer active sau readiness, newer terminal, latest bình thường không bị thay bởi active cũ, marker terminal không cấp quyền, substring không được xem là marker, runtime pause không có marker lưu, timestamp tie, đúng shop/GTIN, và catalog902 sản phẩm vượt một batch SQL.

Lượt GREEN đầu chạy97 ca:96 đạt/1 assertion lỗi trong fixture mới vì `ZnackSanitizer` lưu lỗi null thành chuỗi rỗng. Đã giữ log/XML, sửa test kiểm giá trị rỗng được lưu thực tế; không nới guard hoặc đổi production để làm test xanh.

Lượt cuối **97/97 đạt, 7 suite, 0 failure/error/skip, exit0** trên JDK25/Maven cached offline/Xvfb. Bảy suite gồm mới15, repository3, replenisher10, Znack workflow53, Ozon mapping6, purchase-status UI6 và signing-resume UI4. Hash hai tệp sở hữu không đổi trong lượt cuối, whitespace/diff check đạt.

Lệnh đầy đủ ở `final-command.json`:

```sh
flock /workspace/kiz-first-release-audit-20261116/maven.lock \
  /workspace/toolchains/xvfb-current/root/usr/bin/xvfb-run -a \
  /workspace/toolchains/mvn-vncode -o \
  -Dvncode.appdata.dir=<isolated-profile> \
  -Dtest=KizSigningSummaryRecoveryTest,KizMappingRepositoryTest,KizStockReplenisherTest,OzonProductGtinMappingRepositoryTest,ZnackGtinWorkflowTest,KizPurchaseStatusPresentationTest,KizSigningResumePresentationTest test
```

Bằng chứng: `final-result.json`, `final-process.json`, `final-inputs.json`, `final-junit/`, `implementation.diff`, `red-result.json`, `red-junit.xml`, `red.log`, `first-green-result.json` và `first-green-junit/`.

## Giới hạn

Chỉ dùng SQLite tổng hợp và UI fixture Linux, không mua/gán/ký thật, không Windows/CryptoPro/DPAPI, không installer/Latest. Đây là kiểm riêng trong snapshot baseline cộng đúng hai tệp sửa; parent còn chạy kiểm kết hợp source cuối với các sửa shared/circulation/UI cùng nhiệm vụ. Không cộng97 vào kết quả tổng hợp chồng lặp. Các semantics COALESCE fallback lỗi order khi không có pause đủ điều kiện được giữ, không tự mở rộng phạm vi chẩn đoán khi chưa có producer cụ thể.
