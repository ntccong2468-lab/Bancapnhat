# Đối chiếu bảo mật VN Code 1.6.9

- Run **43131225703** của nguồn app `4ef0f1e2b8ef7b28190de3034b0c67ebf615be0b` kết thúc **failure**: Java CodeQL thất bại tại security gate do finding cũ tiếp tục tồn tại.
- So sánh raw SARIF thực tế với bản 1.6.8 đã công bố: run **42828869332**, nguồn `38906a2108501029d9609ed1d071ee987c8b2637`. Đã kiểm SHA-256 archive theo metadata GitHub và so multiset bản ghi chuẩn hóa, giữ số lần xuất hiện, vị trí, fingerprints, thông điệp và toàn bộ code flows.
- **0 finding mới, 0 finding được giải quyết**; bản ghi Java giữ nguyên **8 finding / 1 bị gate chặn**. Finding bị chặn có severity **7,5**, rule `java/potentially-weak-cryptographic-algorithm`, tại `CryptoProSignedCertificateReader.java:71`.
- JavaScript giữ **0 finding**; job JavaScript và lint đạt. OpenQodex và dependency review được bỏ qua theo điều kiện `workflow_dispatch`, không bổ sung độ phủ quét trong lượt này.
- Cảnh báo/lỗi công cụ không đổi: **1 lỗi extraction GS1** tại `Gs1SignatureInk.java`; độ phủ phân tích Java vẫn có giới hạn.
- Không thêm suppression, không đổi workflow hoặc security gate giữa hai nguồn. Lượt lập hồ sơ này không sửa source, không chạy lại workflow và không phát hành.

Kết quả bảo mật vẫn là **Java gate thất bại**, với finding cũ và giới hạn extraction nêu trên. Bằng chứng chi tiết: [security-comparison.json](security-comparison.json), [metadata workflow](security-workflow-final.json) và [manifest artifact](security-artifact-manifest.json).
