package com.vncode.app.integration.wb;
import com.google.gson.Gson;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class WbShippingServiceTest {
 final Clock clock=Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"),ZoneOffset.UTC);
 final Shop shop=new Shop(12,"Fixture shop","test-only-token");
 final WbShippingContract.Parameters parameters=new WbShippingContract.Parameters("WB-GI-100","selfShipping",LocalDate.parse("2026-10-10"),100);
 static final class Api extends WbApiClient {
  int writes;boolean timeout,accept=true;WbSupplyDto detail=new Gson().fromJson("{\"id\":\"WB-GI-100\"}",WbSupplyDto.class);
  @Override public WbSupplyDto getSupplyDetail(String key,String id){return detail;}
  @Override public void setSupplyShipping(String key,WbShippingContract.Parameters p,Clock clock)throws IOException {
   writes++;if(accept)detail=new Gson().fromJson("{\"id\":\"WB-GI-100\",\"shippingDt\":\"2026-10-10\",\"shippingType\":\"selfShipping\",\"shippingPointId\":100}",WbSupplyDto.class);
   if(timeout)throw new IOException("test-timeout");
  }
 }
 static final class Log extends WbActionLogRepository {
  String status,request;
  @Override public void record(int shopId,String action,String id,List<Long> orders,String status,String request,String response,String error){this.status=status;this.request=request;}
  @Override public Checkpoint latestCheckpoint(int shopId,String action,String supply){return status==null?null:new Checkpoint(status,request);}
 }
 @Test void confirmsWriteAndReadbackAndSkipsAlreadyAppliedParameters()throws Exception {
  var api=new Api();var log=new Log();var service=new WbShippingService(api,log,clock);
  service.ensureShipping(shop,parameters,true);assertEquals(1,api.writes);assertEquals("success",log.status);
  service.ensureShipping(shop,parameters,true);assertEquals(1,api.writes);
 }
 @Test void timeoutIsReconciledWithoutRepeatingTheMutation()throws Exception {
  var api=new Api();api.timeout=true;var log=new Log();var service=new WbShippingService(api,log,clock);
  service.ensureShipping(shop,parameters,true);assertEquals(1,api.writes);assertEquals("reconciled",log.status);
 }
 @Test void ambiguousWriteIsBlockedUntilReadbackConfirmsIt()throws Exception {
  var api=new Api();api.timeout=true;api.accept=false;var log=new Log();var service=new WbShippingService(api,log,clock);
  assertThrows(IOException.class,()->service.ensureShipping(shop,parameters,true));assertEquals("ambiguous",log.status);
  assertThrows(IOException.class,()->service.ensureShipping(shop,parameters,true));assertEquals(1,api.writes);
 }
 @Test void differentSelectionCannotClearAnAmbiguousRequest()throws Exception {
  var api=new Api();api.timeout=true;api.accept=false;var log=new Log();var service=new WbShippingService(api,log,clock);
  assertThrows(IOException.class,()->service.ensureShipping(shop,parameters,true));
  api.detail=new Gson().fromJson("{\"id\":\"WB-GI-100\",\"shippingDt\":\"2026-10-10\",\"shippingType\":\"selfShipping\",\"shippingPointId\":200}",WbSupplyDto.class);
  var replacement=new WbShippingContract.Parameters("WB-GI-100","selfShipping",LocalDate.parse("2026-10-10"),200);
  assertThrows(IOException.class,()->service.ensureShipping(shop,replacement,true));
  assertEquals("ambiguous",log.status);assertEquals(1,api.writes);
 }
 @Test void unconfirmedOrExpiredRequestNeverWrites() {
  var api=new Api();var service=new WbShippingService(api,new Log(),clock);
  assertThrows(IllegalArgumentException.class,()->service.ensureShipping(shop,parameters,false));
  assertThrows(IllegalArgumentException.class,()->service.ensureShipping(shop,new WbShippingContract.Parameters("WB-GI-100","selfShipping",LocalDate.parse("2026-10-08"),100),true));
  assertEquals(0,api.writes);
 }
}
