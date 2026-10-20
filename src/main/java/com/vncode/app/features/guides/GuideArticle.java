package com.vncode.app.features.guides;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;

/** A local browser-readable copy of VNCode's own guide, available without a network login. */
public final class GuideArticle {
    private GuideArticle(){}
    public static Path write(Path directory,String key,String title,String text)throws IOException{
        if(key==null||!key.matches("[a-z]{2,12}"))throw new IllegalArgumentException("Invalid guide");
        Files.createDirectories(directory);var file=directory.resolve(key+".html");
        String html="<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><title>"+escape(title)+"</title><style>body{max-width:820px;margin:48px auto;padding:0 24px;font:18px/1.65 system-ui,sans-serif;color:#16334b;background:#f0f7fc}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere}h1{line-height:1.2}</style><h1>"+escape(title)+"</h1><pre>"+escape(text)+"</pre></html>";
        Files.writeString(file,html,StandardCharsets.UTF_8);return file;
    }
    private static String escape(String value){return (value==null?"":value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
