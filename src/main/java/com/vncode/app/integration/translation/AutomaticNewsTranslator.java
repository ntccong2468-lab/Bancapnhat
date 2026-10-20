package com.vncode.app.integration.translation;

import com.google.gson.JsonParser;
import okhttp3.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Key-free translation of public marketplace bulletins only; no shop credentials are sent. */
public final class AutomaticNewsTranslator implements NewsTranslator {
    private final OkHttpClient http;
    private final HttpUrl endpoint;
    private volatile boolean closed;
    private volatile Call active;
    public AutomaticNewsTranslator(){this(new OkHttpClient(),HttpUrl.get("https://translate.googleapis.com/translate_a/single"));}
    AutomaticNewsTranslator(OkHttpClient client,HttpUrl endpoint){
        this.endpoint=endpoint;
        http=client.newBuilder().connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(20)).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).build();
    }
    public String version(){return "automatic-public-news/vi/2";}
    public List<String> translate(List<String> texts,String source,String target)throws IOException {
        if(texts==null||texts.size()>2||!"vi".equals(target)||!source.matches("[a-z]{2,3}"))throw new IOException("Invalid news translation");
        var result=new ArrayList<String>();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(100);
        for(String text:texts){
            if(text==null||text.length()>100000)throw new IOException("News text too long");
            var protectedValues=new LinkedHashMap<String,String>();
            var matcher=java.util.regex.Pattern.compile("https?://[^\\s)]+|[0-9]+(?:[.,/:-][0-9]+)*%?").matcher(text);
            String nonce=UUID.randomUUID().toString().replaceAll("[^a-f]","").toUpperCase(Locale.ROOT);
            var masked=new StringBuilder();int tokenIndex=0;
            while(matcher.find()){String token="VNCODE_LITERAL_"+nonce+"_"+letters(tokenIndex++)+"_END";protectedValues.put(token,matcher.group());matcher.appendReplacement(masked,java.util.regex.Matcher.quoteReplacement(token));}matcher.appendTail(masked);
            String protectedText=masked.toString();var translated=new StringBuilder();
            for(int start=0;start<protectedText.length();){
                if(closed||System.nanoTime()>deadline)throw new IOException("News translation unavailable");
                int end=Math.min(start+600,protectedText.length());if(end<protectedText.length()&&Character.isHighSurrogate(protectedText.charAt(end-1)))end--;
                int tokenStart=protectedText.lastIndexOf("VNCODE_LITERAL_",end-1);if(tokenStart>=start){int tokenEnd=protectedText.indexOf("_END",tokenStart)+4;if(tokenEnd>end)end=tokenStart>start?tokenStart:tokenEnd;}
                var url=endpoint.newBuilder().addQueryParameter("client","gtx").addQueryParameter("sl",source).addQueryParameter("tl",target).addQueryParameter("dt","t").addQueryParameter("q",protectedText.substring(start,end)).build();
                var call=http.newCall(new Request.Builder().url(url).header("Accept","application/json").build());active=call;if(closed)call.cancel();
                try(var response=call.execute()){
                    if(!response.isSuccessful()||response.body()==null)throw new IOException("News translation unavailable");
                    byte[] bytes=response.body().byteStream().readNBytes(256*1024+1);if(bytes.length>256*1024)throw new IOException("News translation too large");
                    var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonArray();
                    var segments=root.get(0).getAsJsonArray();if(segments.isEmpty())throw new IOException("Empty translation");
                    for(var segment:segments)translated.append(segment.getAsJsonArray().get(0).getAsString());
                }catch(RuntimeException invalid){throw new IOException("Invalid news translation");}finally{if(active==call)active=null;}
                start=end;
            }
            if(!text.isBlank()&&translated.isEmpty())throw new IOException("Empty translation");String value=translated.toString();for(var token:protectedValues.entrySet()){
                int first=value.indexOf(token.getKey());if(first<0||value.indexOf(token.getKey(),first+token.getKey().length())>=0)throw new IOException("Translation changed a protected news value");
                value=value.replace(token.getKey(),token.getValue());
            }result.add(value);
        }
        return List.copyOf(result);
    }
    private static String letters(int index){var value=new StringBuilder();do{value.append((char)('A'+index%26));index=index/26-1;}while(index>=0);return value.reverse().toString();}
    public void close(){closed=true;var call=active;if(call!=null)call.cancel();}
}
