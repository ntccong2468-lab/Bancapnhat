package com.vncode.app.shared;
import com.vncode.app.integration.wb.WbApiException;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
/** Only wrap operations known to be reads; never wrap a seller mutation. */
public final class ReadRetry {
 @FunctionalInterface public interface Read<T>{T execute()throws IOException;}
 @FunctionalInterface public interface Sleeper{void sleep(Duration duration)throws InterruptedException;}
 private final Sleeper sleeper;
 public ReadRetry(){this(duration->Thread.sleep(duration.toMillis()));}
 public ReadRetry(Sleeper sleeper){this.sleeper=java.util.Objects.requireNonNull(sleeper);}
 public <T>T read(Read<T> operation)throws IOException {
  for(int attempt=1;;attempt++) {
   if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("READ_CANCELLED");
   try{return operation.execute();}catch(IOException error){
    if(attempt>=3||!retryable(error))throw error;
    try{sleeper.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(200L*attempt,400L*attempt+1)));}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("READ_CANCELLED");}
   }
  }
 }
 private static boolean retryable(IOException error){
  if(error instanceof WbApiException wb)return wb.getStatusCode()==500||wb.getStatusCode()==502||wb.getStatusCode()==503||wb.getStatusCode()==504;
  return !(error instanceof java.net.ProtocolException)||error instanceof java.net.SocketTimeoutException;
 }
}
