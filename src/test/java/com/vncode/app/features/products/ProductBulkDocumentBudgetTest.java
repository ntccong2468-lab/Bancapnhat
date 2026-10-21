package com.vncode.app.features.products;

import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonListingAdapter;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProductBulkDocumentBudgetTest {
    @TempDir Path root;
    final Shop shop=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");
    final AtomicInteger reads=new AtomicInteger(),calls=new AtomicInteger();
    ProductEditRepository repo;ProductBulkEditService service;
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(2,'Ozon','OZON','22','fixture')");}
        var adapter=new OzonListingAdapter(s->(p,b,m)->{calls.incrementAndGet();return JsonParser.parseString(m?"{\"result\":{\"task_id\":123}}":p.endsWith("options")?"{\"option\":{}}":p.endsWith("params")?"{\"params\":[]}":"{\"result\":[]}");},i->{throw new java.io.IOException();});
        repo=new ProductEditRepository();service=new ProductBulkEditService(repo,(s,k)->{reads.incrementAndGet();return snapshot(k);},s->adapter);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    ProductSnapshot snapshot(ProductKey key){String raw="{\"info\":{\"price\":\"123\"},\"attributes\":{\"offer_id\":\""+key.variantId()+"\",\"attributes\":[]}}";return new ProductSnapshot(key,"live:"+key.cardId(),"Title","Description","Brand",key.variantId(),"9:8",List.of(),List.of(),List.of(),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop),"source.payload",raw));}
    List<ProductKey> keys(int n){var out=new ArrayList<ProductKey>();for(int i=0;i<n;i++)out.add(new ProductKey(2,Marketplace.OZON,Integer.toString(100+i),"offer-"+i));return out;}
    ProductDocument pdf(String name,long size)throws Exception{Path p=root.resolve(name+".pdf");try(var f=new java.io.RandomAccessFile(p.toFile(),"rw")){f.writeBytes("%PDF-1.7\nfixture\n");f.setLength(size);}return new ProductDocument(name,"declaration",name,LocalDate.of(2026,1,1),LocalDate.of(2028,1,1),"","",p.toUri(),Marketplace.OZON,DocumentState.UNVERIFIED,"",null);}
    ProductBulkEdit edit(List<ProductDocument> docs){return new ProductBulkEdit("documents",List.of(),docs,Map.of());}
    long scalar(String sql)throws Exception{try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    @Test void repeatedCertificateSharesMemoryAcrossCards()throws Exception{
        var p=service.preview(shop,keys(3),edit(List.of(pdf("shared",1024*1024))));assertTrue(p.publishable());
        assertTrue(p.items().get(0).preview().preview().documentsJson()==p.items().get(2).preview().preview().documentsJson(),"Repeated file content must share one string");
    }
    @Test void reopeningAndApplyingBatchDoesNotDuplicateCertificateInStoredJobs()throws Exception{
        var p=service.preview(shop,keys(3),edit(List.of(pdf("shared",1024*1024))));
        assertTrue(scalar("SELECT length(preview) FROM product_edit_jobs")<65536,"Batch summary must not embed repeated file data");
        assertTrue(scalar("SELECT sum(length(payload)) FROM product_edit_items")<65536);
        assertEquals(1,scalar("SELECT count(*) FROM product_edit_assets"));
        var reopened=repo.find(2,ListingIdentity.fingerprint(shop),p.id());assertTrue(reopened.publishable());
        assertTrue(reopened.items().get(0).preview().preview().documentsJson()==reopened.items().get(2).preview().preview().documentsJson(),"Reopened cards must share one file payload");
        var result=service.apply(shop,p.id(),true);assertEquals(3,result.items().size());
        assertTrue(result.items().stream().allMatch(i->i.submission()!=null&&i.preview().preview().publishable()));
        assertTrue(scalar("SELECT sum(length(payload)) FROM product_edit_items")<65536,"Checkpoints must retain references too");
        assertEquals(1,scalar("SELECT count(*) FROM product_edit_assets"));
    }
    @Test void combinedOversizedFilesRejectedBeforeAnyCardReadOrEncoding()throws Exception{
        var selection=keys(3);var docs=List.of(pdf("a",6L*1024*1024),pdf("b",6L*1024*1024));
        assertThrows(IllegalArgumentException.class,()->service.preview(shop,selection,edit(docs)));
        assertEquals(0,reads.get());assertEquals(0,calls.get());assertEquals(3,selection.size());assertEquals(0,scalar("SELECT count(*) FROM product_edit_jobs"));
    }
    @Test void documentCountIsBoundedBeforeCardReads()throws Exception{
        var d=pdf("one",1024);var docs=new ArrayList<ProductDocument>();for(int i=0;i<17;i++)docs.add(new ProductDocument("doc-"+i,d.type(),"DOC-"+i,d.validFrom(),d.validUntil(),"","",d.file(),d.marketplace(),d.state(),"",null));
        assertThrows(IllegalArgumentException.class,()->service.preview(shop,keys(1),edit(docs)));assertEquals(0,reads.get());assertEquals(0,calls.get());
    }
}
