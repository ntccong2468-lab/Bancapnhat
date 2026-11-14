# VN Code 1.6.10 — mã nguồn để lưu và build

Kế thừa bản1.6.9, sửa phục hồi lô KIZ chờ ký và popup giữ sai bảng màu sau đổi theme. Nguồn đã kiểm `75691c68fa14f728856b0418fb6008083787ef7c`; tài liệu sau commit này không đổi src/POM/test. Đây là ZIP mã nguồn, chưa phải bộ cài hoặc bản Latest.

- [Báo cáo bốn yêu cầu, sửa lỗi và kiểm thử](docs/validation/2026-11-14-kiz-source-repair.md).
- [Repo rút gọn/hồi quy để duyệt; chưa cài](docs/research/2026-11-14-code-quality-proposals.md).
- [WBCode1.3.25 đọc GTIN và ghép thẻ Ozon](docs/research/wbcode-20261114/README.md).
- [Thử ghép đúng152NAU,size2XL/XXL/52 trênAPIthật](docs/research/simple-ozon-match-20261114/README.md).
- [Điểm tiếp tục cho AI](docs/memory/CURRENT.md).

Build cần **JDK25** và Maven wrapper. Windows: `mvnw.cmd -B clean verify`; Linux có display/Xvfb: `xvfb-run -a ./mvnw -B clean verify`. Bộ test có danh mục lớn nên chạy lâu. Kiểm thử hiện có: 4540 ca Java/569 suite,0failure/error,2skip có danh tính; không chứng minh mua/ký SUZ thật trên máy seller. Giữ toàn bộ src/main,src/test,resources,wrapper,build scripts. Không cần dữ liệu/API seller để build nguồn.

Checksum và receipt đọc lại ZIP được giao riêng cạnh ZIP; báo cáo trong nguồn không thể tự chứa hash của chính gói đang chứa nó. Không kèm appdata seller, bộ cài hoặc code dịch ngược WBCode.
