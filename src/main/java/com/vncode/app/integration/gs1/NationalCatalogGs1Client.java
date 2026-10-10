package com.vncode.app.integration.gs1;

import com.google.gson.*;
import com.vncode.app.integration.znack.signature.*;
import okhttp3.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Independent adapter for the official Catalog web contract documented in docs/research. */
public final class NationalCatalogGs1Client {
    public static final String PROFILE_URL = "https://xn--j1ab.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai/profile";
    private static final int LIMIT = 524288;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient transport;
    private final HttpUrl root;
    public NationalCatalogGs1Client() {
        this(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(45)).build(),
                Objects.requireNonNull(HttpUrl.parse("https://xn--j1ab.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai/rest/")));
    }
    public NationalCatalogGs1Client(OkHttpClient transport, HttpUrl root) {
        this.transport = transport.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        this.root = Objects.requireNonNull(root);
    }
    public Session login(String inn, String fingerprint, ZnackSignatureProvider signer) throws IOException {
        Gs1Membership.requireInn(inn);
        String normalized = fingerprint == null ? "" : fingerprint.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-F0-9]{40}")) throw new IllegalArgumentException("A resolved certificate fingerprint is required");
        Session session = new Session(inn, normalized, Objects.requireNonNull(signer));
        session.authenticate();
        return session;
    }
    @FunctionalInterface public interface XmlSigner { String sign(String xml) throws Exception; }
    /** Local signing failed before any sign-form POST could be made. */
    public static final class SigningFailedException extends IOException {
        private SigningFailedException(){super("GS1 XML signing did not complete; no application was submitted");}
    }
    public record Choice(String id, String text) { @Override public String toString() { return text; } }
    public record Address(String address, String fullGuid, String houseGuid, String postalCode) {
        @Override public String toString() { return address; }
    }
    public final class Session {
        private final String inn;
        private final String fingerprint;
        private final ZnackSignatureProvider signer;
        private final OkHttpClient http;
        private String csrf;
        private Boolean member;
        private boolean excluded;
        public String inn() { return inn; }
        private Session(String inn, String fingerprint, ZnackSignatureProvider signer) {
            this.inn = inn; this.fingerprint = fingerprint; this.signer = signer;
            this.http = transport.newBuilder().cookieJar(new CookieJar() {
                private final List<Cookie> cookies = new ArrayList<>();
                @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
                    for (Cookie fresh : incoming) {
                        cookies.removeIf(c -> c.name().equals(fresh.name()) && c.domain().equals(fresh.domain()) && c.path().equals(fresh.path()));
                        if (fresh.expiresAt() > System.currentTimeMillis()) cookies.add(fresh);
                    }
                }
                @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
                    cookies.removeIf(c -> c.expiresAt() <= System.currentTimeMillis());
                    return cookies.stream().filter(c -> c.matches(url)).toList();
                }
            }).build();
        }
        private void authenticate() throws IOException {
            JsonObject challenge = object(call("signed-data", null));
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(text(challenge, "data")); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid Catalog signing challenge"); }
            if (bytes.length == 0) throw new IOException("Empty Catalog signing challenge");
            String signature;
            try { signature = signer.sign(bytes, ZnackSignatureContext.TRUE_API_DOCUMENT).base64(); }
            catch (CryptoProException error) { throw new IOException("Catalog certificate signing failed (" + error.code() + ")"); }
            JsonObject credentials = new JsonObject();
            credentials.addProperty("inn", inn); credentials.addProperty("fingerprint", fingerprint); credentials.addProperty("signature", signature);
            JsonObject result = object(call("certlogin", credentials));
            csrf = text(result, "csrfToken");
            member = bool(result, "gs1Member"); excluded = Boolean.TRUE.equals(bool(result, "gs1Excluded"));
        }
        public synchronized Gs1Membership membership() throws IOException {
            JsonObject result = object(call("gs1/gcp-gln", null));
            JsonObject form = form();
            JsonObject applicant = form.has("applicant") && form.get("applicant").isJsonObject() ? form.getAsJsonObject("applicant") : new JsonObject();
            if (!inn.equals(text(applicant, "inn"))) throw new IOException("Catalog enterprise identity could not be verified");
            List<Gs1Membership.Prefix> prefixes = new ArrayList<>();
            if (result.has("gcps") && result.get("gcps").isJsonArray()) {
                try {
                    for (JsonElement item : result.getAsJsonArray("gcps")) {
                        JsonObject prefix = item.getAsJsonObject();
                        String gcp = text(prefix, "gcp");
                        if (!gcp.matches("[0-9]{4,12}")) throw new IllegalArgumentException();
                        Integer quota = prefix.has("gtinsLeft") && !prefix.get("gtinsLeft").isJsonNull()
                                ? new java.math.BigDecimal(prefix.get("gtinsLeft").getAsString()).intValueExact() : null;
                        List<String> glns = new ArrayList<>();
                        if (prefix.has("glns") && prefix.get("glns").isJsonArray()) {
                            for (JsonElement gln : prefix.getAsJsonArray("glns")) {
                                String id = text(gln.getAsJsonObject(), "gln");
                                if (!id.matches("[0-9]{13}")) throw new IllegalArgumentException();
                                glns.add(id);
                            }
                        }
                        prefixes.add(new Gs1Membership.Prefix(gcp, quota, glns));
                    }
                } catch (RuntimeException invalid) { throw new IOException("Invalid Catalog GS1 identifiers or allowance"); }
            }
            String name = text(applicant, "companyName");
            if (name.isBlank()) name = text(applicant, "companyNameShort");
            return new Gs1Membership(inn, name, member, excluded, date(text(result,"expiryDate")), prefixes, Instant.now(), Gs1Membership.CATALOG_SOURCE);
        }
        public synchronized JsonObject form() throws IOException {
            JsonObject value=object(call("gs1/form",null));
            if (value.has("applicant") && value.get("applicant").isJsonObject()) {
                String owner=text(value.getAsJsonObject("applicant"),"inn");
                if (!owner.isBlank() && !owner.equals(inn)) throw new IOException("Catalog returned another enterprise application");
            }
            return value;
        }
        public synchronized List<Choice> dictionary(String name) throws IOException {
            if (!Set.of("opf","positions","profiles-organization","enterprise-sector","main-products","documents","provider","participation","selling-ways","distribution-channels").contains(name)) throw new IllegalArgumentException("Unknown GS1 dictionary");
            List<Choice> result=new ArrayList<>();
            for(JsonElement entry:array(call("dictionaries/gs1/"+name,null))) {
                JsonObject value=entry.getAsJsonObject();String id=text(value,"id"),label=text(value,"text");
                if(!id.isBlank()&&!label.isBlank())result.add(new Choice(id,label));
            }
            return List.copyOf(result);
        }
        public synchronized List<Address> addresses(String query) throws IOException {
            if(query==null||query.strip().length()<3)return List.of();
            HttpUrl url=Objects.requireNonNull(root.resolve("gs1/fias-address")).newBuilder().addQueryParameter("address",query.strip()).build();
            List<Address> result=new ArrayList<>();
            for(JsonElement entry:array(exchange(url,null))) {
                JsonObject value=entry.getAsJsonObject();
                result.add(new Address(text(value,"address"),text(value,"fullGuid"),text(value,"houseGuid"),text(value,"postalCode")));
                if(result.size()==15)break;
            }
            return List.copyOf(result);
        }
        public synchronized void saveForm(JsonObject form) throws IOException {
            if(!form.has("applicant")||!form.get("applicant").isJsonObject()||!inn.equals(text(form.getAsJsonObject("applicant"),"inn")))throw new IOException("Application owner differs from certificate enterprise");
            JsonObject draft=form.deepCopy();
            for(String computed:List.of("isSent","errorText","expiryDate"))draft.remove(computed);
            acknowledge(call("gs1/form",draft));
        }
        public synchronized String signingDocument() throws IOException {
            JsonObject document=object(call("gs1/sign-form",null));
            String xml=text(document,"XMLForSign");
            if(xml.isBlank())throw new IOException("Catalog returned no GS1 application to sign");
            return xml;
        }
        /** Signs the caller's retained, reviewed document; never fetches a replacement XML. */
        public synchronized void submitDocument(String xml,XmlSigner signer) throws IOException {
            if(xml==null||xml.isBlank()||xml.length()>LIMIT)throw new IOException("Invalid reviewed GS1 application");
            String signed;
            try{signed=signer.sign(xml);}catch(Exception failure){throw new SigningFailedException();}
            if(signed==null||signed.isBlank())throw new SigningFailedException();
            JsonObject body=new JsonObject();body.addProperty("signedXML",signed);
            acknowledge(call("gs1/sign-form",body));
        }
        private String call(String path,JsonObject body)throws IOException {
            return exchange(Objects.requireNonNull(root.resolve(path)),body);
        }
        private String exchange(HttpUrl url,JsonObject body)throws IOException {
            Request.Builder request=new Request.Builder().url(url).header("Accept","application/json");
            if(csrf!=null&&!csrf.isBlank())request.header("X-Csrf-Token",csrf);
            if(body!=null)request.post(RequestBody.create(body.toString(),JSON));
            try(Response response=http.newCall(request.build()).execute()) {
                if(!response.isSuccessful()||response.body()==null)throw new IOException("Catalog GS1 request failed (HTTP "+response.code()+")");
                byte[] bytes=response.peekBody(LIMIT+1L).bytes();
                if(bytes.length>LIMIT)throw new IOException("Catalog GS1 response exceeds size limit");
                return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
    private static void acknowledge(String response)throws IOException{if(!response.isBlank())object(response);}
    private static JsonElement parsed(String response)throws IOException{
        try{
            JsonElement value=JsonParser.parseString(response);
            if(value.isJsonObject()){
                JsonElement error=value.getAsJsonObject().get("error");
                if(error!=null&&!error.isJsonNull()&&!(error.isJsonPrimitive()&&error.getAsJsonPrimitive().isBoolean()&&!error.getAsBoolean()))throw new IOException("Catalog GS1 request was refused; review the official cabinet");
            }
            return value;
        }catch(RuntimeException invalid){throw new IOException("Invalid Catalog GS1 response");}
    }
    private static JsonObject object(String response)throws IOException{
        JsonElement value=parsed(response);if(!value.isJsonObject())throw new IOException("Expected Catalog GS1 object");return value.getAsJsonObject();
    }
    private static JsonArray array(String response)throws IOException{
        JsonElement value=parsed(response);if(!value.isJsonArray())throw new IOException("Expected Catalog GS1 list");return value.getAsJsonArray();
    }
    public static String text(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()?value.getAsString():"";}
    private static Boolean bool(JsonObject object,String key){JsonElement value=object.get(key);return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():null;}
    private static LocalDate date(String value){
        try{return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalDate();}
        catch(RuntimeException ignored){try{return LocalDate.parse(value);}catch(RuntimeException invalid){return null;}}
    }
}
