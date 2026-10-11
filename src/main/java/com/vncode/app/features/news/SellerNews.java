package com.vncode.app.features.news;
import java.net.URI;
import java.time.Instant;
import java.util.*;
public record SellerNews(String source,String sourceId,String revision,NewsScope scope,Instant publishedAt,String originalTitle,String originalBody,URI sourceURL) {
 public static final String WB="WB_OFFICIAL";
 public SellerNews {Objects.requireNonNull(scope);Objects.requireNonNull(publishedAt);Objects.requireNonNull(sourceURL);if(!WB.equals(source)||sourceId==null||!sourceId.matches("[0-9]{1,30}")||revision==null||!revision.matches("[a-f0-9]{64}")||originalTitle==null||originalTitle.isBlank()||originalTitle.length()>500||originalBody==null||originalBody.length()>100000||!"https".equals(sourceURL.getScheme())||sourceURL.getUserInfo()!=null||sourceURL.getPort()!=-1&&sourceURL.getPort()!=443||sourceURL.getHost()==null||!(sourceURL.getHost().equals("wildberries.ru")||sourceURL.getHost().endsWith(".wildberries.ru")))throw new IllegalArgumentException("Invalid sourced seller news");}
 public static SellerNews wb(NewsScope scope,String id,Instant date,String title,String body,URI url){return new SellerNews(WB,id,NewsScope.hash(id+"\n"+date+"\n"+title+"\n"+body),scope,date,title,body,url);}
 public String displayId(){return "WB:"+scope.shopId()+":"+scope.ownerFingerprint()+":"+sourceId;}
 @Override public String toString(){return "SellerNews[source="+source+",id="+sourceId+"]";}
}
