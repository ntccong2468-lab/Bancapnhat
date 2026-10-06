package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class ZnackApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackApiClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    /** True API cises/info accepts no more than 1,000 identification codes per request. */
    static final int CISES_MAX_CODES_PER_REQUEST = 1_000;
    // National Catalog API v5.62 sections 2.1/2.5: Retry-After is seconds and a limit window is at most five minutes.
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 3;
    private static final long RATE_LIMIT_MAX_TOTAL_DELAY_MS = Duration.ofMinutes(5).toMillis();
    private static final long RATE_LIMIT_FALLBACK_DELAY_MS = 1_000L;
    private final OkHttpClient client;
    private final Sleeper sleeper;
    private final Gson gson = new Gson();

    public ZnackApiClient() {
        this(defaultClient(), Thread::sleep);
    }

    ZnackApiClient(OkHttpClient client) {
        this(client, Thread::sleep);
    }

    ZnackApiClient(Sleeper sleeper) {
        this(defaultClient(), sleeper);
    }

    ZnackApiClient(OkHttpClient client, Sleeper sleeper) {
        this.client = client;
        this.sleeper = sleeper;
    }

    public JsonObject authKey(String base) throws IOException { return get(authBase(base), "/auth/key", null).getAsJsonObject(); }
    public JsonObject signIn(String base, String connection, JsonObject body) throws IOException {
        return post(authBase(base), "/auth/simpleSignIn" + (connection == null || connection.isBlank() ? "" : "/" + connection), null, body).getAsJsonObject();
    }
    public JsonElement products(String base, String token) throws IOException { return products(base, token, 0, 10_000); }
    public JsonElement products(String base, String token, int page, int limit) throws IOException {
        return get(trueApiBase(base, 4), "/product/gtin?includeSubaccount=false&limit=" + limit + "&page=" + page + "&pg=lp", token);
    }
    public JsonElement productInfo(String base,String token,String gtin)throws IOException{
        JsonObject body=new JsonObject();JsonArray gtins=new JsonArray();gtins.add(gtin);body.add("gtins",gtins);
        return post(trueApiBase(base,4),"/product/info",token,body);
    }
    public JsonElement productCards(String base, String token, String gtins) throws IOException {
        return get(trueApiBase(base, 3), "/nk/feed-product?gtins=" + url(gtins), token);
    }
    public JsonElement permitDocuments(String base,String token,String gtin,String inn)throws IOException{
        JsonObject body=new JsonObject();body.addProperty("gtin",gtin);
        if(inn!=null&&!inn.isBlank())body.addProperty("inn",inn.trim());
        return post(nationalCatalogBase(base),"/v4/rd-info-by-gtin",token,body);
    }
    public JsonElement generatedGtins(String base, String token) throws IOException {
        // exist=1 is only a read-only lookup for previously allocated draft GTINs. National
        // Catalog returns 404 when the account has none, so that response must not abort the
        // registration form. The real quantity request still performs the authoritative GS1
        // eligibility/quota check immediately before allocating a new GTIN.
        return getEmptyOnNotFound(nationalCatalogBase(base), "/v3/generate-gtins?exist=1", token);
    }
    public JsonElement generateGtins(String base, String token, int quantity) throws IOException {
        if (quantity < 1 || quantity > 500) throw new IllegalArgumentException("GTIN quantity must be between 1 and 500.");
        // Despite using GET, this endpoint allocates new numbers and is not idempotent.
        // Never let the generic safe-GET retry policy allocate a second batch implicitly.
        return getWithoutRetry(nationalCatalogBase(base), "/v3/generate-gtins?quantity=" + quantity, token);
    }
    public JsonElement nationalCatalogCategories(String base, String token, String tnved) throws IOException {
        // Use the National Catalog gateway exposed by True API. The bearer token is issued by
        // this same GIS MT environment, and the official True API documentation publishes the
        // lookup as /nk/categories. Calling the standalone production host directly can return
        // an empty 404 for an otherwise valid GIS MT token/account combination.
        return getEmptyOnNotFound(trueApiBase(base, 3), "/nk/categories?tnved=" + url(tnved), token);
    }
    public JsonElement nationalCatalogAttributes(String base, String token, long categoryId) throws IOException {
        return getEmptyOnNotFound(trueApiBase(base, 3),
                "/nk/attributes?cat_id=" + categoryId + "&attr_type=m", token);
    }
    public JsonElement submitNationalCatalogFeed(String base, String token, JsonElement feed) throws IOException {
        return post(nationalCatalogBase(base), "/v3/feed", token, feed);
    }
    public JsonElement nationalCatalogFeedStatus(String base, String token, String feedId) throws IOException {
        return get(nationalCatalogBase(base), "/v3/feed-status?verbose=true&feed_id=" + url(feedId), token);
    }
    public JsonElement nationalCatalogSigningDocument(String base, String token, JsonObject body) throws IOException {
        return post(nationalCatalogBase(base), "/v3/feed-product-document", token, body);
    }
    public JsonElement signNationalCatalogProduct(String base, String token, JsonArray body) throws IOException {
        if (body == null || body.isEmpty() || body.size() > 10) {
            throw new IllegalArgumentException("National Catalog signing accepts 1 to 10 cards per batch.");
        }
        return post(nationalCatalogBase(base), "/v3/feed-product-sign-pkcs", token, body);
    }
    public JsonObject createOrder(String base,String token,String omsId,byte[] body,String signature)throws IOException{
        Request request=new Request.Builder().url(join(base,"/api/v3/order?omsId="+url(omsId))).headers(suzHeaders(token).newBuilder().add("X-Signature",signature).build())
                .post(RequestBody.create(body,JSON)).build();
        return execute(request).getAsJsonObject();
    }
    public JsonObject orderList(String base,String token,String omsId)throws IOException{
        return suzGet(base,"/api/v3/order/list?omsId="+url(omsId),token).getAsJsonObject();
    }
    public JsonArray orderStatus(String base,String token,String omsId,String orderId)throws IOException{return suzGet(base,"/api/v3/order/status?omsId="+url(omsId)+"&orderId="+url(orderId),token).getAsJsonArray();}
    public JsonElement codes(String base,String token,String omsId,String orderId,int quantity,String gtin)throws IOException{
        return suzGet(base,"/api/v3/codes?omsId="+url(omsId)+"&orderId="+url(orderId)+"&quantity="+quantity+"&gtin="+url(gtin),token);
    }
    public JsonElement codeBlocks(String base,String token,String omsId,String orderId,String gtin)throws IOException{
        return suzGet(base,"/api/v3/order/codes/blocks?omsId="+url(omsId)+"&orderId="+url(orderId)+"&gtin="+url(gtin),token);
    }
    public JsonElement retryCodeBlock(String base,String token,String omsId,String blockId)throws IOException{
        return suzGet(base,"/api/v3/order/codes/retry?omsId="+url(omsId)+"&blockId="+url(blockId),token);
    }
    public String createDocument(String base,String token,JsonObject body)throws IOException{
        String endpoint=join(trueApiBase(base,3),"/lk/documents/create?pg=lp");
        JsonElement response=post(trueApiBase(base,3),"/lk/documents/create?pg=lp",token,body);
        String documentId=documentId(response);
        if(!documentId.isBlank())return documentId;
        String raw=response==null?"null":response.toString();
        LOGGER.error("Znack API returned an unexpected document creation response. url={}, responseBody={}",
                endpoint,ZnackSanitizer.diagnostic(raw));
        throw new IOException("Znack document creation response did not contain a document ID. Response: "
                +ZnackSanitizer.message(raw));
    }
    public JsonElement document(String base,String token,String documentId)throws IOException{
        return get(trueApiBase(base,4),"/doc/"+url(documentId)+"/info?pg=lp",token);
    }
    public JsonElement cisesInfo(String base,String token,JsonElement body)throws IOException{
        if(body==null||!body.isJsonArray())throw new IllegalArgumentException("Znack cises/info requires an array of KIZ codes.");
        if(body.getAsJsonArray().size()>CISES_MAX_CODES_PER_REQUEST){
            throw new IllegalArgumentException("Znack cises/info accepts at most "
                    +CISES_MAX_CODES_PER_REQUEST+" KIZ codes per request.");
        }
        Request request=new Request.Builder().url(join(trueApiBase(base,3),"/cises/info?pg=lp")).headers(headers(token))
                .post(RequestBody.create(gson.toJson(body),JSON)).build();
        return execute(request,true);
    }

    private JsonElement get(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).get().build());}
    private JsonElement getEmptyOnNotFound(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).get().build(),false,true,true);}
    private JsonElement getWithoutRetry(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).get().build(),false,false);}
    private JsonElement suzGet(String base,String path,String token)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(suzHeaders(token)).get().build());}
    private JsonElement post(String base,String path,String token,Object body)throws IOException{return execute(new Request.Builder().url(join(base,path)).headers(headers(token)).post(RequestBody.create(gson.toJson(body),JSON)).build());}
    private Headers headers(String token){Headers.Builder h=new Headers.Builder().add("Accept","application/json");if(token!=null&&!token.isBlank())h.add("Authorization","Bearer "+token);return h.build();}
    private Headers suzHeaders(String token){Headers.Builder h=new Headers.Builder().add("Accept","application/json");if(token!=null&&!token.isBlank())h.add("clientToken",token);return h.build();}
    // errorCode 1090 ("Проверка учетных данных УОТ не пройдена") is a transient SUZ/УОТ credential
    // check that fails intermittently when several purchases run at once. It rejects the request BEFORE
    // any order is created, so re-sending the identical request is safe (no duplicate order/charge).
    private static final String UOT_CREDENTIAL_ERROR_CODE="1090";
    private static final int UOT_CREDENTIAL_RETRY_ATTEMPTS=3;
    private static final long UOT_CREDENTIAL_RETRY_BASE_DELAY_MS=900;

    private JsonElement execute(Request request)throws IOException{return execute(request,false);}
    private JsonElement execute(Request request,boolean allowNotFoundBody)throws IOException{return execute(request,allowNotFoundBody,true);}
    private JsonElement execute(Request request,boolean allowNotFoundBody,boolean allowRetry)throws IOException{return execute(request,allowNotFoundBody,allowRetry,false);}
    private JsonElement execute(Request request,boolean allowNotFoundBody,boolean allowRetry,boolean emptyOnNotFound)throws IOException{
        long rateLimitDelayUsed=0;
        for(int attempt=1;;attempt++){
            try(Response response=client.newCall(request).execute()){
                String body=response.body()==null?"":response.body().string();
                if(emptyOnNotFound&&response.code()==404){
                    LOGGER.info("Znack API returned no existing resource; continuing with an empty result. method={}, url={}",
                            request.method(),request.url());
                    return JsonNull.INSTANCE;
                }
                if(!response.isSuccessful()&&!(allowNotFoundBody&&response.code()==404&&!body.isBlank())){
                    if(allowRetry&&attempt<UOT_CREDENTIAL_RETRY_ATTEMPTS
                            &&UOT_CREDENTIAL_ERROR_CODE.equals(ZnackErrorMessages.errorCode(body))){
                        LOGGER.warn("Znack УОТ credential check failed (errorCode 1090); retrying {}/{}. method={}, url={}",
                                attempt,UOT_CREDENTIAL_RETRY_ATTEMPTS-1,request.method(),request.url());
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    if(allowRetry&&response.code()==429&&isIdempotent(request)&&attempt<RATE_LIMIT_MAX_ATTEMPTS){
                        long delay=rateLimitDelay(response.header("Retry-After"),attempt);
                        if(delay<=RATE_LIMIT_MAX_TOTAL_DELAY_MS-rateLimitDelayUsed){
                            LOGGER.warn("Znack API rate limit reached; retrying safe request after {} ms. method={}, attempt={}/{}",
                                    delay,request.method(),attempt,RATE_LIMIT_MAX_ATTEMPTS);
                            sleepRateLimit(delay);
                            rateLimitDelayUsed+=delay;
                            continue;
                        }
                    }
                    LOGGER.error("Znack API request failed. method={}, url={}, httpStatus={}, contentType={}, responseBody={}",
                            request.method(),request.url(),response.code(),response.header("Content-Type",""),
                            ZnackSanitizer.diagnostic(body));
                    throw new ZnackApiException("Znack API request failed",response.code(),body,
                            request.method(),request.url().toString());
                }
                if(body.isBlank())return JsonNull.INSTANCE;
                try{
                    return JsonParser.parseString(body);
                }catch(JsonParseException e){
                    LOGGER.error("Znack API returned invalid JSON. method={}, url={}, httpStatus={}, contentType={}, responseBody={}",
                            request.method(),request.url(),response.code(),response.header("Content-Type",""),
                            ZnackSanitizer.diagnostic(body),e);
                    throw new IOException("Znack API returned invalid JSON (HTTP "+response.code()+"): "
                            +ZnackSanitizer.message(body),e);
                }
            }
        }
    }
    private static OkHttpClient defaultClient(){return new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(40)).build();}
    private static boolean isIdempotent(Request request){return "GET".equals(request.method())||"HEAD".equals(request.method());}
    private static long rateLimitDelay(String retryAfter,int attempt){
        if(retryAfter!=null)try{
            long seconds=Long.parseLong(retryAfter.trim());
            if(seconds>=0)return Math.multiplyExact(seconds,1_000L);
        }catch(ArithmeticException|NumberFormatException ignored){}
        return RATE_LIMIT_FALLBACK_DELAY_MS<<(attempt-1);
    }
    private void sleepRateLimit(long delay)throws IOException{
        try{sleeper.sleep(delay);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while waiting for the Znack rate limit.",e);}
    }
    private static void sleepBeforeRetry(int attempt)throws IOException{
        try{Thread.sleep(UOT_CREDENTIAL_RETRY_BASE_DELAY_MS*attempt);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while retrying Znack request.",e);}
    }
    private String documentId(JsonElement response){
        if(response==null||response.isJsonNull())return "";
        if(response.isJsonPrimitive())return response.getAsString().trim();
        if(!response.isJsonObject())return "";
        JsonObject object=response.getAsJsonObject();
        for(String key:new String[]{"uuid","document_id","documentId","id"})
            if(object.has(key)&&!object.get(key).isJsonNull()&&object.get(key).isJsonPrimitive())
                return object.get(key).getAsString().trim();
        return "";
    }
    private static String join(String base,String path){return base.replaceAll("/+$","")+path;}
    private static String url(String v){return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);}
    static String apiRoot(String base) {
        return base.replaceAll("/api/v\\d+/(?:true-api|lk)/?$", "").replaceAll("/+$", "");
    }
    static String authBase(String base) { return trueApiBase(base, 3); }
    static String trueApiBase(String base, int version) { return apiRoot(base) + "/api/v" + version + "/true-api"; }
    static String nationalCatalogBase(String trueApiBase) {
        String normalized=trueApiBase==null?"":trueApiBase.trim().toLowerCase(java.util.Locale.ROOT);
        if(normalized.contains("sandbox"))return ZnackModels.SANDBOX_NATIONAL_CATALOG;
        if(normalized.isBlank()||normalized.contains("markirovka.crpt.ru"))return ZnackModels.PRODUCTION_NATIONAL_CATALOG;
        return apiRoot(trueApiBase);
    }

    public static class ZnackApiException extends IOException {
        private final int statusCode;
        private final String responseBody;
        private final String method;
        private final String url;
        public ZnackApiException(String message,int statusCode,String body){this(message,statusCode,body,"","");}
        public ZnackApiException(String message,int statusCode,String body,String method,String url){
            super(message+" (HTTP "+statusCode+"): "+ZnackSanitizer.message(body));
            this.statusCode=statusCode;
            this.responseBody=ZnackSanitizer.diagnostic(body);
            this.method=method==null?"":method;
            this.url=ZnackSanitizer.diagnostic(url);
        }
        public int statusCode(){return statusCode;}
        public String responseBody(){return responseBody;}
        public String method(){return method;}
        public String url(){return url;}
        public String diagnosticDetails(){
            return "HTTP status: "+statusCode
                    +(method.isBlank()?"":"\nMethod: "+method)
                    +(url.isBlank()?"":"\nURL: "+url)
                    +(responseBody.isBlank()?"":"\nResponse body:\n"+responseBody);
        }
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis)throws InterruptedException; }
}
