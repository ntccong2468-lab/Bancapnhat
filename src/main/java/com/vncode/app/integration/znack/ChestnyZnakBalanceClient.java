package com.vncode.app.integration.znack;
import com.google.gson.*;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.znack.signature.*;
import okhttp3.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Independent ELK session; never accepts True API authentication for the ELK balance domain. */
public final class ChestnyZnakBalanceClient {
 public static final URI PRODUCTION=URI.create("https://markirovka.crpt.ru/");
 private static final String BALANCE="/bff-elk/v1/organisation/balance",PROFILE="/bff-elk/v1/profile/organisation";
 private static final int MAX_BYTES=1024*1024;
 public record ServiceSession(String authKind,URI base,String verifiedInn,String environment,String token,Instant expiresAt) {
  public ServiceSession {Gs1Membership.requireInn(verifiedInn);Objects.requireNonNull(base);Objects.requireNonNull(expiresAt);if(authKind==null||environment==null||token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid service session");}
  @Override public String toString(){return "ServiceSession[redacted]";}
 }
 private final OkHttpClient http;private final URI root;private final Predicate<URI> allowed;
 public ChestnyZnakBalanceClient(){this(new OkHttpClient(),PRODUCTION,uri->"https".equals(uri.getScheme())&&"markirovka.crpt.ru".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443));}
 public ChestnyZnakBalanceClient(OkHttpClient http,URI root,Predicate<URI> allowed){this.root=Objects.requireNonNull(root);this.allowed=Objects.requireNonNull(allowed);var builder=http.newBuilder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(20)).callTimeout(Duration.ofSeconds(30)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);builder.interceptors().clear();builder.networkInterceptors().clear();this.http=builder.build();if(!safe(root))throw new IllegalArgumentException("Unverified ELK origin");}
 public ServiceSession authenticate(String inn,String fingerprint,ZnackSignatureProvider signer)throws IOException {
  Gs1Membership.requireInn(inn);if(fingerprint==null||!fingerprint.matches("[A-Fa-f0-9]{40}"))throw unavailable();
  JsonObject login=loginChallenge(inn,false,signer);
  if(login.has("organisations")) {
   boolean eligible=false;try{for(var entry:login.getAsJsonArray("organisations"))if(inn.equals(text(entry.getAsJsonObject(),"inn")))eligible=true;}catch(RuntimeException malformed){throw unavailable();}
   if(!eligible)throw unavailable();login=loginChallenge(inn,true,signer);
  }
  String token=text(login,"access_token");if(token.isBlank())throw unavailable();
  var session=new ServiceSession("ELK",root,inn,"production",token,Instant.now().plusSeconds(1200));verifyProfile(session);return session;
 }
 private JsonObject loginChallenge(String inn,boolean delegated,ZnackSignatureProvider signer)throws IOException {
  JsonObject challenge=object(call("/bff-elk/api/v1/auth/key",null,null));String data=text(challenge,"data"),uuid=text(challenge,"uuid");if(data.isBlank()||data.length()>65536||uuid.isBlank()||uuid.length()>128)throw unavailable();
  String signature;try{signature=signer.sign(data.getBytes(StandardCharsets.UTF_8),ZnackSignatureContext.AUTH_CHALLENGE).base64().replaceAll("[\\r\\n]","");}catch(CryptoProException error){throw new IOException("ELK certificate signing unavailable");}
  if(signature.isBlank()||signature.length()>MAX_BYTES)throw unavailable();var kep=new JsonObject();kep.addProperty("data",signature);kep.addProperty("mrdToken",delegated);kep.addProperty("uuid",uuid);if(delegated)kep.addProperty("inn",inn);var body=new JsonObject();body.add("kep",kep);return object(call("/bff-elk/v1/united-auth/login",null,body));
 }
 public BalanceSnapshot read(BalanceScope scope,ServiceSession session)throws IOException {
  requireSession(session);if(!scope.inn().equals(session.verifiedInn())||!scope.environment().equals(session.environment()))throw unavailable();
  return readAccounts(session).stream().filter(value->value.scope().equals(scope)).findFirst().orElseThrow(ChestnyZnakBalanceClient::unavailable);
 }
 public List<BalanceSnapshot> readAccounts(ServiceSession session)throws IOException {
  verifyProfile(session);JsonElement response=call(BALANCE,session.token(),null);try{
   if(!response.isJsonArray()||response.getAsJsonArray().size()>1000)throw unavailable();var result=new ArrayList<BalanceSnapshot>();var seen=new HashSet<BalanceScope>();
   for(var entry:response.getAsJsonArray()) {
    var account=entry.getAsJsonObject();String organisation=integerId(account,"organisationId"),group=integerId(account,"productGroupId"),id=integerId(account,"contractId");
    if(organisation.isBlank()||!account.has("balance")||account.get("balance").isJsonNull())throw unavailable();
    if(account.has("currency")&&!"RUB".equals(text(account,"currency")))throw unavailable();
    var minor=new BigDecimal(account.get("balance").getAsString());minor.toBigIntegerExact();var scope=new BalanceScope(session.verifiedInn(),session.environment(),group,id);if(!seen.add(scope))throw unavailable();
    result.add(new BalanceSnapshot(scope,minor.movePointLeft(2).setScale(2),"RUB",Instant.now(),root.resolve(BALANCE)));
   }return List.copyOf(result);
  }catch(RuntimeException malformed){throw unavailable();}
 }
 private void verifyProfile(ServiceSession session)throws IOException {requireSession(session);var profile=object(call(PROFILE,session.token(),null));if(!session.verifiedInn().equals(text(profile,"inn")))throw unavailable();}
 private void requireSession(ServiceSession s)throws IOException {if(s==null||!"ELK".equals(s.authKind())||!"production".equals(s.environment())||!root.equals(s.base())||!safe(s.base())||!s.expiresAt().isAfter(Instant.now()))throw unavailable();}
 private boolean safe(URI uri){return uri.isAbsolute()&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null&&allowed.test(uri);}
 private JsonElement call(String path,String token,JsonObject payload)throws IOException {
  URI endpoint=root.resolve(path);if(!safe(endpoint))throw unavailable();var request=new Request.Builder().url(endpoint.toString()).header("Accept","application/json");if(token!=null)request.header("Authorization","Bearer "+token);
  if(payload!=null)request.post(RequestBody.create(payload.toString(),MediaType.get("application/json; charset=utf-8")));
  try(Response response=http.newCall(request.build()).execute()){
   if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>MAX_BYTES)throw unavailable();byte[] bytes=response.body().byteStream().readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw unavailable();return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
  }catch(IOException|RuntimeException failure){throw unavailable();}
 }
 private static String text(JsonObject json,String key){try{return json.has(key)&&!json.get(key).isJsonNull()?json.get(key).getAsString():"";}catch(RuntimeException malformed){return "";}}
 private static String integerId(JsonObject json,String key)throws IOException {String value=text(json,key);if(!value.matches("[0-9]{1,30}"))throw unavailable();return value;}
 private static JsonObject object(JsonElement value)throws IOException {if(!value.isJsonObject())throw unavailable();return value.getAsJsonObject();}
 private static IOException unavailable(){return new IOException("ELK balance unavailable or scope unverified");}
}
