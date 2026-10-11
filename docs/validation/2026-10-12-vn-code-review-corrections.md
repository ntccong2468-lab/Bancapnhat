# VN code 1.4.0 — kiểm tra sau sửa lỗi review

Ngày 12/10/2026. Review độc lập một lần cho Products/Copy/GS1/News/FBS và 10 shop
đọc phạm vi `0b75b8d..ac65e22`, xác nhận bảy lỗi Important, không có Critical/Minor.
Người triển khai đọc đầy đủ, giữ mức Important theo tác động thực tế và sửa một lượt
bằng kiểm thử hồi quy. Không gọi lại reviewer cho cùng phạm vi. Phần giấy phép mới
chưa có code và không nằm trong review/kiểm thử chức năng giấy phép này.

## Kết quả sửa

| Lỗi | Thay đổi và bằng chứng hồi quy |
|---|---|
| Xóa shop làm mất tác vụ chưa rõ kết quả | Chặn xóa cả source/destination có copy pending và shop có bulk pending; giữ khóa shop xuyên lúc gửi/checkpoint. Test xóa source trong request đang chạy và hai shop sau timeout đạt. |
| Batch gửi payload cũ của card phía sau | Kiểm tra lại từng card dưới durable claim ngay trước gửi; card chắc chắn chưa gửi nhưng thay đổi về NEEDS_DATA. Test card 2 đổi trong lúc card 1 gửi chỉ có một lần ghi đạt. |
| Ozon không còn đường đồng bộ catalog | Header/manual sync trở lại luồng đầy đủ, có catalog từ đầu; đổi shop vẫn đọc local. Test qua controller thật với HTTP fixture chứng minh shop trống có sản phẩm, lần sau cập nhật tên, đơn rời queue được kiểm tra lại. |
| PDF/complex dictionary Ozon dùng sai read/write shape | Map URL PDF sang src_url/index/name, chặn đường dẫn không thể chuyển; chuẩn hóa dictionaryValueId sang dictionary_value_id. Readback kiểm tra cả PDF và ID từ điển. Fixture theo schema chính thức đạt. |
| Giấy hết hạn/bị từ chối vẫn gửi | Dùng chung kiểm tra validity cho create/update WB/Ozon. Test expired/rejected qua bulk service xác nhận không có mutation. |
| Không có writer cho snapshot/giấy tờ | Reader đầy đủ lưu snapshot đúng credential; đọc đầy đủ thay tập tài liệu để bỏ giấy đã gỡ. Nút Đồng bộ Products chạy nền, tối đa một trang 100 dòng, callback giữ scope; bulk đã đối soát đọc lại cache. Test filter/detail không seed cache, token đổi trong HTTP, đổi shop lúc sync và readback đạt. |
| Base64 nhân theo mỗi card | Asset immutable lưu một lần theo hash, journal chứa reference, reopen/checkpoint giải reference và dùng chung payload. Chặn trước đọc/encode nếu quá 16 tài liệu hoặc tổng file 10 MiB; preview giữ tối đa 64 MiB theo ước lượng bảo thủ. Test ba card dùng PDF 1 MiB, lưu/reopen/apply chỉ một asset đạt. |

Trong lượt sửa cũng tái hiện và sửa giới hạn 1.000 đơn của kiểm tra chi tiết Ozon:
đọc keyset 100 posting/lần, không bỏ sót khi status thay đổi. Fixture 1.001 đơn đều
được kiểm tra. Products nhận credential mới khi vẫn cùng shop ID và bỏ callback
của scope cũ; kiểm thử giao diện đạt.

## Kiểm tra hoàn tất trên cloud Linux

```text
DISPLAY=:99 /workspace/wcode-setup/mvn-bancapnhat clean verify
Tests run: 822, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS, 3 phút 18 giây, kết thúc 2026-10-11T22:48:18Z

node --test tools/*.test.mjs
39 passed, 0 failed, 0 skipped

git diff --check
Không có lỗi whitespace
```

Maven dùng Java 25/JavaFX và database tạm. FXML smoke chạy đủ bốn ngôn ngữ và
các kích thước hiện có. Các HTTP mutation trong test dùng transport/server giả;
không dùng token seller, chứng thư doanh nghiệp hoặc tài khoản license thật.

## Các quyết định trong lượt sửa

1. Giữ giấy tờ theo card trong lần đọc đầy đủ; partial cache cũ vẫn merge. Chi phí:
   snapshot của variant khác có thể cần đọc lại sau refresh card, tránh giữ giấy đã gỡ.
2. Đồng bộ catalog Ozon đầy đủ là thao tác người dùng yêu cầu; bắt đầu từ trang đầu.
   Chi phí: thêm lượt đọc API khi manual sync, không gây full sync khi đổi shop.
3. Giấy tờ Products kiểm tra tối đa 100 dòng/trang và báo số chưa đọc được. Chi phí:
   các trang chưa refresh chưa có metadata mới, cần đồng bộ riêng; không công bố đã
   kiểm tra toàn bộ giấy tờ catalog chỉ từ một trang.
4. Bulk file tối đa 16 tài liệu/10 MiB và preview 64 MiB; giữ lựa chọn khi từ chối.
   Chi phí: dữ liệu lớn hơn cần chia batch. Tệp dùng chung vẫn dùng được cho nhiều card.
5. Readback cache lỗi không đảo kết quả remote đã biết hoặc gửi lại. Chi phí:
   người bán có thể cần bấm Đồng bộ Products để cập nhật cache sau đối soát.

## Chưa nghiệm thu

Chưa chạy Windows native CI cho phạm vi sửa này, chưa có EXE 1.4.0 được phát hành,
chưa đo máy Windows 2 CPU/4 GiB và chưa thử shop/chứng thư/máy in thật.
[Bằng chứng 10 shop trước đó](2026-10-11-vn-code-1.4.0.md) giữ nguyên nguồn/ngày đo;
không biến kết quả cũ thành phép đo của lượt sửa mới.

Thiết kế account/offline/device đã được người sở hữu duyệt, nhưng mới nhận README
website; cần [nguồn backend](../research/2026-10-12-vn-code-license-backend-intake.md)
để lập kế hoạch và tích hợp. Không phát hành bản 1.4.0 cá nhân chưa có module giấy phép
thay cho bản có quyền sử dụng mà người sở hữu vừa yêu cầu. Tag/bộ cài cũ giữ nguyên.
