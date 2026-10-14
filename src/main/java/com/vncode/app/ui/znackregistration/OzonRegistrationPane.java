package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Native Ozon identities and reviewed National Catalog attributes; no WB identifier conversion. */
public final class OzonRegistrationPane extends VBox {
    private final Shop owner;
    private final Runnable openGtin;
    private final ProductRepository products=new ProductRepository();
    private final OzonCardRegistrationRepository registrations=new OzonCardRegistrationRepository();
    private final OzonCardRegistrationWorkflow workflow=new OzonCardRegistrationWorkflow();
    private final TableView<ProductRow> table=new TableView<>();
    private final Map<ProductKey,OzonCardRegistrationRepository.Row> states=new HashMap<>();
    private final TextField search=new TextField();
    private final Label title=new Label(),status=new Label(),count=new Label(),selection=new Label();
    private final Button documents=new Button(),refresh=new Button(),sync=new Button(),sign=new Button(),gtin=new Button(),all=new Button(),clear=new Button(),register=new Button(),newCards=new Button(),stopBatch=new Button(),more=new Button();
    private final PauseTransition debounce=new PauseTransition(Duration.millis(250)),refreshDebounce=new PauseTransition(Duration.millis(400));
    private List<ProductKey> frozen=List.of();
    private String cursor;
    private long total,generation;
    private boolean busy,disposed,programmaticSelection,batchReviewing,deferredReload;
    private volatile long lastBatchProgress;
    private final java.util.concurrent.atomic.AtomicBoolean cancelBatch=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean progressQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reviewPending=new java.util.concurrent.atomic.AtomicBoolean();

    public OzonRegistrationPane(Shop shop,Runnable openGtin) {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");
        this.owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());this.openGtin=Objects.requireNonNull(openGtin);
        setId("ozonRegistrationPane");setSpacing(8);setPadding(new Insets(12));
        title.getStyleClass().add("h2");status.setWrapText(true);status.getStyleClass().add("text-muted");
        var actions=new FlowPane(10,8,documents,refresh,sync,sign,gtin,stopBatch);var filters=new FlowPane(8,8,search,all,clear,register,newCards);search.setPrefWidth(300);
        HBox.setHgrow(search,Priority.ALWAYS);getChildren().setAll(title,actions,status,filters,table,new HBox(10,count,selection,more));VBox.setVgrow(table,Priority.ALWAYS);
        table.setId("ozonRegistrationTable");table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<ProductRow>)change->{if(!programmaticSelection){frozen=List.of();buttons();}});
        documents.setOnAction(e->configureDocuments());refresh.setOnAction(e->reload());sync.setOnAction(e->syncCatalog());sign.setOnAction(e->signApproved());gtin.setOnAction(e->openGtin.run());
        stopBatch.setOnAction(e->{cancelBatch.set(true);stopBatch.setDisable(true);status.setText(tr("znack.registration.batch_stopping"));});all.setOnAction(e->selectAll());clear.setOnAction(e->{frozen=List.of();table.getSelectionModel().clearSelection();buttons();});
        register.setOnAction(e->registerSelection());newCards.setOnAction(e->registerBatch(true));more.setOnAction(e->load(true));
        debounce.setOnFinished(e->reload());refreshDebounce.setOnFinished(e->reload());search.textProperty().addListener((o,a,b)->debounce.playFromStart());
        applyTranslations();reload();
    }
    public void dispose(){disposed=true;cancelBatch.set(true);++generation;debounce.stop();refreshDebounce.stop();}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    public void applyTranslations() {
        title.setText(tr("znack.registration.title")+" · Ozon · "+owner.getName());
        documents.setText(tr("znack.registration.documents"));refresh.setText(tr("znack.registration.refresh"));sync.setText(tr("znack.registration.sync_znack"));sign.setText(tr("znack.registration.sign_approved"));
        gtin.setText(tr("znack.registration.update_marketplace"));all.setText(tr("znack.registration.select_all"));clear.setText(tr("znack.registration.clear_selection"));
        register.setText(tr("znack.registration.register_selected"));newCards.setText(tr("znack.registration.batch_new_cards"));stopBatch.setText(tr("znack.registration.batch_stop"));more.setText(tr("znack.registration.load_more"));search.setPromptText(tr("products.search"));
        table.getColumns().clear();column("Product ID / offer_id",row->row.key().cardId()+" / "+row.key().variantId());column(tr("fbo.column.name"),ProductRow::title);
        column(tr("products.article"),ProductRow::vendorArticle);column(tr("fbo.column.color"),ProductRow::color);column(tr("fbo.column.size"),ProductRow::size);
        column("Barcode / GTIN",row->String.join(", ",row.barcodes())+candidateText(row.key()));column(tr("znack.registration.status"),row->tr("znack.registration.status."+state(row.key()).name().toLowerCase(Locale.ROOT)));
        var actions=new TableColumn<ProductRow,ProductRow>(tr("znack.registration.action"));actions.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        actions.setCellFactory(c->new TableCell<>() {
            final MenuButton menu=new MenuButton();final MenuItem create=new MenuItem(),resume=new MenuItem(),switchCard=new MenuItem();
            {menu.getItems().setAll(create,resume,switchCard);create.setOnAction(e->prepare(getItem().key(),state(getItem().key())==Status.PUBLISHED));
                resume.setOnAction(e->resume(getItem().key()));switchCard.setOnAction(e->complete(getItem().key()));}
            @Override protected void updateItem(ProductRow row,boolean empty) {
                super.updateItem(row,empty);if(empty||row==null){setGraphic(null);return;}
                Status state=state(row.key());var checkpoint=states.get(row.key());boolean candidate=checkpoint!=null&&!safe(checkpoint.gtin()).isBlank()&&!checkpoint.activeGtin().equals(checkpoint.gtin());
                menu.setText(tr("znack.registration.action"));menu.setDisable(busy);create.setText(tr(state==Status.PUBLISHED?"znack.registration.create_new_card":"znack.registration.create"));
                create.setDisable(busy||(!Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.PUBLISHED).contains(state))||(state==Status.PUBLISHED&&candidate));
                resume.setText(tr("znack.registration.resume"));resume.setDisable(busy||checkpoint==null||(safe(checkpoint.feedId()).isBlank()&&state!=Status.READY_TO_SUBMIT)||state==Status.PUBLISHED);
                switchCard.setText(tr("znack.registration.switch_card"));switchCard.setDisable(busy||state!=Status.PUBLISHED||!candidate);setGraphic(menu);
            }
        });table.getColumns().add(actions);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setRowFactory(t->new TableRow<>(){@Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);var r=row==null?null:states.get(row.key());setTooltip(empty||r==null||safe(r.error()).isBlank()?null:new Tooltip(r.error()));}});buttons();
    }
    private void column(String text,java.util.function.Function<ProductRow,String> value){var column=new TableColumn<ProductRow,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(160);table.getColumns().add(column);}
    private ProductQuery query(String cursor){return new ProductQuery(owner.getId(),Marketplace.OZON,search.getText(),List.of(),null,false,cursor,300);}
    private void reload(){if(disposed)return;if(batchReviewing){deferredReload=true;return;}debounce.stop();++generation;cursor=null;frozen=List.of();load(false);}
    private record Loaded(ProductPage page,Map<ProductKey,OzonCardRegistrationRepository.Row> states){}
    private void load(boolean append) {
        if(disposed||(append&&(busy||cursor==null)))return;
        ProductQuery query=query(append?cursor:null);
        background(()->{var page=products.loadPage(query);return new Loaded(page,registrations.findMany(page.rows().stream().map(ProductRow::key).toList()));},loaded->{
            if(!append){states.clear();table.getItems().clear();}states.putAll(loaded.states());total=loaded.page().total();cursor=loaded.page().nextCursor();
            programmaticSelection=true;try{table.getItems().addAll(loaded.page().rows());}finally{programmaticSelection=false;}
            status.setText(tr("znack.registration.ozon_review_note"));table.refresh();buttons();
        });
    }
    private void selectAll(){if(busy)return;ProductQuery query=query(null);background(()->products.freezeKeys(query),keys->{frozen=keys;programmaticSelection=true;try{table.getSelectionModel().selectAll();}finally{programmaticSelection=false;}buttons();});}
    private List<ProductKey> selected(){return frozen.isEmpty()?table.getSelectionModel().getSelectedItems().stream().map(ProductRow::key).toList():frozen;}
    private void registerSelection(){var selected=selected();if(selected.isEmpty()||busy)return;if(selected.size()>1){registerBatch(false);return;}prepare(selected.getFirst(),false);}
    private void registerBatch(boolean newCard) {
        var keys=List.copyOf(selected());if(keys.isEmpty()||busy||keys.size()>1000){status.setText(tr("znack.registration.batch_limit"));return;}
        cancelBatch.set(false);batchReviewing=true;buttons();
        background(()->workflow.prepareBatch(owner,keys,newCard,n->{long now=System.nanoTime();if(n==keys.size()||now-lastBatchProgress>=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)){lastBatchProgress=now;Platform.runLater(()->{if(!disposed&&batchReviewing)status.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),n,keys.size()));});}}),review->{
            if(disposed)return;var approved=OzonRegistrationBatchDialog.review(review);
            if(approved.isEmpty()){batchReviewing=false;buttons();if(deferredReload){deferredReload=false;reload();}return;}
            busy=true;buttons();workflow.submitBatchReviewed(owner,approved,newCard,true,listener(),cancelBatch::get).whenComplete((n,error)->Platform.runLater(()->{
                if(disposed)return;busy=false;batchReviewing=false;deferredReload=false;buttons();
                status.setText(error==null?java.text.MessageFormat.format(tr("znack.registration.batch_queued"),n):tr("znack.registration.batch_read_failed"));refreshDebounce.playFromStart();
            }));
        });
    }
    private void prepare(ProductKey key,boolean newCard) {
        if(busy||disposed)return;
        if(newCard&&!confirm(tr("znack.registration.confirm_replacement")))return;
        background(()->workflow.prepare(owner,key),prepared->continuePrepare(key,newCard,prepared));
    }
    private void continuePrepare(ProductKey key,boolean newCard,OzonCardRegistrationWorkflow.Preparation prepared) {
        if(prepared.preflight()==null) {
            var dialog=new TextInputDialog(prepared.tnved());dialog.setTitle(tr("znack.registration.title"));dialog.setHeaderText(tr("znack.registration.ozon_tnved"));
            var value=dialog.showAndWait();if(value.isEmpty())return;
            if(!value.get().strip().matches("[0-9]{10}")){status.setText(tr("znack.registration.ozon_tnved"));return;}
            background(()->workflow.prepare(owner,key,value.get().strip()),next->continuePrepare(key,newCard,next));return;
        }
        if(prepared.categoryId()==0) {
            var categories=prepared.preflight().categories();if(categories.isEmpty()){status.setText(tr("znack.registration.category_missing"));return;}
            var dialog=new ChoiceDialog<Category>(categories.getFirst(),categories);dialog.setHeaderText(tr("znack.registration.choose_category"));
            var selected=dialog.showAndWait();if(selected.isEmpty())return;
            background(()->workflow.withCategory(owner,prepared,selected.get().id()),next->continuePrepare(key,newCard,next));return;
        }
        var draft=OzonRegistrationReviewDialog.review(prepared);
        if(draft==null)return;
        background(()->workflow.registerReviewed(owner,prepared,draft,true,newCard,listener()),accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});
    }
    private void resume(ProductKey key){if(!confirm(tr("znack.registration.confirm_resume")))return;background(()->workflow.resume(owner,key,listener()),accepted->refreshDebounce.playFromStart());}
    private void complete(ProductKey key){if(!confirm(tr("znack.registration.confirm_switch")))return;background(()->workflow.complete(owner,key,true,listener()),accepted->refreshDebounce.playFromStart());}
    private void signApproved(){
        if(busy)return;var chosen=selected();ProductQuery query=query(null);
        background(()->{
            var keys=chosen.isEmpty()?products.freezeKeys(query):chosen;
            var rows=registrations.findMany(keys);
            return keys.stream().filter(key->{var row=rows.get(key);return row!=null&&!row.feedId().isBlank()
                    &&Set.of(Status.READY_TO_SIGN,Status.FEED_SUBMITTED,Status.PROCESSING,Status.SIGNING).contains(row.status());}).toList();
        },keys->{
            if(keys.isEmpty()){status.setText(tr("znack.registration.no_approved"));return;}
            if(keys.size()>10000){status.setText(tr("znack.registration.batch_too_large"));return;}
            if(!confirm(java.text.MessageFormat.format(tr("znack.registration.confirm_sign"),keys.size())))return;
            background(()->workflow.signApproved(owner,keys,true,listener()),accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});
        });
    }
    private void configureDocuments() {
        if(busy)return;
        background(()->new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings(),settings->{
            var dialog=new Dialog<ZnackModels.GoodsDocument>();dialog.setTitle(tr("znack.registration.documents"));
            var type=new ComboBox<String>();type.getItems().setAll("CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE");type.setValue(settings.documentType()==null||settings.documentType().isBlank()?"CONFORMITY_DECLARATION":settings.documentType());
            type.setConverter(new javafx.util.StringConverter<>(){
                public String toString(String value){return value==null?"":tr("CONFORMITY_CERTIFICATE".equals(value)?"znack.registration.certificate":"znack.registration.declaration");}
                public String fromString(String value){throw new UnsupportedOperationException();}
            });
            var number=new TextField(safe(settings.documentNumber()));var date=new TextField(safe(settings.documentDate()));date.setPromptText("yyyy-MM-dd / dd.MM.yyyy");
            var grid=new GridPane();grid.setHgap(10);grid.setVgap(10);grid.addRow(0,new Label(tr("znack.registration.document_type")),type);grid.addRow(1,new Label(tr("znack.registration.document_number")),number);grid.addRow(2,new Label(tr("znack.registration.document_date")),date);
            var note=new Label(tr("znack.registration.ozon_review_note"));note.setWrapText(true);note.setMaxWidth(600);grid.add(note,0,3,2,1);dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK,ButtonType.CANCEL);
            dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{try{if(number.getText().isBlank()||date.getText().isBlank()||!type.getItems().contains(type.getValue()))throw new IllegalArgumentException();settings.withDefaultGoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip()).validateDefaultGoodsDocument();}catch(IllegalArgumentException invalid){event.consume();note.setText(tr("znack.registration.config_required"));}});
            dialog.setResultConverter(button->button==ButtonType.OK?new ZnackModels.GoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip()):null);
            var result=dialog.showAndWait();if(result.isEmpty())return;var document=result.get();
            background(()->{com.vncode.app.integration.listing.ListingIdentity.require(owner);var target=new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));target.saveSettings(target.getSettings().withDefaultGoodsDocument(document.type(),document.number(),document.date()));return true;},saved->status.setText(tr("znack.registration.config_saved")));
        });
    }
    private void syncCatalog(){if(busy)return;background(()->{new com.vncode.app.features.gtinsync.RegisteredGtinSyncService(NationalCatalogGtinSource.forShop(owner),new com.vncode.app.features.gtinsync.GtinSyncRepository()).refresh(owner.getId(),Marketplace.OZON);return true;},done->reload());}
    private java.util.function.BiConsumer<Status,String> listener(){return (state,detail)->{
        if(detail!=null&&!detail.isBlank())reviewPending.set(true);
        if(progressQueued.compareAndSet(false,true))Platform.runLater(()->{
            progressQueued.set(false);if(disposed)return;
            if(reviewPending.getAndSet(false))status.setText(tr("znack.registration.bulk_review"));refreshDebounce.playFromStart();
        });
    };}
    private <T>void background(Callable<T> work,Consumer<T> accept) {
        if(disposed)return;long current=generation;busy=true;buttons();Task<T> task=new Task<>(){@Override protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(e->{if(disposed||current!=generation)return;busy=false;buttons();accept.accept(task.getValue());});
        Consumer<Throwable> failure=error->{if(disposed||current!=generation)return;busy=false;batchReviewing=false;deferredReload=false;buttons();AlertService.showDetailedError(com.vncode.app.integration.znack.ZnackErrorDetails.summary(error),com.vncode.app.integration.znack.ZnackErrorDetails.format(error));};
        task.setOnFailed(e->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }
    private void buttons(){stopBatch.setVisible(batchReviewing&&busy);stopBatch.setManaged(stopBatch.isVisible());stopBatch.setDisable(cancelBatch.get());for(var button:List.of(documents,refresh,sync,sign,gtin,all,clear,register,newCards,more))button.setDisable(busy||batchReviewing);register.setDisable(busy||batchReviewing||selected().isEmpty());newCards.setDisable(busy||batchReviewing||selected().isEmpty());clear.setDisable(busy||batchReviewing||selected().isEmpty());search.setDisable(batchReviewing);more.setVisible(cursor!=null);more.setManaged(cursor!=null);count.setText(java.text.MessageFormat.format(tr("znack.registration.page_count"),table.getItems().size(),total));selection.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selected().size()));}
    private Status state(ProductKey key){var row=states.get(key);return row==null?Status.NOT_CREATED:row.status();}
    private String candidateText(ProductKey key){var row=states.get(key);return row==null?"":"\n"+tr("znack.registration.active_gtin")+": "+safe(row.activeGtin())+"\nGTIN: "+safe(row.gtin());}
    private static boolean confirm(String message){var alert=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private static String safe(String value){return value==null?"":value;}
}
