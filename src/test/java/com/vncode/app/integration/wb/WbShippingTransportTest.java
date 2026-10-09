package com.vncode.app.integration.wb;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WbShippingTransportTest {
 @Test void shippingPatchIsNotAutomaticallyReplayedAfterHttp408() throws Exception {
  var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/marketplace/v3/fbs/supplies/shipping-method",exchange->{
   exchange.getRequestBody().readAllBytes();int n=calls.incrementAndGet();byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(n==1?408:200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
  try {
   var request=new okhttp3.Request.Builder().url("http://127.0.0.1:"+server.getAddress().getPort()+"/api/marketplace/v3/fbs/supplies/shipping-method")
     .patch(okhttp3.RequestBody.create("{}",okhttp3.MediaType.get("application/json"))).build();
   var execute=WbApiClient.class.getDeclaredMethod("executeOnce",String.class,okhttp3.Request.class,Class.class);execute.setAccessible(true);
   assertThrows(java.lang.reflect.InvocationTargetException.class,()->execute.invoke(new WbApiClient(),"test-only-token",request,Void.class));
   assertEquals(1,calls.get(),"A timed-out PATCH must be read back, never replayed inside OkHttp");
  } finally {server.stop(0);}
 }
}
