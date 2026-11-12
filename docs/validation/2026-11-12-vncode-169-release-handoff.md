# Bàn giao phát hành VN Code 1.6.9

VN Code **1.6.9** đã được công bố làm Latest lúc **05:47:19 UTC / 08:47:19 MSK ngày 12.11.2026**. Release immutable **442540592**, tag `v1.6.9`, nguồn app **`4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b`** trên `release/vn-code-1.6.9`. [Bộ cài Windows EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.6.9/VN-code-1.6.9-Windows-x64.exe) · [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.6.9) · [Receipt cuối](vncode-169-20261112/final-release.json).

Publisher trên nhánh `release/vn-code-1.6.9-publication`, commit **`b9635f80f0b125ad33830f71430088358ccc671e`**, run **43163588699 SUCCESS**. Thay đổi tài liệu sau công bố không thay mã runtime, nguồn tag hoặc asset đã phát hành.

## Nội dung đã gộp

Nguồn 1.6.9 kế thừa bản 1.6.8 **`38906a2108501029d9609ed1d071ee987c8b2637`** và các nhóm sau:

- `80e9324`: thêm ngày hết hạn ở cấu hình giấy tờ; kiểm tra ngày, lưu theo đúng giấy tờ và giữ giá trị khi sửa kết nối. Ngày trống là chưa biết; giấy tờ cấu hình không tự trở thành bằng chứng Catalog/registry.
- `d8a4859`: theo dõi tình trạng mua KIZ đến khi có bằng chứng lưu thông; phân biệt tải mã với đã lưu thông, giữ hồ sơ kết nối gốc cho lô cũ, đối soát kết quả gửi chưa rõ để tránh mua hoặc gửi lặp. Không thêm tùy chọn tắt, không bỏ quyền, chữ ký, owner hay gate giấy tờ.
- `0cd111c`: cải thiện đồng bộ và phân trang sản phẩm WB/Ozon, đọc WB theo lô, phân biệt trạng thái/nguồn giấy tờ và giữ cache hợp lệ khi phản hồi mới thiếu hoặc sai cấu trúc.

Kiểm kê nguồn ghi nhận **28 tệp runtime khác 1.6.8: 24 Java, 4 locale**; xem [source inclusion](vncode-169-20261112/source-inclusion.json). Windows-MCP là công cụ phát triển, không thuộc runtime app; prototype mã hóa credential/DPAPI chưa tích hợp vào runtime. Kiểm kê nguồn không tự chứng minh quan hệ byte biên dịch với nguồn.

## Build và kiểm thử

Windows Actions **43131000128**, attempt 2 job **130952771574**, đúng nguồn, kết thúc **SUCCESS**. Bước full Java chạy từ **03:38:41 đến 05:37:36 UTC**. JUnit ghi nhận **4.464 ca trong 565 lớp; 4.462 đạt, 0 failure, 0 error và 2 OS skip**. Hai skip là `Gs1PrefixTemplateStoreTest.privatePermissionsAreAppliedWhereSupported` và `WindowsSecretProtectorTest.neverFallsBackToPlaintextOffWindows`. Native migration, Windows data protection/DPAPI, cài mới/cùng tồn tại/gỡ và nâng cấp thụ động bằng EXE từ 1.6.8 đều được CI ghi nhận đạt. Node source **174 ca** và publisher **185 ca** ở phạm vi riêng; không cộng thành tổng kiểm thử app. [JUnit](vncode-169-20261112/windows-actual-junit-summary.json) · [package review](vncode-169-20261112/package-review.json).

Attempt 1 job **130894820018** FAIL sau khi GitHub báo hosted runner mất liên lạc lúc 03:30:05 UTC. Không có JUnit hay kết quả assertion; ZIP log hợp lệ nhưng rỗng 22 byte, 0 artifact và trạng thái bước 8 cũ còn `in_progress`. Nguyên nhân CPU/bộ nhớ/mạng không được xác định. Rerun failed jobs được nhận lúc 03:31:27 UTC và dùng cùng nguồn/gate; không xóa lịch sử thất bại hay biến nó thành lỗi assertion ứng dụng. [Annotation](vncode-169-20261112/windows-failure-annotations-attempt1.json) · [run attempt 1](vncode-169-20261112/windows-run-failed-attempt1.json).

Kiểm tra package độc lập PASS: [receipt](vncode-169-20261112/package-review.json) SHA-256 `a7a16838a673eed05fe2b69fd10a13231d8831933fda7b6deefc960bc1111b3d`. Artifact Actions **13918353316**, ZIP **530.320.968 byte**, SHA-256 `6c9144d982b1faad81204304ffbb0795bf70754301838e4c36ace9d46fa64221`. Source ZIP chứa 3.193 tệp; JAR portable có 2.000 class, các thay đổi runtime được kiểm tra hiện diện và provenance CI gắn nguồn với build, không có biên dịch độc lập. Đọc tĩnh PE độc lập xác nhận byte MSI nhúng trong EXE khớp MSI đã review; verifier không chạy bộ cài. Các bài 300/10.000 dùng dữ liệu tổng hợp, không phải ghi Catalog thật.

## Tệp công khai và updater

Đã tải thực tế **16 tệp public**, đối chiếu byte/hash với artifact đã review và manifest. Các tệp tải chính:

| Tệp | Kích thước byte | SHA-256 |
| --- | ---: | --- |
| EXE | 149.432.832 | `b516a2bfb2182a9f0e5f82821cb7f299798258e1764eb0897a410be1985060d1` |
| MSI | 148.763.909 | `df41d1c79a73f92208c2d6c6ffc6fbc61dae35e5cb3debdf9b409b0628ceadf4` |
| ZIP portable | 148.353.662 | `a43e26199f27458d5264bd86a2c63f2976783cc0ccfc56be92d5bb61961bea58` |

[Preservation](vncode-169-20261112/public-preservation-verified.json) PASS: giữ đủ **18 release, 290 asset và 47 raw tag ref** cũ; sau công bố **19 release, 306 asset, 48 raw tag ref**. Phép so sánh offline dựa vào các snapshot đã thu thập; provenance/completeness của snapshot phụ thuộc collector và proxy/CA. Không prune hoặc ghi đè bản cũ.

[Updater 1.6.8 gốc sau công bố](vncode-169-20261112/updater-after.json) báo **AVAILABLE 1.6.9** và chọn đúng MSI/hash/size ở trên; kiểm integrity xác nhận cả 75 JAR gốc không đổi. Probe đọc mạng qua proxy/CA Cloud trên Linux và chỉ chọn asset theo Windows sau bước mạng; proxy có thể gắn quyền repository. Không suy ra đã hiển thị popup, cài xong trên máy seller hay truy cập anonymous. Trước công bố binary gốc báo CURRENT 1.6.8.

## Giới hạn và bàn giao tiếp

Fresh security run **43131225703** so với 1.6.8: 8 finding Java cũ, 0 mới/0 giải quyết; **Java gate FAIL** do một finding mức cao. GS1 extraction error cũ còn và làm hạn chế phạm vi; JavaScript 0 finding. Đây không phải kết quả security toàn bộ xanh. [So sánh](vncode-169-20261112/security-comparison.json).

WB thật ở một snapshot đọc đủ **388/388 bài, 4 HTTP, 23,208 giây**; không chứng minh mọi cửa hàng hoặc Windows seller. Ozon thật trả **403/code 7**, chưa nghiệm thu seller. Không seller API write, mua KIZ có phí, ký thật, license activation, Gmail hay Portal deploy trong lượt phát hành. Chưa nghiệm thu OMS/CryptoPro seller hoặc mua/ký đầu-cuối. Giữ profile, feed, jobs và journal khi nâng cấp; không đổi release/tag/asset 1.6.8 và 1.6.9 đã immutable. Các lượt RED và checkpoint lịch sử được giữ nguyên trong [bộ nhớ dự án](../PROJECT_MEMORY.md) và [sổ sau 1.6.8](../memory/POST-1.6.8.md).
