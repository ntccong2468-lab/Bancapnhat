package com.vncode.app.integration.znack.registration;
import com.google.gson.*;
import com.vncode.app.features.products.*;
import com.vncode.app.features.gtinsync.GtinSyncRepository;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
public final class CodeDoctorLiveCacheReopen {
  public static void main(String[] args)throws Exception {
    var root=Path.of(args[0]);System.setProperty("vncode.appdata.dir",root.resolve("sdk-profile").toString());
    var previous=JsonParser.parseString(Files.readString(root.resolve("sdk-summary.json"))).getAsJsonObject();
    if(!"COMPLETE_READ_PARSE_PERSIST_MATCH".equals(previous.get("outcome").getAsString()))throw new AssertionError("Live scan not complete");
    var shop=new ShopRepository().findAll().getFirst();var repo=new ProductRepository();String cursor=null;int rows=0;Set<String> keys=new HashSet<>();
    do {var page=repo.loadPage(new ProductQuery(shop.getId(),Marketplace.WILDBERRIES,"",List.of(),null,false,cursor,500));rows+=page.rows().size();for(var p:page.rows())if(!keys.add(p.key().cardId()+":"+p.key().variantId()))throw new AssertionError("Repeated persisted product");cursor=page.nextCursor();}while(cursor!=null&&!cursor.isBlank());
    var catalog=new GtinSyncRepository().catalog(shop.getId(),Marketplace.WILDBERRIES);var states=new TreeMap<String,Integer>();
    for(var card:catalog)states.merge(CatalogCardState.of(card).name(),1,Integer::sum);
    if(rows!=previous.getAsJsonObject("wb").get("localListRows").getAsInt()||catalog.size()!=previous.getAsJsonObject("catalog").get("cards").getAsInt()||!new Gson().toJsonTree(states).equals(previous.getAsJsonObject("catalog").get("states")))throw new AssertionError("Cache changed after JVM restart");
    var out=Map.of("checkedAt",Instant.now().toString(),"freshJvm",true,"networkRequests",0,"wbVariants",rows,"catalogCards",catalog.size(),"states",states,"sameCountsAndStates",true,"sellerMutations",0);
    String safe=new GsonBuilder().setPrettyPrinting().create().toJson(out);Files.writeString(root.resolve("sdk-reopen-summary.json"),safe);System.out.println(safe);
  }
}
