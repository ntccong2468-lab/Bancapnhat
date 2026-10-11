package com.vncode.app.features.news;
import com.vncode.app.integration.translation.*;
import com.vncode.app.models.Shop;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class NewsTranslationServiceTest {
 @TempDir Path directory;
 private SellerNews news(String body){return SellerNews.wb(NewsScope.forShop(new Shop(1,"Fixture","token")),"1",Instant.EPOCH,"Заголовок",body,URI.create("https://seller.wildberries.ru/"));}
 @Test void revisionInvalidatesCacheAndRepeatedHashUsesOneTranslation(){var calls=new AtomicInteger();var translator=new NewsTranslator(){public String version(){return "fixture-v1";}public List<String> translate(List<String> text,String source,String target){calls.incrementAndGet();return List.of("Tiêu đề",text.get(1));}};var service=new NewsTranslationService(directory.resolve("news.db"),translator);assertEquals(NewsTranslationService.State.TRANSLATED,service.translate(news("GTIN 04612345678901"),"vi").state());service.translate(news("GTIN 04612345678901"),"vi");assertEquals(1,calls.get());service.translate(news("GTIN 04612345678901 Size 2XL"),"vi");assertEquals(2,calls.get());}
 @Test void missingKeyOrQuotaKeepsOriginal(){var service=new NewsTranslationService(directory.resolve("news.db"),null);assertEquals(NewsTranslationService.State.UNCONFIGURED,service.translate(news("Body"),"vi").state());var translator=new NewsTranslator(){public String version(){return "fixture";}public List<String> translate(List<String> t,String s,String d)throws java.io.IOException{throw new java.io.IOException("quota");}};var result=new NewsTranslationService(directory.resolve("news.db"),translator).translate(news("Body"),"vi");assertEquals(NewsTranslationService.State.FAILED,result.state());assertEquals("Body",result.original().originalBody());}
 @Test void numbersDatesAndGtinMustBePreserved(){var translator=new NewsTranslator(){public String version(){return "fixture";}public List<String> translate(List<String> t,String s,String d){return List.of("Tiêu đề","GTIN 4612345678901 ngày 2026-10-01");}};assertEquals(NewsTranslationService.State.FAILED,new NewsTranslationService(directory.resolve("news.db"),translator).translate(news("GTIN 04612345678901 ngày 2026-10-01"),"vi").state());}
 @Test void defaultSavedKeyRequiresDpapi()throws Exception{if(!com.vncode.app.shared.WindowsSecretProtector.supported()){var repository=new TranslationSettingsRepository(directory.resolve("settings.db"));assertThrows(java.io.IOException.class,()->repository.save(URI.create("https://translate.api.cloud.yandex.net/translate/v2/translate"),"","secret-fixture"));assertTrue(repository.load().isEmpty());assertFalse(java.nio.file.Files.readString(directory.resolve("settings.db"),java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret-fixture"));}else{var repository=new TranslationSettingsRepository(directory.resolve("settings.db"));repository.save(URI.create("https://translate.api.cloud.yandex.net/translate/v2/translate"),"","secret-fixture");var settings=repository.load().orElseThrow();assertEquals("secret-fixture",settings.apiKey());assertFalse(settings.toString().contains("secret-fixture"));assertFalse(java.nio.file.Files.readString(directory.resolve("settings.db"),java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret-fixture"));repository.save(settings.endpoint(),"","changed-fixture");assertNotEquals(settings.revision(),repository.load().orElseThrow().revision());}}
}
