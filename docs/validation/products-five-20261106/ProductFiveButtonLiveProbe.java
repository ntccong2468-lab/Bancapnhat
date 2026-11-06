package com.vncode.app.features.products;
import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.features.listingcopy.CopyState;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbProductSnapshotReader;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** User-requested five-field live check: resends the observed values, never invents business data. */
public final class ProductFiveButtonLiveProbe {
 static Path root;static Gson json=new GsonBuilder().setPrettyPrinting().create();static String field="prepare";static int mutations,reads;
 static Map<String,Object> summary=new LinkedHashMap<>();static List<Map<String,Object>> outcomes=new ArrayList<>();
 static void save(String file,Object value)throws IOException{Files.writeString(root.resolve(file),ProductJson.GSON.toJson(value));}
 static void checkpoint(String phase)throws IOException{summary.put("phase",phase);summary.put("mutationsAttempted",mutations);summary.put("readCalls",reads);summary.put("fields",outcomes);save("live-summary.json",summary);}
 static final class Transport implements ListingTransport {
  final ListingTransport delegate;Transport(Shop s){delegate=new ListingHttpClient(s);}
  void intent(String path,JsonElement body,boolean mutation)throws IOException{
   if(mutation){if(!path.equals("content/v2/cards/update")||mutations>=5)throw new SecurityException("Unapproved mutation");mutations++;save("private-request-"+field+".json",body);checkpoint("DISPATCHING_"+field);}
   else reads++;
  }
  JsonElement receipt(JsonElement value,boolean mutation)throws IOException{if(mutation){save("private-response-"+field+".json",value);checkpoint("ACKNOWLEDGED_"+field);}return value;}
  public JsonElement call(String path,JsonElement body,boolean mutation)throws IOException{intent(path,body,mutation);return receipt(delegate.call(path,body,mutation),mutation);}
  public JsonElement call(String path,JsonElement body,boolean mutation,Runnable before)throws IOException{
   if(!mutation){reads++;return delegate.call(path,body,false,before);}return receipt(delegate.call(path,body,true,()->{before.run();try{intent(path,body,true);}catch(IOException e){throw new UncheckedIOException(e);}}),true);
  }
  public JsonElement call(String path,JsonElement body,boolean mutation,CheckedGuard fresh,Runnable before)throws IOException{
   if(!mutation){reads++;return delegate.call(path,body,false,fresh,before);}return receipt(delegate.call(path,body,true,fresh,()->{before.run();try{intent(path,body,true);}catch(IOException e){throw new UncheckedIOException(e);}}),true);
  }
 }
 static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing configured test credentials");return value;}
 static Object business(ProductSnapshot p){
  var out=new TreeMap<String,Object>();out.put("title",p.title());out.put("description",p.description());out.put("brand",p.brand());out.put("article",p.vendorArticle());out.put("category",p.categoryId());out.put("dimensions",p.dimensions());out.put("media",p.media());out.put("attributes",p.attributes());
  out.put("variants",p.variants().stream().map(v->List.of(v.id(),v.sellerArticle(),v.color(),v.size(),v.russianSize(),v.barcodes())).toList());
  out.put("documents",p.documents().stream().map(d->Arrays.asList(d.id(),d.type(),d.number(),d.validFrom(),d.validUntil(),d.owner(),d.scope())).sorted(Comparator.comparing(Object::toString)).toList());return out;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=2||!args[1].equals("--execute-current-values"))throw new IllegalArgumentException("Explicit --execute-current-values required; read the existing journal first");
  root=Path.of(args[0]);if(Files.exists(root.resolve("dispatch-started")))throw new IllegalStateException("Existing dispatch journal: reconcile only, never rerun");
  System.setProperty("vncode.appdata.dir",root.resolve("profile").toString());summary.put("startedAt",Instant.now().toString());summary.put("mode","LIVE_PRODUCTION_SERVICE_RESEND_CURRENT_VALUES");summary.put("licenseAuthority","LOCAL_TEST_FIXTURE_NO_REMOTE_ACTIVATION");summary.put("businessValueChangesRequested",0);summary.put("licenseActivations",0);
  try(var license=LicenseDomainFixture.active(root.resolve("test-license"))){
   LicenseRuntime.install(license);Database.initDatabase();var shops=new ShopRepository();shops.insert(new Shop("Live WB",Marketplace.WILDBERRIES,null,required("VNCODE_WB_TEST_API_KEY")));var shop=shops.findAll().getFirst();
   var candidate=JsonParser.parseString(Files.readString(root.resolve("private-candidates.json"))).getAsJsonArray().get(0).getAsJsonObject();var key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,candidate.get("cardId").getAsString(),candidate.get("variantId").getAsString());
   var transport=new Transport(shop);var reader=new WbProductSnapshotReader(s->transport);var baseline=reader.readForSynchronization(shop,key);save("private-before.json",baseline);
   var paper=baseline.documents().stream().filter(d->ConfiguredProductDeclaration.isDeclaration(d.type())&&d.validFrom()!=null&&d.validUntil()!=null&&!d.validFrom().isAfter(LocalDate.now())&&!d.validUntil().isBefore(LocalDate.now())).findFirst().orElseThrow(()->new IllegalStateException("No current active declaration"));
   var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));var formatter=DateTimeFormatter.ofPattern("dd.MM.uuuu");var config=settings.getSettings().withDefaultGoodsDocument("CONFORMITY_DECLARATION",paper.number(),formatter.format(paper.validFrom()));var tree=ProductJson.GSON.toJsonTree(config).getAsJsonObject();tree.addProperty("documentExpiryDate",formatter.format(paper.validUntil()));settings.saveSettings(ProductJson.GSON.fromJson(tree,ZnackModels.Settings.class));
   var edits=new LinkedHashMap<String,ProductBulkEdit>();edits.put("brand",new ProductBulkEdit("brand",List.of(baseline.brand()),null,Map.of()));
   for(var name:List.of("material","gender","tnved")){var attrs=ProductFieldNames.sourceAttributes(name,baseline.attributes());if(attrs.size()!=1)throw new IllegalStateException("Source field identity ambiguous");edits.put(name,new ProductBulkEdit(name,attrs.getFirst().values(),null,Map.of()));}
   edits.put("documents",new ProductBulkEdit("documents",List.of(),List.of(ConfiguredProductDeclaration.load(shop)),Map.of()));
   var service=new ProductBulkEditService(new ProductEditRepository(),(s,keys)->ProductEditApiSession.open(s,keys,transport));
   Files.writeString(root.resolve("dispatch-started"),Instant.now().toString());checkpoint("READY");
   for(var entry:edits.entrySet()){
    field=entry.getKey();checkpoint("PREVIEWING_"+field);int before=mutations;
    var preview=service.preview(shop,List.of(key),entry.getValue());save("private-preview-"+field+".json",preview);
    var outcome=new LinkedHashMap<String,Object>();outcome.put("field",field);outcomes.add(outcome);save("private-job-"+field+".json",Map.of("id",preview.id()));
    if(!preview.publishable()){outcome.put("state","PREVIEW_BLOCKED");outcome.put("issueCodes",preview.items().stream().flatMap(i->i.issues().stream()).map(i->i.code()).distinct().toList());checkpoint("BLOCKED");throw new IllegalStateException("Product preview blocked");}
    var result=service.apply(shop,preview.id(),true);int rounds=0;
    while(result.items().stream().anyMatch(i->Set.of(CopyState.REMOTE_PROCESSING,CopyState.RECONCILE_REQUIRED,CopyState.SENDING).contains(i.state()))&&rounds++<30){Thread.sleep(Math.min(5000,500+rounds*500));result=service.reconcile(shop,preview.id());checkpoint("READBACK_"+field);}
    save("private-result-"+field+".json",result);outcome.put("state",result.items().getFirst().state().name());outcome.put("writeCalls",mutations-before);outcome.put("readbackRounds",rounds);outcome.put("issueCodes",result.items().stream().flatMap(i->i.issues().stream()).map(i->i.code()).distinct().toList());checkpoint("VERIFIED_"+field);
    if(result.items().stream().anyMatch(i->i.state()!=CopyState.RECONCILED))throw new IllegalStateException("Unsettled product mutation: reconcile before another field");
   }
   var after=reader.readForSynchronization(shop,key);save("private-after.json",after);boolean unchanged=ListingIdentity.canonical(ProductJson.GSON.toJsonTree(business(baseline))).equals(ListingIdentity.canonical(ProductJson.GSON.toJsonTree(business(after))));summary.put("allBusinessFieldsUnchanged",unchanged);summary.put("completedAt",Instant.now().toString());if(!unchanged)throw new IllegalStateException("Business readback differs");checkpoint("COMPLETE");
  }catch(Throwable failure){summary.put("failureClass",failure.getClass().getSimpleName());checkpoint("STOPPED");throw failure;}finally{LicenseRuntime.close();}
  System.out.println(json.toJson(summary));System.exit(0);
 }
}
