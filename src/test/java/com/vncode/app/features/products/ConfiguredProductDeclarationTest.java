package com.vncode.app.features.products;

import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.models.Shop;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredProductDeclarationTest {
    private static final String TYPED="ЕАЭС N RU Д-CN.PА08.B.54321/25";
    private static final String CANONICAL="ЕАЭС N RU Д-CN.РА08.В.54321/25";
    private static final String SOURCE="https://api-seller.ozon.ru/v1/product/certificate/list";
    @TempDir Path directory;
    final Shop shop=new Shop(1,"Owner",Marketplace.OZON,"22","fixture"),other=new Shop(2,"Other",Marketplace.OZON,"23","other-fixture");
    final LocalDate issued=LocalDate.now().minusDays(2),expiry=issued.plusYears(1);

    @BeforeEach void setup()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            statement.execute("INSERT INTO shops(id,name,api_key,marketplace,client_id) VALUES(1,'Owner','fixture','OZON','22'),(2,'Other','other-fixture','OZON','23')");
        }
        configure(TYPED);
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    void configure(String number)throws Exception{
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName()));
        settings.saveSettings(settings.getSettings().withDefaultGoodsDocument("CONFORMITY_DECLARATION",number,issued.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu"))));
        try(var connection=Database.getConnection();var statement=connection.prepareStatement("UPDATE znack_settings SET document_expiry_date=? WHERE shop_id=?")){
            statement.setString(1,expiry.toString());statement.setInt(2,shop.getId());statement.executeUpdate();
        }
    }
    ProductDocument document(String id,String type,String number,LocalDate from,LocalDate until,String source){
        return new ProductDocument(id,type,number,from,until,"Own applicant","Own scope",null,Marketplace.OZON,DocumentState.UNVERIFIED,source,Instant.now(),Map.of("certificate_type","declaration"));
    }
    void save(Shop owner,List<ProductDocument> documents){
        var key=new ProductKey(owner.getId(),Marketplace.OZON,"123","own-offer");
        var snapshot=new ProductSnapshot(key,"live:fixture","Fixture","","Brand","own-offer","9:1",List.of(),List.of(),List.of(),null,documents,Instant.now(),Map.of("owner",ListingIdentity.fingerprint(owner)));
        new ProductRepository().saveVerifiedSnapshot(owner,snapshot);
    }
    @Test void canonicalConfigurationReusesTheExactOwnedOzonReferenceWithoutRewritingIt(){
        var existing=document("91","declaration",TYPED,issued,expiry,SOURCE);save(shop,List.of(existing));
        var selected=ConfiguredProductDeclaration.load(shop);
        assertEquals(existing,selected);assertEquals(TYPED,selected.number());assertEquals("91",selected.id());assertEquals(SOURCE,selected.source());
        assertEquals(TYPED,new ProductRepository().shopDocuments(shop).getFirst().number());
    }
    @Test void canonicalMatchingKeepsTypeDateSourceAndNumericIdGuards(){
        for(var existing:List.of(document("91","certificate",TYPED,issued,expiry,SOURCE),
                document("91","declaration",TYPED,issued.plusDays(1),expiry,SOURCE),
                document("91","declaration",TYPED,issued,expiry.plusDays(1),SOURCE),
                document("91","declaration",TYPED,issued,expiry,"https://example.invalid/unverified"),
                document("not-owned-id","declaration",TYPED,issued,expiry,SOURCE),
                document("91","declaration","ЕАЭС N RU Д-CN.PА08.B.54322/25",issued,expiry,SOURCE))){
            save(shop,List.of(existing));var selected=ConfiguredProductDeclaration.load(shop);
            assertEquals("",selected.id());assertEquals(CANONICAL,selected.number());assertEquals("app:configured-declaration",selected.source());
        }
    }
    @Test void anotherShopsEquivalentCertificateCannotBeReused(){
        save(other,List.of(document("91","declaration",TYPED,issued,expiry,SOURCE)));
        var selected=ConfiguredProductDeclaration.load(shop);assertEquals("",selected.id());assertEquals(CANONICAL,selected.number());
    }
    @Test void multipleOwnedReferencesWithEquivalentRegistrySpellingRemainAmbiguous(){
        save(shop,List.of(document("91","declaration",TYPED,issued,expiry,SOURCE),document("92","declaration",CANONICAL,issued,expiry,SOURCE)));
        var selected=ConfiguredProductDeclaration.load(shop);assertEquals("",selected.id());assertEquals(CANONICAL,selected.number());
    }
    @Test void arbitraryDocumentNumbersAreNotMatchedByVisualLetterSimilarity()throws Exception{
        configure("SYNTHETIC-PB");save(shop,List.of(document("91","declaration","SYNTHETIC-РВ",issued,expiry,SOURCE)));
        var selected=ConfiguredProductDeclaration.load(shop);assertEquals("",selected.id());assertEquals("SYNTHETIC-PB",selected.number());
    }
}
