package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.CatalogErrorRepairService.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogErrorRepairDialogTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",directory.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    ProductRow row(String id,String size){return new ProductRow(new ProductKey(1,Marketplace.WILDBERRIES,"101",id),"Synthetic suit","SUIT","101","Suit","Fixture","ЧЕРНЫЙ",size,List.of(),"","","хлопок","6112110000",DocumentState.UNVERIFIED,"Мужской",true,size);}
    ErrorCard card(List<ProductRow> rows){return new ErrorCard("00000000000017","SUIT","ЧЕРНЫЙ","1","CATALOG_CARD_ERRORS",rows);}
    @Test void reportKeepsAllErrorsAndRequiresExplicitVariantBeforeEdit()throws Exception{
        var a=row("201","52");var b=row("202","54");var card=card(List.of(a,b));var unmatched=new ErrorCard("00000000000024","MISSING","","1","CATALOG_CARD_ERRORS",List.of());
        var future=new FutureTask<Map<ErrorCard,ProductKey>>(() -> {
            Platform.runLater(()->{
                var pane=pane("catalogErrorReportDialog");pane.applyCss();pane.layout();assertEquals(2,((TableView<?>)pane.lookup("#catalogErrorReportTable")).getItems().size());
                ((Button)pane.lookup("#catalogErrorEdit")).fire();assertTrue(pane.getScene().getWindow().isShowing(),"Ambiguous size must not choose the first variant");
                @SuppressWarnings("unchecked") var choices=(ComboBox<ProductRow>)pane.lookup("#catalogErrorVariant");assertNull(choices.getValue());choices.setValue(b);((Button)pane.lookup("#catalogErrorEdit")).fire();
            });return CatalogErrorRepairDialog.report(new StackPane(),List.of(card,unmatched));
        });Platform.runLater(future);var result=future.get(15,TimeUnit.SECONDS);assertEquals(Map.of(card,b.key()),result);
    }
    Proposal proposal(){
        var row=row("201","52");var key=row.key();var snapshot=new ProductSnapshot(key,"live:fixture","Suit","","Fixture","SUIT","1",List.of(),List.of(),List.of(new ProductVariant("201","SUIT","ЧЕРНЫЙ","52","",false,"52")),null,List.of(),Instant.now());
        var doc=new ZnackModels.GoodsDocument("CONFORMITY_DECLARATION","FIXTURE-PERMIT","2026-01-01");var attrs=Map.of(2478L,"Suit",2504L,"Fixture",13914L,"SUIT",13933L,"6112110000",35L,"52",36L,"ЧЕРНЫЙ",2483L,"хлопок",14013L,"Мужской",23557L,"FIXTURE-PERMIT:::2026-01-01");
        return new Proposal(card(List.of(row)),snapshot,"fixture",91,new Draft("6112110000","6112",1,"Suit","Fixture",attrs,Map.of(35L,"РОССИЯ")),List.of(new Attribute(35,"Размер","",false,false,false,false,List.of(),List.of("РОССИЯ"))),List.of(doc),Map.of(35L,"1",23557L,"OLD:::2026-01-01"),List.of(),null,"fixture");
    }
    @Test void applyClosesProposalAndReturnsReviewedRealSizeAndPermit()throws Exception{
        var p=proposal();var future=new FutureTask<List<Draft>>(() -> {Platform.runLater(()->{var pane=pane("catalogErrorProposalDialog");pane.applyCss();pane.layout();assertTrue(((TableView<?>)pane.lookup("#catalogErrorProposalTable")).getItems().size()>=10);var window=pane.getScene().getWindow();((Button)pane.lookup("#catalogRepairApply")).fire();assertFalse(window.isShowing());});return CatalogErrorRepairDialog.proposals(new StackPane(),new CatalogErrorRepairDialog.Prepared(List.of(p),List.of()));});Platform.runLater(future);var result=future.get(15,TimeUnit.SECONDS);assertEquals("52",result.getFirst().attributes().get(35L));assertEquals("FIXTURE-PERMIT:::2026-01-01",result.getFirst().attributes().get(23557L));
    }
    @Test void cancelNeverReturnsAnApprovedCorrection()throws Exception{
        var future=new FutureTask<List<Draft>>(() -> {Platform.runLater(()->{var pane=pane("catalogErrorProposalDialog");var cancel=pane.getButtonTypes().stream().filter(b->b.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();((Button)pane.lookupButton(cancel)).fire();});return CatalogErrorRepairDialog.proposals(new StackPane(),new CatalogErrorRepairDialog.Prepared(List.of(proposal()),List.of()));});Platform.runLater(future);assertNull(future.get(15,TimeUnit.SECONDS));
    }
    static DialogPane pane(String id){return Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(n->id.equals(n.getId())).map(n->(DialogPane)n).findFirst().orElseThrow();}
}
