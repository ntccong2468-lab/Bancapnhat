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
    @Test void selectingOtherRevealsItsMandatoryFieldBeforeTheActualApplyButtonCanClose()throws Exception{
        var child=new Attribute(23828,"Вид товара (при отсутствии в справочнике)","text",false,false,false,true,List.of());
        var parent=new Attribute(12,"Вид товара","text",true,false,true,false,List.of("Костюм","НЕТ В СПРАВОЧНИКЕ"),List.of(),Map.of("НЕТ В СПРАВОЧНИКЕ",List.of(child)));
        var result=new java.util.concurrent.atomic.AtomicReference<Draft>();
        com.vncode.app.testsupport.FxDialogDriver.choose(()->result.set(RegistrationDraftCompletionDialog.show(null,draft,List.of(parent),"")),p->"registrationCatalogMissingDialog".equals(p.getId()),pane->{
            pane.applyCss();pane.layout();var choice=(ComboBox<String>)pane.lookup("#registrationCatalogMissingValue");choice.setValue("НЕТ В СПРАВОЧНИКЕ");
            ((Button)pane.lookup("#registrationCatalogMissingApply")).fire();assertTrue(pane.getScene().getWindow().isShowing());
            var table=(TableView<Attribute>)pane.lookup(".table-view");assertTrue(table.getItems().stream().anyMatch(a->a.id()==23828));
            pane.applyCss();pane.layout();var entry=pane.lookupAll("#registrationCatalogMissingValue").stream().filter(n->n instanceof ComboBox<?> box&&box.isEditable()).map(n->(ComboBox<String>)n).findFirst().orElseThrow();entry.getEditor().setText("Трикотажный костюм");
            return pane.getButtonTypes().stream().filter(b->b.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
        });assertNotNull(result.get());assertEquals("Трикотажный костюм",result.get().attributes().get(23828L));assertFalse(draft.attributes().containsKey(23828L));
    }
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
    @Test void safeLiteralSuggestionsCanBeAppliedWithoutPickingEveryFieldAndAmbiguousSizesRemainUnfilled(){
        var letterSize=new Attribute(35,"Размер","string",false,false,true,false,List.of(),List.of("РОССИЯ","МЕЖДУНАРОДНЫЙ"));
        var current=new Draft("6112110000","6112",1,"Selected product","Fixture",Map.of(10L," вьетнам ",35L," L "),Map.of(35L,"EU"),true,true);
        var schema=List.of(country,letterSize);var proposed=RegistrationDraftCompletionDialog.suggestions(current,schema,"");
        assertEquals("Вьетнам",proposed.get(10L).text());assertEquals(new RegistrationDraftCompletionDialog.Value("L","МЕЖДУНАРОДНЫЙ"),proposed.get(35L));
        var completed=RegistrationDraftCompletionDialog.complete(current,schema,"",proposed);assertTrue(RegistrationDraftCompletionDialog.missing(completed,schema,"").isEmpty());
        var ambiguous=RegistrationDraftCompletionDialog.suggestion(size,"52",null,"");assertNull(ambiguous.type());assertEquals("znack.registration.missing_type_reason",RegistrationDraftCompletionDialog.problem(size,ambiguous,""));
        assertEquals("",RegistrationDraftCompletionDialog.suggestion(country,"",null,"").text(),"Never pick the first of several countries");
    }
    @Test void clickingApplyUsesTheShownSuggestionAndNamesTheSelectedProduct()throws Exception{
        var current=new Draft("6112110000","6112",1,"Selected product","Fixture",Map.of(10L,"вьетнам",13914L,"Selected article"),Map.of());
        var future=new FutureTask<Draft>(()->{
            Platform.runLater(()->{
                var pane=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->"registrationCatalogMissingDialog".equals(n.getId())).findFirst().orElseThrow();pane.applyCss();pane.layout();
                assertTrue(((Label)pane.lookup("#registrationCatalogMissingProduct")).getText().contains("Selected article"));
                var box=(ComboBox<?>)pane.lookup("#registrationCatalogMissingValue");assertEquals("Вьетнам",box.getValue());
                var table=(TableView<?>)pane.lookup(".table-view");assertEquals(4,table.getColumns().size());
                ((Button)pane.lookup("#registrationCatalogMissingApply")).fire();
            });return RegistrationDraftCompletionDialog.show(null,current,List.of(country),"");
        });Platform.runLater(future);assertEquals("Вьетнам",future.get(15,TimeUnit.SECONDS).attributes().get(10L));
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
    }    @Test void completeSelectionDialogNamesTheExactCatalogAttributeAndVariantAndDoesNotCloseWithMissingValues()throws Exception{
        var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),"101","201");
        var finding=new RegistrationBatchPreflight.Finding("catalog:10",key,"Selected Article","","","",country,null,"value",List.of());
        var review=new RegistrationBatchPreflight.Review(List.of(finding),List.of(key),new com.vncode.app.integration.znack.registration.RegistrationDynamicPreflight.Review(List.of(),List.of()));
        var result=new java.util.concurrent.atomic.AtomicBoolean(true);
        com.vncode.app.testsupport.FxDialogDriver.choose(()->result.set(RegistrationBatchPreflight.show(null,shop,review)),pane->"registrationMissingFieldsDialog".equals(pane.getId()),pane->{
            pane.applyCss();pane.layout();var table=(TableView<RegistrationBatchPreflight.Finding>)pane.lookup(".table-view");
            assertEquals(List.of(finding),table.getItems());assertTrue(RegistrationBatchPreflight.fieldLabel(finding).contains("Страна [10]"));assertTrue(table.getColumns().getFirst().getCellObservableValue(0).getValue().toString().contains("Selected Article · 201"));
            var box=(ComboBox<?>)pane.lookup("#registrationMissingValue");assertTrue(box.getItems().containsAll(List.of("Россия","Вьетнам")));
            ((Button)pane.lookup("#registrationMissingApply")).fire();assertTrue(pane.getScene().getWindow().isShowing());assertTrue(((Label)pane.lookup("#registrationMissingStatus")).getText().contains("Selected Article"));
            return pane.getButtonTypes().stream().filter(button->button.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });assertFalse(result.get());
    }

}
