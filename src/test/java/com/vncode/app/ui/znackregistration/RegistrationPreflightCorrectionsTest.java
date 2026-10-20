package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationPreflightCorrectionsTest {
    @TempDir Path root;Shop shop;ProductSnapshot snapshot;Adapter adapter;RegistrationPreflightCorrections corrections;
    ProductKey key;int reads;
    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);key=new ProductKey(1,shop.getMarketplace(),"123","201");
        snapshot=new ProductSnapshot(key,"live:before","Title","Keep description","","article","9",List.of(),List.of(new ProductAttribute("77","Keep",List.of("original"),null)),List.of(new ProductVariant("201","article","Brown","52","",false),new ProductVariant("202","article","Brown","54","",false)),null,List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop)));
        adapter=new Adapter();ProductSnapshotReader reader=(s,k)->{reads++;return snapshot;};
        corrections=new RegistrationPreflightCorrections(new ProductBulkEditService(new ProductEditRepository(),reader,s->adapter),reader,s->adapter);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    RegistrationBatchPreflight.Finding finding(String field){return new RegistrationBatchPreflight.Finding(field,key,"article","","","9");}
    @Test void allMissingFieldsAreOnePatchAndReadbackNeverRepeatsAnAcceptedSubmission()throws Exception{
        var brand=finding("brand");var material=finding("material");var gender=finding("gender");var tnved=finding("tnved");
        var review=new RegistrationBatchPreflight.Review(List.of(brand,material,gender,tnved));
        var session=corrections.prepare(shop,review,Map.of(brand," Fixture ",material," хлопок | лайкра ",gender,"Мужской",tnved,"6112110000"));
        assertEquals(0,adapter.writes);assertEquals(Map.of("brand",List.of("Fixture"),"attribute:1",List.of("хлопок","лайкра"),"attribute:2",List.of("Мужской"),"attribute:3",List.of("6112110000")),adapter.lastChanges);
        assertFalse(session.advance(p->{}));assertFalse(session.advance(p->{}));assertEquals(1,adapter.writes);
        adapter.ready=true;assertTrue(session.advance(p->{}));assertTrue(session.advance(p->{}));assertEquals(1,adapter.writes);
        assertEquals("Keep description",adapter.original.description());assertEquals(List.of("original"),adapter.original.attributes().getFirst().values());assertEquals(2,adapter.original.variants().size());
        try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertTrue(r.next());assertEquals(0,r.getInt(1),"Completing marketplace fields must not allocate a GTIN or create a Catalog request");}
    }
    @Test void allValuesAreValidatedBeforeAnyRemoteWriteOrRead()throws Exception{
        var brand=finding("brand");var tnved=finding("tnved");var review=new RegistrationBatchPreflight.Review(List.of(brand,tnved));
        assertThrows(IllegalArgumentException.class,()->corrections.prepare(shop,review,Map.of(brand,"Fixture",tnved,"bad")));assertEquals(0,reads);assertEquals(0,adapter.writes);
        var material=finding("material");assertThrows(IllegalArgumentException.class,()->corrections.prepare(shop,new RegistrationBatchPreflight.Review(List.of(material)),Map.of(material," | ")));assertEquals(0,reads);
    }
    @Test void siblingVariantsShareOneCardPatchAndConflictingValuesAreBlocked()throws Exception{
        var a=finding("brand");var b=new RegistrationBatchPreflight.Finding("brand",new ProductKey(1,shop.getMarketplace(),"123","202"),"article","","","9");var review=new RegistrationBatchPreflight.Review(List.of(a,b));
        assertThrows(IllegalArgumentException.class,()->corrections.prepare(shop,review,Map.of(a,"A",b,"B")));assertEquals(0,reads);
        var session=corrections.prepare(shop,review,Map.of(a,"A",b,"A"));adapter.ready=true;assertTrue(session.advance(p->{}));assertEquals(1,adapter.writes);
    }
    @Test void changedOwnerCannotApplyAPreparedCorrection()throws Exception{
        var field=finding("brand");var session=corrections.prepare(shop,new RegistrationBatchPreflight.Review(List.of(field)),Map.of(field,"A"));
        try(var c=Database.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE shops SET api_key='rotated' WHERE id=1");}
        assertThrows(SecurityException.class,()->session.advance(p->{}));assertEquals(0,adapter.writes);
    }
    class Adapter implements ListingTargetAdapter {
        int writes;boolean ready;Map<String,List<String>> lastChanges;ProductSnapshot original;
        public List<TargetCategory> categories(Shop s){return List.of();}
        public List<TargetAttribute> attributes(Shop s,String category){return List.of(new TargetAttribute("1","Состав","string",true,"",5,"","",false),new TargetAttribute("2","Пол","string",true,"",1,"","",false),new TargetAttribute("3","ТН ВЭД","string",true,"",1,"","",false));}
        public CopyPreview preview(Shop s,ProductSnapshot snapshot,CopyOptions o){throw new UnsupportedOperationException();}
        public ProductEditPreview previewUpdate(Shop s,ProductSnapshot snapshot,Map<String,List<String>> changes,List<ProductDocument> documents){
            lastChanges=Map.copyOf(changes);original=snapshot;assertNull(documents,"Unrelated documents must be preserved by the native adapter");String json=ProductJson.GSON.toJson(changes),owner=ListingIdentity.fingerprint(s);var options=new CopyOptions(changes.keySet(),Set.of(),snapshot.categoryId(),Map.of(),changes,Map.of(),null,"RUB",List.of(),null);
            return new ProductEditPreview(snapshot.key(),snapshot.revision(),new CopyPreview(s.getId(),s.getMarketplace(),owner,snapshot.key(),snapshot.revision(),CopyPreview.hash(json,"[]","[]",owner,snapshot.revision(),"UPDATE"),json,"[]","[]",Map.of(),List.of(),List.of(snapshot.vendorArticle()),options,"UPDATE"));
        }
        public RemoteSubmission submitUpdate(Shop s,ProductEditPreview p){writes++;return submit(s,p.preview());}
        public RemoteSubmission submit(Shop s,CopyPreview p){return new RemoteSubmission(s.getId(),s.getMarketplace(),p.owner(),p.requestHash(),null,p.articles(),p.requestJson(),p.mediaJson(),p.documentsJson(),"CARD",Map.of());}
        public RemoteResult reconcile(Shop s,RemoteSubmission submission){return ready?new RemoteResult(CopyState.RECONCILED,List.of("123"),List.of(),Map.of("article","123")):RemoteResult.pending();}
        public RemoteSubmission finishAssets(Shop s,RemoteSubmission submission){return submission;}
    }
}
