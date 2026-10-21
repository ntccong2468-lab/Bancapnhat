package com.vncode.app.features.products;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.ozon.OzonShopBrand;
import com.vncode.app.models.Shop;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductDefaultProposalTest {
    @TempDir Path directory;private String previous;
    private final Shop wb=new Shop(1,"WB","fixture"),ozon=new Shop(2,"Ozon",Marketplace.OZON,"22","fixture");
    private final Map<ProductKey,ProductSnapshot> snapshots=new HashMap<>();
    @BeforeEach void setup()throws Exception{
        previous=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'WB','fixture','WILDBERRIES',NULL),(2,'Ozon','fixture','OZON','22')");
        }
    }
    @AfterEach void restore(){if(previous==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",previous);}
    @Test void majorityBrandNeverBecomesAnAutomaticCorrectionForOtherProducts(){
        var first=save(wb,"1","Majority",List.of());var second=save(wb,"2","Majority",List.of());var third=save(wb,"3","Different",List.of());
        var proposal=service().defaultProposal(wb,List.of(first,second,third),"brand");
        assertEquals("",proposal.value());assertTrue(proposal.expectedRevisions().isEmpty());assertEquals(3,proposal.rows().size());
        assertTrue(proposal.rows().stream().noneMatch(ProductSuggestionService.ProposalRow::automatic));
        assertTrue(proposal.rows().stream().allMatch(row->row.reason().equals("conflicting_selection")));
    }
    @Test void missingCompositionDoesNotInheritAnotherProductsCotton(){
        var first=save(wb,"1","Brand",List.of(new ProductAttribute("7","Состав",List.of("хлопок 100%"),"")));
        var second=save(wb,"2","Brand",List.of());var proposal=service().defaultProposal(wb,List.of(first,second),"material");
        assertEquals("",proposal.value());assertEquals("",proposal.rows().getLast().proposed());assertEquals("missing",proposal.rows().getLast().reason());assertTrue(proposal.expectedRevisions().isEmpty());
    }
    @Test void missingOrLegacyUnownedSnapshotsCannotAuthorizeAutomaticValues(){
        var key=save(wb,"1","Brand",List.of());var source=snapshots.get(key);
        snapshots.put(key,new ProductSnapshot(key,source.revision(),source.title(),source.description(),source.brand(),source.vendorArticle(),source.categoryId(),source.media(),source.attributes(),source.variants(),source.dimensions(),source.documents(),source.checkedAt(),Map.of()));
        var proposal=service().defaultProposal(wb,List.of(key,new ProductKey(1,Marketplace.WILDBERRIES,"missing","")),"brand");
        assertEquals("",proposal.value());assertTrue(proposal.rows().stream().allMatch(row->row.reason().equals("unverified")));
    }
    @Test void equalVerifiedValuesRetainPerProductVersionSourceAndOldValue(){
        var first=save(wb,"1","Brand",List.of());var second=save(wb,"2","Brand",List.of());
        var proposal=service().defaultProposal(wb,List.of(first,second),"brand");
        assertEquals("Brand",proposal.value());assertEquals(2,proposal.expectedRevisions().size());
        assertTrue(proposal.rows().stream().allMatch(row->row.source().equals("marketplace")&&row.before().equals("Brand")&&row.checkedAt()!=null));
        assertTrue(proposal.expectedRevisions().values().stream().allMatch(value->value.startsWith("live:proposal:")));
    }
    @Test void shopBrandFallbackIsOnlyForMissingOzonBrandsAndItsConfigurationIsBound(){
        var key=save(ozon,"1","",List.of());new OzonShopBrand().save(ozon,"Configured brand");
        var proposal=service().defaultProposal(ozon,List.of(key),"brand");assertEquals("Configured brand",proposal.value());assertEquals("shop_brand",proposal.rows().getFirst().source());
        new OzonShopBrand().save(ozon,"Changed brand");assertNotEquals(proposal.expectedRevisions().get(key),ProductSuggestionService.proposalRevision(ozon,snapshots.get(key),"brand"));
        var existing=save(ozon,"2","Existing brand",List.of());assertEquals("Existing brand",service().defaultProposal(ozon,List.of(existing),"brand").value());
        assertEquals("",service().defaultProposal(ozon,List.of(key,existing),"brand").value());
    }
    @Test void contradictoryAttributesDoNotBecomeAnAutomaticCombinedGender(){
        var key=save(wb,"1","Brand",List.of(new ProductAttribute("7","Пол",List.of("Мужской"),""),new ProductAttribute("8","Gender",List.of("Женский"),"")));
        var proposal=service().defaultProposal(wb,List.of(key),"gender");assertEquals("",proposal.value());assertEquals("conflicting_attributes",proposal.rows().getFirst().reason());
    }
    private ProductSuggestionService service(){return new ProductSuggestionService((shop,key)->{throw new AssertionError("Proposal is read-only over verified saved facts");},shop->{throw new AssertionError("Never guess from a dictionary");},key->Optional.ofNullable(snapshots.get(key)));}
    private ProductKey save(Shop shop,String id,String brand,List<ProductAttribute> attributes){
        var key=new ProductKey(shop.getId(),shop.getMarketplace(),id,"offer-"+id);
        snapshots.put(key,new ProductSnapshot(key,"live:fixture","Fixture","",brand,"article-"+id,"9",List.of(),attributes,List.of(),new PackageDimensions(null,null,null,null),List.of(),Instant.now(),Map.of("owner",ListingIdentity.fingerprint(shop))));return key;
    }
}
