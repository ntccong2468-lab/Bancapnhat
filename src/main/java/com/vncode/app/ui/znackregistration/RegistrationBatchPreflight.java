package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.tnved.*;
import com.vncode.app.features.sync.RegistrationSources;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
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
    record Finding(String field,ProductKey key,String article,String before,String suggestion,String category,Attribute attribute,String suggestedType,String reason,List<String> options){
        Finding(String field,ProductKey key,String article,String before,String suggestion,String category){this(field,key,article,before,suggestion,category,null,null,"",List.of());}
        Finding(String field,ProductKey key,String article,String before,String suggestion){this(field,key,article,before,suggestion,"");}
    }
    record Review(List<Finding> findings,List<ProductKey> selected,RegistrationDynamicPreflight.Review dynamic){
        Review(List<Finding> findings){this(findings,List.of(),null);}
        Review{findings=List.copyOf(findings);selected=List.copyOf(selected);}List<ProductKey> keys(String field){return findings.stream().filter(f->f.field().equals(field)).map(Finding::key).distinct().toList();}}
    static Map<Finding,String> initialValues(Review review){
        var values=new LinkedHashMap<Finding,String>();
        for(var f:review.findings()){
            String value=f.suggestion().isBlank()?f.before():f.suggestion();
            if(f.field().equals("documents"))value=RegistrationPermitValidator.advisory(f.reason())?tr("znack.registration.preflight.keep_document"):"";
            else if(f.attribute()!=null)value=RegistrationDraftCompletionDialog.suggestion(f.attribute(),value,f.suggestedType(),"").text();
            else if(f.field().equals("catalog_category")&&f.options().size()==1)value=f.options().getFirst();
            values.put(f,value);
        }
        return values;
    }
    static Review read(Shop owner,List<ProductKey> selected){
        ListingIdentity.require(owner);var findings=new ArrayList<Finding>();var repo=new ProductRepository();var advisor=new ProductTnvedAdvisor();var cards=new HashSet<String>();
        var sources=new RegistrationSources();boolean sourceReady=sources.check(owner).ready();
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings();
        for(var key:selected){ListingIdentity.require(owner,key);if(!cards.add(key.cardId()))continue;
            var snapshot=repo.localSnapshot(key).orElse(null);if(snapshot==null)continue;
            if(!ListingIdentity.fingerprint(owner).equals(snapshot.metadata().get("owner"))){if(!sourceReady)continue;sources.requireMember(owner,key.cardId(),key.variantId());}
            if((snapshot.brand()==null||snapshot.brand().isBlank())&&(owner.getMarketplace()!=com.vncode.app.integration.marketplace.Marketplace.OZON||new com.vncode.app.integration.ozon.OzonShopBrand().load(owner).isBlank()))findings.add(new Finding("brand",key,snapshot.vendorArticle(),"","",snapshot.categoryId()));
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
    static Review readComplete(Shop owner,List<ProductKey> selected){return reviewStaleApprovals(owner,readComplete(owner,selected,Map.of(),Map.of()));}
    static Review reviewStaleApprovals(Shop owner,Review current){
        if(current.dynamic()==null)return current;
        for(var plan:current.dynamic().plans())try{RegistrationReviewedValues.load(owner,plan.snapshot(),plan.schema());}catch(RegistrationValidationException changed){if(changed.getMessage()!=null&&changed.getMessage().contains("ZNACK_REVIEWED_VALUES_CHANGED"))return forceReview(current);throw changed;}
        return current;
    }
    static Review readComplete(Shop owner,List<ProductKey> selected,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered){
        return readComplete(owner,selected,entered,Map.of());
    }
    static Review readComplete(Shop owner,List<ProductKey> selected,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered,Map<ProductKey,String> documents){
return readComplete(owner,selected,entered,documents,Map.of());
    }
    static Review readComplete(Shop owner,List<ProductKey> selected,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> entered,Map<ProductKey,String> documents,Map<ProductKey,Long> categories){
        try{
            var snapshots=new RegistrationSnapshotReview().read(owner,selected);
            var common=read(owner,selected);
            if(!common.findings().isEmpty())return new Review(common.findings(),selected,null);
            var dynamic=new RegistrationDynamicPreflight().read(owner,snapshots,entered,documents,categories);var findings=new ArrayList<Finding>();
            for(var missing:dynamic.missing())findings.add(new Finding("catalog:"+missing.attribute().id(),missing.key(),missing.article(),missing.before(),missing.suggestion(),"",missing.attribute(),missing.suggestedType(),missing.reason(),List.of()));
            for(var warning:dynamic.documents())findings.add(new Finding("documents",warning.key(),warning.article(),RegistrationDiagnostics.reason(warning.detail()),"","",null,null,warning.detail(),List.of()));
            for(var block:dynamic.blocked())findings.add(new Finding(block.field(),block.key(),block.article(),RegistrationDiagnostics.reason(block.detail()),"","",null,null,block.detail(),block.categories().stream().map(Object::toString).toList()));
            return new Review(findings,selected,dynamic);
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("ZNACK_PREFLIGHT_READ_FAILED",failure);}
    }
    static String fieldLabel(Finding finding){
        if(finding.field().equals("catalog_category"))return tr("znack.registration.choose_category");
        if(finding.field().equals("catalog_confirmation"))return tr("znack.registration.preflight.review_next_fields");
        if(finding.attribute()==null)return tr("products.edit_"+finding.field());
        String known=switch((int)finding.attribute().id()){case 2478->"fbo.column.name";case 2504->"products.edit_brand";case 35->"fbo.column.size";case 36->"fbo.column.color";case 14013->"products.edit_gender";case 2483->"products.edit_material";default->"";};
        String name=finding.attribute().name()+" ["+finding.attribute().id()+"]";
        return known.isBlank()?name:tr(known)+" · "+name;
    }
    static String variantLabel(Review review,Finding finding){
        if(review.dynamic()!=null)for(var plan:review.dynamic().plans())if(plan.snapshot().key().equals(finding.key())){
            var selected=plan.snapshot().variants().stream().filter(v->finding.key().variantId().equals(v.id())||finding.key().variantId().equals(v.sellerArticle())).findFirst();
            if(selected.isPresent()){
                var variant=selected.get();var sizes=new LinkedHashSet<String>();
                for(String value:List.of(Objects.toString(variant.size(),""),Objects.toString(variant.russianSize(),"")))if(!value.isBlank())sizes.add(value);
                if(!sizes.isEmpty())return finding.article()+" · "+String.join(" / ",sizes);
            }
        }
        return finding.article()+" · "+finding.key().variantId();
    }
    private static String findingReason(Finding finding){
        if(finding.field().equals("documents")||finding.field().equals("catalog_category"))return finding.before();
        if("changed".equals(finding.reason()))return tr("znack.registration.reason.ZNACK_REVIEWED_VALUES_CHANGED");
        return tr("type".equals(finding.reason())?"znack.registration.missing_type_reason":finding.before().isBlank()?"znack.registration.missing_value_reason":"znack.registration.invalid_value_reason");
    }
    static boolean show(javafx.stage.Window window,Shop owner,Review review){return show(window,owner,review,false);}
    static boolean show(javafx.stage.Window window,Shop owner,Review review,boolean reviewAllFields){
        if(!reviewAllFields&&review.findings().isEmpty())return true;
        return new Editor(window,owner,review,reviewAllFields).show();
    }
    static boolean sameFacts(RegistrationDynamicPreflight.Review before,RegistrationDynamicPreflight.Review after){
        if(before.plans().size()!=after.plans().size())return false;var old=new HashMap<ProductKey,RegistrationDynamicPreflight.Plan>();before.plans().forEach(p->old.put(p.snapshot().key(),p));
        for(var plan:after.plans()){var previous=old.get(plan.snapshot().key());if(previous==null||!RegistrationReviewedValues.productRevision(previous.snapshot()).equals(RegistrationReviewedValues.productRevision(plan.snapshot()))||!RegistrationReviewedValues.schemaRevision(previous.schema()).equals(RegistrationReviewedValues.schemaRevision(plan.schema()))||previous.documents()!=null&&plan.documents()!=null&&!RegistrationReviewedValues.permitRevision(previous.documents()).equals(RegistrationReviewedValues.permitRevision(plan.documents())))return false;}return true;
    }
    static Review forceReview(Review current){
        var findings=new LinkedHashMap<String,Finding>();current.findings().forEach(f->findings.put(f.key()+"/"+f.field(),f));
        for(var plan:current.dynamic().plans())for(var attribute:RegistrationAttributeRules.required(plan.schema(),plan.draft().attributes()))if(!Set.of(3959L,13933L,23557L,23561L).contains(attribute.id())){var key=plan.snapshot().key();String value=plan.draft().attributes().getOrDefault(attribute.id(),"");var finding=new Finding("catalog:"+attribute.id(),key,plan.snapshot().vendorArticle(),value,value,"",attribute,plan.draft().attributeTypes().get(attribute.id()),"changed",List.of());findings.putIfAbsent(key+"/"+finding.field(),finding);}
        if(findings.isEmpty()&&!current.dynamic().plans().isEmpty()){var plan=current.dynamic().plans().getFirst();var finding=new Finding("catalog_confirmation",plan.snapshot().key(),plan.snapshot().vendorArticle(),plan.draft().goodName(),plan.draft().goodName(),"",null,null,"changed",List.of());findings.put("confirmation",finding);}
        return new Review(List.copyOf(findings.values()),current.selected(),current.dynamic());
    }
    static final class Editor {
        final Shop owner;Review review;final Dialog<Boolean> dialog=new Dialog<>();
        final TableView<Finding> table=new TableView<>();final Label status=new Label();
        final Map<Finding,String> values=new LinkedHashMap<>();final Map<Finding,String> types=new LinkedHashMap<>();
        final Map<ProductKey,String> approvedDocuments=new HashMap<>();final Map<ProductKey,Long> chosenCategories=new HashMap<>();
        final Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> reviewed=new LinkedHashMap<>();final Map<String,List<String>> suggestions=new HashMap<>();
        final ButtonType applyType=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);
        final RegistrationPreflightCorrections corrections=new RegistrationPreflightCorrections();
        final javafx.animation.PauseTransition retry=new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        RegistrationPreflightCorrections.Session session;volatile boolean disposed;boolean busy;int polls;boolean awaitingFullReview;
        final Set<Task<?>> reads=new HashSet<>();
        @FunctionalInterface interface ReviewReader{Review read(Shop shop,List<ProductKey> keys,Map<ProductKey,Map<Long,RegistrationReviewedValues.Value>> values,Map<ProductKey,String> documents,Map<ProductKey,Long> categories);}
        ReviewReader reviewReader=RegistrationBatchPreflight::readComplete;
        Editor(javafx.stage.Window window,Shop owner,Review review){this(window,owner,review,false);}
        Editor(javafx.stage.Window window,Shop owner,Review initial,boolean reviewAllFields){
            if(reviewAllFields&&initial.dynamic()==null&&initial.findings().isEmpty())throw new RegistrationValidationException("ZNACK_REGISTRATION_STATE_REQUIRES_REVIEW");
            Review review=reviewAllFields&&initial.dynamic()!=null?forceReview(initial):initial;
            this.owner=owner;this.review=review;awaitingFullReview=reviewAllFields&&initial.dynamic()==null;if(window!=null)dialog.initOwner(window);
            dialog.setTitle(tr("znack.registration.batch_preflight"));dialog.setHeaderText(tr("znack.registration.complete_missing_hint"));
            values.putAll(initialValues(review));review.findings().forEach(f->types.put(f,f.attribute()==null?f.suggestedType():RegistrationDraftCompletionDialog.suggestion(f.attribute(),values.get(f),f.suggestedType(),"").type()));
            table.getItems().setAll(review.findings());table.setPrefHeight(390);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            column(table,"Article / "+tr("znack.registration.preflight.variant"),f->variantLabel(this.review,f));column(table,tr("znack.registration.complete_missing_field"),RegistrationBatchPreflight::fieldLabel);column(table,tr("znack.registration.preflight.target"),f->tr(f.attribute()==null&&!f.field().startsWith("catalog_")?"znack.registration.preflight.marketplace":"znack.registration.preflight.catalog_only"));column(table,tr("edit.before"),f->RegistrationCountryDisplay.label(f.attribute()==null?0:f.attribute().id(),f.before()));
            column(table,tr("jobs.reason"),RegistrationBatchPreflight::findingReason);
            var input=new TableColumn<Finding,Finding>(tr("znack.registration.complete_missing_value"));input.setCellValueFactory(row->new javafx.beans.property.ReadOnlyObjectWrapper<>(row.getValue()));input.setPrefWidth(330);input.setMinWidth(180);
            input.setCellFactory(c->new TableCell<>(){
                final ComboBox<String> box=new ComboBox<>();Finding active;boolean rendering;
                {box.setEditable(true);box.setMaxWidth(Double.MAX_VALUE);box.setId("registrationMissingValue");box.setPromptText(tr("znack.registration.complete_missing_value"));
                 box.getEditor().textProperty().addListener((o,a,b)->{if(!rendering&&active!=null)values.put(active,RegistrationCountryDisplay.value(active.attribute()==null?0:active.attribute().id(),b,box.getItems()));});box.valueProperty().addListener((o,a,b)->{if(!rendering&&active!=null&&b!=null)values.put(active,b);});}
                protected void updateItem(Finding finding,boolean empty){super.updateItem(finding,empty);active=empty?null:finding;if(active==null){setGraphic(null);return;}
                    if(active.field().equals("documents")){
                        var target=active;var edit=new Button(tr("products.edit_documents"));edit.setId("registrationMissingDocuments");edit.setOnAction(e->completeDocuments(target));
                        var read=new Button(tr("znack.registration.refresh"));read.setOnAction(e->verifyDocuments(target));
                        var saved=new Label(values.getOrDefault(target,""));saved.setWrapText(true);var content=new VBox(4,new HBox(8,edit,read),saved);
                        if(!target.reason().isBlank()&&RegistrationPermitValidator.advisory(target.reason())){var keep=new CheckBox(tr("znack.registration.preflight.keep_document"));keep.setWrapText(true);keep.setId("registrationKeepDocument");keep.setSelected(!values.getOrDefault(target,"").isBlank());keep.selectedProperty().addListener((o,a,b)->{if(b){approveDocument(target);values.put(target,tr("znack.registration.preflight.keep_document"));}else{approvedDocuments.remove(target.key());values.put(target,"");}});content.getChildren().add(keep);}setGraphic(content);return;
                    }
                    rendering=true;long attributeId=active.attribute()==null?0:active.attribute().id();RegistrationCountryDisplay.configure(box,attributeId);var options=new LinkedHashSet<String>();if(!active.suggestion().isBlank())options.add(active.suggestion());if(active.attribute()!=null)options.addAll(active.attribute().presets());else{options.addAll(active.options());options.addAll(suggestions.getOrDefault(group(active),List.of()));}box.getItems().setAll(options);box.setValue(values.getOrDefault(active,""));box.getEditor().setText(RegistrationCountryDisplay.label(attributeId,values.getOrDefault(active,"")));rendering=false;setGraphic(box);}
            });table.getColumns().add(input);
            var typeColumn=new TableColumn<Finding,Finding>(tr("znack.registration.preflight.value_type"));typeColumn.setCellValueFactory(row->new javafx.beans.property.ReadOnlyObjectWrapper<>(row.getValue()));typeColumn.setCellFactory(c->new TableCell<>(){
                final ComboBox<String> box=new ComboBox<>();Finding active;boolean rendering;
                {box.setMaxWidth(Double.MAX_VALUE);box.setId("registrationMissingValueType");box.valueProperty().addListener((o,a,b)->{if(!rendering&&active!=null)types.put(active,b);});}
                protected void updateItem(Finding finding,boolean empty){super.updateItem(finding,empty);active=empty?null:finding;if(active==null||active.attribute()==null||active.attribute().valueTypes().isEmpty()){setGraphic(null);return;}rendering=true;box.getItems().setAll(active.attribute().valueTypes());box.setValue(types.get(active));rendering=false;setGraphic(box);}
            });table.getColumns().add(typeColumn);status.setWrapText(true);status.setId("registrationMissingStatus");
            var hint=new Label(tr("znack.registration.missing_suggestions_hint"));hint.setWrapText(true);
            var content=new VBox(10,hint,table,status);VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(880,550);dialog.getDialogPane().setId("registrationMissingFieldsDialog");
            dialog.getDialogPane().getButtonTypes().setAll(applyType,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));var apply=(Button)dialog.getDialogPane().lookupButton(applyType);apply.setId("registrationMissingApply");apply.getStyleClass().add("btn-primary");
            apply.addEventFilter(javafx.event.ActionEvent.ACTION,e->{e.consume();if(!busy)apply();});dialog.setResultConverter(button->false);
            dialog.setOnHidden(e->{disposed=true;retry.stop();for(var read:List.copyOf(reads))read.cancel(true);reads.clear();});retry.setOnFinished(e->advance());DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        }
        boolean show(){loadSuggestions();return dialog.showAndWait().orElse(false);}
        void loadSuggestions(){
            var findings=review.findings();var groups=new LinkedHashMap<String,Finding>();findings.forEach(f->groups.putIfAbsent(group(f),f));
            var task=new Task<Void>(){protected Void call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){
                var service=new ProductSuggestionService();
                for(var finding:findings){
                    if(disposed)return null;String proposal=service.defaultValue(owner,List.of(finding.key()),finding.field());
                    if(!proposal.isBlank())Platform.runLater(()->{if(!disposed&&!busy&&values.getOrDefault(finding,"").isBlank()){values.put(finding,proposal);table.refresh();}});
                }
                for(var entry:groups.entrySet()){
                    if(disposed)return null;if(entry.getValue().field().equals("documents")||entry.getValue().attribute()!=null||entry.getValue().field().startsWith("catalog_"))continue;try{var found=service.suggest(owner,List.of(entry.getValue().key()),entry.getValue().field(),"",partial->offer(entry.getKey(),partial.values()));offer(entry.getKey(),found.values());}catch(Exception unavailable){/* Manual input remains available. */}
                }return null;
            }}};executeRead(task,error->{});
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
            java.util.function.Consumer<Throwable> failed=error->{busy=false;if(disposed)return;table.setDisable(false);dialog.getDialogPane().lookupButton(applyType).setDisable(false);status.setText(FriendlyErrorService.format(error));};task.setOnFailed(e->failed.accept(task.getException()));executeRead(task,failed);
        }
        void apply(){
            if(session!=null){advance();return;}
            var missing=review.findings().stream().filter(f->values.get(f)==null||values.get(f).isBlank()).toList();
            if(!missing.isEmpty()){status.setText(tr("znack.registration.ozon_required")+": "+missing.stream().map(f->f.article()+" · "+fieldLabel(f)).collect(java.util.stream.Collectors.joining(", ")));return;}
            if(review.findings().stream().anyMatch(f->f.field().equals("tnved")&&!values.get(f).strip().matches("[0-9]{10}"))){status.setText(tr("znack.registration.ozon_tnved"));return;}
            if(review.dynamic()!=null){
                review.findings().stream().filter(f->f.field().equals("documents")).forEach(f->approveDocument(f));
                for(var finding:review.findings())if(finding.attribute()!=null){String type=types.get(finding);if(type==null&&finding.attribute().valueTypes().isEmpty())type="";if(type==null){status.setText(tr("znack.registration.preflight.missing_type")+": "+fieldLabel(finding));return;}reviewed.computeIfAbsent(finding.key(),key->new LinkedHashMap<>()).put(finding.attribute().id(),new RegistrationReviewedValues.Value(values.get(finding),type));}
                for(var finding:review.findings())if(finding.field().equals("catalog_category")){var option=review.dynamic().blocked().stream().filter(b->b.key().equals(finding.key())&&b.field().equals("catalog_category")).flatMap(b->b.categories().stream()).filter(c->c.toString().equals(values.get(finding))).findFirst();if(option.isEmpty()){status.setText(tr("znack.registration.choose_category"));return;}chosenCategories.put(finding.key(),option.get().id());}
                freshReview(true);return;
            }
            var entered=Map.copyOf(values);var fields=new Review(review.findings().stream().filter(f->!f.field().equals("documents")).toList());work(()->{session=corrections.prepare(owner,fields,entered);return session.advance(p->progress(p));});
        }
        void advance(){if(!disposed&&!busy&&session!=null)work(()->session.advance(this::progress));}
        void progress(ProductBulkEditService.Progress p){Platform.runLater(()->{if(!disposed)status.setText(tr("znack.registration.complete_missing_saving")+" "+p.completed()+"/"+p.total());});}
        void work(Work worker){
            busy=true;table.setDisable(true);dialog.getDialogPane().lookupButton(applyType).setDisable(true);status.setText(tr("znack.registration.complete_missing_saving"));
            var task=new Task<Boolean>(){protected Boolean call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){return worker.run();}}};
            task.setOnSucceeded(e->{busy=false;if(disposed)return;if(task.getValue()){session=null;if(review.selected().isEmpty()){dialog.setResult(true);dialog.close();}else freshReview(true);}else if(++polls<60)retry.playFromStart();else{status.setText(tr("znack.registration.complete_missing_pending"));dialog.getDialogPane().lookupButton(applyType).setDisable(false);}});
            var notified=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<Throwable> failed=error->{if(!notified.compareAndSet(false,true))return;busy=false;if(disposed)return;status.setText(FriendlyErrorService.format(error));dialog.getDialogPane().lookupButton(applyType).setDisable(false);if(session==null)table.setDisable(false);};task.setOnFailed(e->failed.accept(task.getException()));task.setOnCancelled(e->Platform.runLater(()->failed.accept(readTimeout())));AppTaskExecutor.executeCatalog(task,failed);
        }
        void approveDocument(Finding finding){if(review.dynamic()!=null)review.dynamic().plans().stream().filter(p->p.snapshot().key().equals(finding.key())&&p.documents()!=null).findFirst().ifPresent(p->approvedDocuments.put(finding.key(),RegistrationReviewedValues.permitRevision(p.documents())));}
        void freshReview(boolean saveWhenComplete){
            busy=true;table.setDisable(true);dialog.getDialogPane().lookupButton(applyType).setDisable(true);status.setText(tr("znack.registration.preflight.checking_schema"));
            var entered=new LinkedHashMap<ProductKey,Map<Long,RegistrationReviewedValues.Value>>();reviewed.forEach((key,map)->entered.put(key,Map.copyOf(map)));var keys=review.selected();var previous=review.dynamic();var acknowledged=Map.copyOf(approvedDocuments);var categories=Map.copyOf(chosenCategories);boolean requireFullReview=awaitingFullReview;
            var task=new Task<Review>(){protected Review call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){var checked=reviewReader.read(owner,keys,Map.copyOf(entered),acknowledged,categories);if(requireFullReview&&checked.dynamic()!=null)return forceReview(checked);if(previous!=null&&checked.dynamic()!=null&&!sameFacts(previous,checked.dynamic()))return forceReview(checked);if(isCancelled()||disposed||Thread.currentThread().isInterrupted())throw new InterruptedException("Registration preflight cancelled");if(saveWhenComplete&&checked.findings().isEmpty()&&checked.dynamic()!=null)RegistrationReviewedValues.save(owner,RegistrationDynamicPreflight.approvals(checked.dynamic()));return checked;}}};
            task.setOnSucceeded(e->{busy=false;if(disposed)return;review=task.getValue();if(requireFullReview&&review.dynamic()!=null)awaitingFullReview=false;if(review.findings().isEmpty()){dialog.setResult(true);dialog.close();return;}values.clear();values.putAll(initialValues(review));types.clear();review.findings().forEach(f->types.put(f,f.attribute()==null?f.suggestedType():RegistrationDraftCompletionDialog.suggestion(f.attribute(),values.get(f),f.suggestedType(),"").type()));table.getItems().setAll(review.findings());table.setDisable(false);dialog.getDialogPane().lookupButton(applyType).setDisable(false);status.setText(tr("znack.registration.preflight.review_next_fields"));loadSuggestions();});
            java.util.function.Consumer<Throwable> failed=error->{busy=false;if(disposed)return;table.setDisable(false);dialog.getDialogPane().lookupButton(applyType).setDisable(false);status.setText(FriendlyErrorService.format(error));};task.setOnFailed(e->failed.accept(task.getException()));executeRead(task,failed);
        }
        void executeRead(Task<?> task,java.util.function.Consumer<Throwable> failed){
            reads.add(task);task.stateProperty().addListener((observable,previous,current)->{if(current==javafx.concurrent.Worker.State.CANCELLED||current==javafx.concurrent.Worker.State.FAILED||current==javafx.concurrent.Worker.State.SUCCEEDED)reads.remove(task);});
            var notified=new java.util.concurrent.atomic.AtomicBoolean();java.util.function.Consumer<Throwable> once=error->{if(notified.compareAndSet(false,true))failed.accept(error);};
            task.setOnFailed(e->once.accept(task.getException()));task.setOnCancelled(e->Platform.runLater(()->once.accept(readTimeout())));AppTaskExecutor.executeCatalog(task,once);
        }
        private static String group(Finding f){return f.field()+"\n"+f.category();}
        interface Work{boolean run()throws Exception;}
    }
    static void readPill(StackPane host,String id,Task<?> task,Runnable cancelled){
        var pill=RegistrationStatusPill.show(host,id,tr("znack.registration.preflight.checking_schema"),true);
        var cancel=new Button(tr("common.cancel"));cancel.setId(id+"Cancel");cancel.setOnAction(event->{cancelled.run();task.cancel(true);});pill.getChildren().add(cancel);
        task.stateProperty().addListener((observable,previous,current)->{if(current==javafx.concurrent.Worker.State.SUCCEEDED||current==javafx.concurrent.Worker.State.FAILED||current==javafx.concurrent.Worker.State.CANCELLED)host.getChildren().remove(pill);});
    }
    static java.net.SocketTimeoutException readTimeout(){return new java.net.SocketTimeoutException("National Catalog preflight read exceeded its deadline.");}
    private static void column(TableView<Finding> table,String label,java.util.function.Function<Finding,String> value){var column=new TableColumn<Finding,String>(label);column.setCellValueFactory(row->new ReadOnlyStringWrapper(value.apply(row.getValue())));column.setPrefWidth(160);RegistrationTextCells.wrap(column);table.getColumns().add(column);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
