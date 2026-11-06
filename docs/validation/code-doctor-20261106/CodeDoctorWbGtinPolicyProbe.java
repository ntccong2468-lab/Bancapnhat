package com.vncode.app.integration.znack.registration;
import com.google.gson.*;
import com.vncode.app.features.license.*;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.sync.ZnackAutoSyncService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbGtinAdapter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
/** Live content reads and identity validation only. No GTIN submit or other mutation call. */
public final class CodeDoctorWbGtinPolicyProbe {
  public static void main(String[] args)throws Exception{
    var root=Path.of(args[0]);System.setProperty("vncode.appdata.dir",root.resolve("sdk-profile").toString());
    try(var license=LicenseDomainFixture.active(root.resolve("local-policy-test-license"))){
      LicenseRuntime.install(license);var shop=new ShopRepository().findAll().getFirst();
      var source=new GtinSyncRepository().catalog(shop.getId(),Marketplace.WILDBERRIES);
      var index=new GtinCatalogIndex(source);var service=new ZnackAutoSyncService();
      var matcher=ZnackAutoSyncService.class.getDeclaredMethod("match",com.vncode.app.models.Shop.class,ProductRow.class,GtinCatalogIndex.class);matcher.setAccessible(true);
      var repo=new ProductRepository();String cursor=null;int all=0,unique=0,conflicts=0,ambiguous=0;
      var requested=new ArrayList<ProductRow>();var samples=new ArrayList<ProductRow>();var byProduct=new HashMap<String,RegisteredGtin>();var diagnostics=new TreeMap<String,Integer>();
      do {var page=repo.loadPage(new ProductQuery(shop.getId(),Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,500));
        for(var p:page.rows()){
          all++;
          if(Objects.toString(System.getenv("VNCODE_WB_TEST_ARTICLE"),"<not-selected>").equalsIgnoreCase(p.vendorArticle().strip())){
            requested.add(p);
            var sameArticle=source.stream().filter(c->GtinMatchingService.normalizeArticle(p.vendorArticle()).equals(GtinMatchingService.normalizeArticle(c.article()))).toList();
            boolean color=sameArticle.stream().anyMatch(c->OzonSkuGtinMatcher.sameColor(p.color(),c.color()));
            boolean size=sameArticle.stream().anyMatch(c->GtinVariantIdentity.sizeMatches(c,GtinVariantIdentity.primarySize(p),p.russianSize()));
            diagnostics.merge("ARTICLE_PRESENT_"+!sameArticle.isEmpty(),1,Integer::sum);diagnostics.merge("COLOR_MATCH_"+color,1,Integer::sum);diagnostics.merge("SIZE_MATCH_"+size,1,Integer::sum);
          }
          @SuppressWarnings("unchecked") var matched=(List<RegisteredGtin>)matcher.invoke(service,shop,p,index);
          if(matched.size()>1)ambiguous++;
          if(matched.size()==1){unique++;var c=matched.getFirst();if(!GtinTnvedIdentity.compatible(p.tnved(),c.details().values("tnved")))conflicts++;
            if(samples.size()<5){samples.add(p);byProduct.put(p.key().variantId(),c);}
          }
        }cursor=page.nextCursor();
      }while(cursor!=null&&!cursor.isBlank());
      var adapter=new WbGtinAdapter(shop);int verified=0;
      for(var p:samples){
        var key=new GtinSyncModels.ProductKey(shop.getId(),Marketplace.WILDBERRIES,p.key().cardId(),p.key().variantId());
        var fresh=adapter.read(key);adapter.validateIdentity(fresh,byProduct.get(p.key().variantId()));verified++;
      }
      var out=new LinkedHashMap<String,Object>();out.put("checkedAt",Instant.now().toString());out.put("wbVariants",all);out.put("uniquePublishedMatches",unique);out.put("crossTnvedGroupMatchesAllowed",conflicts);out.put("ambiguousMatches",ambiguous);out.put("requestedArticleVariantCount",requested.size());out.put("requestedArticleMatchingFacts",diagnostics);out.put("liveApiSampleVariants",samples.size());out.put("freshApiVariantIdentityChecksPassed",verified);out.put("certificateOwnerVerified",false);out.put("licenseAuthority","LOCAL_TEST_FIXTURE");out.put("sellerMutationRequests",0);out.put("gtinsWritten",0);out.put("mode","PRODUCTION_MATCHER_AND_LIVE_WB_READ_VALIDATION_ONLY");
      String safe=new GsonBuilder().setPrettyPrinting().create().toJson(out);Files.writeString(root.resolve("wb-policy-summary.json"),safe);System.out.println(safe);
    }finally{LicenseRuntime.close();}
    System.exit(0);
  }
}
