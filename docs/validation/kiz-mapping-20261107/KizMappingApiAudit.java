package com.vncode.app.features.kizmapping;
import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Diagnose fresh real HTTP data; no fake HTTP, license fixture, certificate, or paid request. */
public final class KizMappingApiAudit {
 static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
 static final String SOURCE="LIVE_API_DIAGNOSTIC_OWNER_NOT_VERIFIED";
 static Path root;
 static void write(String name,Object value)throws Exception{Files.writeString(root.resolve(name),ProductJson.GSON.toJson(value));}
 static <K>void count(Map<K,Integer> counts,K key){counts.merge(key,1,Integer::sum);}
 public static void main(String[] args)throws Exception {
  root=Path.of(args[0]).toRealPath();if(!root.toAbsolutePath().normalize().startsWith(Path.of("/tmp"))||!Files.isDirectory(root)
   ||Files.getPosixFilePermissions(root).stream().anyMatch(permission->permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_")))
   throw new IllegalArgumentException("Expected the private owner-only /tmp API directory; never use an app or checkout profile");var summary=new LinkedHashMap<String,Object>();
  summary.put("mode","FRESH_REAL_API_DATA_PRODUCTION_PARSER_REPOSITORY_REVIEW");summary.put("mockHttpResponses",0);summary.put("licenseFixtures",0);summary.put("certificateFixtures",0);summary.put("paidRequests",0);summary.put("remoteMutations",0);
  System.setProperty("vncode.appdata.dir",root.resolve("analysis-profile-"+args[1]).toString());Database.initDatabase();
  String wbKey=Objects.requireNonNull(System.getenv("VNCODE_WB_TEST_API_KEY"));
  try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,?,'WILDBERRIES',?)")){p.setString(1,"Live API diagnostic");p.setString(2,wbKey);p.executeUpdate();}
  var shop=new Shop(1,"Live API diagnostic",wbKey);
  var cards=Arrays.asList(JSON.fromJson(Files.readString(root.resolve("private-wb-cards.json")),WbProductCard[].class));
  new WbProductRepository().saveProductBatch(1,cards);
  var products=new ProductRepository();var rows=new ArrayList<ProductRow>();String cursor=null;
  do{var page=products.loadPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,1000));rows.addAll(page.rows());cursor=page.nextCursor();}while(cursor!=null);
  write("private-product-rows.json",rows);
  var locations=new HashMap<String,String>();
  for(var item:JsonParser.parseString(Files.readString(root.resolve("private-catalog-batches.json"))).getAsJsonArray())for(var gtin:item.getAsJsonObject().getAsJsonArray("gtins"))locations.put(gtin.getAsString(),item.getAsJsonObject().get("file").getAsString());
  var reader=new NationalCatalogGtinSource.CardReader(){String current="";JsonElement body;Instant observed;
   public JsonElement read(String gtin)throws Exception{String file=locations.get(gtin);if(!file.equals(current)){current=file;body=JsonParser.parseString(Files.readString(root.resolve(file)));observed=Files.getLastModifiedTime(root.resolve(file)).toInstant();}return body;}
   public Instant observedAt(){return observed;}
  };
  var parser=new NationalCatalogGtinSource(1,page->{throw new IllegalStateException("No invented list response");},reader,SOURCE);
  var readCard=NationalCatalogGtinSource.class.getDeclaredMethod("readCard",String.class);readCard.setAccessible(true);
  var catalog=new LinkedHashMap<String,RegisteredGtin>();var parseErrors=new TreeMap<String,Integer>();
  for(var gtin:locations.keySet())try{var card=(RegisteredGtin)readCard.invoke(parser,gtin);catalog.put(GtinNormalizer.normalize(card.gtin()),card);}catch(java.lang.reflect.InvocationTargetException e){count(parseErrors,e.getCause().getClass().getSimpleName());}
  write("private-parsed-catalog-"+args[1]+".json",catalog.values());
  var states=new TreeMap<String,Integer>();var docTypes=new TreeMap<String,Integer>();int withDocs=0;
  for(var card:catalog.values()){count(states,CatalogCardState.of(card).name());if(!card.details().documents().isEmpty())withDocs++;for(var doc:card.details().documents())count(docTypes,doc.type());}
  if(args[1].equals("after-registry")){
   var target=JsonParser.parseString(Files.readString(root.resolve("private-single-published-candidates.json"))).getAsJsonArray().get(0).getAsJsonObject();String code=target.getAsJsonObject("catalog").get("gtin").getAsString();String normalized=GtinNormalizer.normalize(code);var card=catalog.get(normalized);
   var registry=JsonParser.parseString(Files.readString(root.resolve("fresh-sdk-registry-response.json")));var details=CatalogProductDataParser.withRegistry(card.details(),registry);
   catalog.put(normalized,new RegisteredGtin(card.gtin(),card.article(),card.color(),card.size(),card.tradeUnit(),card.published(),card.source(),card.syncedAt(),details));summary.put("realRegistryEnrichedCards",1);
  }
  var baseline=WbCatalogMappingService.review(rows,catalog,Map.of(),SOURCE);var reasons=new TreeMap<String,Integer>();for(var issue:baseline.issues())count(reasons,issue.reason());
  var codeCounts=new TreeMap<Integer,Integer>();var matchingByAttributes=new GtinCatalogIndex(new ArrayList<>(catalog.values()));
  int noBarcodeWithPublishedCandidate=0,barcodeMissingFromCatalog=0,withPublishedBarcode=0,multipleCodesWithOnlyOneCatalogMatch=0;
  var variantIssues=new ArrayList<Map<String,Object>>();
  for(var row:rows){
   var codes=new LinkedHashSet<String>();for(var code:row.barcodes())if(RegisteredGtinValidator.isValid(code)&&!GtinNormalizer.isTechnicalRange(code))codes.add(GtinNormalizer.normalize(code));
   count(codeCounts,codes.size());
   var observed=codes.stream().map(catalog::get).filter(Objects::nonNull).toList();
   if(observed.stream().anyMatch(RegisteredGtin::published))withPublishedBarcode++;
   if(!codes.isEmpty()&&observed.isEmpty())barcodeMissingFromCatalog++;
   if(codes.size()>1&&observed.stream().filter(RegisteredGtin::published).count()==1)multipleCodesWithOnlyOneCatalogMatch++;
   var byAttributes=matchingByAttributes.attributes(row.vendorArticle(),row.color(),GtinVariantIdentity.primarySize(row),row.russianSize());
   if(byAttributes.size()==1&&!codes.contains(GtinNormalizer.normalize(byAttributes.getFirst().gtin())))noBarcodeWithPublishedCandidate++;
   if(!observed.isEmpty()||byAttributes.size()==1){var item=new LinkedHashMap<String,Object>();item.put("cardId",row.key().cardId());item.put("variantId",row.key().variantId());item.put("article",row.vendorArticle());item.put("size",row.size());item.put("codes",codes);item.put("observedCatalogGtins",observed.stream().map(RegisteredGtin::gtin).toList());item.put("attributeMatches",byAttributes.stream().map(RegisteredGtin::gtin).toList());variantIssues.add(item);}
  }
  write("private-variant-findings.json",variantIssues);write("private-mapping-issues-"+args[1]+".json",baseline.issues());
  summary.put("wbCards",cards.size());summary.put("wbVariants",rows.size());summary.put("catalogParsed",catalog.size());summary.put("catalogStates",states);summary.put("catalogParseErrors",parseErrors);summary.put("catalogCardsWithDocuments",withDocs);summary.put("documentTypes",docTypes);
  summary.put("physicalDataReviewOnly",Map.of("mappedByCurrentReview",baseline.mapped(),"issues",baseline.issues().size(),"reasonCounts",reasons,"orderableBarcodeCountPerVariant",codeCounts,"variantsWithPublishedCatalogBarcode",withPublishedBarcode,"variantsWithCodesAbsentFromCatalog",barcodeMissingFromCatalog,"multipleOrderableCodesButOnlyOnePublishedCatalogMatch",multipleCodesWithOnlyOneCatalogMatch,"uniquePublishedAttributeCandidateNotYetInWbBarcodes",noBarcodeWithPublishedCandidate));
  var gate=WbCatalogMappingService.class.getDeclaredMethod("problem",ProductRow.class,RegisteredGtin.class,String.class);gate.setAccessible(true);
  var observedCandidates=new ArrayList<Map<String,Object>>();var gateCounts=new TreeMap<String,Integer>();
  for(var row:rows){var observed=row.barcodes().stream().filter(RegisteredGtinValidator::isValid).filter(code->!GtinNormalizer.isTechnicalRange(code)).map(GtinNormalizer::normalize).distinct().map(catalog::get).filter(Objects::nonNull).filter(RegisteredGtin::published).toList();
   if(observed.size()==1){var candidate=observed.getFirst();String problem=(String)gate.invoke(null,row,candidate,SOURCE);count(gateCounts,problem.isBlank()?"ALL_CURRENT_PHYSICAL_GATES_PASS":problem);
    var item=new LinkedHashMap<String,Object>();item.put("row",row);item.put("catalog",candidate);item.put("physicalGate",problem);item.put("articleColorSizeMatch",ListingIdentity.compatibleVariant(candidate,row.vendorArticle(),row.color(),row.size(),row.russianSize()));observedCandidates.add(item);}}
  write("private-single-published-candidates.json",observedCandidates);summary.put("singlePublishedCandidateGateCounts",gateCounts);
  summary.put("certificateOwnerVerified",false);summary.put("mappingProofWritten",false);summary.put("purchaseAuthorized",false);
  try{RegisteredGtinProof.current(shop);summary.put("fullAppScopePreflight","UNEXPECTED_SCOPE");}catch(RegisteredGtinProof.UnverifiedOwnerException absent){summary.put("fullAppScopePreflight",absent.reason().name());}
  summary.put("scopeLimitation","Physical-data review of fresh real HTTP reads. Actual certificate/company binding is not configured or invented; no verified mapping or purchase is claimed.");summary.put("finishedAt",Instant.now().toString());
  write("mapping-analysis-"+args[1]+".json",summary);System.out.println(JSON.toJson(summary));System.exit(0);
 }
}
