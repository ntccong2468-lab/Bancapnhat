# CryptoPro: chứng thư mới không có trong danh sách VN Code

Ngày 09.11.2026 MSK. Áp dụng Code Doctor và review mã chéo. Bổ sung trước khi công bố 1.6.7; tiếp tục giữ toàn bộ bản gộp và 1.6.6.

## Chẩn đoán và bằng chứng

Người dùng xác nhận chứng thư mới có trong CryptoPro/WBCode trên cùng máy, nhưng không có trong danh sách VN Code. Mã discovery cũ chỉ đọc `certmgr -list -store uMy`; đường ký CAdES vốn hỗ trợ cả kho container location100. Đây là thiếu khả năng xác nhận được từ mã; vị trí chứng thư thực trên máy seller chưa được kiểm chứng.

Đã khôi phục nguyên service gốc0f5 và chạy hồi quy hiện hữu constructor/API: mong OLD+RENEWED nhưng chỉ nhận OLD, 1 assertion failure/0 error. Lượt này dùng thumbprint tổng hợp trước khi review ràng buộc DER. Sau review, fixture dùng hai DER/serial khác nhau và thumbprint tính từ DER; parser từ chối thumbprint của chứng thư khác. Không coi fixture là thiết bị CryptoPro thật.

Danh sách vốn được đọc lại khi mở dropdown; không có cache dài hạn được chứng minh là nguyên nhân. Không lọc theo INN, thuật toán hoặc HasPrivateKey. Chứng thư hết hạn vẫn hiện nhưng bị vô hiệu hóa theo chính sách có sẵn.

## Thay đổi

- Discovery giữ nguồn certmgr, bổ sung CAdES current-user2/container100/CAPICOM2, cả hai host PowerShell. COM chỉ đọc và xuất chứng thư công khai; không import, xuất khóa riêng hoặc ký khi đọc danh sách. Một mục hỏng không làm mất các mục hợp lệ khác.
- Ràng buộc thumbprint40hex với DER bằng helper định danh Windows hiện có. Không thêm thuật toán hash hoặc thay CMS/signature algorithm. Khử trùng đúng fingerprint, giữ nguyên alias khác nhau. Metadata discovery không trở thành chứng minh khóa riêng, CMS hoặc doanh nghiệp.
- Làm mới khi mở Chỉnh sửa; vẫn làm mới ở dropdown. Giữ lựa chọn theo fingerprint chuẩn hóa, không tự chọn chứng thư gia hạn thay chứng thư đã cấu hình, kể cả sau nhiều lần không tìm thấy. Chứng thư mới không kế thừa trạng thái xác minh của chứng thư cũ.
- Đổi cửa hàng, tải lại, Hủy và lưu thành công vô hiệu hóa kết quả cũ, ngắt riêng tác vụ đọc chứng thư và dọn trạng thái bận. Callback cũ không xóa trạng thái tác vụ mới. Không hủy tác vụ ký hoặc seller write.
- Provider ký dùng chung đọc uMy trước. Khi chưa có đúng40hex nhưng CAdES đọc được chính xác chứng thư đó, chọn CAdES trước bất kỳ lần ký nào. Không chuyển đường sau lỗi ký container hoặc thêm retry cho lỗi private key, cancel, timeout hay lỗi chứng thư mơ hồ. Giữ fallback thiếu công cụ/license riêng của cryptcp như trước.
- Tra cứu và các tiến trình ký trong lượt Windows chia sẻ thời gian còn lại; đổi host không được nhận ngân sách mới. Hạn này bắt đầu sau khi lấy khóa hàng đợi ký; không bao gồm thời gian chờ khóa hoặc tối đa khoảng500ms cleanup tiến trình hiện có.
- Không thay repository/DB, chính sách đổi người ký, owner/INN, phạm vi phê duyệt, dữ liệu Catalog/KIZ hoặc license. Chọn chứng thư gia hạn khác thumbprint vẫn phải xác minh và tuân theo chính sách lưu đổi người ký hiện có; đọc danh sách không lưu hoặc đổi người ký.

## Kiểm thử đã chạy

- RED: service gốc0f5, một hồi quy thất bại đúng việc bỏ sót chứng thư token mới; không lỗi biên dịch.
- Lượt focused đầu125 ca có1 failure ở test ngân sách thời gian dùng một lần `parkNanos`, có thể trả sớm. Giữ log/receipt thất bại; sửa fixture chờ đủ monotonic elapsed, giữ các ngưỡng kiểm tra. Không hạ assertion hoặc bỏ test.
- Sau các sửa review:191/14 ca đạt; sau bổ sung hủy tác vụ đọc: **192 ca/14 lớp, 0 failure/error/skip** trên Linux/Xvfb/JDK25. Các lượt giao nhau không cộng dồn. Lệnh chính xác, XML/hash và input hashes nằm trong JSON đi kèm.
- `mvn-vncode -DskipTests clean package` đạt sau khi lưu kết quả kiểm thử riêng. **1.986 class production**, khớp byte output compiler, Java major69, không test fixture. JAR SHA `a7c341edaad942fa38d7b7136f8da12941e8991c365118828899c64eb38d9bbd`. Đây chưa phải bộ cài Windows.
- Hai case PowerShell chỉ kiểm cấu trúc trên Linux. Fresh Windows preflight phải chạy parser PowerShell thật và script với COM tổng hợp valid/broken/valid; các case đó không đọc CryptoPro seller. Preflight giữ toàn bộ nhóm trước và bổ sung discovery/UI/routing; full unfiltered clean verify và mọi native/install/upgrade gate giữ nguyên.
- Review chéo discovery, UI và provider đã xử lý DER/fingerprint, alias, mục hỏng, callback Hủy/lưu và stale queue. Không phát hiện blocker còn lại trong phạm vi đọc; chưa có case riêng executor saturation.

## Phần còn lại và bàn giao

Baseline0f5/run42587222212 đã đạt4122 ca Windows và đủ16asset/native/DPAPI/cài-gỡ/coexistence/passive upgrade. Bộ baseline được lưu nguyên, **không dùng để nghiệm thu mã chứng thư mới**. Cần frozen app SHA mới, fresh Windows/full/package, helper/hash và review trước Publish; tiếp theo kiểm đủ16 public bytes, bảo toàn các release cũ và updater thật1.6.6 nhận1.6.7.

Chưa nghiệm thu thiết bị/chứng thư thật trên máy seller. Không API seller mutation, mua KIZ, gửi thẻ, ký thật, kích hoạt license hoặc Gmail trong lượt sửa này. CodeQL cũ026 có8 finding cũ/Java gateFAIL; runtime nay đã đổi nên phải giữ kết quả đó là lịch sử và ghi scan/review mới riêng, không tuyên bố toàn bộ security xanh.

Bài học: discovery phải bao phủ nguồn được signer hỗ trợ; không suy private-key proof từ danh sách. Quyết định đổi provider phải xảy ra trước khi ký với cùng định danh đã đối chiếu. Tác vụ đọc hủy được cần cả interruption lẫn generation guard. Không tối ưu scanner bằng cách thay định danh40hex của Windows bằng hash khác.

## Bổ sung: Windows fixture Progress, không sửa production

Fresh Windows42605121356/sourcee774 thất bại sớm220ca/219pass/1failure/0error/0skip, chưa full/bộ cài. Script thật đã trả đúng hai cert tổng hợp/DER;38routing+6UI đạt. Lỗi duy nhất assertion cuối stderr0 nhận392byte. Diagnostic42606535850 checkoutđúnge774 chỉ đổi message assertion để capture, giữ expected0: tái hiệnRED1ca và xác nhận đúng một CLIXML progress “Preparing modules for first use.”, không error record hoặc dữ liệu cert. Hash392byte `a3639af5e23464fc391dae127fd5493f02c8178068a35866d9c7fdd35dcd623f`; reviewer độc lập xác nhận. [Receipt](2026-11-09-cryptopro-windows-progress-diagnostic.json). DiagnosticRED không phải nghiệm thu app đạt.

Sửa đúng một dòng trong preamble **fixture tổng hợp**: `$ProgressPreference = 'SilentlyContinue'` trước Add-Member. Không đổi ErrorAction, lọc stderr, hạ assertion hoặc sửa production. Sau sửa12/12 discoveryca Linux đạt; hai nhánh PowerShellLinux vẫn chỉ cấu trúc.192ca/14 lớp và package trước vẫn là bằng chứng cho đúng runtime giữ nguyên; testfixture đã thay nên ghi riêng12ca, không cộng thành204ca. Cần SHA mới và fresh220preflight/fullWindows; không recovery/reuse runfailed. Giữ original109 +5runtime, version/schema/deps/UpgradeCode. Kinh nghiệm: stderr PowerShell -EncodedCommand có thể chứa CLIXML progress; phải capture/phân loại trước khi sửa, không coi độ dài stderr là lỗi API hoặc che toàn bộ luồng lỗi.
