package com.vncode.app.features.kizmapping;
import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public final class KizMappingFreshRead {
 static final String SOURCE="LIVE_API_DIAGNOSTIC_OWNER_NOT_VERIFIED";
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]).toRealPath();if(!root.toAbsolutePath().normalize().startsWith(Path.of("/tmp"))||!Files.isDirectory(root)
   ||Files.getPosixFilePermissions(root).stream().anyMatch(permission->permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_")))
   throw new IllegalArgumentException("Expected the private owner-only /tmp API directory; never use an app or checkout profile");var summary=new LinkedHashMap<String,Object>();summary.put("startedAt",Instant.now().toString());summary.put("mockResponses",0);summary.put("licenseFixtures",0);summary.put("paidRequests",0);
  var chosen=JsonParser.parseString(Files.readString(root.resolve("private-single-published-candidates.json"))).getAsJsonArray().get(0).getAsJsonObject();
  var row=ProductJson.GSON.fromJson(chosen.get("row"),ProductRow.class);var prior=ProductJson.GSON.fromJson(chosen.get("catalog"),RegisteredGtin.class);
  System.setProperty("vncode.appdata.dir",root.resolve("analysis-profile-real-gates").toString());com.vncode.app.config.Database.initDatabase();
  var shop=new com.vncode.app.models.Shop(1,"Live API diagnostic",Objects.requireNonNull(System.getenv("VNCODE_WB_TEST_API_KEY")));
  var wb=new WbProductSnapshotReader().readForGtinSync(shop,row.key());Files.writeString(root.resolve("private-fresh-wb-snapshot-"+args[1]+".json"),ProductJson.GSON.toJson(wb));
  var key=row.key();var variant=wb.variants().stream().filter(v->v.id().equals(key.variantId())).findFirst().orElseThrow();
  String tnved=ProductFieldNames.sourceAttributes("tnved",wb.attributes()).stream().flatMap(a->a.values().stream()).findFirst().orElse("");
  row=new ProductRow(key,wb.title(),wb.vendorArticle(),key.cardId(),wb.categoryId(),wb.brand(),variant.color(),variant.size(),variant.barcodes(),"","","",tnved,DocumentState.UNVERIFIED,"",true,variant.russianSize(),variant.size());
  summary.put("marketplaceRereadViaProductionSdk",true);summary.put("tnvedStillDifferent",!GtinTnvedIdentity.compatible(tnved,prior.details().values("tnved")));
  var selected=new LinkedHashSet<String>();selected.add(prior.gtin());for(String code:row.barcodes())if(RegisteredGtinValidator.isValid(code)&&!GtinNormalizer.isTechnicalRange(code))selected.add(GtinNormalizer.normalize(code));
  var api=ZnackApiClient.forCatalogKey(Objects.requireNonNull(System.getenv("VNCODE_CATALOG_TEST_API_KEY")),()->{});
  JsonElement body;
  try{body=api.productCards(ZnackModels.PRODUCTION_TRUE_API,null,String.join(";",selected));Files.writeString(root.resolve("fresh-sdk-mixed-catalog-response.json"),body.toString());summary.put("mixedBarcodeCatalogRequest","HTTP_200");summary.put("selectedCodes",selected.size());summary.put("returnedCards",body.getAsJsonObject().getAsJsonArray("result").size());}
  catch(ZnackApiClient.ZnackApiException e){summary.put("mixedBarcodeCatalogHttp",e.statusCode());Files.writeString(root.resolve("fresh-sdk-summary.json"),ProductJson.GSON.toJson(summary));return;}
  Instant at=Instant.now();var reader=new NationalCatalogGtinSource.CardReader(){public JsonElement read(String gtin){return body;}public Instant observedAt(){return at;}};
  var source=new NationalCatalogGtinSource(1,p->{throw new IllegalStateException("No simulated page");},reader,SOURCE);
  var parse=NationalCatalogGtinSource.class.getDeclaredMethod("readCard",String.class);parse.setAccessible(true);var parsed=new LinkedHashMap<String,RegisteredGtin>();int notReturned=0;
  for(String code:selected)try{var card=(RegisteredGtin)parse.invoke(source,code);parsed.put(GtinNormalizer.normalize(card.gtin()),card);}catch(java.lang.reflect.InvocationTargetException absent){if(absent.getCause() instanceof java.io.IOException)notReturned++;else throw absent;}
  summary.put("cardsParsed",parsed.size());summary.put("codesNotReturned",notReturned);
  var review=WbCatalogMappingService.review(List.of(row),parsed,Map.of(),SOURCE);summary.put("mappingReview",Map.of("mapped",review.mapped(),"reasons",review.issues().stream().map(WbCatalogMappingService.Issue::reason).toList()));
  try{
   Thread.sleep(1100);var registry=api.permitDocuments(ZnackModels.PRODUCTION_TRUE_API,null,prior.gtin(),"");Files.writeString(root.resolve("fresh-sdk-registry-response.json"),registry.toString());var details=CatalogProductDataParser.withRegistry(parsed.get(GtinNormalizer.normalize(prior.gtin())).details(),registry);summary.put("registryRead","HTTP_200");summary.put("registryDocumentStatus",details.single("@documents_status"));summary.put("activeRegistryDocuments",details.documents().size());
   var card=parsed.get(GtinNormalizer.normalize(prior.gtin()));var enriched=new RegisteredGtin(card.gtin(),card.article(),card.color(),card.size(),card.tradeUnit(),card.published(),card.source(),card.syncedAt(),details);parsed.put(GtinNormalizer.normalize(card.gtin()),enriched);
   var enrichedReview=WbCatalogMappingService.review(List.of(row),parsed,Map.of(),SOURCE);summary.put("reviewAfterRealRegistryRead",Map.of("mapped",enrichedReview.mapped(),"reasons",enrichedReview.issues().stream().map(WbCatalogMappingService.Issue::reason).toList()));
   summary.put("differentTnvedNoLongerBlocksThisRealVariant",enrichedReview.mapped()==1&&enrichedReview.issues().isEmpty());
   summary.put("paidPurchaseVerified",false);summary.put("certificateOwnerVerified",false);
  }catch(ZnackApiClient.ZnackApiException e){summary.put("registryHttpStatus",e.statusCode());}catch(Exception e){summary.put("registryFailureType",e.getClass().getSimpleName());}
  summary.put("completedAt",Instant.now().toString());Files.writeString(root.resolve("fresh-sdk-summary-"+args[1]+".json"),ProductJson.GSON.toJson(summary));System.out.println(ProductJson.GSON.toJson(summary));System.exit(0);
 }
}
