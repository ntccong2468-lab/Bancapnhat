package com.vncode.app.integration.listing;

import com.google.gson.JsonElement;
import java.io.IOException;
/** A single verified endpoint request. Mutations have no retry or redirect. */
@FunctionalInterface
public interface ListingTransport {
    JsonElement call(String path,JsonElement body,boolean mutation) throws IOException;
    /** Production transports run this after limiter/budget waits, immediately before the request.
     * The default supports deterministic fixture transports without introducing retries. */
    default JsonElement call(String path,JsonElement body,boolean mutation,Runnable beforeSend)throws IOException {
        beforeSend.run();return call(path,body,mutation);
    }
}
