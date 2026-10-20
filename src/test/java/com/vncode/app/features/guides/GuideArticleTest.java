package com.vncode.app.features.guides;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class GuideArticleTest {
    @TempDir Path directory;
    @Test void localGuidePreservesTextWithoutExecutingMarkup()throws Exception{
        var file=GuideArticle.write(directory,"znack","Đăng ký <thẻ>","Dòng 1\n<script>alert('secret')</script> & ví dụ");
        var html=Files.readString(file);assertTrue(html.contains("Đăng ký &lt;thẻ&gt;"));assertTrue(html.contains("Dòng 1\n&lt;script&gt;"));assertFalse(html.contains("<script>"));assertTrue(html.contains("default-src 'none'"));
        assertThrows(IllegalArgumentException.class,()->GuideArticle.write(directory,"../outside","x","x"));
    }
}
