package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationDraftCompletionDialogTest {
    @TempDir Path root;
    final Attribute country=new Attribute(10,"Страна","string",true,false,true,false,List.of("Россия","Вьетнам"));
    final Attribute size=new Attribute(35,"Размер","string",false,false,true,false,List.of(),List.of("РОССИЯ","EU"));
    final Draft draft=new Draft("6112110000","6112",1,"Title","Fixture",Map.of(35L,"52",ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID,"D:::2026-01-01"),Map.of(),true,true);
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",root.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void clear()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void missingSchemaFieldsRequireLiteralPresetsAndAnExplicitAmbiguousType(){
        var schema=List.of(country,size);assertEquals(2,RegistrationDraftCompletionDialog.missing(draft,schema,"").size());
        assertThrows(IllegalArgumentException.class,()->RegistrationDraftCompletionDialog.complete(draft,schema,"",Map.of(10L,new RegistrationDraftCompletionDialog.Value("invented",null),35L,new RegistrationDraftCompletionDialog.Value("52","РОССИЯ"))));
        var result=RegistrationDraftCompletionDialog.complete(draft,schema,"",Map.of(10L,new RegistrationDraftCompletionDialog.Value("Вьетнам",null),35L,new RegistrationDraftCompletionDialog.Value(" 52 ","РОССИЯ")));
        assertEquals("Вьетнам",result.attributes().get(10L));assertEquals("52",result.attributes().get(35L));assertEquals("РОССИЯ",result.attributeTypes().get(35L));assertEquals(draft.attributes().get(ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID),result.attributes().get(ZnackNationalCatalogService.DECLARATION_ATTRIBUTE_ID));assertTrue(result.autoSign());assertTrue(result.isKit());assertTrue(RegistrationDraftCompletionDialog.missing(result,schema,"").isEmpty());
    }
    @Test void sellerCanChooseSuggestedValueInsideTheTableAndApplyBelowIt()throws Exception{
        var future=new FutureTask<Draft>(()->{
            Platform.runLater(()->{
                var pane=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->n.getId()!=null&&n.getId().equals("registrationCatalogMissingDialog")).findFirst().orElseThrow();
                pane.applyCss();pane.layout();var button=(Button)pane.lookup("#registrationCatalogMissingApply");assertNotNull(button);button.fire();assertTrue(pane.getScene().getWindow().isShowing(),"Missing value must keep the form open");
                var box=(ComboBox<String>)pane.lookup("#registrationCatalogMissingValue");assertNotNull(box);assertFalse(box.isEditable());box.setValue("Вьетнам");
                var table=(TableView<?>)pane.lookup(".table-view");assertTrue(button.localToScene(button.getBoundsInLocal()).getMinY()>=table.localToScene(table.getBoundsInLocal()).getMaxY());button.fire();
            });
            return RegistrationDraftCompletionDialog.show(null,draft,List.of(country),"");
        });Platform.runLater(future);var result=future.get(15,TimeUnit.SECONDS);assertNotNull(result);assertEquals("Вьетнам",result.attributes().get(10L));
    }
    @Test void missingDocumentsOpenTheExistingSuggestionFormFromTheirRowWithoutAllocatingAnything()throws Exception{
        var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        var key=new com.vncode.app.features.products.ProductKey(1,shop.getMarketplace(),"101","201");var review=new RegistrationBatchPreflight.Review(List.of(new RegistrationBatchPreflight.Finding("documents",key,"Synthetic article","","","1")));
        var future=new FutureTask<Boolean>(()->{
            Platform.runLater(()->{
                var pane=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->"registrationMissingFieldsDialog".equals(n.getId())).findFirst().orElseThrow();pane.applyCss();pane.layout();
                ((Button)pane.lookup("#registrationMissingApply")).fire();assertTrue(pane.getScene().getWindow().isShowing());
                var complete=(Button)pane.lookup("#registrationMissingDocuments");assertNotNull(complete);complete.fire();
                var documents=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->"productBulkEditDialog".equals(n.getId())).findFirst().orElseThrow();documents.applyCss();documents.layout();assertNotNull(documents.lookup("#bulkExistingDocument"));assertNotNull(documents.lookup("#editApply"));assertEquals(2,documents.lookupAll(".date-picker").size());
                ((Button)documents.lookup(".button:cancel")).fire();((Button)pane.lookup(".button:cancel")).fire();
            });return RegistrationBatchPreflight.show(null,shop,review);
        });Platform.runLater(future);assertFalse(future.get(15,TimeUnit.SECONDS));
        try(var c=com.vncode.app.config.Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
}
