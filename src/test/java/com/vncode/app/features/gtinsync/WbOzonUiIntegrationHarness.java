package com.vncode.app.features.gtinsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vncode.app.features.catalogsync.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingHttpClient;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** UI integration fixture: real review/SQLite/dispatch/Catalog queue, synthetic WB/Catalog readers and Ozon HTTP adapter. */
public final class WbOzonUiIntegrationHarness implements AutoCloseable {
    public final Shop wb,ozon;
    public final AtomicInteger writes=new AtomicInteger(),reads=new AtomicInteger(),readsAfterWrite=new AtomicInteger();
    public final WbOzonGtinReviewService review;
    public final WbOzonGtinDispatchService dispatch;
    public final WbOzonLinkRepository links=new WbOzonLinkRepository();
    private final MockWebServer server=new MockWebServer();
    private final com.vncode.app.features.products.ProductKey wk,ok;
    private final GtinSyncModels.ProductKey target;
    private final GtinSyncModels.RegisteredGtin card;
    private final ProductSnapshot wbSnapshot,ozonSnapshot;
    private volatile boolean added;

    public WbOzonUiIntegrationHarness() throws Exception {
        wb=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        ozon=GtinWriteTestFixture.shop(Marketplace.OZON);
        wk=new com.vncode.app.features.products.ProductKey(wb.getId(),Marketplace.WILDBERRIES,"101","201");
        ok=new com.vncode.app.features.products.ProductKey(ozon.getId(),Marketplace.OZON,"501","offer-a");
        target=new GtinSyncModels.ProductKey(ozon.getId(),Marketplace.OZON,"501","offer-a");
        var fields=new LinkedHashMap<String,List<String>>();
        fields.put("@catalog_id",List.of("91"));fields.put("@catalog_status",List.of("published"));
        fields.put("tnved",List.of("6112110000"));fields.put("brand",List.of("Brand"));
        fields.put("material",List.of("Cotton"));fields.put("gender",List.of("Male"));
        card=new GtinSyncModels.RegisteredGtin(GtinWriteTestFixture.GTIN,"model","Blue","52",true,true,
                RegisteredGtinProof.source(ozon),Instant.now(),new CatalogProductData(fields,List.of(),List.of()));
        new GtinSyncRepository().saveCatalog(ozon.getId(),Marketplace.OZON,List.of(card));
        var attrs=List.of(new ProductAttribute("1","ТН ВЭД",List.of("6112110000"),""),
                new ProductAttribute("2","Состав",List.of("Cotton"),""),new ProductAttribute("3","Пол",List.of("Male"),""));
        var wv=new ProductVariant("201","model","Blue","52","",false,"52",List.of(card.gtin()),ProductVariant.GtinEvidence.UNVERIFIED);
        var ov=new ProductVariant("offer-a","model","Blue","52","",false,"52",List.of(),ProductVariant.GtinEvidence.UNVERIFIED);
        wbSnapshot=new ProductSnapshot(wk,"live:wb","WB title","","Brand","model","1",List.of(),attrs,List.of(wv),null,List.of(),Instant.now());
        ozonSnapshot=new ProductSnapshot(ok,"live:ozon","Ozon different title","","Brand","model","1",List.of(),attrs,List.of(ov),null,List.of(),Instant.now());
        review=new WbOzonGtinReviewService(new Gateway(),links);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest req){
            if(req.getPath().equals("/v1/barcode/add")){
                writes.incrementAndGet();
                try {var body=com.google.gson.JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
                    var item=body.getAsJsonArray("barcodes").get(0).getAsJsonObject();
                    if(!RegisteredGtinProof.sameCode(item.get("barcode").getAsString(),card.gtin())||item.get("sku").getAsLong()!=901L)
                        return new MockResponse().setResponseCode(400);
                }catch(RuntimeException bad){return new MockResponse().setResponseCode(400);}
                added=true;return new MockResponse().setResponseCode(200).setBody("{\"errors\":[]}");
            }
            if(req.getPath().equals("/v3/product/info/list")){reads.incrementAndGet();if(added)readsAfterWrite.incrementAndGet();return new MockResponse().setResponseCode(200).setBody(added?"{\"barcodes\":[\"00000000000017\"]}":"{\"barcodes\":[]}");}
            return new MockResponse().setResponseCode(404);
        }});
        server.start();
        var fakeAdapter=new HttpAdapter(new ListingHttpClient(ozon,new OkHttpClient(),server.url("/")));
        var gtins=new GtinSyncRepository();var plans=new CatalogSyncRepository();
        var edit=new ProductBulkEditService(new ProductEditRepository(),(s,k)->ozonSnapshot,s->null);
        var catalog=new CatalogSyncService(plans,gtins,edit,(s,k)->ozonSnapshot,s->null,k->fakeAdapter,(s,g)->{},(s,selected)->{});
        WbOzonGtinDispatchService.LinkStore store=new WbOzonGtinDispatchService.LinkStore(){
            public Optional<WbOzonLinkRepository.Link> find(Shop w,Shop o,String p,String v){return links.find(w,o,p,v);}
            public Optional<String> findDispatch(Shop w,Shop o,WbOzonLinkRepository.Link l){return links.findDispatch(w,o,l);}
            public List<WbOzonLinkRepository.Link> linksForDispatch(Shop w,Shop o,String j){return links.linksForDispatch(w,o,j);}
            public void confirmAndSaveDispatch(Shop w,Shop o,List<WbOzonLinkRepository.Link> l,String j){links.confirmAndSaveDispatch(w,o,l,j);}
        };
        WbOzonGtinDispatchService.CatalogPlan plan=new WbOzonGtinDispatchService.CatalogPlan(){
            public CatalogSyncModels.Job preview(Shop s,List<CatalogSyncModels.Selection> x,CatalogSyncModels.Options o)throws Exception{return catalog.preview(s,x,o);}
            public CatalogSyncModels.Job runCross(Shop w,Shop o,String id)throws Exception{return catalog.runReviewedWbOzon(w,o,id);}
            public CatalogSyncModels.Job read(Shop s,String id){return catalog.read(s,id);}
        };
        dispatch=new WbOzonGtinDispatchService(store,plan,(s,l)->{
            if(!l.wbRevision().equals(RegistrationReviewedValues.productRevision(wbSnapshot)))throw new IllegalStateException("WB_PRODUCT_CHANGED");
        },(s,k,l)->{});
    }
    public WbOzonGtinReviewService.Snapshot scan(Consumer<String> progress)throws Exception{return review.scan(wb,ozon,progress);}
    public WbOzonGtinReviewService.ApprovalReport approve(WbOzonGtinReviewService.Snapshot s,List<WbOzonGtinMatcher.Candidate> rows,Consumer<String> p)throws Exception{return review.approveWithReport(wb,ozon,s,rows,p);}
    public List<WbOzonGtinDispatchService.Report> apply(List<WbOzonLinkRepository.Link> rows,Consumer<WbOzonGtinDispatchService.Report> p)throws Exception{return dispatch.apply(wb,ozon,rows,p);}
    private ProductRow row(boolean isWb){
        return new ProductRow(isWb?wk:ok,isWb?"WB title":"Ozon different title","model","","costume","Brand","Blue","52",
                isWb?List.of(card.gtin()):List.of(),"","","Cotton","6112110000",DocumentState.VALID,"Male",true,"52","52");
    }
    private final class Gateway implements WbOzonGtinReviewService.Gateway {
        public ProductRefreshService.Report refresh(Shop s,Consumer<ProductRefreshService.Progress> p){return new ProductRefreshService.Report(1,1,0);}
        public ProductPage page(ProductQuery q){return new ProductPage(List.of(row(q.marketplace()==Marketplace.WILDBERRIES)),1,null);}
        public List<com.vncode.app.features.products.ProductKey> keys(ProductQuery q){return List.of(q.marketplace()==Marketplace.WILDBERRIES?wk:ok);}
        public List<GtinSyncModels.RegisteredGtin> catalog(Shop s,Consumer<NationalCatalogGtinSource.CardIssue> issues){return List.of(card);}
        public ProductSnapshot wb(Shop s,com.vncode.app.features.products.ProductKey k){return wbSnapshot;}
        public OzonExistingCardLinkService.Review ozon(Shop s,GtinSyncModels.ProductKey k,String g){
            return new OzonExistingCardLinkService.Review(k,g,91,"901","Ozon different title","model","Blue","52",
                    "model","Blue","52","6112110000",ListingIdentity.fingerprint(ozon),RegisteredGtinProof.source(ozon),
                    RegistrationReviewedValues.productRevision(ozonSnapshot),"b".repeat(64));
        }
    }
    private final class HttpAdapter implements GtinMarketplaceAdapter {
        final ListingHttpClient http;HttpAdapter(ListingHttpClient http){this.http=http;}
        public GtinSyncModels.Capability capability(GtinSyncModels.ProductKey k){return new GtinSyncModels.Capability(true,false,"");}
        public GtinSyncModels.ProductSnapshot read(GtinSyncModels.ProductKey k)throws Exception {
            var response=http.call("v3/product/info/list",new JsonObject(),false).getAsJsonObject();var codes=new ArrayList<String>();
            response.getAsJsonArray("barcodes").forEach(v->codes.add(v.getAsString()));
            return new GtinSyncModels.ProductSnapshot(k,"model","Blue","52",codes,"stable",Instant.now());
        }
        public boolean conflicts(GtinSyncModels.ProductKey k,String g){return false;}
        public void validateIdentity(GtinSyncModels.ProductSnapshot p,GtinSyncModels.RegisteredGtin g){}
        public GtinSyncModels.Submission submit(GtinSyncModels.Preview p)throws Exception {
            var body=new JsonObject();var values=new JsonArray();var item=new JsonObject();
            item.addProperty("sku",901);item.addProperty("barcode",p.newGtin());values.add(item);body.add("barcodes",values);
            http.call("v1/barcode/add",body,true);return new GtinSyncModels.Submission("fixture",Instant.now());
        }
        public GtinSyncModels.Verification verify(GtinSyncModels.Preview p,GtinSyncModels.Submission s)throws Exception {
            return RegisteredGtinProof.contains(read(p.before().key()).barcodes(),p.newGtin())?GtinSyncModels.Verification.APPLIED:GtinSyncModels.Verification.UNKNOWN;
        }
    }
    public void close() throws IOException {server.shutdown();}
}
