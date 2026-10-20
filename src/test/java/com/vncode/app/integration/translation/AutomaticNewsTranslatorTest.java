package com.vncode.app.integration.translation;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AutomaticNewsTranslatorTest {
 @Test void translatesPublicNewsWithoutCredentialsAndPreservesUnicodeChunks()throws Exception{
  try(var server=new MockWebServer()){
   server.start();server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest request){
    assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Api-Key"));assertEquals("vi",request.getRequestUrl().queryParameter("tl"));
    String q=request.getRequestUrl().queryParameter("q");assertTrue(q.length()<=1800);assertFalse(Character.isHighSurrogate(q.charAt(q.length()-1)));
    var segment=new JsonArray();segment.add(q);var segments=new JsonArray();segments.add(segment);var root=new JsonArray();root.add(segments);return new MockResponse().setBody(root.toString());
   }});
   var translator=new AutomaticNewsTranslator(new OkHttpClient(),server.url("/translate"));String original="GTIN 04612345678901 · "+"a😀".repeat(1500);
   assertEquals(List.of(original,"Tin tức"),translator.translate(List.of(original,"Tin tức"),"ru","vi"));assertTrue(server.getRequestCount()>=4);
  }
 }
 @Test void rateLimitsAndMalformedResponsesAreReportedWithoutRetrying()throws Exception{
  try(var server=new MockWebServer()){server.start();var translator=new AutomaticNewsTranslator(new OkHttpClient(),server.url("/translate"));
   server.enqueue(new MockResponse().setResponseCode(429));assertThrows(java.io.IOException.class,()->translator.translate(List.of("Новости"),"ru","vi"));assertEquals(1,server.getRequestCount());
   server.enqueue(new MockResponse().setBody("<html>unavailable</html>"));assertThrows(java.io.IOException.class,()->translator.translate(List.of("Новости"),"ru","vi"));assertEquals(2,server.getRequestCount());
  }
 }
 @Test void closeStopsRequests()throws Exception{
  try(var server=new MockWebServer()){server.start();server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));var translator=new AutomaticNewsTranslator(new OkHttpClient(),server.url("/translate"));
   var task=new java.util.concurrent.FutureTask<>(()->translator.translate(List.of("Новости"),"ru","vi"));Thread.ofVirtual().start(task);assertNotNull(server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS));translator.close();assertThrows(java.util.concurrent.ExecutionException.class,()->task.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,server.getRequestCount());
  }
 }
}
