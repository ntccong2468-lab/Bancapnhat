# Công cụ đã dùng cho 1.6.9

Các helper được lưu nguyên byte sau khi chạy thật. [toolchain.json](../toolchain.json) và [manifest](../evidence-manifest.json) ghi SHA-256. Helper chuẩn bị/negative control chỉ kiểm bản thân công cụ; kết luận release thật nằm ở [package-review.json](../package-review.json), [public-preservation-verified.json](../public-preservation-verified.json) và [updater-verification.json](../updater-verification.json).

| Tệp | Phạm vi |
| --- | --- |
| `verify-package.py` | Kiểm offline 16 payload, raw Actions ZIP digest, nguồn Git, 28 runtime file, production JAR, XML suite/skip/stress và MSI trong PE EXE. SHA `14a790dc658ffdb3f4cdf30259e2b8ef15898b2abd89d7d107f3f6054a1c66b9`. |
| `verify-public.py` | Kiểm offline snapshot GitHub trước/sau, 16 byte public, release/tag immutable, bảo toàn bản cũ và bằng chứng updater. SHA `98da641b5298bfd9208460e276492687b2cf02c49ba0c35684a018a122c94efd`. |
| `Installed168UpdateProbe.java`, `run-probe.py` | Gọi updater từ 75 JAR **gốc đã phát hành 1.6.8**, không rebuild app, không override version/channel, không cài hay gọi seller API. Giữ SHA nguồn/class/runner/JAR trong receipt. |
| `collect-build.py`, `collect-public.py` | Chỉ GET metadata/download; khóa đúng nguồn/run/release và kiểm digest trước khi dùng byte. Không publish. |
| `compare_sarif.py`, `build_security_summary.py` | Đối chiếu finding/codeflow/notification và gate thực tế; không suppress finding. Raw SARIF trước/sau cùng metadata nằm ở `../security/`. |

Các công cụ khóa riêng nguồn `4ef0f1e…`, build `43131000128` attempt 2, artifact `13918353316`, release `442540592` và phiên bản 1.6.9; không thay pin để dùng làm chứng nhận cho bản khác.

Đọc `--help` rồi truyền **đầy đủ đường dẫn** khi chạy offline ở nơi khác. Một số default giữ đường dẫn thực tế `/tmp/vncode-169-release-audit` và `/tmp/vncode-169-exact168-probe`; cần khôi phục đúng cây tệp hoặc chỉ định lại tất cả đầu vào. `verify-package.py` bắt buộc `--expected-tests 4464 --expected-suites 565`; `verify-public.py` bắt buộc `--release-id 442540592`. Không suy số từ forecast hoặc sửa input để làm xanh.

Raw Actions ZIP gốc 530.320.968 byte/SHA `6c9144d982b1faad81204304ffbb0795bf70754301838e4c36ace9d46fa64221` không được commit vào Git; artifact có retention 90 ngày. Muốn chạy lại toàn bộ kiểm gói phải còn đúng ZIP gốc, không thể tạo lại digest đó bằng cách tự nén 16 tệp public. Các receipt đã lưu vẫn là chứng cứ lịch sử; helper không tự thay thế đầu vào bị thiếu. Payload public được lấy từ release immutable và phải so lại hash với receipt.

Probe online sau này có thể thấy một Latest mới hơn; không ghi đè chứng cứ before/after đã lưu hoặc gọi kết quả mới là lượt kiểm lịch sử. Để khôi phục probe 1.6.8, dùng portable gốc SHA `5e4a8ac20439df64a61b847571f9cd8581b817f661749481d29fd6c76126e141`, 148.326.903 byte; đối chiếu toàn bộ manifest JAR và source/class/runner pins. Thay JDK hoặc compile lại probe phải kiểm bytecode, không được giả nhận là binary app gốc được build lại.

Đây là kiểm provenance/byte và kết quả CI; nguồn–JAR không được chứng minh bằng một lượt compile độc lập thứ hai. Không có mua KIZ/ký hay nghiệm thu máy seller thật trong các helper này.
