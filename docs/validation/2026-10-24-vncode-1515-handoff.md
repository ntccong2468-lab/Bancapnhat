# Bàn giao VNCode 1.5.15

Đã công bố **Latest v1.5.15 lúc 19:47:09 UTC ngày 24.10.2026**, Release `422945241`, bất biến. [Trang phát hành](https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v1.5.15) · [Bộ cài EXE](https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.5.15/VN-code-1.5.15-Windows-x64.exe).

## Mã và nội dung

- Nhánh `release/vn-code-1.5.15`; mã ứng dụng và tag `9f6155dbbbdd6af80e09897f2f2863b479b97a33`. Hash cây nguồn `92e3150566270de6ef53ef92592bb4f1e112b4e4c78fd0e843a8097decb8e4c7`.
- Kế thừa toàn bộ 1.5.14. Thêm tối ưu gửi từng thẻ, tự tiếp tục lỗi tạm thời, khôi phục pause tự động của bản cũ và sửa cấp/gửi bị HTTP 429 cho WB/Ozon. Không đổi thành feed nhiều thẻ. Giữ deadline, GTIN/feed/payload, owner/scope, thao tác Tạm dừng/Hủy và cổng đối soát lần ghi chưa rõ.
- Phần ứng dụng đã chốt trước dựng; sau đó chỉ thêm quy trình công bố, bằng chứng và bàn giao. Không thay bộ cài hoặc tag sau kiểm thử.

## Bằng chứng

- [Windows build 40123187426](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40123187426) và Linux đúng SHA: **3.009 ca, 3.007 đạt, 2 bỏ qua theo OS, 0 lỗi/thất bại**. Công cụ Node 117/117; bộ hồi quy Catalog trước đóng gói 1.246/1.246, lô giả lập 117/117 với nhiều lần giới hạn và trả kết quả chậm.
- Native Windows mở app, DPAPI, cài/gỡ và nâng cấp thụ động bằng EXE từ 1.5.14 giữ dữ liệu đều đạt. Giữ UpgradeCode `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`, định danh và thư mục dữ liệu.
- [Công bố 40124695253](https://github.com/ntccong2468-lab/Bancapnhat/actions/runs/40124695253) từ `5a7c0fc` thành công: 16 tài sản; tải công khai và kiểm tra byte/checksum đủ 11 tài sản bắt buộc, manifest/checksum và tag. Gói đúng phiên bản, các phương thức sửa Catalog và 1.149 file nguồn/test/pom khớp nguồn chốt.
- Bộ cập nhật từ JAR Windows đã phát hành 1.5.14 đọc JSON `/releases/latest` công khai, nhận 1.5.15 mới hơn và chọn đúng MSI/hash/kích thước. Không suy ra popup đã hiện trên thiết bị seller.

| Tài sản | Kích thước | SHA256 |
| --- | ---: | --- |
| VN-code-1.5.15-Windows-x64.exe | 147.270.144 byte | `c70aea8d2cf5697e046ecf35095c89d959977d8130578d0c66314489b5cc3349` |
| VN-code-1.5.15-Windows-x64.msi | 146.601.225 byte | `d4cc481b46d3eaf4c5099abbfcf4575f33ec0ef19dbcd7e5b6533d5898b192d5` |

[Biên nhận công bố](2026-10-24-vncode-1515-published-latest.json) · [Tải công khai](2026-10-24-vncode-1515-public-verification.json) · [Full suite trước công bố](2026-10-24-vncode-1515-prebuild.json).

## Giới hạn và kinh nghiệm

Không ghi Catalog/sàn production, ký bằng chứng thư seller hoặc mua KIZ có phí trong lượt phát hành. Kiểm thử nghiệp vụ dùng fixture/HTTP localhost; native và upgrade chạy trên Windows thật của CI. Bộ cài chưa có chữ ký Authenticode. Giữ các release cũ.

Lượt đầu `40123011112` bị hủy sớm để tăng riêng timeout bài stress 117 thẻ theo tốc độ SQLite native Windows. Mã mới đã chạy lại đầy đủ; không dùng kết quả bị hủy. Probe cập nhật cục bộ phải có đủ thư viện của runtime 1.5.14, gồm SLF4J/OkHttp; thiếu classpath trong probe không phải lỗi ứng dụng và đã được sửa trước kết luận.

Phiên tiếp theo dùng bản nền 1.5.15 đã kiểm chứng, tăng phiên bản khi sửa ứng dụng; giữ danh tính giao dịch và dữ liệu đang dở. Không ghi đè bản bất biến.
