package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Read-only live production SDK diagnostic; remote writes deliberately absent. */
public final class CodeDoctorLiveWbCatalog {
    static Path root;
    static Gson json=new GsonBuilder().setPrettyPrinting().create();
    static Map<String,Object> result=new LinkedHashMap<>();
    static int catalogLists=0,catalogBatches=0,wbPages=0;
    static long started=System.nanoTime(),lastCatalog;
    static void write(String file,Object value)throws Exception {Files.writeString(root.resolve(file),json.toJson(value));}
    static void progress(String phase,int done,int total) {try{write("sdk-progress.json",Map.of("phase",phase,"completed",done,"total",total,"elapsedMs",(System.nanoTime()-started)/1_000_000));}catch(Exception e){throw new IllegalStateException("progress_write_failed");}}
    static void pace()throws Exception {long wait=1_000_000_000L-(System.nanoTime()-lastCatalog);if(wait>0)Thread.sleep(Duration.ofNanos(wait));lastCatalog=System.nanoTime();}
    static String secret(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException("MISSING_ENV_"+name);return v;}
    public static void main(String[] args)throws Exception {
        root=Path.of(args[0]);var profile=root.resolve("sdk-profile");Files.createDirectories(profile);
        System.setProperty("vncode.appdata.dir",profile.toString());
        result.put("startedAt",Instant.now().toString());result.put("mode","LIVE_READ_ONLY_PRODUCTION_SDK");
        result.put("licenseAuthority","ISOLATED_LOCAL_TEST_FIXTURE_NO_REMOTE_ACTIVATION");
        result.put("certificateOwnerVerified",false);result.put("sellerMutations",0);result.put("licenseActivations",0);
        try(var license=LicenseDomainFixture.active(root.resolve("local-test-license"))) {
            LicenseRuntime.install(license);Database.initDatabase();
            var shops=new ShopRepository();shops.insert(new Shop("Live WB",Marketplace.WILDBERRIES,null,secret("VNCODE_WB_TEST_API_KEY")));
            var shop=shops.findAll().getFirst();
            progress("WB_FULL_SYNC",0,0);
            int written=new WbProductSyncService().syncFromStart(shop,cards->{wbPages++;progress("WB_FULL_SYNC",wbPages,0);});
            var products=new ProductRepository();var rows=new ArrayList<ProductRow>();String cursor=null;long expected=-1;int localPages=0;
            do {var page=products.loadPage(new ProductQuery(shop.getId(),Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,500));
                if(expected>=0&&expected!=page.total())throw new IllegalStateException("LOCAL_TOTAL_CHANGED");
                expected=page.total();rows.addAll(page.rows());cursor=page.nextCursor();localPages++;
            }while(cursor!=null&&!cursor.isBlank());
            if(rows.size()!=expected)throw new IllegalStateException("LOCAL_PAGE_COUNT_MISMATCH");
            var wbVariants=new WbGtinProductReader(shop,new WbApiClient()).list();
            Set<String> localKeys=new HashSet<>(),remoteKeys=new HashSet<>();
            for(var row:rows)if(!localKeys.add(row.key().cardId()+":"+row.key().variantId()))throw new IllegalStateException("DUPLICATE_LOCAL_KEY");
            for(var row:wbVariants)if(!remoteKeys.add(row.key().productId()+":"+row.key().variantId()))throw new IllegalStateException("DUPLICATE_REMOTE_KEY");
            if(!localKeys.equals(remoteKeys))throw new IllegalStateException("LOCAL_REMOTE_KEYS_DIFFER");
            result.put("wb",Map.of("cardsWritten",written,"nonemptySyncPages",wbPages,"variantReaderRows",wbVariants.size(),"localListRows",rows.size(),"localListPages",localPages,"localRemoteVariantKeysEqual",true));
            write("sdk-summary.json",result);
            var api=ZnackApiClient.forCatalogKey(secret("VNCODE_CATALOG_TEST_API_KEY"),()->ListingIdentity.require(shop));
            var source=new NationalCatalogGtinSource(shop.getId(),page->{
                pace();catalogLists++;var response=api.products(ZnackModels.PRODUCTION_TRUE_API,null,page,1000);
                write("sdk-catalog-list-"+page+".json",response);return response;
            },new NationalCatalogGtinSource.BatchedCards(gtins->{
                pace();catalogBatches++;var response=api.productCards(ZnackModels.PRODUCTION_TRUE_API,null,gtins);
                write("sdk-catalog-batch-"+catalogBatches+".json",response);return response;
            }),"LIVE_READ_ONLY_UNVERIFIED_CERTIFICATE_SCOPE").catalogListWindow().resumable().progress(p->progress(p.phase(),p.completed(),p.total()));
            List<RegisteredGtin> catalog=source.read(shop.getId());
            var states=new TreeMap<String,Integer>();int units=0,detailsWithoutArticle=0,detailsWithoutColor=0,detailsWithoutSize=0;
            for(var card:catalog){states.merge(CatalogCardState.of(card).name(),1,Integer::sum);if(card.tradeUnit())units++;if(card.article().isBlank())detailsWithoutArticle++;if(card.color().isBlank())detailsWithoutColor++;if(card.size().isBlank())detailsWithoutSize++;}
            var store=new GtinSyncRepository();store.saveCatalog(shop.getId(),Marketplace.WILDBERRIES,catalog);var persisted=store.catalog(shop.getId(),Marketplace.WILDBERRIES);
            Set<String> saved=new HashSet<>(),read=new HashSet<>();for(var c:catalog)read.add(c.gtin());for(var c:persisted)saved.add(c.gtin());
            if(!read.equals(saved))throw new IllegalStateException("CATALOG_PERSISTENCE_MISMATCH");
            result.put("catalog",Map.of("cards",catalog.size(),"listRequests",catalogLists,"detailBatchRequests",catalogBatches,"maxGtinsPerDetailRequest",25,"states",states,"tradeUnits",units,"missingArticle",detailsWithoutArticle,"missingColor",detailsWithoutColor,"missingSize",detailsWithoutSize,"persistedRows",persisted.size()));
            write("sdk-summary.json",result);
            var all=new GtinCatalogIndex(catalog,true);var published=new GtinCatalogIndex(catalog);
            var categories=new TreeMap<String,Integer>();var matchedStates=new TreeMap<String,Integer>();int attrsOnly=0,barcodePresent=0,publishedUnique=0,tnvedConflict=0,tnvedUnknown=0,compatible=0;
            var privateMatches=new ArrayList<Map<String,Object>>();
            for(var p:rows){
                var codes=all.codes(p.barcodes());var attributes=all.attributes(p.vendorArticle(),p.color(),GtinVariantIdentity.primarySize(p),p.russianSize());
                var union=new LinkedHashSet<RegisteredGtin>();union.addAll(codes);union.addAll(attributes);
                String category=union.isEmpty()?"NO_CANDIDATE":union.size()>1?"AMBIGUOUS":"UNIQUE_CANDIDATE";categories.merge(category,1,Integer::sum);
                if(union.size()==1){var c=union.iterator().next();matchedStates.merge(CatalogCardState.of(c).name(),1,Integer::sum);
                    if(codes.contains(c))barcodePresent++;else attrsOnly++;
                    var pub=published.attributes(p.vendorArticle(),p.color(),GtinVariantIdentity.primarySize(p),p.russianSize());
                    boolean sameAttributes=attributes.contains(c),hasTnved=!p.tnved().isBlank()&&!c.details().values("tnved").isEmpty();
                    boolean groupMatches=GtinTnvedIdentity.compatible(p.tnved(),c.details().values("tnved"));
                    if(c.published()) {publishedUnique++;if(!hasTnved)tnvedUnknown++;else if(!groupMatches)tnvedConflict++;else compatible++;}
                    privateMatches.add(Map.of("cardId",p.key().cardId(),"variantId",p.key().variantId(),"gtin",c.gtin(),"remoteState",CatalogCardState.of(c).name(),"barcodeAlreadyPresent",codes.contains(c),"attributesAgree",sameAttributes,"tnvedKnown",hasTnved,"tnvedGroupCompatible",groupMatches));
                }
            }
            write("sdk-private-matches.json",privateMatches);
            var match=new LinkedHashMap<String,Object>();match.put("variantsByCandidateCount",categories);match.put("uniqueCandidateRemoteStates",matchedStates);match.put("uniqueCandidateAlreadyInWbBarcodes",barcodePresent);match.put("uniqueCandidateAttributesOnly",attrsOnly);match.put("publishedUniqueCandidates",publishedUnique);match.put("publishedTnvedGroupConflict",tnvedConflict);match.put("publishedTnvedUnknown",tnvedUnknown);match.put("publishedTnvedGroupCompatible",compatible);match.put("authorizesWrites",false);result.put("matching",match);
            result.put("completedAt",Instant.now().toString());result.put("elapsedMs",(System.nanoTime()-started)/1_000_000);result.put("outcome","COMPLETE_READ_PARSE_PERSIST_MATCH");
            write("sdk-summary.json",result);progress("COMPLETE",catalog.size(),catalog.size());
        }catch(Throwable failure){result.put("outcome","BLOCKED");result.put("failureClass",failure.getClass().getSimpleName());write("sdk-summary.json",result);throw failure;}
        finally {LicenseRuntime.close();}
        System.exit(0);
    }
}
