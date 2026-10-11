package com.vncode.app.integration.listing;

import com.google.gson.JsonElement;
import java.io.IOException;
/** A single verified endpoint request. Mutations have no retry or redirect. */
@FunctionalInterface
public interface ListingTransport { JsonElement call(String path,JsonElement body,boolean mutation) throws IOException; }
