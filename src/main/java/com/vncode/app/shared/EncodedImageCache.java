package com.vncode.app.shared;
/** One encoded-image budget for WB, Ozon and printing; decoded thumbnails have a separate 32 MiB. */
public final class EncodedImageCache {
 private static final ThumbnailCache<byte[]> CACHE=new ThumbnailCache<>(32L*1024*1024,bytes->bytes.length);
 private EncodedImageCache(){}
 public static ThumbnailCache<byte[]> shared(){return CACHE;}
}
