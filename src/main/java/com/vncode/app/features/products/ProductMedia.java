package com.vncode.app.features.products;

import java.net.URI;
public record ProductMedia(URI uri,String sha256,Integer width,Integer height,String mimeType) {}
