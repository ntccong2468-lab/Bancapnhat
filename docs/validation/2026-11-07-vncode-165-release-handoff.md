# Phát hành bản gộp VN Code 1.6.5

## Quyền và nguồn

Người dùng đã yêu cầu gom tất cả chỉnh sửa và đưa lên Latest để app nhận cập nhật. Yêu cầu mới thay thế các mốc trước chỉ gom/chưa phát hành. Không mở rộng sang ghi API seller, mua KIZ, kích hoạt license hay triển khai portal trong lượt phát hành.

Nguồn app cố định: `4ffea8a2732ad04d1a3fc481d472867ba2683a8a`, nhánh `release/vn-code-1.6.5`. Runtime, test Java và POM giống nguồn chức năng `cae86ef`; kế thừa Latest 1.6.4 và toàn bộ sửa Znack/bốn trạng thái, năm nút sản phẩm/giấy tờ tuyên bố, GTIN/KIZ mapping, PDF kết hợp, nhận đơn/thay KIZ Ozon và liên kết Catalog khác tên/article đã duyệt. [Đối chiếu 10 mốc và hash 35 tệp runtime](2026-11-07-vncode-165-source-inclusion.json).

## Kiểm chứng đang thực hiện

- Build Windows đầy đủ: [run 42382780334](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42382780334), workflow `publish-vncode-baseline.yml`. Đã pin đúng SHA và Latest cũ 1.6.4; preflight Windows đạt; full Java đang chạy tại checkpoint này. Không tạo tag hoặc đổi Latest trước khi bộ cài cuối và các smoke đạt.
- Dispatch `42382737001` có input SHA gõ sai đã được hủy trước đóng gói, không đủ điều kiện dùng làm bản phát hành. Các dispatch tiếp theo lấy SHA trực tiếp từ Git.
- Công cụ phát hành: `node --test tools/*.test.mjs`: **155/155**, không bỏ qua, gồm năm ca chặn sai nguồn/bộ cài/smoke/checksum/draft của 1.6.5. Publisher mới giữ các gate bản 1.6.4 và chặn chạy khi chưa pin release ID thực.
- [Security run 42383419649](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/42383419649): lint/audit và JavaScript CodeQL đạt; Java SARIF gate thất bại với 8 finding, gồm 1 high SHA-1 tại định danh chứng thư CryptoPro. Cả tám vị trí thuộc source không đổi từ 1.6.4. [Biên nhận/triage](2026-11-07-vncode-165-security.json), [đánh giá mục đích thumbprint](2026-11-03-release-security-review.md). Không sửa query/ngưỡng/gate; không gọi toàn bộ kiểm tra bảo mật là đạt. OpenQodex và Dependency Review bị skip theo điều kiện manual dispatch, không phải một lần scan thành công mới.
- Publisher `571d44b` được kiểm ở security run `42384190788`: lint/audit và CodeQL JS đạt; Java giữ 8 finding/1 high. [OpenQodex/Gitleaks cục bộ](2026-11-07-vncode-165-source-scan.json) đã chạy trên 2.879 tệp trong snapshot source, đối chiếu mười cảnh báo với file không đổi của lượt triage trước; không xác nhận credential auth thật. Chỉ chọn Gitleaks, 21 scanner khác tắt; không gọi đây là review AI hoặc scan Git history.
- Đã tải portable 1.6.4 công khai, kiểm đúng SHA-256 GitHub rồi trích JAR thật để kiểm updater sau công bố. Chưa quan sát thông báo trên máy seller.

## Hoàn tất tiếp theo

Chờ đúng run Windows hoàn thành; kiểm artifact cuối, source ZIP, JAR, toàn bộ smoke và hash. Tạo đúng một draft/tag trên SHA đã build, pin ID tạo trả về, tải đủ tài sản trước Publish vì GitHub khóa release sau công bố. Kiểm lại Latest/16 asset bằng tải công khai và chạy updater của binary 1.6.4. Giữ nguyên mọi bản phát hành cũ; lưu biên nhận, cập nhật bộ nhớ và push bàn giao cuối.

## Giới hạn nghiệp vụ

Các biên nhận API thật trước đó giữ nguyên ngày/phạm vi: chín đơn gilet Ozon và một GTIN được ghi/đọc lại. Chúng không chứng minh mọi đơn phát sinh trong tương lai, nhánh thay mã bị từ chối đã chạy thật, mua KIZ có phí hoặc toàn bộ hàng đợi PKI/UI đã được nghiệm thu. Lượt Windows đóng gói dùng dữ liệu kiểm thử riêng; không giả cờ live API thành true.

## Bộ cài cuối đã kiểm trước công bố

- Windows build `42382780334` hoàn tất thành công lúc 16:17 UTC ngày 07.11.2026; đúng nguồn app `4ffea8a`. **3.935 ca / 524 lớp, 0 thất bại, 0 lỗi, 2 bỏ qua theo hệ thống; 3.933 đạt**. Native launcher, migration 3→6/snapshot, DPAPI, cài/gỡ độc lập với WCode và nâng cấp thụ động từ 1.6.4 giữ dữ liệu đều đạt.
- Đã tải artifact `13581316597`: đủ 16 tệp, checksum/manifest/Git bundle đạt; chính EXE và MSI khớp fingerprint của smoke nâng cấp. [Build/gates và toàn bộ hash](2026-11-07-vncode-165-build.json).
- [Đối chiếu payload](2026-11-07-vncode-165-package.json): 2.875 tệp source ZIP trùng Git (2.559 tệp chỉ chuẩn hóa CRLF); JAR 1.949 class/Java 25 đúng phiên bản, có class sửa mới, không fixture/probe, launcher production và đúng feed cập nhật. Bộ cài chưa ký Authenticode.
- Tag nhẹ `v1.6.5` đã trỏ đúng `4ffea8a`. Draft mới **437689422**, pin từ phản hồi POST tạo thành công; chưa công bố ở checkpoint này. Trường GitHub `created_at` theo ngày commit nguồn, không dùng nó để suy draft đã tồn tại từ trước hoặc tạo thêm draft.
- Quyền API/GitHub/Git có gián đoạn 401 khoảng 18:30 MSK, được kiểm lại đạt 18:42 MSK; agent không đổi credential/cấu hình và không chạy lại build. Chưa xác định nguyên nhân refresh phía nền tảng.
