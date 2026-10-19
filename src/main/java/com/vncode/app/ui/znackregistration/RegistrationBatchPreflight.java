package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.tnved.*;
import com.vncode.app.features.sync.RegistrationSources;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import java.util.*;

/** Complete missing marketplace fields in one table before allocating a GS1 number. */
final class RegistrationBatchPreflight {
    record Finding(String field,ProductKey key,String article,String before,String suggestion,String category){
        Finding(String field,ProductKey key,String article,String before,String suggestion){this(field,key,article,before,suggestion,"");}
    }
    record Review(List<Finding> findings){Review{findings=List.copyOf(findings);}List<ProductKey> keys(String field){return findings.stream().filter(f->f.field().equals(field)).map(Finding::key).distinct().toList();}}
    static Map<Finding,String> initialValues(Review review){var values=new LinkedHashMap<Finding,String>();review.findings().forEach(f->values.put(f,f.field().equals("documents")?"":f.suggestion().isBlank()?f.before():f.suggestion()));return values;}
    static Review read(Shop owner,List<ProductKey> selected){
        ListingIdentity.require(owner);var findings=new ArrayList<Finding>();var repo=new ProductRepository();var advisor=new ProductTnvedAdvisor();var cards=new HashSet<String>();
        var sources=new RegistrationSources();boolean sourceReady=sources.check(owner).ready();
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings();
        for(var key:selected){ListingIdentity.require(owner,key);if(!cards.add(key.cardId()))continue;
            var snapshot=repo.localSnapshot(key).orElse(null);if(snapshot==null)continue;
            if(!ListingIdentity.fingerprint(owner).equals(snapshot.metadata().get("owner"))){if(!sourceReady)continue;sources.requireMember(owner,key.cardId(),key.variantId());}
            if(snapshot.brand()==null||snapshot.brand().isBlank())findings.add(new Finding("brand",key,snapshot.vendorArticle(),"","",snapshot.categoryId()));
            if(com.vncode.app.integration.znack.registration.ZnackRegistrationDocuments.select(snapshot,settings,java.time.LocalDate.now()).candidates().isEmpty())findings.add(new Finding("documents",key,snapshot.vendorArticle(),"","",snapshot.categoryId()));
            for(String field:List.of("gender","material"))if(snapshot.attributes().stream().filter(a->a.complexId().isBlank()||a.complexId().equals("0")).filter(a->ProductFieldNames.matches(field,a.name())).flatMap(a->a.values().stream()).allMatch(String::isBlank))findings.add(new Finding(field,key,snapshot.vendorArticle(),"","",snapshot.categoryId()));
            var proposal=advisor.propose(snapshot);
            String gender=snapshot.attributes().stream().filter(a->ProductFieldNames.matches("gender",a.name())).flatMap(a->a.values().stream()).collect(java.util.stream.Collectors.joining(" "));
            boolean tariffConflict=!com.vncode.app.integration.znack.registration.RegistrationTariffRules.genderProblem(ProductFieldNames.tnvedCode(proposal.current()),gender).isBlank();
            // A category/material suggestion does not establish that a seller's valid code is wrong.
            if(!ProductFieldNames.tnvedCode(proposal.current()).matches("[0-9]{10}")||tariffConflict)
                findings.add(new Finding("tnved",key,snapshot.vendorArticle(),proposal.current(),proposal.unique()?proposal.candidates().getFirst().code():"",snapshot.categoryId()));
        }
        return new Review(findings);
    }
    static boolean show(javafx.stage.Window window,Shop owner,Review review){
        if(review.findings().isEmpty())return true;
        return new Editor(window,owner,review).show();
    }
    private static final class Editor {
        final Shop owner;final Review review;final Dialog<Boolean> dialog=new Dialog<>();
        final TableView<Finding> table=new TableView<>();final Label status=new Label();
        final Map<Finding,String> values=new LinkedHashMap<>();final Map<String,List<String>> suggestions=new HashMap<>();
        final ButtonType applyType=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);
        final RegistrationPreflightCorrections corrections=new RegistrationPreflightCorrections();
        final javafx.animation.PauseTransition retry=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        RegistrationPreflightCorrections.Session session;volatile boolean disposed;boolean busy;int polls;
        Editor(javafx.stage.Window window,Shop owner,Review review){
            this.owner=owner;this.review=review;if(window!=null)dialog.initOwner(window);
            dialog.setTitle(tr("znack.registration.batch_preflight"));dialog.setHeaderText(tr("znack.registration.complete_missing_hint"));
            values.putAll(initialValues(review));
            table.getItems().setAll(review.findings());table.setPrefHeight(390);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            column(table,"Article",Finding::article);column(table,tr("znack.registration.complete_missing_field"),f->tr("products.edit_"+f.field()));column(table,tr("edit.before"),Finding::before);
            var input=new TableColumn<Finding,Finding>(tr("znack.registration.complete_missing_value"));input.setCellValueFactory(row->new javafx.beans.property.ReadOnlyObjectWrapper<>(row.getValue()));input.setPrefWidth(330);input.setMinWidth(180);
            input.setCellFactory(c->new TableCell<>(){
                final ComboBox<String> box=new ComboBox<>();Finding active;boolean rendering;
                {box.setEditable(true);box.setMaxWidth(Double.MAX_VALUE);box.setId("registrationMissingValue");box.setPromptText(tr("znack.registration.complete_missing_value"));
                 box.getEditor().textProperty().addListener((o,a,b)->{if(!rendering&&active!=null)values.put(active,b);});box.valueProperty().addListener((o,a,b)->{if(!rendering&&active!=null&&b!=null)values.put(active,b);});}
                protected void updateItem(Finding finding,boolean empty){super.updateItem(finding,empty);active=empty?null:finding;if(active==null){setGraphic(null);return;}
                    if(active.field().equals("documents")){
                        var target=active;var edit=new Button(tr("products.edit_documents"));edit.setId("registrationMissingDocuments");edit.setOnAction(e->completeDocuments(target));
                        var read=new Button(tr("znack.registration.refresh"));read.setOnAction(e->verifyDocuments(target));
                        var saved=new Label(values.getOrDefault(target,""));saved.setWrapText(true);setGraphic(new VBox(4,new HBox(8,edit,read),saved));return;
                    }
                    rendering=true;var options=new LinkedHashSet<String>();if(!active.suggestion().isBlank())options.add(active.suggestion());options.addAll(suggestions.getOrDefault(group(active),List.of()));box.getItems().setAll(options);box.setValue(values.getOrDefault(active,""));box.getEditor().setText(values.getOrDefault(active,""));rendering=false;setGraphic(box);}
            });table.getColumns().add(input);status.setWrapText(true);status.setId("registrationMissingStatus");
            var hint=new Label(tr("znack.registration.missing_suggestions_hint"));hint.setWrapText(true);
            var content=new VBox(10,hint,table,status);VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(880,550);dialog.getDialogPane().setId("registrationMissingFieldsDialog");
            dialog.getDialogPane().getButtonTypes().setAll(applyType,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));var apply=(Button)dialog.getDialogPane().lookupButton(applyType);apply.setId("registrationMissingApply");apply.getStyleClass().add("btn-primary");
            apply.addEventFilter(javafx.event.ActionEvent.ACTION,e->{e.consume();if(!busy)apply();});dialog.setResultConverter(button->false);
            dialog.setOnHidden(e->{disposed=true;retry.stop();});retry.setOnFinished(e->advance());DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        }
        boolean show(){loadSuggestions();return dialog.showAndWait().orElse(false);}
        void loadSuggestions(){
            var groups=new LinkedHashMap<String,Finding>();review.findings().forEach(f->groups.putIfAbsent(group(f),f));
            var task=new Task<Void>(){protected Void call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){
                var service=new ProductSuggestionService();for(var entry:groups.entrySet()){
                    if(disposed)return null;if(entry.getValue().field().equals("documents"))continue;try{var found=service.suggest(owner,List.of(entry.getValue().key()),entry.getValue().field(),"",partial->offer(entry.getKey(),partial.values()));offer(entry.getKey(),found.values());}catch(Exception unavailable){/* Manual input remains available. */}
                }return null;
            }}};AppTaskExecutor.executeCatalog(task,error->{});
        }
        void offer(String key,List<String> options){Platform.runLater(()->{if(!disposed&&!busy){suggestions.put(key,List.copyOf(options));table.refresh();}});}
        void completeDocuments(Finding finding){
            if(disposed||busy)return;
            com.vncode.app.ui.products.ProductBulkEditPane.showDialog(dialog.getDialogPane().getScene().getWindow(),owner,"documents",List.of(finding.key()),()->{
                if(!disposed)verifyDocuments(finding);
            });
        }
        void verifyDocuments(Finding finding){
            if(disposed||busy)return;values.put(finding,"");busy=true;table.setDisable(true);dialog.getDialogPane().lookupButton(applyType).setDisable(true);
            var task=new Task<Boolean>(){protected Boolean call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){
                var snapshot=owner.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.OZON?new com.vncode.app.integration.ozon.OzonProductSnapshotReader().readForSynchronization(owner,finding.key()):new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(owner,finding.key());
                var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings();
                var selected=com.vncode.app.integration.znack.registration.ZnackRegistrationDocuments.select(snapshot,settings,java.time.LocalDate.now());
                var codes=snapshot.attributes().stream().filter(a->ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).filter(v->v.matches("[0-9]{4,10}")).distinct().toList();
                if(codes.size()!=1)return false;
                var session=com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.productionCatalog(owner);
                return !session.catalog().reviewDocuments(session.token(),selected,codes.getFirst()).candidates().isEmpty();
            }}};
            task.setOnSucceeded(e->{busy=false;if(disposed)return;table.setDisable(false);dialog.getDialogPane().lookupButton(applyType).setDisable(false);if(task.getValue())values.put(finding,tr("znack.registration.complete_missing_documents_saved"));status.setText(tr(task.getValue()?"znack.registration.complete_missing_documents_saved":"znack.registration.complete_missing_documents_pending"));table.refresh();});
            java.util.function.Consumer<Throwable> failed=error->{busy=false;if(disposed)return;table.setDisable(false);dialog.getDialogPane().lookupButton(applyType).setDisable(false);status.setText(FriendlyErrorService.format(error));};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.executeCatalog(task,failed);
        }
        void apply(){
            if(session!=null){advance();return;}
            var missing=review.findings().stream().filter(f->values.get(f)==null||values.get(f).isBlank()).toList();
            if(!missing.isEmpty()){status.setText(tr("znack.registration.ozon_required")+": "+missing.stream().map(f->f.article()+" · "+tr("products.edit_"+f.field())).collect(java.util.stream.Collectors.joining(", ")));return;}
            if(review.findings().stream().anyMatch(f->f.field().equals("tnved")&&!values.get(f).strip().matches("[0-9]{10}"))){status.setText(tr("znack.registration.ozon_tnved"));return;}
            var entered=Map.copyOf(values);var fields=new Review(review.findings().stream().filter(f->!f.field().equals("documents")).toList());work(()->{session=corrections.prepare(owner,fields,entered);return session.advance(p->progress(p));});
        }
        void advance(){if(!disposed&&!busy&&session!=null)work(()->session.advance(this::progress));}
        void progress(ProductBulkEditService.Progress p){Platform.runLater(()->{if(!disposed)status.setText(tr("znack.registration.complete_missing_saving")+" "+p.completed()+"/"+p.total());});}
        void work(Work worker){
            busy=true;table.setDisable(true);dialog.getDialogPane().lookupButton(applyType).setDisable(true);status.setText(tr("znack.registration.complete_missing_saving"));
            var task=new Task<Boolean>(){protected Boolean call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){return worker.run();}}};
            task.setOnSucceeded(e->{busy=false;if(disposed)return;if(task.getValue()){dialog.setResult(true);dialog.close();}else if(++polls<60)retry.playFromStart();else{status.setText(tr("znack.registration.complete_missing_pending"));dialog.getDialogPane().lookupButton(applyType).setDisable(false);}});
            java.util.function.Consumer<Throwable> failed=error->{busy=false;if(disposed)return;status.setText(FriendlyErrorService.format(error));dialog.getDialogPane().lookupButton(applyType).setDisable(false);if(session==null)table.setDisable(false);};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.executeCatalog(task,failed);
        }
        private static String group(Finding f){return f.field()+"\n"+f.category();}
        interface Work{boolean run()throws Exception;}
    }
    private static void column(TableView<Finding> table,String label,java.util.function.Function<Finding,String> value){var column=new TableColumn<Finding,String>(label);column.setCellValueFactory(row->new ReadOnlyStringWrapper(value.apply(row.getValue())));column.setPrefWidth(160);RegistrationTextCells.wrap(column);table.getColumns().add(column);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
