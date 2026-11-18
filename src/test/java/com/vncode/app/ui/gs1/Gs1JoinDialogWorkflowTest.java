package com.vncode.app.ui.gs1;

import com.google.gson.*;
import com.vncode.app.config.Database;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Gs1JoinDialogWorkflowTest {
    @TempDir Path data;
    @BeforeAll static void toolkit(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    @BeforeEach void setup(){System.setProperty("vncode.appdata.dir",data.toString());Database.initDatabase();}
    @AfterEach void cleanup(){System.clearProperty("vncode.appdata.dir");}
    @Test void CannotAdvanceWithoutTheCurrentStepAndSigningIsReservedForTheReviewStep()throws Exception{
        fx(()->{
            var editor=editor(new JsonObject());var pane=dialog(editor).getDialogPane();
            Button next=button(editor,"gs1JoinNext"),back=button(editor,"gs1JoinBack");
            assertNotNull(next,"The six-step workflow needs an explicit validated next action");assertNotNull(back);
            assertTrue(back.isDisabled());next.fire();assertEquals(0,steps(editor).getSelectionModel().getSelectedIndex());
            assertFalse(((Label)field(editor,"errors")).getText().isBlank());return null;
        });
    }
    @Test void EditingAnAddressClearsAllPreviouslyVerifiedAddressIdentifiers()throws Exception{
        fx(()->{
            var editor=editor(completeForm());
            var legal=internalForm(editor).getAsJsonObject("addresses").getAsJsonObject("legalAddress");
            var input=all(dialog(editor).getDialogPane().getContent()).stream().filter(TextField.class::isInstance).map(TextField.class::cast)
                    .filter(t->t.getText().equals("Verified legal address")).findFirst().orElseThrow();
            input.setText("Changed address");assertFalse(legal.has("fullGuid"));assertFalse(legal.has("houseGuid"));
            assertFalse(legal.has("postalCode"),"A postcode belongs to the old verified address too");return null;
        });
    }
    @Test void ProductionAddressRequiresTheSameVerifiedRussianAddressEvidenceAsTheOtherAddresses()throws Exception{
        fx(()->{
            var form=completeForm();var production=form.getAsJsonObject("addresses").getAsJsonArray("productionAddresses").get(0).getAsJsonObject();
            production.remove("fullGuid");var editor=editor(form);var valid=Gs1JoinDialog.class.getDeclaredMethod("valid");valid.setAccessible(true);
            assertEquals(false,valid.invoke(editor),"A nonblank production address without a selected FIAS suggestion cannot be submitted");return null;
        });
    }
    @Test void CompleteFormReachesReviewAndPreservesUnknownServerDataWithoutEditingTheOriginal()throws Exception{
        fx(()->{
            var original=completeForm();var before=original.deepCopy();var saved=new java.util.concurrent.atomic.AtomicReference<JsonObject>();
            var editor=new Gs1JoinDialog(new Gs1Pane(),"7701234567",original,Map.of(),q->List.of(),(form,sign)->{assertTrue(sign);saved.set(form);});
            var pane=dialog(editor).getDialogPane();var submit=pane.getButtonTypes().stream().filter(t->t.getText().equals(com.vncode.app.shared.I18nService.getInstance().tr("gs1.wizard.apply"))).findFirst().orElseThrow();
            assertEquals(5,steps(editor).getSelectionModel().getSelectedIndex());assertNull(saved.get(),"A ready profile is reviewed before Apply; opening it must not save or sign");
            button(editor,"gs1JoinBack").fire();assertEquals(4,steps(editor).getSelectionModel().getSelectedIndex());assertTrue(pane.lookupButton(submit).isDisabled());
            button(editor,"gs1JoinNext").fire();assertEquals(5,steps(editor).getSelectionModel().getSelectedIndex());
            assertFalse(pane.lookupButton(submit).isDisabled());((Button)pane.lookupButton(submit)).fire();assertNotNull(saved.get());
            assertEquals("unchanged",saved.get().getAsJsonObject("retainedServerField").get("value").getAsString());assertEquals(before,original);return null;
        });
    }
    @Test void CopyLegalAddressRetainsVerifiedIdentifiersAndDoesNotAliasTheOriginal()throws Exception{
        fx(()->{
            var original=completeForm();var editor=editor(original);var internal=internalForm(editor);var physical=internal.getAsJsonObject("addresses").getAsJsonObject("physicalAddress");physical.addProperty("retainedServerAddressField","keep");
            button(editor,"gs1CopyLegalAddress").fire();
            assertEquals("legal-guid",physical.get("fullGuid").getAsString());assertEquals("legal-house",physical.get("houseGuid").getAsString());assertEquals("123456",physical.get("postalCode").getAsString());assertEquals("keep",physical.get("retainedServerAddressField").getAsString());
            assertEquals("physical-guid",original.getAsJsonObject("addresses").getAsJsonObject("physicalAddress").get("fullGuid").getAsString());return null;
        });
    }
    @Test void AddingThenRemovingProductionAddressDoesNotLeaveInvisibleValidationFields()throws Exception{
        fx(()->{
            var editor=editor(completeForm());var pane=dialog(editor).getDialogPane();var production=internalForm(editor).getAsJsonObject("addresses").getAsJsonArray("productionAddresses");
            var before=production.get(0);button(editor,"gs1AddProductionAddress").fire();assertEquals(2,production.size());
            var remove=all(pane.getContent()).stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b->b.getText().equals(com.vncode.app.shared.I18nService.getInstance().tr("gs1.remove_production_address"))).toList();
            remove.getLast().fire();assertEquals(1,production.size());assertSame(before,production.get(0));
            var valid=Gs1JoinDialog.class.getDeclaredMethod("valid");valid.setAccessible(true);assertEquals(true,valid.invoke(editor));return null;
        });
    }
    @Test void ChangingCountryInvalidatesThePreviouslyVerifiedAddress()throws Exception{
        fx(()->{
            var editor=editor(completeForm());var legal=internalForm(editor).getAsJsonObject("addresses").getAsJsonObject("legalAddress");
            var location=all(dialog(editor).getDialogPane().getContent()).stream().filter(TextField.class::isInstance).map(TextField.class::cast).filter(t->t.getText().equals("Verified legal address")).findFirst().orElseThrow();
            var section=(javafx.scene.layout.VBox)location.getParent().getParent();
            var country=all(section).stream().filter(TextField.class::isInstance).map(TextField.class::cast).filter(t->t.getText().equals("RU")).findFirst().orElseThrow();
            country.setText("KZ");assertFalse(legal.has("fullGuid"));assertFalse(legal.has("houseGuid"));assertFalse(legal.has("postalCode"));return null;
        });
    }
    @Test void AnUnknownMarkingAnswerRequiresAnExplicitSellerChoiceInsteadOfBecomingFalse()throws Exception{
        fx(()->{
            var source=completeForm();source.getAsJsonObject("applicant").remove("isMarked");var editor=editor(source);
            var answer=all(dialog(editor).getDialogPane().getContent()).stream().filter(CheckBox.class::isInstance).map(CheckBox.class::cast).filter(box->"gs1ApplicantMarked".equals(box.getId())).findFirst().orElseThrow();
            assertTrue(answer.isIndeterminate());assertFalse(internalForm(editor).getAsJsonObject("applicant").has("isMarked"));
            button(editor,"gs1JoinNext").fire();assertEquals(0,steps(editor).getSelectionModel().getSelectedIndex());
            answer.fire();assertFalse(answer.isIndeterminate());assertTrue(internalForm(editor).getAsJsonObject("applicant").get("isMarked").getAsBoolean());return null;
        });
    }
    @Test void ScrollingALongStepCannotMoveItsTabStripIntoABlankWholeFormTail()throws Exception{
        fx(()->{
            var choices=new ArrayList<com.vncode.app.integration.gs1.NationalCatalogGs1Client.Choice>();for(int index=0;index<25;index++)choices.add(new com.vncode.app.integration.gs1.NationalCatalogGs1Client.Choice("sales-"+index,"Synthetic sales method "+index));
            var editor=new Gs1JoinDialog(new Gs1Pane(),"7701234567",completeForm(),Map.of("selling-ways",choices),q->List.of(),(form,sign)->fail("Scrolling cannot submit a form"));
            var dialog=dialog(editor);dialog.show();try{
                var pane=dialog.getDialogPane();steps(editor).getSelectionModel().select(3);pane.applyCss();pane.layout();
                if(pane.getContent() instanceof ScrollPane outer){outer.setVvalue(1);outer.layout();}
                var scroll=(ScrollPane)steps(editor).getTabs().get(3).getContent();scroll.setVvalue(1);pane.layout();scroll.layout();pane.layout();
                var frame=pane.localToScene(pane.getBoundsInLocal());var tabs=steps(editor).localToScene(steps(editor).getBoundsInLocal());
                assertTrue(tabs.getMinY()>=frame.getMinY()-1,"The step tabs must remain visible after scrolling to the final field");
                assertTrue(tabs.getMaxY()<=frame.getMaxY()+1,"The working step must stay inside the dialog viewport");
            }finally{dialog.close();}return null;
        });
    }
    @Test void ALookupCompletingAfterCopyDoesNotOpenAnObsoleteSuggestionOrChangeTheCopiedAddress()throws Exception{
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var popups=new java.util.concurrent.atomic.AtomicInteger();
        var editor=fx(()->{
            var value=new Gs1JoinDialog(new Gs1Pane(),"7701234567",completeForm(),Map.of(),query->{
                started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new java.io.IOException("Test lookup release timed out");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                return List.of(new com.vncode.app.integration.gs1.NationalCatalogGs1Client.Address("Obsolete suggestion","old-guid","old-house","999999"));
            },(form,sign)->fail("Address lookup cannot submit a form"));dialog(value).show();return value;
        });
        javafx.collections.ListChangeListener<javafx.stage.Window> watcher=change->{while(change.next())for(var window:change.getAddedSubList()){
            popups.incrementAndGet();Platform.runLater(()->{if(window.getScene()!=null&&window.getScene().lookup(".dialog-pane") instanceof DialogPane pane){var cancel=pane.getButtonTypes().stream().filter(t->t.getButtonData().isCancelButton()).findFirst();cancel.ifPresent(t->((Button)pane.lookupButton(t)).fire());}});
        }};
        try{
            fx(()->{
                javafx.stage.Window.getWindows().addListener(watcher);
                var location=all(dialog(editor).getDialogPane().getContent()).stream().filter(TextField.class::isInstance).map(TextField.class::cast).filter(t->t.getText().equals("Physical address")).findFirst().orElseThrow();
                var section=(javafx.scene.layout.VBox)location.getParent().getParent();
                all(section).stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getText().equals(com.vncode.app.shared.I18nService.getInstance().tr("gs1.find_address"))).findFirst().orElseThrow().fire();return null;
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));fx(()->{button(editor,"gs1CopyLegalAddress").fire();return null;});release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(com.vncode.app.shared.AppTaskExecutor.hasRunningTasks()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(com.vncode.app.shared.AppTaskExecutor.hasRunningTasks());
            fx(()->{assertEquals(0,popups.get(),"An obsolete lookup must not offer choices for a replaced editor");assertEquals("legal-guid",internalForm(editor).getAsJsonObject("addresses").getAsJsonObject("physicalAddress").get("fullGuid").getAsString());return null;});
        }finally{release.countDown();fx(()->{javafx.stage.Window.getWindows().removeListener(watcher);dialog(editor).close();return null;});}
    }
    private static Gs1JoinDialog editor(JsonObject form){return new Gs1JoinDialog(new Gs1Pane(),"7701234567",form,Map.of(),q->List.of(),(f,s)->fail("Review/navigation must not submit a form"));}
    private static Button button(Gs1JoinDialog editor,String id)throws Exception{
        var pane=dialog(editor).getDialogPane();
        for(var type:pane.getButtonTypes())if(pane.lookupButton(type) instanceof Button button&&id.equals(button.getId()))return button;
        return all(pane.getContent()).stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->id.equals(b.getId())).findFirst().orElse(null);
    }
    private static JsonObject completeForm(){return JsonParser.parseString("""
        {"applicant":{"inn":"7701234567","companyName":"Fixture company","companyNameShort":"Fixture","countryId":"RU","firstName":"Test","lastName":"Seller","email":"fixture@example.invalid","phone":"+70000000000","ogrn":"1234567890123","kpp":"770101001","okved2":"47.91","opf":"fixture-opf","position":"fixture-position","isMarked":true},
         "bankDetails":{"name":"Fixture bank","bik":"123456789","settlementAccount":"12345678901234567890","noCorrespondentAccount":true},
         "addresses":{"legalAddress":{"countryId":"RU","fullAddress":"Verified legal address","fullGuid":"legal-guid","houseGuid":"legal-house","postalCode":"123456"},"physicalAddress":{"countryId":"RU","fullAddress":"Physical address","fullGuid":"physical-guid"},"productionAddresses":[{"countryId":"RU","fullAddress":"Production address","fullGuid":"production-guid"}]},
         "companyDetails":{"enterpriseSector":"fixture","mainProductGCP":"fixture","obtainingDocumentsMethod":"PAPER","productsCountToMark":"less1000","accountType":["fixture-account"],"partisipationInGovernmentProjects":[{"label":"Fixture participation","value":"fixture-participation","checked":true}],"implementationMethods":{"fields":[{"label":"Fixture sales","value":"fixture-sales","checked":true}]},"distributionChannels":{"fields":[{"label":"Fixture distribution","value":"fixture-distribution","checked":true}]}},
         "contacts":[{"lastName":"Seller","firstName":"Test","email":"fixture@example.invalid","phone":"+70000000000","position":"fixture-position"}],"retainedServerField":{"value":"unchanged"}}
        """).getAsJsonObject();}
    private static List<Node> all(Node node){var result=new ArrayList<Node>();result.add(node);if(node instanceof ScrollPane scroll)result.addAll(all(scroll.getContent()));else if(node instanceof TabPane tabs)for(var tab:tabs.getTabs())result.addAll(all(tab.getContent()));else if(node instanceof Parent parent)for(var child:parent.getChildrenUnmodifiable())result.addAll(all(child));return result;}
    private static Object field(Object value,String key)throws Exception{var f=value.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(value);}
    @SuppressWarnings("unchecked")private static Dialog<Void> dialog(Gs1JoinDialog editor)throws Exception{return (Dialog<Void>)field(editor,"dialog");}
    private static TabPane steps(Gs1JoinDialog editor)throws Exception{return (TabPane)field(editor,"steps");}
    private static JsonObject internalForm(Gs1JoinDialog editor)throws Exception{return (JsonObject)field(editor,"form");}
    private static <T>T fx(Callable<T> work)throws Exception{var future=new FutureTask<>(work);Platform.runLater(future);try{return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Error cause)throw cause;if(failed.getCause() instanceof Exception cause)throw cause;throw failed;}}
}
