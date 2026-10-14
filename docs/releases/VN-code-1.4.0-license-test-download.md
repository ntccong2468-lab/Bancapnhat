# Tải bộ cài VNCode 1.4.0 để kiểm tra license

[Bản nháp trên GitHub](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/untagged-fba41945e21453e7c37e). Đăng nhập tài khoản GitHub có quyền repo, mở Assets rồi tải **VN-code-1.4.0-Windows-x64.exe**. Đây là EXE Windows x64 thật, Java đã đóng gói; không phải mã nguồn ZIP.

SHA-256: `c54f547819112f2fa10a80d73805e086b4f17af39b1cca5b28715706c50a4110`. Có file checksum và build-info.json trong Assets. [Build đã đạt](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38492821195); [workflow đính kèm đã đạt](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/38495962520).

Đóng VNCode trước khi chạy bộ cài. Kiểm tra limit của đúng khách trên vncode.id.vn: nếu thử 10 cửa hàng, đặt Cửa hàng=10 và Lưu giới hạn; mặc định hiện là 1 máy, 1 cửa hàng, offline 72 giờ. Trong app nhập Gmail, xác minh rồi nhập mã license đã được cấp. Ghi lại trạng thái kích hoạt hoặc mã lỗi.

Windows đã qua 1.175 kiểm thử Java/JavaFX (2 trường hợp dành cho nền tảng khác bỏ qua), 46 kiểm thử Node, launcher/GS1/DPAPI, 10 cửa hàng và ba DPI, cùng nâng cấp giữ dữ liệu từ năm bản cũ. Bộ cài chưa ký Authenticode. Bản này ở Draft để thử license; chưa phát hành công khai hoặc cập nhật tự động. Khóa công khai vncode-1 đã pin; cấu hình website đã báo mail_ready=true/signing_ready=true, nhưng chữ ký/kích hoạt thật và nghiệm thu backend/thiết bị/offline còn chờ. Không dùng kết quả fixture để ghi nhận những mục này đạt.
