package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.testsupport.FxDialogDriver;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationCountryDisplayTest {
    @TempDir Path profile;
    private final Attribute country=new Attribute(2630,"Страна производства","text",true,true,true,false,List.of("RU","CN"));
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",profile.toString());com.vncode.app.config.Database.initDatabase();}
    @AfterEach void clear()throws Exception{com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}

    @Test void russianNamesAreOnlyLabelsForTheProductionCountryAttribute(){
        assertEquals("Россия",RegistrationCountryDisplay.label(2630,"RU"));
        assertEquals("Китай",RegistrationCountryDisplay.label(2630,"CN"));
        assertEquals("RU",RegistrationCountryDisplay.label(35,"RU"));
        assertEquals("РОССИЯ",RegistrationCountryDisplay.label(35,"РОССИЯ"));
        assertEquals("RU",RegistrationCountryDisplay.value(2630,"Россия",List.of("RU","CN")));
        assertEquals("Россия",RegistrationCountryDisplay.value(35,"Россия",List.of("RU","CN")));
        assertEquals("Россия",RegistrationCountryDisplay.value(2630,"Россия",List.of("CN")),"A label cannot introduce a code absent from the validated schema");
    }
    @Test void actualDraftDropdownShowsRussiaAndApplyRetainsTheOfficialIsoValue()throws Exception{
        var input=new Draft("6103109000",1,"Selected product","Fixture",Map.of(2630L,"ru"));
        var result=new AtomicReference<Draft>();
        FxDialogDriver.choose(()->result.set(RegistrationDraftCompletionDialog.show(null,input,List.of(country),"")),pane->"registrationCatalogMissingDialog".equals(pane.getId()),pane->{
            pane.applyCss();pane.layout();var box=(ComboBox<String>)pane.lookup("#registrationCatalogMissingValue");
            assertEquals("RU",box.getValue());assertEquals("Россия",box.getButtonCell().getText());
            assertDropdownShowsRussia(box);
            return pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
        });
        assertNotNull(result.get());assertEquals("RU",result.get().attributes().get(2630L));
        assertEquals("ru",input.attributes().get(2630L));
        assertNoRegistration();
    }
    @Test void batchEditorShowsRussiaWithoutReplacingItsStoredCode()throws Exception{
        var shop=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),"101","201");
        var finding=new RegistrationBatchPreflight.Finding("catalog:2630",key,"Selected article","RU","RU","",country,"","value",List.of());
        var review=new RegistrationBatchPreflight.Review(List.of(finding));
        FxDialogDriver.choose(()->RegistrationBatchPreflight.show(null,shop,review),pane->"registrationMissingFieldsDialog".equals(pane.getId()),pane->{
            pane.applyCss();pane.layout();var box=(ComboBox<String>)pane.lookup("#registrationMissingValue");
            assertEquals("RU",box.getValue());assertEquals("Россия",box.getEditor().getText());
            assertEquals("Россия",((TableView<?>)pane.lookup(".table-view")).getColumns().get(3).getCellObservableValue(0).getValue());
            assertDropdownShowsRussia(box);
            assertEquals("RU",box.getConverter().fromString(box.getEditor().getText()));
            return pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        });assertNoRegistration();
    }
    private static void assertDropdownShowsRussia(ComboBox<String> box){
        box.show();
        for(var window:List.copyOf(Window.getWindows()))if(window.isShowing()&&window.getScene()!=null){window.getScene().getRoot().applyCss();window.getScene().getRoot().layout();}
        assertTrue(List.copyOf(Window.getWindows()).stream().filter(window->window.isShowing()&&window.getScene()!=null)
                .flatMap(window->window.getScene().getRoot().lookupAll(".list-cell").stream())
                .filter(node->node instanceof ListCell<?>).map(node->((ListCell<?>)node).getText()).anyMatch("Россия"::equals));
        box.hide();assertEquals("RU",box.getValue());
    }
    private static void assertNoRegistration()throws Exception{
        try(var connection=com.vncode.app.config.Database.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM znack_card_registrations")){assertTrue(result.next());assertEquals(0,result.getInt(1));}
    }
}
