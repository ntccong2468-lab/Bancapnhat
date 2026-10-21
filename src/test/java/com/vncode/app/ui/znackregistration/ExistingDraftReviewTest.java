package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic UI fixtures; approving a review is separate from any Catalog write. */
class ExistingDraftReviewTest {
    @TempDir Path directory;
    RegistrationBatchPreflight.Editor editor;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @BeforeEach void setup()throws Exception {System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();try(var connection=Database.getConnection()){RegistrationReviewedValues.ensureTables(connection);}}
    @AfterEach void cleanup()throws Exception {if(editor!=null)fx(()->{editor.dialog.close();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void aCompleteDraftStillDisplaysAllRequiredFieldsBeforeAnyApproval()throws Exception {
        var owner=new Shop(1,"Fixture","fixture");var key=new ProductKey(1,owner.getMarketplace(),"101","1");
        fx(()->{editor=new RegistrationBatchPreflight.Editor(null,owner,completeReview(owner,List.of(key)),true);
            assertEquals(Set.of("catalog:2478","catalog:35"),editor.table.getItems().stream().map(RegistrationBatchPreflight.Finding::field).collect(java.util.stream.Collectors.toSet()));
            assertTrue(editor.values.values().containsAll(List.of("Synthetic name","52")));assertNotEquals(Boolean.TRUE,editor.dialog.getResult());return null;});
        assertNoRegistrationWrites();
    }
    @Test void completingMarketplaceFieldsMustNotSkipTheSubsequentFullCatalogReview()throws Exception {
        var owner=new Shop(1,"Fixture","fixture");var key=new ProductKey(1,owner.getMarketplace(),"101","1");
        var common=new RegistrationBatchPreflight.Review(List.of(new RegistrationBatchPreflight.Finding("brand",key,"Synthetic article","","Brand")),List.of(key),null);
        fx(()->{editor=new RegistrationBatchPreflight.Editor(null,owner,common,true);editor.reviewReader=(shop,keys,values,documents,categories)->completeReview(shop,keys);editor.dialog.show();editor.freshReview(true);return null;});
        com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{assertFalse(editor.awaitingFullReview);assertTrue(editor.dialog.isShowing());assertNotEquals(Boolean.TRUE,editor.dialog.getResult());
            assertEquals(Set.of("catalog:2478","catalog:35"),editor.table.getItems().stream().map(RegistrationBatchPreflight.Finding::field).collect(java.util.stream.Collectors.toSet()));return null;});
        assertNoRegistrationWrites();
    }
    static RegistrationBatchPreflight.Review completeReview(Shop owner,List<ProductKey> keys){
        var schema=List.of(new Attribute(2478,"Наименование","text",false,false,true,false,List.of()),new Attribute(35,"Размер","text",false,false,true,false,List.of()));
        var plans=new ArrayList<RegistrationDynamicPreflight.Plan>();
        for(var key:keys){var snapshot=new ProductSnapshot(key,"live:synthetic-review","Synthetic name","","Brand","Synthetic article","1",List.of(),List.of(),List.of(new ProductVariant(key.variantId(),"Synthetic article","Blue","52","",false)),null,List.of(),Instant.now());
            plans.add(new RegistrationDynamicPreflight.Plan(snapshot,new Draft("6204",1,"Synthetic name","Brand",Map.of(2478L,"Synthetic name",35L,"52")),schema));}
        return new RegistrationBatchPreflight.Review(List.of(),keys,new RegistrationDynamicPreflight.Review(plans,List.of()));
    }
    private static void assertNoRegistrationWrites()throws Exception {
        try(var connection=Database.getConnection();var statement=connection.createStatement()){
            for(String name:List.of("znack_card_registrations","ozon_card_registrations","znack_registration_reviewed_values")){
                try(var rows=statement.executeQuery("SELECT count(*) FROM "+name)){assertTrue(rows.next());assertEquals(0,rows.getInt(1),name);}
            }
        }
    }
    private static <T>T fx(Callable<T> call)throws Exception{var task=new FutureTask<>(call);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
