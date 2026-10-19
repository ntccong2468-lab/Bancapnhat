package com.vncode.app.ui.gs1;

import com.google.gson.JsonParser;
import com.vncode.app.config.Database;
import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Gs1PaneTest {
    @TempDir Path root;
    private Gs1Pane pane;
    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setUp()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        fx(()->{pane=new Gs1Pane();new Scene(pane,1000,850);field("shop",new Shop(1,"Fixture","fixture"));field("inn","7707083893");field("environment","production");return null;});
    }
    @AfterEach void tearDown()throws Exception{fx(()->{pane.dispose();return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");}
    @Test void configuredCacheShowsTheActualProfileEmailAndIndependentSourceTimes()throws Exception{
        var owner=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        com.vncode.app.features.gs1.Gs1UiTestFixture.sync(owner,true);fx(()->{pane.setShop(owner);return null;});awaitCache("source@example.invalid");
        fx(()->{
            pane.applyCss();pane.layout();
            var method=Gs1Pane.class.getDeclaredMethod("profileEmail");method.setAccessible(true);assertEquals("source@example.invalid",method.invoke(pane));
            assertEquals(1,pane.lookupAll("#gs1CheckAllButton").size());assertNotNull(pane.lookup("#gs1ConfigureZnack"));assertNotNull(pane.lookup("#gs1SyncReport"));
            var checks=(GridPane)pane.lookup("#gs1ReadinessGrid");assertEquals(6,checks.getChildren().size());
            var detailField=Gs1Pane.class.getDeclaredField("detail");detailField.setAccessible(true);assertTrue(((Label)detailField.get(pane)).getText().contains(I18nService.getInstance().tr("gs1.sync.catalog_checked")));
            capture("gs1-ready",1366,768);capture("gs1-ready-small",1024,600);return null;
        });
    }
    @Test void failedRefreshKeepsTheCorrectScopedDataAndExposesTheError()throws Exception{
        var owner=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        com.vncode.app.features.gs1.Gs1UiTestFixture.sync(owner,true);fx(()->{pane.setShop(owner);return null;});awaitCache("source@example.invalid");
        com.vncode.app.features.gs1.Gs1UiTestFixture.sync(owner,false);com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{pane.refreshFromCache();return null;});awaitStatus("NETWORK_TIMEOUT");
        fx(()->{var f=Gs1Pane.class.getDeclaredField("membership");f.setAccessible(true);assertTrue(((Gs1Membership)f.get(pane)).member());capture("gs1-partial",1366,768);return null;});
    }
    @Test void switchingToAnUnconfiguredShopClearsTheOldMemberAndEmail()throws Exception{
        var owner=com.vncode.app.features.gtinsync.GtinWriteTestFixture.shop(com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES);
        com.vncode.app.features.gs1.Gs1UiTestFixture.sync(owner,true);fx(()->{pane.setShop(owner);return null;});awaitCache("source@example.invalid");
        try(var c=Database.getConnection();var sql=c.createStatement()){sql.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(9,'No signer','OZON','synthetic','synthetic')");}
        fx(()->{pane.setShop(new Shop(9,"No signer",com.vncode.app.integration.marketplace.Marketplace.OZON,"synthetic","synthetic"));return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();
        fx(()->{var memberField=Gs1Pane.class.getDeclaredField("membership");memberField.setAccessible(true);assertNull(memberField.get(pane));var emailField=Gs1Pane.class.getDeclaredField("profileEmail");emailField.setAccessible(true);assertEquals("",emailField.get(pane));assertNotNull(pane.lookup("#gs1ConfigureZnack"));return null;});
    }
    private void awaitCache(String email)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(fx(()->{var f=Gs1Pane.class.getDeclaredField("profileEmail");f.setAccessible(true);return email.equals(f.get(pane));}))return;Thread.sleep(10);}fail("Scoped profile did not load");
    }
    private void awaitStatus(String code)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(fx(()->{var f=Gs1Pane.class.getDeclaredField("status");f.setAccessible(true);return ((Label)f.get(pane)).getText().contains(code);}))return;Thread.sleep(10);}fail("Expected visible status "+code);
    }
    private void capture(String name,int width,int height)throws Exception{
        String directory=System.getProperty("vncode.item5.capture.dir");if(directory==null)return;
        var language=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);render();
        pane.resize(width,height);pane.setPrefSize(width,height);var scene=pane.getScene();scene.getStylesheets().add(Gs1Pane.class.getResource("/com/vncode/app/styles/theme.css").toExternalForm());pane.applyCss();pane.layout();var image=pane.snapshot(null,null);
        var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));java.nio.file.Files.createDirectories(Path.of(directory));javax.imageio.ImageIO.write(png,"png",Path.of(directory,name+".png").toFile());I18nService.getInstance().setLanguage(language);render();
    }
    @Test void anotherPrefixPreventsFalseShortageAndRenewalOnlyAppearsWhenDue()throws Exception{
        fx(()->{
            var date=LocalDate.now(ZoneId.of("Europe/Moscow"));
            field("membership",member(true,date.plusDays(80),List.of(new Gs1Membership.Prefix("4601234",0,List.of()),new Gs1Membership.Prefix("4601235",20,List.of()))));render();
            assertFalse(button("gs1MoreCodes").isDisabled(),"A fresh member can open the prefix dialog even while codes remain; sending is checked separately");assertTrue(button("gs1Renew").isDisabled());
            field("membership",member(true,date.plusDays(60),List.of(new Gs1Membership.Prefix("4601234",0,List.of()))));render();
            assertFalse(button("gs1MoreCodes").isDisabled());assertFalse(button("gs1Renew").isDisabled());
            return null;
        });
    }
    @Test void accountCardsAndInboxUseTheFullWorkspaceWithSixChecksInTwoColumns()throws Exception{
        fx(()->{
            var date=LocalDate.now(ZoneId.of("Europe/Moscow"));field("membership",member(true,date.plusYears(1),List.of(new Gs1Membership.Prefix("4601234",20,List.of("4601234000000")))));render();
            var window=new Stage();window.setScene(pane.getScene());window.show();
            try{pane.applyCss();pane.layout();var split=(SplitPane)pane.lookup("#gs1WorkspaceSplit");var checks=(GridPane)pane.lookup("#gs1ReadinessGrid");
                assertEquals(Orientation.HORIZONTAL,split.getOrientation());assertEquals(2,split.getItems().size());
                assertNotNull(pane.lookup("#gs1MembershipCard"));assertNotNull(pane.lookup("#gs1PrefixesCard"));assertNotNull(pane.lookup("#gs1InboxPanel"));
                assertEquals(6,checks.getChildren().size());assertEquals(2,checks.getColumnConstraints().size());
                for(int index=0;index<6;index++){assertEquals(index%2,GridPane.getColumnIndex(checks.getChildren().get(index)));assertEquals(index/2,GridPane.getRowIndex(checks.getChildren().get(index)));}
                var inbox=pane.lookup("#gs1InboxPanel");assertTrue(inbox.getBoundsInLocal().getHeight()>pane.getHeight()*.5,"Inbox must keep the main workspace height instead of sitting below a large scrolling header");
                assertTrue(inbox.localToScene(inbox.getBoundsInLocal()).getMinX()>pane.lookup("#gs1MembershipCard").localToScene(pane.lookup("#gs1MembershipCard").getBoundsInLocal()).getMinX());
                assertEquals(0,pane.activeTaskCount(),"Rendering never prompts for a PIN or starts remote checks");
            }finally{window.close();}return null;
        });
    }
    @Test void narrowWorkspaceStacksCardsAndInboxWithoutHidingEitherPanel()throws Exception{
        fx(()->{render();pane.resize(760,850);pane.layout();var split=(SplitPane)pane.lookup("#gs1WorkspaceSplit");
            assertEquals(Orientation.VERTICAL,split.getOrientation());assertTrue(pane.lookup("#gs1AccountScroll").isVisible());assertTrue(pane.lookup("#gs1InboxPanel").isVisible());assertTrue(pane.lookup("#gs1InboxPanel").getBoundsInLocal().getHeight()>100);return null;
        });
    }
    @Test void syncButtonQueuesConfiguredBackgroundReadWithoutOpeningBrowserOrDuplicatingMembershipButton()throws Exception{
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        fx(()->{
            pane.setOnSyncRequested(requests::incrementAndGet);render();
            button("gs1CheckAllButton").fire();
            assertEquals(1,requests.get());assertEquals(0,pane.activeTaskCount());
            assertNull(pane.lookup("#gs1CheckMembership"));
            var statusField=Gs1Pane.class.getDeclaredField("status");statusField.setAccessible(true);var status=(Label)statusField.get(pane);assertEquals(I18nService.getInstance().tr("gs1.auto_sync_queued"),status.getText());
            return null;
        });
    }
    @Test void prefixTableUsesSourcedDatesAndNeverTreatsUnknownAsUnlimitedOrPaid()throws Exception{
        fx(()->{
            var date=LocalDate.now(ZoneId.of("Europe/Moscow"));var explicit=new Gs1Membership.Prefix("4601234",20,List.of(),date.plusMonths(3),false);var inherited=new Gs1Membership.Prefix("4601235",null,List.of());var data=member(true,date.plusYears(1),List.of(explicit,inherited));
            field("membership",data);render();var table=(TableView<Gs1Membership.Prefix>)pane.lookup("#gs1PrefixTable");assertEquals(2,table.getItems().size());assertEquals(4,table.getColumns().size());
            assertEquals(explicit.expiry(),Gs1Pane.prefixValidUntil(explicit,data));assertEquals(data.expiry(),Gs1Pane.prefixValidUntil(inherited,data));
            assertNull(Gs1Pane.prefixValidUntil(inherited,member(true,null,List.of())));
            assertEquals(I18nService.getInstance().tr("gs1.unknown_value"),table.getColumns().get(3).getCellObservableValue(inherited).getValue());
            var unknown=member(true,null,List.of(inherited));field("membership",unknown);render();var unknownTable=(TableView<Gs1Membership.Prefix>)pane.lookup("#gs1PrefixTable");
            assertEquals(1,unknownTable.getItems().size(),"Known prefixes remain visible while membership expiry is unverified");
            assertEquals(I18nService.getInstance().tr("gs1.unknown_value"),unknownTable.getColumns().get(2).getCellObservableValue(inherited).getValue());return null;
        });
    }
    @Test void agreementRemainsUnverifiedInEveryLanguageEvenWithReportedAuthentication()throws Exception{
        var i18n=I18nService.getInstance();var prior=i18n.getCurrentLanguage();
        try{fx(()->{field("czAuthenticated",Boolean.TRUE);for(var language:AppLanguage.values()){
            i18n.setLanguage(language);render();var agreement=(Label)pane.lookup("#gs1ReadinessStateAGREEMENT");assertTrue(agreement.getText().contains(i18n.tr("gs1.readiness.state.UNKNOWN")));assertFalse(agreement.getStyleClass().contains("activation-active"));
            for(String key:List.of("gs1.account_title","gs1.check_all","gs1.inbox_title","gs1.prefix.valid_until","gs1.prefix.source_notice"))assertNotEquals(key,i18n.tr(key));
            assertNotNull(pane.lookup("#gs1InboxPanel"));assertNotNull(pane.lookup("#gs1CheckAllButton"));assertEquals(0,pane.activeTaskCount());
        }return null;});}finally{fx(()->{i18n.setLanguage(prior);return null;});}
    }
    @Test void expiredVerifiedCertificateKeepsItsLocalIdentityAndBlocksFreshWork()throws Exception{
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT OR IGNORE INTO shops(id,name,api_key) VALUES(1,'Fixture','fixture')");}
        var repo=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(1,"Fixture"));repo.saveSettings(repo.getSettings());
        var metadata=new com.google.gson.JsonObject();metadata.addProperty("inn","7707083893");metadata.addProperty("thumbprint","A".repeat(40));metadata.addProperty("hasPrivateKey",true);metadata.addProperty("subject","O=Fixture, INNLE=7707083893");metadata.addProperty("validFrom",Instant.now().minusSeconds(86400).toString());metadata.addProperty("validTo",Instant.now().minusSeconds(60).toString());
        try(var c=Database.getConnection();var p=c.prepareStatement("UPDATE znack_settings SET participant_inn=?,signer_certificate=?,certificate_metadata_json=?,signer_tested_at=? WHERE shop_id=1")){
            p.setString(1,"7707083893");p.setString(2,"A".repeat(40));p.setString(3,metadata.toString());p.setString(4,Instant.now().minusSeconds(3600).toString());p.executeUpdate();
        }
        fx(()->{
            var local=Gs1Pane.class.getDeclaredMethod("context",Shop.class);local.setAccessible(true);field("currentContext",local.invoke(null,new Shop(1,"Fixture","fixture")));
            var readiness=Gs1Pane.class.getDeclaredMethod("readiness");readiness.setAccessible(true);var snapshot=(com.vncode.app.features.gs1.Gs1ReadinessService.Snapshot)readiness.invoke(pane);
            assertEquals("gs1.readiness.signature.expired",snapshot.check(com.vncode.app.features.gs1.Gs1ReadinessService.Kind.SIGNATURE).messageKey());
            var fresh=Gs1Pane.class.getDeclaredMethod("context");fresh.setAccessible(true);var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->fresh.invoke(pane));assertEquals("gs1.no_signer",error.getCause().getMessage());assertEquals(0,pane.activeTaskCount());return null;
        });
    }
    @Test void submittedApplicationIsDisabledInAllFourLanguagesAndOffersInvoiceInquiry()throws Exception{
        var i18n=I18nService.getInstance();var prior=i18n.getCurrentLanguage();
        try{fx(()->{
            field("membership",member(false,null,List.of()));
            field("applicationForm",JsonParser.parseString("{\"applicant\":{\"inn\":\"7707083893\"},\"isSent\":true}").getAsJsonObject());
            for(var language:AppLanguage.values()){
                i18n.setLanguage(language);render();
                assertTrue(button("gs1Join").isDisabled());assertEquals(i18n.tr("gs1.action.APPLICATION_SUBMITTED"),button("gs1Join").getText());
                assertTrue(button("gs1MoreCodes").isDisabled());
                assertNotEquals("gs1.readiness.title",i18n.tr("gs1.readiness.title"));
                assertNotEquals("gs1.document_source_notice",i18n.tr("gs1.document_source_notice"));
            }return null;
        });}finally{fx(()->{i18n.setLanguage(prior);return null;});}
    }
    @Test void dialogsAreCenteredRelativeToTheActualOwnerWindow()throws Exception{
        fx(()->{
            var owner=new Stage();owner.setScene(new Scene(new VBox(),900,650));owner.setX(200);owner.setY(100);owner.show();
            var dialog=new Dialog<Void>();dialog.initOwner(owner);dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);dialog.getDialogPane().setPrefSize(500,300);Gs1Dialogs.centerOnOwner(dialog);
            try{dialog.show();var window=dialog.getDialogPane().getScene().getWindow();
                assertEquals(owner.getX()+(owner.getWidth()-window.getWidth())/2,window.getX(),1);
                assertEquals(owner.getY()+(owner.getHeight()-window.getHeight())/2,window.getY(),1);
            }finally{dialog.close();owner.close();}return null;
        });
    }
    @Test void automaticReminderRequiresTheDisplayedConsentAndCancelLeavesItDisabled()throws Exception{
        var request=fx(()->{
            var repoField=Gs1Pane.class.getDeclaredField("repository");repoField.setAccessible(true);var repo=(com.vncode.app.features.gs1.Gs1Repository)repoField.get(pane);
            var row=repo.createRequest("7707083893","MORE_CODES","markirovka@gs1ru.org","Fixture prefix","Reviewed original");
            repo.setState(row.inn(),row.id(),com.vncode.app.features.gs1.Gs1Repository.State.SENT);
            repo.addMessage(row.inn(),row.id(),"fixture-sent",true,"sender@fixture.ru",row.body(),false);
            repo.saveMailAccount(row.inn(),new com.vncode.app.integration.gs1.Gs1MailAccount("smtp.fixture",465,"imap.fixture",993,"user","sender@fixture.ru","dpapi:fixture"));
            var current=repo.request(row.inn(),row.id());var show=Gs1Pane.class.getDeclaredMethod("showThread",com.vncode.app.features.gs1.Gs1Repository.Request.class);show.setAccessible(true);show.invoke(pane,current);return current;
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean displayed=false;
        while(!displayed&&System.nanoTime()<deadline){displayed=fx(()->{pane.applyCss();pane.layout();return pane.lookup("#gs1AutoReminder")!=null;});if(!displayed)Thread.sleep(10);}
        assertTrue(displayed);
        var checkbox=fx(()->(javafx.scene.control.CheckBox)pane.lookup("#gs1AutoReminder"));
        assertFalse(fx(checkbox::isSelected));
        com.vncode.app.testsupport.FxDialogDriver.choose(checkbox::fire,
            dialog->"gs1AutoReminderConsent".equals(dialog.getId()),dialog->{
                assertTrue(dialog.getContent().lookupAll(".text-area").stream().anyMatch(javafx.scene.control.TextArea.class::isInstance),"The reviewed reminder body is displayed before consent");
                return ButtonType.CANCEL;
            });
        fx(()->{
            assertFalse(checkbox.isSelected());
            var repoField=Gs1Pane.class.getDeclaredField("repository");repoField.setAccessible(true);var repo=(com.vncode.app.features.gs1.Gs1Repository)repoField.get(pane);
            assertTrue(repo.reminderConsent(request.inn(),request.id()).isEmpty());return null;
        });
    }
    private Gs1Membership member(boolean active,LocalDate expiry,List<Gs1Membership.Prefix> prefixes){return new Gs1Membership("7707083893","Fixture",active,false,expiry,prefixes,Instant.now(),Gs1Membership.CATALOG_SOURCE);}
    private Button button(String id){return (Button)pane.lookup("#"+id);}
    private void render()throws Exception{var method=Gs1Pane.class.getDeclaredMethod("render");method.setAccessible(true);method.invoke(pane);pane.applyCss();pane.layout();}
    private void field(String name,Object value)throws Exception{var field=Gs1Pane.class.getDeclaredField(name);field.setAccessible(true);field.set(pane,value);}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS);}
}
