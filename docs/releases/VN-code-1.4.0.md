# VN code 1.4.0 Preview

**Chưa phát hành.** Client đăng ký Gmail/OTP và nhập khóa license đã được viết theo Portal 0.3.1. [Hợp đồng và các điểm cần nghiệm thu](../research/2026-10-13-vn-code-desktop-license-contract.md). Đã pin public key `vncode-1` do chủ website cung cấp; chưa xác minh server ký bằng khóa tương ứng hoặc nghiệm thu website/Windows. Không phát hành khi chưa hoàn thành các cổng này.

Bản cập nhật của cùng VN code Windows do Nguyễn Thành Công phát triển và quản lý.
Giữ `%LOCALAPPDATA%\VNcodeApp`, `%LOCALAPPDATA%\VNcodeData`, shop, mẫu tem và lịch sử;
UpgradeCode giữ `8CBBA0E2-6E73-4F56-9101-6BC0948D3C72`. Logo VN trắng trên nền đen.

## Sử dụng phần mới

- **Sản phẩm:** chọn shop, đồng bộ khi cần, tìm kiếm/lọc; chọn trang hoặc toàn bộ
  kết quả đã chốt. Sửa nhãn hiệu, chất liệu, TN VED hoặc giấy tờ bằng bảng giá trị
  trước/sau rồi xác nhận. Chuyển shop không thay chủ sở hữu của tác vụ đã gửi.
  Nút Đồng bộ tải catalog và kiểm tra chi tiết/giấy tờ tối đa 100 dòng của trang
  đang xem; các trang khác cần đồng bộ riêng. Cache đọc mới được kiểm tra đúng shop;
  giấy tờ đã bị sàn gỡ được loại khỏi cache sau lần đọc đầy đủ.
- **GS1:** kiểm tra chứng thư còn hạn và INN trong Cấu hình Znack. Hạn mức GTIN
  National Catalog và tiền Честный ЗНАК là hai nguồn riêng. Chọn đúng nhóm hàng/
  tài khoản cho tiền; số chưa lấy được hiển thị trạng thái chưa lấy được.
- **Công cụ → Sao chép bài đăng:** chọn bài nguồn và shop WB/Ozon đích, ánh xạ
  danh mục, thuộc tính, giá, size và kích thước. Kiểm tra giấy tờ/GTIN đúng biến
  thể. Có preview từng đích, tối đa 100 bài × 5 shop/lần. Tài liệu Ozon cần tệp
  gốc trong giới hạn 10 MiB; phần không thể chuyển phải được xử lý rõ ràng.
  Sửa giấy tờ hàng loạt tối đa 16 tài liệu, tổng tệp gốc tối đa 10 MiB; tệp dùng
  chung được lưu một lần. Preview có ngân sách dữ liệu 64 MiB, vượt phải chia batch.
- **Tin tức:** đồng bộ từ WB; xem link và nội dung gốc. Muốn dịch tiếng Việt,
  cấu hình service account Yandex Translate có quyền/billing; dịch là dịch vụ
  tùy chọn. Khi không dịch được, giữ nguyên nội dung gốc.
- **FBS Ozon:** đơn mới → xác nhận đóng gói/chờ giao → đang giao. Có tìm kiếm,
  sắp xếp, phân trang và kết quả từng đơn. Kiểm tra marking/KIZ trước in.
  Kết quả gửi không rõ cần đối soát, không bấm gửi lại thay cho đối soát.

TN VED không có mục sidebar độc lập, vẫn có trong thông tin và tác vụ cần mã.
Các chức năng in/mapping/mẫu tem/GS1 có sẵn được giữ trong cùng ứng dụng.

## GS1 bổ sung theo Wbcode 1.3.4–1.3.7

- Bố cục thành viên, sáu kiểm tra theo lưới 2 × 3 và bảng GCP/GLN/hạn hiệu lực/GTIN
  bên trái; hộp thư riêng bên phải, tự xếp dọc khi cửa sổ hẹp. Nút Kiểm tra tất cả
  lấy Catalog và CZ theo hai phiên xác thực riêng, không tự hỏi PIN khi mở màn hình.
  Bảng kiểm tra chữ ký, đăng nhập CZ, thỏa thuận, số dư, thành viên và GTIN;
  sidebar có `!` khi cần xử lý. Dữ liệu chưa rõ giữ chưa xác minh.
- INN doanh nghiệp và tên lấy từ chứng thư thực đã ký CMS; certmgr thiếu thông
  tin không làm mất metadata đã xác minh. Số dư giữ phạm vi nhóm hàng/tài khoản.
- Đơn đã gửi bị khóa gửi lại; lưu bản nháp cũng kiểm tra trạng thái hiện tại.
  Doanh nghiệp đã nộp đơn có nút hỏi hóa đơn phí gia nhập; bị loại có thư phục hồi
  riêng. Hết GTIN chỉ khi toàn bộ prefix đang hoạt động đã xác minh đều hết.
- Xin thêm prefix: tự lấy doanh nghiệp, INN, liên hệ và mọi GCP từ cùng tài khoản
  National Catalog; tên người ký từ chứng thư thực. Chọn 999 hoặc 99.999 mã,
  xem cảnh báo tổng hạn mức của mọi tiền tố. Chỉ gửi khi thành viên còn hiệu lực
  và toàn bộ hạn mức đã xác minh đều bằng 0; đọc mới cả trước nhận tác vụ và ngay
  trước gửi SMTP. Ngày chưa rõ không được suy thành đã đóng phí vô thời hạn.
- Seller chọn mẫu Word 01.04.01. VNCode giữ nội dung/bố cục hỗ trợ, tự điền
  doanh nghiệp/INN/mọi GCP/ngày/người ký, tải DOCX để in/đóng dấu hoặc vẽ chữ ký
  trong app rồi duyệt PDF thực tế. Có thể đính kèm PDF một trang hay JPG/PNG đơn
  giấy đã ký; ảnh tự chỉnh hướng và chuyển PDF. Chữ ký riêng không lưu. Bản chưa
  ký chỉ để xem; sửa dữ liệu làm mất xác nhận duyệt. Mẫu lưu riêng theo doanh nghiệp.
- Sau gửi, có thể bật và duyệt tự nhắc một lần sau 7 ngày. VNCode phải đang mở;
  kiểm tra mỗi 10 phút, đọc IMAP trước gửi, dừng khi có phản hồi hoặc thay đổi
  hộp thư/nội dung/quyền. Không tự thử lại khi chưa rõ kết quả hay chưa gửi.

Mẫu Word cá nhân bạn cung cấp đã được kiểm tra đọc/điền, không đóng gói vào ứng dụng
và không coi là bằng chứng nguồn mẫu chính thức. Bộ chuyển Word hỗ trợ đơn một trang
với đoạn văn đơn giản; mẫu có bảng/hình/trường Word hoặc phông không hỗ trợ phải dùng
PDF đã ký. Thỏa thuận CZ mở tài khoản chính
thức; kiểm tra/ký ngay trong app còn cần hợp đồng API. Chứng thư/mail thật và hồ sơ
GS1 chưa từng mở chưa nghiệm thu. [Nguồn và giới hạn](../research/2026-10-12-vn-code-gs1-action-contract.md).

## 10 cửa hàng và dữ liệu lớn

Tác vụ đọc SQL cục bộ có luồng riêng. 2 tác vụ mạng và 2 tải/giải mã ảnh dùng
chung cho toàn ứng dụng; HTTP seller tối đa 2, hàng đợi có giới hạn. Không tải
toàn catalog khi đổi shop. Dòng hiển thị phân trang, ảnh lười tải, lịch sử tác
vụ dùng summary. Cache ảnh WB/FBO và thumbnail tổng 64 MiB; đây là ngân sách
cache, không phải giới hạn RAM của toàn JVM hay một lần in lớn.

Kiểm tra mô phỏng dùng 100.000 sản phẩm/10.000 đơn, cả 6 WB + 4 Ozon và 10 Ozon.
Xem [báo cáo kiểm chứng](../validation/2026-10-11-vn-code-1.4.0.md). Chưa có phép
đo baseline/current trên Windows 2 CPU/4 GiB; không cam kết mọi máy sẽ không lag.

## Znack và nhiều máy khách bổ sung

Cấu hình Znack có thông tin chủ tài khoản/INN, số dư và hạn chứng thư. Đăng ký
WB/Ozon giữ checkpoint đúng doanh nghiệp và giấy tờ từng sản phẩm; Ozon dùng SKU
gốc. Tạo thẻ mới vẫn giữ GTIN cũ cho KIZ/in đến khi candidate xuất bản và xuất
hiện trên đúng biến thể. Thêm GTIN vào sàn giữ barcode cũ và có đọc lại sau gửi.

KIZ WB xử lý luân phiên theo shop, reservation theo lô và checkpoint bền vững;
lịch sử in có đối soát read-only. Ozon chọn/in toàn bộ theo bộ lọc, đọc SQL theo
nhóm và ghép PDF tuần tự. Hai tác vụ không cùng ghi một tệp đầu ra.
Mỗi khách chạy VNCode trên máy riêng; chỉ dùng chung website đăng ký/license.
Source website Portal 0.3.1 tại `website/license-portal`; chủ website đã báo triển khai và đăng nhập admin được, còn chờ nghiệm thu license thật.

[Số đo và kiểm chứng bổ sung](../validation/2026-10-14-vn-code-140-scaling.md).
[Kiến trúc](../architecture/vncode-140-multi-client.md).

## Cài đặt sau khi phát hành

Hiện chưa có EXE 1.4.0 đã nghiệm thu để cài. Quy trình sau áp dụng khi release
được phát hành và cổng license/Windows đã đạt.

Đóng VN code, sao lưu dữ liệu theo chức năng có sẵn rồi chạy EXE của release
`v1.4.0-preview.1`. Java được đóng gói. Đây là nâng cấp cùng ứng dụng; bộ cài
không thay dữ liệu seller bằng fixture. Schema 4 lên 5 có snapshot kiểm tra.
Không dùng binary cũ để mở database đã lên schema mới.

Bộ cài Preview chưa ký Authenticode. `checksums.sha256`, `build-info.json` và
`update-manifest.json` của release ghi đúng bytes, URL, commit và CI run ID.
Manifest Preview là thông tin **cài thủ công**, `automaticUpdate=false`; không
đưa bản chưa nghiệm thu vào feed tự cập nhật. Không sửa tag hoặc bộ cài cũ.

## Phạm vi còn cần nghiệm thu

Xem [feature-status](VN-code-1.4.0-status.json): tài khoản/chứng thư/SMTP/IMAP/
Yandex/máy in và thao tác đăng bài/giấy tờ thật chưa nghiệm thu. Luồng ADD GTIN WB/Ozon có hợp đồng và fixture, chưa nghiệm thu seller thật;
thay/xóa barcode sàn chưa mở; các cải tiến FBO, trình thiết kế nhúng, quy tắc TN VED
và hosting chưa được công bố hoàn tất. Module Gmail/OTP/license đã có client và kiểm thử fixture; xác minh chữ ký server, backend/email và Windows native vẫn là điều kiện trước phát hành.
