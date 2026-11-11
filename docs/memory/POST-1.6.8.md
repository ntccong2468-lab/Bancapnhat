# Các thay đổi sau bản 1.6.8 — chưa phát hành

Đóng sổ ngày 11.11.2026 MSK trước khi sửa tiếp đồng bộ sản phẩm/giấy tờ. Mã gộp hiện có: `d8a48594e46d3298bf3eed0ac7cbf6d989afb5b5`; bàn giao `911a3b065e11403e39c8a4bd967e4893655dc382`. Giữ toàn bộ nguồn phát hành 1.6.8 `38906a2108501029d9609ed1d071ee987c8b2637` và các thay đổi dưới đây. Không coi commit mã hoặc JAR kiểm build là bộ cài đã phát hành.

| Phần | Hành vi đã sửa | Nguồn và bằng chứng |
| --- | --- | --- |
| Ngày hết hạn giấy tờ | Thêm ô hạn ở cấu hình dùng chung; nhận ISO/dd.MM, chặn ngày sai/hạn trước hiệu lực; giữ bản nháp theo đúng giấy tờ. Lưu giấy tờ/kết nối trong transaction để không ghi đè nhau; blank không phải vô thời hạn. | `80e9324c24e1d1fdee1dbbdbc56854b1e8f0149f`; [bàn giao](../validation/2026-11-10-permit-expiry-config-handoff.md), 230/230 ca ở lượt riêng. |
| Mua KIZ luôn theo dõi lưu thông | Không có tùy chọn bật/tắt. Các đường mua mới, mua bù khi in và FBO lưu ý định lưu thông riêng trong job, không đổi global profile. | `d8a48594e46d3298bf3eed0ac7cbf6d989afb5b5`; [bàn giao/sơ đồ](../validation/2026-11-11-kiz-circulation-audit.md). |
| Trạng thái mua/kho | Phân biệt đã tải mã, đang đối soát và đã lưu thông; chỉ xác nhận khi đủ CIS đã INTRODUCED. Giữ inventory đã dùng/giữ chỗ/archive và trạng thái SUZ EXHAUSTED/CLOSED qua chuyển trạng thái cục bộ. | Cùng nguồn d8a4859; [421 ca/24 lớp đạt](../validation/kiz-circulation-20261111/focused-tests.json), [JAR build/69 class-resource](../validation/kiz-circulation-20261111/local-package.json). |
| Lô cũ và gửi không rõ kết quả | Đối soát bằng hồ sơ gốc, đúng order/GTIN/số lượng/CIS/owner; thiếu profile không mượn cấu hình mới. Timeout/5xx không cấp quyền mua hoặc gửi chứng từ trùng. Lô cũ opt-out chỉ được quan sát, không tự ký mới. | Cùng nguồn d8a4859; [review giới hạn](../validation/kiz-circulation-20261111/source-review.json). |
| Thứ tự xác minh lưu thông | Đọc CIS trước Catalog/TN VED dành cho chứng từ mới; đã INTRODUCED không bị chặn bởi thiếu TN VED/lỗi đọc thẻ. APPLIED thiếu TN VED tiếp tục đối soát, chưa được gửi chứng từ. | [RED/GREEN thứ tự](../validation/kiz-circulation-20261111/ordering-focused-tests.json); không bỏ giấy tờ hoặc đổi nghiệp vụ pháp lý. |
| Công cụ Windows-MCP | Cài cloud client, bộ bootstrap Windows có DPAPI/auth, pin/hash, skill và kiểm giao thức; sửa canonical route/EOL. Đây là công cụ phát triển, không thêm vào runtime app. | `def8e8ff0467a4141fc2399ecd68372ebe1b42f9` và `4fa9ba08977a9c29e51845d3c25d9527cd9a2069`; [bàn giao](../validation/2026-11-10-windows-mcp-setup.md). |

## Trạng thái nghiệm thu và phát hành

- 421 ca của KIZ và 230 ca của hạn giấy tờ là hai lượt theo nguồn/phạm vi riêng, có giao nhau; không cộng thành số ca khác nhau của toàn app.
- Chưa nghiệm thu mua KIZ/ký thật hoặc app Windows trên máy seller. Windows-MCP client và native CI của công cụ không chứng minh máy seller đã kết nối.
- Latest đã đọc ở bàn giao gần nhất vẫn là immutable 1.6.8/440439895. Chưa bump version, tạo bộ cài hoặc publish các phần mới trên. Giữ nguyên asset/tag đã phát hành.
- Prototype mã hóa credential chưa nối runtime/dữ liệu thật. Luồng giấy tờ bị giữ để bổ sung vẫn dùng cơ chế xác minh/tiếp tục hiện có; không đổi dữ liệu chưa xác minh thành hợp lệ.

## Việc mới sau checkpoint này

Người dùng yêu cầu kiểm tra cửa hàng WB/Ozon đồng bộ thiếu sản phẩm và giấy tờ hợp lệ nhưng app vẫn báo chưa xác minh. Ảnh mới hiển thị 327 sản phẩm, kiểm tra 195, còn 132 chưa lấy được; phải truy ý nghĩa bộ đếm trước khi kết luận thiếu danh sách hay thiếu hồ sơ. Nhánh `fix/product-sync-permits-20261111` xuất phát từ 911a3b0. Đây là yêu cầu đang điều tra, chưa có kết luận hoặc nghiệm thu tại checkpoint này. Không lưu API key hoặc danh tính sản phẩm seller vào bộ nhớ.
