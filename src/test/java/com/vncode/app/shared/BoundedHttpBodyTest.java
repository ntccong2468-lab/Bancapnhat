package com.vncode.app.shared;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BoundedHttpBodyTest {
    @Test void chunkedBodyCannotExceedLimit()throws Exception {
        try(var server=new MockWebServer()){server.start();server.enqueue(new MockResponse().setChunkedBody("x".repeat(100),5));try(var response=new OkHttpClient().newCall(new Request.Builder().url(server.url("/")).build()).execute()){assertThrows(java.io.IOException.class,()->BoundedHttpBody.utf8(response.body(),16));}}
    }
    @Test void boundedUtf8PreservesSellerText()throws Exception {try(var body=ResponseBody.create("Брюки VN",MediaType.get("application/json"))){assertEquals("Брюки VN",BoundedHttpBody.utf8(body,100));}}
}
