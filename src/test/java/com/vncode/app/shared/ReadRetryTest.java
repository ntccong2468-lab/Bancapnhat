package com.vncode.app.shared;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ReadRetryTest {
 @Test void retriesTransientReadsAtMostThreeTimes() throws Exception {
  AtomicInteger attempts=new AtomicInteger();var retry=new ReadRetry(duration -> {});
  assertEquals("ok",retry.read(() -> {if(attempts.incrementAndGet()<3)throw new IOException("offline");return "ok";}));assertEquals(3,attempts.get());
 }
 @Test void authenticationAndValidationAreNotRetried() {
  for(int status:new int[]{400,401,403,404,422}){AtomicInteger attempts=new AtomicInteger();var retry=new ReadRetry(duration -> {});
   assertThrows(com.vncode.app.integration.wb.WbApiException.class,()->retry.read(()->{attempts.incrementAndGet();throw new com.vncode.app.integration.wb.WbApiException("safe",status,"");}));assertEquals(1,attempts.get());}
 }
 @Test void givesUpAndPreservesInterruption() {
  AtomicInteger attempts=new AtomicInteger();var retry=new ReadRetry(duration -> {});
  assertThrows(IOException.class,()->retry.read(()->{attempts.incrementAndGet();throw new IOException("offline");}));assertEquals(3,attempts.get());
  try{var interrupted=new ReadRetry(duration -> {throw new InterruptedException();});assertThrows(java.io.InterruptedIOException.class,()->interrupted.read(()->{throw new IOException("offline");}));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
 }
}
