package com.vncode.app.ui.gs1;

import com.vncode.app.config.Database;
import com.vncode.app.features.gs1.*;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.OperatorAgreement;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises native UI consent and state with synthetic PDF/transport inputs, never a real signer/API. */
class Gs1AgreementDialogTest {
    @TempDir Path directory;
    private final OperatorAgreement first=new OperatorAgreement("11","Synthetic agreement A","a.pdf","2026-11-17",1,1,null);
    private final OperatorAgreement second=new OperatorAgreement("12","Synthetic agreement B","b.pdf","2026-11-17",1,2,null);
    private final AtomicInteger signatures=new AtomicInteger(),reconciliations=new AtomicInteger(),observations=new AtomicInteger(),preparations=new AtomicInteger();
    private final AtomicBoolean current=new AtomicBoolean(true);
    private final AtomicReference<byte[]> signedBytes=new AtomicReference<>();
    private boolean failSubmission,cancelBeforeSubmission;
    private final AtomicReference<String> preparedRequest=new AtomicReference<>();
    private Shop shop;
    private byte[] pdf;
    private Gs1AgreementDialog controller;
    private Dialog<?> dialog;
    private AppLanguage priorLanguage;

    @BeforeAll static void startFx(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setUp()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var source=new PDDocument();var output=new ByteArrayOutputStream()){source.addPage(new PDPage());source.addPage(new PDPage());source.save(output);pdf=output.toByteArray();}
        fx(()->{
            priorLanguage=I18nService.getInstance().getCurrentLanguage();I18nService.getInstance().setLanguage(AppLanguage.VI);
            controller=new Gs1AgreementDialog(null,List.of(first,second),new Gs1AgreementDialog.Operations(){
                public Gs1AgreementService.Prepared prepare(OperatorAgreement document)throws Exception{
                    var prepared=Gs1AgreementTestFixture.prepare(new Gs1Repository(),shop,document,pdf);preparedRequest.set(prepared.requestId());preparations.incrementAndGet();return prepared;
                }
                public byte[] pdf(OperatorAgreement document){return pdf.clone();}
                public Gs1AgreementService.Result sign(Gs1AgreementService.Prepared document,boolean consent)throws Exception{
                    assertTrue(consent);signatures.incrementAndGet();signedBytes.set(document.pdf());
                    var repository=new Gs1Repository();assertTrue(repository.claimSend("7700000000",document.requestId()));
                    if(cancelBeforeSubmission){repository.releaseUnsent("7700000000",document.requestId());throw new IOException("synthetic signer cancelled before POST");}
                    if(failSubmission){repository.setState("7700000000",document.requestId(),Gs1Repository.State.RECONCILE_REQUIRED);throw new IOException("synthetic uncertain submission");}
                    repository.setState("7700000000",document.requestId(),Gs1Repository.State.SENT);
                    return new Gs1AgreementService.Result(document.requestId(),document.agreement(),Gs1AgreementService.State.PENDING);
                }
                public Gs1AgreementService.Result reconcile(String requestId){reconciliations.incrementAndGet();return new Gs1AgreementService.Result(requestId,first,Gs1AgreementService.State.PENDING);}
                public void requireCurrent(){if(!current.get())throw new IllegalStateException("GS1_AGREEMENT_ACCOUNT_CHANGED");}
            },result->observations.incrementAndGet());
            var field=Gs1AgreementDialog.class.getDeclaredField("dialog");field.setAccessible(true);dialog=(Dialog<?>)field.get(controller);dialog.show();return null;
        });
    }
    @AfterEach void tearDown()throws Exception{
        fx(()->{if(dialog!=null)dialog.close();if(priorLanguage!=null)I18nService.getInstance().setLanguage(priorLanguage);return null;});com.vncode.app.testsupport.ShopReadTestCleanup.awaitSharedIdle();System.clearProperty("vncode.appdata.dir");
    }
    @Test void signingRequiresTheActualPdfThenExplicitConsentAndPreservesItsBytes()throws Exception{
        fx(()->{assertTrue(button("gs1AgreementSign").isDisabled());assertTrue(consent().isDisabled());assertEquals(0,signatures.get());button("gs1AgreementOpenPdf").fire();return null;});
        awaitPdf();
        fx(()->{assertFalse(consent().isDisabled());assertFalse(consent().isSelected());assertTrue(button("gs1AgreementSign").isDisabled());capture("agreement-preview");consent().fire();assertFalse(button("gs1AgreementSign").isDisabled());capture("agreement-consent");button("gs1AgreementSign").fire();return null;});
        await(()->signatures.get()==1&&observations.get()==1);
        assertArrayEquals(pdf,signedBytes.get(),"The source PDF is signed, never the raster preview or an amended document");
        fx(()->{assertTrue(button("gs1AgreementSign").isDisabled());assertFalse(button("gs1AgreementReconcile").isDisabled());capture("agreement-pending");button("gs1AgreementSign").fire();assertEquals(1,signatures.get());return null;});
    }
    @Test void aLostSubmissionResponseOffersReadOnlyReconciliationInsteadOfSigningAgain()throws Exception{
        failSubmission=true;openPdf();
        fx(()->{consent().fire();button("gs1AgreementSign").fire();return null;});
        await(()->signatures.get()==1&&!button("gs1AgreementReconcile").isDisabled());
        String request=preparedRequest.get();
        fx(()->{assertTrue(button("gs1AgreementSign").isDisabled());button("gs1AgreementOpenPdf").fire();return null;});
        await(()->preparations.get()==2&&!button("gs1AgreementOpenPdf").isDisabled()&&((ImageView)dialog.getDialogPane().lookup("#gs1AgreementPdf")).getImage()!=null);
        assertEquals(request,preparedRequest.get());assertEquals(Gs1Repository.State.RECONCILE_REQUIRED,new Gs1Repository().request("7700000000",request).state());
        fx(()->{assertTrue(consent().isDisabled());assertFalse(consent().isSelected());assertTrue(button("gs1AgreementSign").isDisabled());assertFalse(button("gs1AgreementReconcile").isDisabled());button("gs1AgreementSign").fire();button("gs1AgreementReconcile").fire();return null;});
        await(()->reconciliations.get()==1&&observations.get()==1);assertEquals(1,signatures.get());
    }
    @Test void cancellingBeforePostCanRetryTheExactDurableDraftOnlyAfterReopeningAndFreshConsent()throws Exception{
        cancelBeforeSubmission=true;openPdf();String request=preparedRequest.get();
        fx(()->{consent().fire();button("gs1AgreementSign").fire();return null;});
        await(()->signatures.get()==1&&!button("gs1AgreementReconcile").isDisabled());
        assertEquals(Gs1Repository.State.DRAFT,new Gs1Repository().request("7700000000",request).state());
        fx(()->{assertTrue(consent().isDisabled());assertFalse(consent().isSelected());assertTrue(button("gs1AgreementSign").isDisabled());return null;});
        cancelBeforeSubmission=false;
        fx(()->{button("gs1AgreementOpenPdf").fire();return null;});
        await(()->preparations.get()==2&&!button("gs1AgreementOpenPdf").isDisabled()&&((ImageView)dialog.getDialogPane().lookup("#gs1AgreementPdf")).getImage()!=null);
        assertEquals(request,preparedRequest.get(),"Reopening must restore the exact persisted draft, never create a second request");
        fx(()->{assertFalse(consent().isDisabled(),"A fresh durable DRAFT proof must release the stale in-dialog submission marker");assertFalse(consent().isSelected());assertTrue(button("gs1AgreementSign").isDisabled());assertTrue(button("gs1AgreementReconcile").isDisabled());assertEquals(1,signatures.get());consent().fire();button("gs1AgreementSign").fire();return null;});
        await(()->signatures.get()==2&&observations.get()==1);
        assertEquals(Gs1Repository.State.SENT,new Gs1Repository().request("7700000000",request).state());
        assertEquals(1,new Gs1Repository().requests("7700000000").size());assertEquals(0,reconciliations.get());
    }
    @Test void changingTheDocumentClearsThePdfAndItsConsent()throws Exception{
        openPdf();fx(()->{consent().fire();assertFalse(button("gs1AgreementSign").isDisabled());list().getSelectionModel().select(1);
            assertNull(((ImageView)dialog.getDialogPane().lookup("#gs1AgreementPdf")).getImage());assertFalse(consent().isSelected());assertTrue(consent().isDisabled());assertTrue(button("gs1AgreementSign").isDisabled());assertEquals(0,signatures.get());return null;});
    }
    @Test void everyPdfPageCanBeViewedAndMovingPagesClearsConsent()throws Exception{
        openPdf();fx(()->{assertFalse(button("gs1AgreementNextPage").isDisabled());consent().fire();button("gs1AgreementNextPage").fire();assertFalse(consent().isSelected());return null;});
        awaitPdf();fx(()->{assertFalse(button("gs1AgreementPreviousPage").isDisabled());assertTrue(button("gs1AgreementNextPage").isDisabled());assertTrue(button("gs1AgreementSign").isDisabled());assertEquals(0,signatures.get());return null;});
    }
    @Test void anAccountChangeBetweenPreviewAndSigningPreventsTheSigningCallback()throws Exception{
        openPdf();current.set(false);fx(()->{consent().fire();button("gs1AgreementSign").fire();return null;});
        await(()->!button("gs1AgreementReconcile").isDisabled());assertEquals(0,signatures.get());assertEquals(0,observations.get());
        fx(()->{assertTrue(button("gs1AgreementSign").isDisabled());return null;});
    }
    @Test void anAlreadySignedDocumentCanBeReadWithoutPreparingAnotherSignature()throws Exception{
        var signed=new OperatorAgreement("13","Synthetic signed agreement","signed.pdf","2026-11-17",2,1,4);
        fx(()->{list().getItems().add(signed);list().getSelectionModel().select(signed);button("gs1AgreementOpenPdf").fire();return null;});
        await(()->((ImageView)dialog.getDialogPane().lookup("#gs1AgreementPdf")).getImage()!=null&&!button("gs1AgreementOpenPdf").isDisabled());
        fx(()->{assertTrue(consent().isDisabled());assertTrue(button("gs1AgreementSign").isDisabled());assertFalse(button("gs1AgreementSavePdf").isDisabled());assertEquals(0,signatures.get());return null;});
    }
    @Test void closeUsesTheCurrentLanguageAndRetainsCancelSemantics()throws Exception{
        fx(()->{var close=dialog.getDialogPane().getButtonTypes().stream().filter(type->type.getButtonData().isCancelButton()).findFirst().orElseThrow();
            assertEquals(I18nService.getInstance().tr("common.close"),close.getText());((Button)dialog.getDialogPane().lookupButton(close)).fire();assertFalse(dialog.isShowing());assertEquals(0,signatures.get());return null;});
    }
    private void openPdf()throws Exception{fx(()->{button("gs1AgreementOpenPdf").fire();return null;});awaitPdf();}
    private void awaitPdf()throws Exception{await(()->!consent().isDisabled()&&((ImageView)dialog.getDialogPane().lookup("#gs1AgreementPdf")).getImage()!=null);}
    private void await(BooleanSupplier condition)throws Exception{long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<limit){if(fx(condition::getAsBoolean))return;Thread.sleep(10);}fail("Expected agreement UI state was not reached");}
    private Button button(String id){return (Button)dialog.getDialogPane().lookup("#"+id);}
    private CheckBox consent(){return (CheckBox)dialog.getDialogPane().lookup("#gs1AgreementConsent");}
    private void capture(String name)throws Exception{
        String destination=System.getProperty("vncode.gs1.capture.dir");if(destination==null)return;
        var pane=dialog.getDialogPane();pane.applyCss();pane.layout();var frame=pane.snapshot(null,null);
        var png=new java.awt.image.BufferedImage((int)frame.getWidth(),(int)frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,frame.getPixelReader().getArgb(x,y));
        java.nio.file.Files.createDirectories(Path.of(destination));javax.imageio.ImageIO.write(png,"png",Path.of(destination,name+".png").toFile());
    }
    @SuppressWarnings("unchecked")private ListView<OperatorAgreement> list(){return (ListView<OperatorAgreement>)dialog.getDialogPane().lookup("#gs1AgreementList");}
    private static <T>T fx(java.util.concurrent.Callable<T> work)throws Exception{return Gs1AgreementPaneTest.fx(work);}
}
