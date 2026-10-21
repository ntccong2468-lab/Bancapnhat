# Cài skill find-reverse-engineered cho VN Code

Ngày kiểm tra: 21.10.2026 (Europe/Moscow). Nhánh riêng:
`chore/install-find-reverse-engineered-skill-20261021`.

## Nguồn và phạm vi

Nguồn: <https://github.com/alexgaoth/awesome-reverse-engineered>, commit
`586cffd011070efff6875b3f3921b05637f67786`.

Repository nguồn gồm dữ liệu `data/entries.json`, các bảng danh mục, website,
script bảo trì và một plugin Claude. Skill dùng được độc lập nằm tại
`plugin/skills/find-reverse-engineered/SKILL.md`; script duy nhất cần cho luồng
tìm kiếm là `scripts/re_index.py`. Không cần cài manifest `.claude-plugin`,
website, npm hoặc script refresh toàn repository.

Đã cài tại `.agents/skills/find-reverse-engineered/` với:

- `SKILL.md` có tên/mô tả theo cấu trúc skill Codex và hướng dẫn dùng trong Web.
- Script Python gốc đã điều chỉnh đường dẫn/caching; không thêm dependency.
- Snapshot đủ 12.866 mục; checksum khớp bản nguồn đã ghim.
- Giấy phép MIT cho mã và CC0-1.0 cho dữ liệu, giữ nguyên toàn văn.
- Bản SKILL.md gốc, nguồn/commit/checksum, kiểm thử và kết quả tìm kiếm.

Thay đổi thích nghi: snapshot nằm trong skill thay vì phụ thuộc `data/` của
repository nguồn; cache dùng đường dẫn ghi được trong Codex; có chế độ offline;
cache lỗi/không ghi được không chặn tìm kiếm; đọc README qua `gh api` đã cấu hình,
không trích xuất token bằng `gh auth token`. Không chạy mã tải từ Internet bằng
`curl | python`. Không thay đổi mã nguồn, cấu hình build hay bản phát hành VN Code.

## Kiểm tra thực tế trên Codex Web

| Kiểm tra | Kết quả |
| --- | --- |
| Clone repository nguồn qua mạng hiện có | Đạt |
| Python chuẩn, không pip/npm | Đạt |
| Metadata skill: tên khớp thư mục, có description | Đạt |
| Bộ kiểm tra tương thích offline | 7/7 đạt |
| Tìm kiếm khi mạng bị chặn giả lập, cache hỏng hoặc không ghi được | Đạt, dùng snapshot |
| Chạy script từ thư mục khác | Đạt |
| Tải chỉ mục online vào cache `/tmp` | Đạt, 12.866 mục |
| `search` cho 8 bộ từ khóa Nga/Anh/phiên âm | Đạt; kết quả rỗng được giữ nguyên |
| `categories` | Đạt |
| `info`: đọc README thật | Đạt cho 5 repository được chọn |
| `clone`: clone tham chiếu vào `/tmp` | Đạt với `glmn/wb-private-api`; không chạy mã của dự án |

Lệnh kiểm tra:

```bash
python3 -B -m unittest discover -s .agents/skills/find-reverse-engineered/tests -v
RE_INDEX_OFFLINE=1 python3 .agents/skills/find-reverse-engineered/scripts/re_index.py search wildberries --json
RE_INDEX_CACHE_DIR=/tmp/vncode-re-skill-online-cache python3 .agents/skills/find-reverse-engineered/scripts/re_index.py search wildberries --json
```

Đây là kiểm tra khả năng chạy script trong môi trường Codex Web hiện tại.
Không có kiểm thử mở một phiên Codex mới để quan sát skill tự xuất hiện trong
danh sách hệ thống. Để sử dụng ở phiên tiếp theo, checkout/đưa nhánh này vào nhánh
làm việc của phiên đó; có thể yêu cầu `$find-reverse-engineered` hoặc chỉ rõ đường
dẫn SKILL.md. Nếu phiên đang mở chưa nhận skill mới, mở phiên mới trên nhánh đã có
các tệp này. Không tuyên bố skill đã được hot-reload vào catalog của phiên hiện tại.

## Kết quả tìm kiếm

Phải phân biệt chỉ mục của skill với tìm kiếm GitHub bổ sung. Chỉ mục không phải
danh sách đầy đủ mọi SDK/API. Thuật toán gốc trả cả kết quả khớp một phần khi có
nhiều từ khóa; các mục chỉ chứa từ `catalog` không chứng minh liên quan Catalog CRPT.

| Nhóm | Trong chỉ mục của skill | GitHub bổ sung đã đọc README |
| --- | --- | --- |
| Честный ЗНАК | Không có kết quả phù hợp với tên Nga, `chestny znak`, `crpt` | [FairMark/FairMarkClient](https://github.com/FairMark/FairMarkClient): MIT, .NET, True API/SUZ/EDO Lite; cập nhật 2022 |
| National Catalog | Không có kết quả khớp cả `national catalog`; tên Nga cũng không có | [NationalCatalogApiClient](https://github.com/Center4ResearchPerspectiveTechnologies/NationalCatalogApiClient): Apache-2.0, PHP, API v3, cập nhật 2019 |
| CryptoPro | Không có kết quả với `CryptoPro`/`криптопро` | [CryptoPro/libcore](https://github.com/CryptoPro/libcore): thuộc tổ chức CryptoPro, .NET; GitHub không nhận diện license. [AlexMAS/GostCryptography](https://github.com/AlexMAS/GostCryptography): MIT, .NET, CMS/PKCS#7/XML |
| Wildberries API | Có [glmn/wb-private-api](https://github.com/glmn/wb-private-api) | README xác nhận JavaScript/private API; GitHub không nhận diện license |

Các client cũ chỉ là tài liệu tham khảo, chưa xác minh tương thích API hiện hành.
Các dự án .NET/PHP không phải module có thể đưa thẳng vào app Java. Metadata license
rỗng chưa xác định quyền tái sử dụng; phải kiểm tra giấy phép trước khi sao chép mã.
Với Wildberries, ưu tiên API seller chính thức tại <https://dev.wildberries.ru/>.
Không dùng kết quả private API để thay thế luồng seller chính thức khi không cần.

Không gọi API seller, không dùng khóa WB/Ozon/Catalog hoặc chữ ký, không tạo/sửa
thẻ, không mua KIZ, không chạy SDK tìm được. Không kết luận các repository tìm được
sẽ sửa được lỗi production VN Code chỉ từ kết quả tìm kiếm.

Commit HEAD của từng dự án, ngày cập nhật, license metadata, từ khóa và các kết quả
khớp một phần được lưu trong [SEARCH-RESULTS.json](SEARCH-RESULTS.json).
Nguồn và checksum đầu vào nằm trong [SOURCE.json](SOURCE.json).
