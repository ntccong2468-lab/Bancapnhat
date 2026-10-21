package com.vncode.app.ui.znackregistration;

import com.vncode.app.config.Database;
import com.vncode.app.integration.znack.registration.RegistrationSigningReview;
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real modal review; all identities, XML and certificate metadata are synthetic. */
class RegistrationSigningReviewDialogTest {
    @TempDir Path directory;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException running){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();}
    @AfterEach void cleanup()throws Exception {
        fx(()->{var pane=showingPane();if(pane!=null)cancel(pane);return null;});
        System.clearProperty("vncode.appdata.dir");
    }
    @Test void cancelDisplaysExactDocumentsAndReturnsNoApproval()throws Exception {
        var review=review();var before=storedWrites();
        boolean result=drive(review,pane->{
            pane.applyCss();pane.layout();
            @SuppressWarnings("unchecked") var table=(TableView<RegistrationSigningReview.Card>)pane.lookup("#registrationSigningReviewCards");
            assertNotNull(table);assertEquals(review.cards(),List.copyOf(table.getItems()));
            for(var card:review.cards()){
                var cells=new ArrayList<String>();
                for(var column:table.getColumns())cells.add(String.valueOf(column.getCellData(card)));
                String displayed=String.join("\n",cells);
                for(String value:List.of(card.productId(),card.variantId(),card.revision(),card.gtin(),String.valueOf(card.goodId()),card.catalogState()))assertTrue(displayed.contains(value),value);
            }
            var signer=(Label)pane.lookup("#registrationSigningReviewSigner");assertNotNull(signer);
            assertTrue(signer.getText().contains(review.owner()));
            assertTrue(signer.getText().contains(review.signer()));
            assertTrue(signer.getText().contains(review.scope().inn()));
            assertTrue(signer.getText().contains(review.scope().environment()));
            assertTrue(signer.getText().contains(review.scope().fingerprint()));
            for(var card:review.cards()){
                table.getSelectionModel().select(card);
                var xml=(TextArea)pane.lookup("#registrationSigningReviewXml");
                assertNotNull(xml);assertFalse(xml.isEditable());
                assertEquals(new String(card.document().xml(),StandardCharsets.UTF_8),xml.getText());
                assertTrue(((Label)pane.lookup("#registrationSigningReviewHash")).getText().contains(card.document().sha256()));
            }
            cancel(pane);
        });
        assertFalse(result);assertEquals(before,storedWrites(),"Review/cancel must not grant consent, queue requests or sign documents");
    }
    @Test void confirmApprovesOnlyTheDisplayedImmutableReviewWithoutExecutingIt()throws Exception {
        var review=review();var originalXml=review.cards().getFirst().document().xml();var before=storedWrites();
        boolean result=drive(review,pane->{
            var confirm=pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            assertEquals(I18nService.getInstance().tr("znack.registration.sign_approved"),confirm.getText());
            ((Button)pane.lookupButton(confirm)).fire();
        });
        assertTrue(result);assertArrayEquals(originalXml,review.cards().getFirst().document().xml());
        assertEquals(before,storedWrites(),"Returning explicit approval is separate from native queue/CMS/API execution");
    }
    private RegistrationSigningReview review(){
        var first=new ZnackNationalCatalogService.SigningDocument(91,"<?xml version=\"1.0\"?><card>Первый &amp; первый</card>".getBytes(StandardCharsets.UTF_8));
        var second=new ZnackNationalCatalogService.SigningDocument(92,"<card>第二张\nточный XML</card>".getBytes(StandardCharsets.UTF_8));
        return new RegistrationSigningReview("synthetic-owner",new ZnackRegistrationScope("7700000000","https://catalog.example.invalid/test","A".repeat(40)),"Synthetic certificate",
                List.of(new RegistrationSigningReview.Card("000501","offer-001","revision:first","00000000000017",91L,"READY_TO_SIGN",first),
                        new RegistrationSigningReview.Card("000502","offer-002","revision:second","00000000000024",92L,"APPROVED",second)));
    }
    private static boolean drive(RegistrationSigningReview review,java.util.function.Consumer<DialogPane> interaction)throws Exception {
        var driver=new FutureTask<Void>(()->{
            var pane=Objects.requireNonNull(showingPane());
            var window=pane.getScene().getWindow();
            try{interaction.accept(pane);}finally{if(window.isShowing())cancel(pane);}
            return null;
        });
        var result=new FutureTask<Boolean>(()->{Platform.runLater(driver);return RegistrationSigningReviewDialog.review(null,review);});
        Platform.runLater(result);
        boolean approved=result.get(15,TimeUnit.SECONDS);driver.get(5,TimeUnit.SECONDS);return approved;
    }
    private static DialogPane showingPane(){
        return Window.getWindows().stream().filter(Window::isShowing).filter(window->window.getScene()!=null)
                .map(window->window.getScene().getRoot()).filter(root->"registrationSigningReviewDialog".equals(root.getId()))
                .map(root->(DialogPane)root).findFirst().orElse(null);
    }
    private static void cancel(DialogPane pane){
        var cancel=pane.getButtonTypes().stream().filter(type->type.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
        ((Button)pane.lookupButton(cancel)).fire();
    }
    private static Map<String,Long> storedWrites()throws Exception {
        var counts=new LinkedHashMap<String,Long>();
        try(var c=Database.getConnection();var statement=c.createStatement()){
            for(String table:List.of("znack_registration_signing_consents","znack_card_registrations","znack_card_registration_signatures","ozon_card_registrations")){
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());counts.put(table,rows.getLong(1));}
            }
        }
        return counts;
    }
    private static <T>T fx(Callable<T> task)throws Exception {var future=new FutureTask<>(task);Platform.runLater(future);return future.get(5,TimeUnit.SECONDS);}
}
