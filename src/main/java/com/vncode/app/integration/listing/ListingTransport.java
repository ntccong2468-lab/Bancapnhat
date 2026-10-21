package com.vncode.app.integration.listing;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.io.UncheckedIOException;
/** A single verified endpoint request. Mutations have no retry or redirect. */
@FunctionalInterface
public interface ListingTransport {
    @FunctionalInterface
    interface CheckedGuard { void check() throws IOException; }
    JsonElement call(String path,JsonElement body,boolean mutation) throws IOException;
    /** Between complete mutation batches, outside HTTP permits; fixtures may advance a virtual clock. */
    default void pauseBetweenWrites(java.time.Duration delay)throws InterruptedException{Thread.sleep(delay.toMillis());}
    /** Production transports run this after limiter/budget waits, immediately before the request.
     * The default supports deterministic fixture transports without introducing retries. */
    default JsonElement call(String path,JsonElement body,boolean mutation,Runnable beforeSend)throws IOException {
        beforeSend.run();return call(path,body,mutation);
    }
    /** Fresh network reads must run outside the seller HTTP permit. The final guard is local only. */
    default JsonElement call(String path,JsonElement body,boolean mutation,CheckedGuard freshRead,Runnable beforeSend)throws IOException {
        try {
            return call(path,body,mutation,()->{
                try { freshRead.check(); }
                catch(IOException error) { throw new UncheckedIOException(error); }
                beforeSend.run();
            });
        } catch(UncheckedIOException error) { throw error.getCause(); }
    }
}
