---
name: openqodex
description: Dùng khi người dùng yêu cầu quét hoặc review thay đổi VN Code bằng OpenQodex; kiểm tra phạm vi, kết quả từng scanner và báo cáo giới hạn trước khi sửa lỗi có bằng chứng.
---

# Kiểm tra thay đổi với OpenQodex

Đọc `AGENTS.md` và trạng thái Git trước. Giữ nguyên thay đổi sẵn có, giới hạn phạm vi theo yêu cầu và dùng dữ liệu tổng hợp để kiểm thử. Không chạy lệnh ghi lên WB, Ozon, Catalog hoặc dịch vụ license, không mua KIZ, không in hay đưa credential vào báo cáo.

1. Xác định commit cơ sở và các tệp cần kiểm tra. OpenQodex `scan` chỉ chạy các scanner phù hợp với thay đổi và không có AI reviewer. Finding ngoài dòng thay đổi có thể không ảnh hưởng verdict; các cảnh báo dependency ngoài dòng thay đổi chỉ có tính tham khảo. Không gọi kết quả đó là review toàn repository.
2. Ưu tiên kết quả workflow scanner-only của repository khi có; xác định rõ run, commit, scanner thực tế và artifact. Chỉ chạy CLI đã được kiểm tra nguồn, phiên bản và quyền của môi trường. `scan --base <ref> --format sarif --report-dir <thư-mục-tạm-tuyệt-đối>` là dạng lệnh được tài liệu upstream hỗ trợ; kiểm tra ref là hợp lệ và dùng thư mục report riêng. Không tự cài đặt hoặc kích hoạt hook để chạy một lượt kiểm tra.
3. Đọc `scan.json`, cảnh báo và mã thoát. Scanner `failed`, `not_installed`, `installing`, `untrusted` hoặc bị tắt không phải là thành công. Mã thoát 0 không chứng minh mọi scanner đã chạy: OpenQodex không tự biến mọi lỗi scanner thành mã lỗi. `--offline` bỏ qua Semgrep và OSV; báo phần thiếu. Không xuất nguyên nội dung secret được phát hiện.
4. Đối chiếu từng finding với nguồn và đường gọi; nêu file, vị trí, mức độ, bằng chứng, khả năng false positive và phạm vi chưa quét. Tái hiện bằng dữ liệu giả khi thực tế cho phép. Chỉ sửa lỗi được chứng minh và trong phạm vi người dùng cho phép; kiểm thử hồi quy và báo kết quả thực tế. Giữ các kiểm tra Java/Maven, Node, bảo mật và nền tảng hiện có; scanner không thay thế test hoặc xác minh runtime.
5. Chỉ gọi kết quả là review độc lập khi reviewer riêng thực sự khởi chạy và hoàn thành; nếu unavailable, timeout hoặc exit 2, báo chính xác. Sự có mặt của `codex` CLI trong Codex Cloud không chứng minh tiến trình reviewer lồng có thể hoạt động. Không tự khởi tạo login, yêu cầu hoặc cấu hình API key, chạy `init`/`trust`, cài hook, bật AI review qua dịch vụ bên ngoài hay gửi mã tới dịch vụ đó. Chỉ thực hiện các hành động đó khi phù hợp với quyền và chỉ dẫn cụ thể của người dùng.

Trong GitHub Action 0.16.0, `review: off` là scanner-only, `fail-on-tool-error: true` không tự bảo đảm mọi scanner con đã chạy, và `upload-sarif: false` không gửi kết quả tới GitHub code scanning. Truy cập mạng có thể lấy quy tắc Semgrep và gửi tên/phiên bản dependency tới osv.dev. Scan theo thay đổi và kiểm tra định kỳ toàn repository là hai phạm vi khác nhau; đọc cấu hình workflow trước khi báo cáo.

Nguồn tham khảo: [OpenQodex 0.16.0](https://github.com/openqodex/openqodex/tree/6d879d65de94ab201bddd9ccad76e1d1bcff70ca), [CLI](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/cli.md), [scanners](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/scanners.md), [GitHub Action](https://github.com/openqodex/openqodex/blob/6d879d65de94ab201bddd9ccad76e1d1bcff70ca/docs/github-action.md).
