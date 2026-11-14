# Repo đề xuất cho VN Code — chờ duyệt

Khảo sát ngày **14.11.2026, giờ Moscow** từ repository, tài liệu release, license và Maven Central chính thức. Các công cụ dưới đây **chưa được cài hoặc nối vào VN Code**. Bản nguồn sửa KIZ 1.6.10 không thực hiện rút gọn diện rộng.

| Mục tiêu | Repo đề xuất | Lý do và phạm vi nên duyệt |
| --- | --- | --- |
| Rút gọn/sắp xếp mã Java có kiểm soát | [OpenRewrite](https://github.com/openrewrite/rewrite), [Maven plugin](https://github.com/openrewrite/rewrite-maven-plugin) | AST có kiểu; chạy từng recipe bằng `dryRun`, xem patch trước. Chỉ chọn lớp và recipe đã thẩm định, giữ tên FXML/controller, JSON/API, schema, license và luồng ký. |
| Phương án thay thế khi cần bộ biến đổi riêng | [Spoon](https://github.com/INRIA/spoon) | Release 11.5.0 xác nhận Java 25; core MIT hoặc CeCILL-C. Cần viết processor và test, không có thao tác tự rút gọn toàn app an toàn. |
| Bắt thay đổi phá ranh giới kiến trúc | [ArchUnit](https://github.com/TNG/ArchUnit) **1.5.1** | Dependency kiểm thử, Apache-2.0; upstream CI có Java 25. Viết rule theo ranh giới thật, báo vi phạm hiện hữu, không tự xóa chức năng để xanh. |
| Biết nhánh sửa chưa được test | [JaCoCo](https://github.com/jacoco/jacoco) **0.8.16** | EPL-2.0; Java 25 được hỗ trợ chính thức từ 0.8.14. Xuất coverage XML/HTML; coverage không chứng minh assertion đúng. |
| Kiểm test có phát hiện điều kiện sai | [PIT](https://github.com/hcoles/pitest) **1.30.2**, [JUnit 5 plugin](https://github.com/pitest/pitest-junit5-plugin) **1.2.3** | Apache-2.0; upstream có CI Java 25. Pilot logic KIZ trước, dùng fixture; chạy chậm hơn và có mutant tương đương/timeout. Chưa nghiệm thu với Jupiter 5.12.1 của VN Code. |

**Khuyến nghị duyệt:** ArchUnit + JaCoCo trước; PIT chỉ cho nhóm logic KIZ sau. Với rút gọn mã, duyệt pilot OpenRewrite theo từng recipe và patch riêng, tách khỏi sửa lỗi nghiệp vụ. Không công cụ nào bảo đảm tuyệt đối “sửa một chỗ không hỏng chỗ khác”; vẫn cần kiểm thử chức năng, dữ liệu, API, PDF và Windows.

## Phiên bản, kho tải và license OpenRewrite

Core đọc được hiện tại là 8.107.3, Maven plugin 6.64.0. README plugin mới chuyển phát hành sang Code Genome và tải cần xác thực. Ứng viên pilot public Maven Central đã xác minh là **plugin 6.46.1 / default core 8.89.0**, có parser `rewrite-java-25`; core/parser Apache-2.0. Đây là cặp ứng viên, **chưa chạy với VN Code**.

Không tự cài bản mới cần tài khoản hoặc áp mọi recipe. [`rewrite-static-analysis`](https://github.com/openrewrite/rewrite-static-analysis) hiện dùng **Moderne Source Available License**; license core Apache không tự áp cho tất cả recipe. Phải duyệt cả recipe, license và phạm vi.

Các nguồn có ghim:

- [OpenRewrite core](https://github.com/openrewrite/rewrite/tree/16227d24f77a1027c4c86cbc6d59dac35a91119d), [Java 25 module](https://github.com/openrewrite/rewrite/blob/16227d24f77a1027c4c86cbc6d59dac35a91119d/rewrite-java-25/build.gradle.kts).
- [README Maven plugin](https://github.com/openrewrite/rewrite-maven-plugin/blob/a73f7c0c71c20718dbe3d75b44169bef155d224d/README.md), [public plugin 6.46.1](https://repo.maven.apache.org/maven2/org/openrewrite/maven/rewrite-maven-plugin/6.46.1/rewrite-maven-plugin-6.46.1.pom), [public parser Java 25](https://repo.maven.apache.org/maven2/org/openrewrite/rewrite-java-25/8.89.0/rewrite-java-25-8.89.0.pom).
- [License của static-analysis](https://github.com/openrewrite/rewrite-static-analysis/blob/80ce59c1e46a5eb4052706335a1164f5a756db6c/LICENSE/moderne-source-available-license.md).

## Căn cứ Java 25 của nhóm hồi quy

- [ArchUnit 1.5.1](https://github.com/TNG/ArchUnit/releases/tag/v1.5.1), [CI release có Java 25](https://github.com/TNG/ArchUnit/blob/6c2d659e5681b50541930d236a8c46aed5e44a5c/.github/workflows/build.yml).
- [JaCoCo 0.8.16](https://github.com/jacoco/jacoco/releases/tag/v0.8.16), [changes hỗ trợ Java 25](https://github.com/jacoco/jacoco/blob/466838a293cc2387f5788eddd9e41e1750d33b58/org.jacoco.doc/docroot/doc/changes.html#L105).
- [PIT 1.30.2](https://github.com/hcoles/pitest/releases/tag/1.30.2), [CI Java 25](https://github.com/hcoles/pitest/blob/0fd2f92147db47cb30ce14a6b29e3be53707d3bc/.github/workflows/ci.yml), [plugin JUnit 5 trên Central](https://repo.maven.apache.org/maven2/org/pitest/pitest-junit5-plugin/maven-metadata.xml).

Đây là bằng chứng upstream, không phải kết quả tích hợp trên VN Code. POM nền dùng Java 25/JavaFX 25.0.2, Jupiter 5.12.1 và Surefire 3.2.5. Phương án duyệt phải giữ bộ build thông thường, các gate cũ và runtime dependency; không thay JUnit hoặc schema chỉ để cài công cụ.

## Các lựa chọn không ưu tiên

- [Error Prone/Refaster](https://github.com/google/error-prone) 2.52.0: Apache-2.0, có toolchain/CI Java 25; phù hợp template thay đổi nhỏ, cần cấu hình javac/exports và kiểm chứng tương đương.
- [PMD/CPD](https://github.com/pmd/pmd): phát hiện ứng viên mã trùng, không tự chứng minh field/method không dùng.
- [ProGuard](https://github.com/Guardsquare/proguard): làm nhỏ bytecode, không phải rút gọn nguồn. FXML, Gson và reflection có thể hỏng nếu thiếu keep rule. Không bật profile obfuscate cũ trong đợt sửa KIZ.
- STARTS/Ekstazi hoặc Revapi/japicmp không thay được hồi quy hành vi của app: công cụ chọn test cần xác minh thêm Java 25/JUnit, còn kiểm API/binary không phát hiện đủ sai dữ liệu, ký, đơn hàng hay PDF.

Chưa có sự chấp thuận cài công cụ; không tự áp refactor hoặc thêm dependency vào bản nguồn giao trong đợt này.
