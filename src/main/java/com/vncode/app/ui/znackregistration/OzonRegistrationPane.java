package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackErrorDetails;
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
    private final RegistrationSources sources=new RegistrationSources();
    private RegistrationSources.Readiness readiness;
    private RegistrationCardIndex cardIndex;
    private boolean latestReadUnknown;
    private String sourceReadAt="";
    private final Map<ProductKey,String> skus=new HashMap<>();
    private final Shop owner;
    private final Runnable openGtin;
    private final ProductRepository products=new ProductRepository();
    private final OzonCardRegistrationRepository registrations=new OzonCardRegistrationRepository();
    private final OzonCardRegistrationWorkflow workflow=new OzonCardRegistrationWorkflow();
    private java.util.function.BiConsumer<ProductKey,Boolean> preparationStarter=this::readPreparation;
    private java.util.function.BiPredicate<ProductKey,Boolean> replacementConfirmation=(key,newCard)->confirm(tr(newCard&&state(key)==Status.ERROR?"znack.registration.confirm_rejected_replacement":"znack.registration.confirm_replacement"));
    private final OzonRegistrationBatchService batches=new OzonRegistrationBatchService();
    private java.util.function.BiFunction<Shop,String,Boolean> batchControl=(owner,action)->switch(action){case "PAUSE"->batches.pause(owner);case "RESUME"->batches.resumePaused(owner);case "CANCEL"->batches.cancel(owner);default->false;};
    private java.util.function.BooleanSupplier cancelPendingConfirmation=RegistrationDiagnostics::confirmCancelPending;
    private final MenuItem pauseBatchAction=new MenuItem(),resumeBatchAction=new MenuItem(),cancelPendingBatchAction=new MenuItem();
    private boolean batchControlBusy;
    private java.util.function.Function<Shop,OzonRegistrationBatchService.Progress> batchReader=batches::latest;
    private final Label batchProgress=new Label();
    private final javafx.animation.Timeline batchRefresh=new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(1),e->readBatchProgress()));
    private final java.util.concurrent.atomic.AtomicBoolean batchReadQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private OzonRegistrationBatchService.Progress batchState;
    private String batchPillText="";
    private final PauseTransition batchRowsRefresh=new PauseTransition(Duration.millis(400));
    private long lastBatchRowsRefreshNanos;
    private boolean batchRowsReadInFlight;

    private final TableView<ProductRow> table=new TableView<>();
    private final Map<ProductKey,OzonCardRegistrationRepository.Row> states=new HashMap<>();
    private final TextField search=new TextField();
    private final ComboBox<String> statusFilter=new ComboBox<>();
    private final MenuButton categoryFilter=new MenuButton();
    private final com.vncode.app.ui.controls.CategoryFilterMenu categories=new com.vncode.app.ui.controls.CategoryFilterMenu(categoryFilter,this::reload);
    private final CheckBox autoSign=new CheckBox(),allFiltered=new CheckBox();
    private final Label title=new Label(),status=new Label(),count=new Label(),selection=new Label();
    private final Button report=new Button(),documents=new Button(),refresh=new Button(),sync=new Button(),sign=new Button(),gtin=new Button(),all=new Button(),clear=new Button(),register=new Button(),newCards=new Button(),stopBatch=new Button(),more=new Button();
    private final PauseTransition debounce=new PauseTransition(Duration.millis(250)),refreshDebounce=new PauseTransition(Duration.millis(400));
    private List<ProductKey> frozen=List.of();
    private String cursor;
    private long total,generation;
    private boolean busy,disposed,programmaticSelection,batchReviewing,deferredReload,selectionPreparing;
    private volatile long lastBatchProgress;
    private Task<RegistrationBatchPreflight.Review> registrationPreflightTask;
    private Task<?> signingReviewTask;
    private java.util.function.BiFunction<Shop,List<ProductKey>,RegistrationBatchPreflight.Review> preflightReader=RegistrationBatchPreflight::readComplete;
    private final java.util.concurrent.atomic.AtomicBoolean cancelBatch=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean progressQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reviewPending=new java.util.concurrent.atomic.AtomicBoolean();

    private String lastActionRun="";
    public OzonRegistrationPane(Shop shop){this(shop,null);}
    public OzonRegistrationPane(Shop shop,Runnable openGtin) {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");
        this.owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.openGtin=openGtin==null?()->GtinBackgroundStatus.start((StackPane)table.getParent(),owner,new HashSet<>(selected()),this::refreshLocal,result->{lastActionRun=result.run();status.setText(GtinBackgroundStatus.summary(result));}):openGtin;
        setId("ozonRegistrationPane");getStyleClass().add("registration-reference");setSpacing(10);setPadding(new Insets(38,18,20,30));
        title.getStyleClass().add("h2");status.setWrapText(true);status.getStyleClass().add("text-muted");
        title.setMinWidth(210);title.setPrefWidth(240);title.setMaxWidth(260);var space=new Region();HBox.setHgrow(space,Priority.ALWAYS);
        var header=new HBox(14,title,space,documents,sign,gtin,sync);header.setId("ozonRegistrationHeader");header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        for(var button:List.of(documents,sign,gtin,sync)){button.setMinWidth(100);button.setPrefWidth(210);button.setWrapText(true);}
        documents.setPrefWidth(158);sign.setPrefWidth(230);gtin.setPrefWidth(264);sync.setPrefWidth(237);
        var options=new ContextMenu();for(var node:List.<javafx.scene.Node>of(refresh,report,newCards,autoSign,clear,stopBatch,selection))options.getItems().add(new CustomMenuItem(node,false));
        pauseBatchAction.setId("ozonZnackPauseBatch");resumeBatchAction.setId("ozonZnackResumeBatch");cancelPendingBatchAction.setId("ozonZnackCancelPendingBatch");
        pauseBatchAction.setOnAction(e->controlBatch("PAUSE"));resumeBatchAction.setOnAction(e->controlBatch("RESUME"));cancelPendingBatchAction.setOnAction(e->{if(cancelPendingConfirmation.getAsBoolean())controlBatch("CANCEL");});options.getItems().addAll(new SeparatorMenuItem(),pauseBatchAction,resumeBatchAction,cancelPendingBatchAction);
        title.setContextMenu(options);title.setFocusTraversable(true);title.setTooltip(new Tooltip(owner.getName()+"\n"+tr("znack.registration.more_actions_hint")));
        statusFilter.getItems().setAll("ALL","NOT_CREATED","IN_PROGRESS","GTIN_REVIEW_REQUIRED","FEED_REVIEW_REQUIRED","COMPLETED","ERROR","MATCH_REVIEW");statusFilter.setValue("ALL");statusFilter.setId("ozonRegistrationStatusFilter");statusFilter.setPrefWidth(220);
        statusFilter.setConverter(new javafx.util.StringConverter<>(){public String toString(String value){return tr("znack.registration.filter."+(value==null?"all":value.toLowerCase(java.util.Locale.ROOT)));}public String fromString(String value){throw new UnsupportedOperationException();}});
        statusFilter.valueProperty().addListener((observable,previous,current)->reload());
        var clearFilters=new Button(tr("znack.registration.clear"));clearFilters.setOnAction(e->{search.clear();categories.clear();statusFilter.setValue("ALL");reload();});
        var brandConfig=new Button(tr("ozon.brand.configure"));brandConfig.setId("ozonConfigureBrand");brandConfig.setOnAction(e->configureBrand());
        categoryFilter.setId("ozonRegistrationCategoryFilter");var filters=new FlowPane(10,8,search,categoryFilter,statusFilter,clearFilters);autoSign.setSelected(true);autoSign.setWrapText(true);autoSign.setId("ozonRegistrationAutoSign");search.setPrefWidth(375);categoryFilter.setPrefWidth(134);statusFilter.setPrefWidth(225);VBox.setMargin(filters,new Insets(45,0,14,15));
        options.getItems().add(new CustomMenuItem(brandConfig,false));
        allFiltered.setOnAction(e->{if(allFiltered.isSelected())all.fire();else clear.fire();});
        var footer=new HBox(10,allFiltered,register,count,more);footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(search,Priority.ALWAYS);batchProgress.setCursor(javafx.scene.Cursor.HAND);batchProgress.setOnMouseClicked(e->report.fire());batchProgress.setId("ozonRegistrationBatchProgress");batchProgress.setWrapText(true);batchProgress.getStyleClass().add("text-muted");getChildren().setAll(header,status,filters,new StackPane(table),footer,batchProgress);VBox.setVgrow(table.getParent(),Priority.ALWAYS);
        TableScrollLoader.install(table,()->!disposed&&!busy&&!batchReviewing&&ready()&&cursor!=null,()->load(true));
        table.setId("ozonRegistrationTable");table.setFixedCellSize(76);table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<ProductRow>)change->{if(!programmaticSelection){frozen=List.of();buttons();}});
        documents.setOnAction(e->configureDocuments());refresh.setOnAction(e->reload());sync.setOnAction(e->syncCatalog());sign.setOnAction(e->signApproved());gtin.setOnAction(e->{if(ready()&&!busy)this.openGtin.run();});report.setOnAction(e->RegistrationSyncReportDialog.show(getScene()==null?null:getScene().getWindow(),owner,lastActionRun));
        stopBatch.setOnAction(e->{cancelBatch.set(true);stopBatch.setDisable(true);status.setText(tr("znack.registration.batch_stopping"));});all.setOnAction(e->selectAll());clear.setOnAction(e->{frozen=List.of();table.getSelectionModel().clearSelection();buttons();});
        register.setOnAction(e->{if("ERROR".equals(statusFilter.getValue())||table.getSelectionModel().getSelectedItems().stream().anyMatch(row->view(row).status().equals("source.errors")))openErrorRepair(selected().isEmpty());else registerSelection();});newCards.setOnAction(e->registerBatch(true));more.setOnAction(e->load(true));
        debounce.setOnFinished(e->reload());refreshDebounce.setOnFinished(e->refreshBatchRows());batchRowsRefresh.setOnFinished(e->refreshBatchRows());search.textProperty().addListener((o,a,b)->debounce.playFromStart());
        applyTranslations();reload();batchRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE);batchRefresh.play();readBatchProgress();
    }
    public void refreshLocal(){if(GtinBackgroundStatus.holdsRows((StackPane)table.getParent()))return;if(!busy&&!batchReviewing)refreshDebounce.playFromStart();else deferredReload=true;}
    public void dispose(){disposed=true;if(registrationPreflightTask!=null){registrationPreflightTask.cancel(true);registrationPreflightTask=null;}if(signingReviewTask!=null){signingReviewTask.cancel(true);signingReviewTask=null;}cancelBatch.set(true);++generation;debounce.stop();refreshDebounce.stop();batchRefresh.stop();batchRowsRefresh.stop();}
    private boolean batchActive(){return batchState!=null&&!Set.of("COMPLETED","FAILED","CANCELLED").contains(batchState.state().name());}
    private boolean batchRunning(){return batchActive()&&!"PAUSED".equals(batchState.state().name());}
    private void readBatchProgress(){
        if(disposed||!batchReadQueued.compareAndSet(false,true))return;
        var task=new Task<OzonRegistrationBatchService.Progress>(){protected OzonRegistrationBatchService.Progress call(){return batchReader.apply(owner);}};
        task.setOnSucceeded(e->{batchReadQueued.set(false);if(!disposed)renderBatchProgress(task.getValue());});
        Consumer<Throwable> failed=error->{batchReadQueued.set(false);if(!disposed)showBatchProgressFailure(error);};
        task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private String batchSummary(OzonRegistrationBatchService.Progress progress){
        boolean paused="PAUSED".equals(progress.state().name()),cancelled="CANCELLED".equals(progress.state().name());
        boolean active=!Set.of("COMPLETED","FAILED","CANCELLED").contains(progress.state().name());
        String key=active?(progress.state()==OzonRegistrationBatchService.State.WAITING?"znack.registration.batch_submission_waiting":"znack.registration.batch_submission_progress"):"znack.registration.batch_submission_finished";
        return tr("znack.registration.report_scope_batch")+" · "+(paused?tr("znack.registration.batch_paused")+" · ":cancelled?tr("znack.status_value.cancelled")+" · ":"")+java.text.MessageFormat.format(tr(key),progress.submitted(),progress.total(),progress.remaining(),progress.failed())+" · "+java.text.MessageFormat.format(tr("znack.registration.batch_receipts"),progress.acceptedFeeds(),progress.total(),progress.awaitingReadback());
    }
    private void showBatchProgressFailure(Throwable error){
        String code=SyncFailure.of(error,0).code();String detail=reason(code);
        if(detail.equals(code))detail=tr(code.equals("SYNC_STORAGE")?"error.database_storage":code.equals("SYNC_STORAGE_BUSY")?"error.database_busy":code.startsWith("NETWORK_")?"error.network":"znack.registration.batch_progress_read_unknown");
        String text=java.text.MessageFormat.format(tr("znack.registration.batch_progress_read_failed"),detail,code);
        if(batchState!=null)text=batchSummary(batchState)+" · "+text;
        batchProgress.setText(text);
        if(!text.equals(batchPillText)){batchPillText=text;RegistrationStatusPill.show((StackPane)table.getParent(),"ozonRegistrationBatchStatus",text,false);}
    }
    private void renderBatchProgress(OzonRegistrationBatchService.Progress progress){
        var previous=batchState;batchState=progress;
        if(progress==null){batchProgress.setText("");batchPillText="";((StackPane)table.getParent()).getChildren().removeIf(node->"ozonRegistrationBatchStatus".equals(node.getId()));buttons();return;}
        if(lastActionRun.isBlank()||batchActive()&&(previous==null||!previous.runId().equals(progress.runId())))lastActionRun=progress.runId();
        String text=batchSummary(progress);
        if(!safe(progress.detail()).isBlank()){
            String detail=reason(progress.detail());
            if(detail.equals(progress.detail())&&!detail.matches("[A-Z][A-Z0-9_]{1,100}"))detail=tr("znack.registration.batch_progress_read_unknown");
            text+=" · "+CatalogFeedback.display(detail);
        }
        batchProgress.setText(text);if(!progress.lastSubmittedArticle().isBlank())text+=" · "+progress.lastSubmittedArticle();
        if(!text.equals(batchPillText)){batchPillText=text;RegistrationStatusPill.show((StackPane)table.getParent(),"ozonRegistrationBatchStatus",text,batchRunning(),!batchActive());}
        boolean changed=previous==null||previous.submitted()!=progress.submitted()||previous.failed()!=progress.failed()||previous.state()!=progress.state()||!previous.detail().equals(progress.detail());
        boolean watchingProcessing="IN_PROGRESS".equals(statusFilter.getValue())&&batchActive()
                &&System.nanoTime()-lastBatchRowsRefreshNanos>=java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        if(changed||watchingProcessing)requestBatchRowsRefresh();buttons();
    }
    private void requestBatchRowsRefresh(){
        if(disposed||busy||batchReviewing||selectionPreparing||batchRowsReadInFlight||GtinBackgroundStatus.holdsRows((StackPane)table.getParent()))return;
        if(batchRowsRefresh.getStatus()!=javafx.animation.Animation.Status.RUNNING)batchRowsRefresh.playFromStart();
    }
    private void refreshBatchRows(){
        if(disposed||busy||batchReviewing||selectionPreparing||batchRowsReadInFlight||GtinBackgroundStatus.holdsRows((StackPane)table.getParent()))return;
        lastBatchRowsRefreshNanos=System.nanoTime();load(false,true);
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    public void applyTranslations() {
        pauseBatchAction.setText(tr("catalogsync.pause"));resumeBatchAction.setText(tr("catalogsync.resume"));cancelPendingBatchAction.setText(tr("gtinsync.cancel"));
        categories.setTexts(tr("ozon.mapping.filter.category"),tr("ozon.mapping.filter.category"),tr("znack.registration.clear"));
        title.setText(tr("znack.registration.title"));
        documents.setText(tr("znack.registration.documents"));refresh.setText(tr("znack.registration.refresh"));sync.setText(tr("znack.registration.sync_znack"));report.setText(tr("znack.registration.report"));sign.setText(tr("znack.registration.sign_approved"));
        gtin.setText(tr("znack.registration.update_ozon"));all.setText(tr("znack.registration.select_all"));clear.setText(tr("znack.registration.clear_selection"));
        autoSign.setText(tr("znack.registration.auto_sign"));allFiltered.setText(tr("znack.registration.select_all_filtered"));register.setText(tr("znack.registration.register_short"));newCards.setText(tr("znack.registration.batch_new_cards"));stopBatch.setText(tr("znack.registration.batch_stop"));more.setText(tr("znack.registration.load_more"));search.setPromptText(tr("products.search"));
        table.getColumns().clear();
        var select=new TableColumn<ProductRow,ProductRow>("✓");select.setPrefWidth(64);select.setMaxWidth(64);select.setSortable(false);select.setCellValueFactory(v->new ReadOnlyObjectWrapper<>(v.getValue()));
        select.setCellFactory(v->new TableCell<>(){final CheckBox check=new CheckBox();{setAlignment(javafx.geometry.Pos.CENTER);check.setOnAction(e->{if(getItem()==null)return;frozen=List.of();if(check.isSelected())table.getSelectionModel().select(getItem());else table.getSelectionModel().clearSelection(getIndex());buttons();});check.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);}protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);if(empty||row==null){setGraphic(null);return;}check.setSelected(selected().contains(row.key()));setGraphic(check);}});table.getColumns().add(select);
        var images=new TableColumn<ProductRow,ProductRow>(tr("fbo.column.image"));images.setPrefWidth(90);images.setMaxWidth(90);images.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        var imageService=new com.vncode.app.features.fbo.FboProductImageService();
        images.setCellFactory(column->new TableCell<>(){
            private final javafx.scene.image.ImageView image=new javafx.scene.image.ImageView();private String current="";
            {image.setFitWidth(40);image.setFitHeight(56);image.setPreserveRatio(true);}
            protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);current=empty||row==null?"":safe(row.thumbnailUrl());image.setImage(null);setGraphic(current.isBlank()?null:image);String requested=current;
                if(!requested.isBlank())imageService.loadThumbnailBytes(requested).whenComplete((bytes,error)->Platform.runLater(()->{if(!disposed&&current.equals(requested)&&bytes!=null&&bytes.length>0)image.setImage(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)));}));}
        });table.getColumns().add(images);column(tr("znack.registration.review_product_offer_id"),row->row.key().cardId()+" / "+row.key().variantId());column(tr("fbo.column.name"),row->row.title()+(safe(row.tnved()).isBlank()?"":"\nTN VED: "+row.tnved()));
        column("SKU",row->skus.getOrDefault(row.key(),""));column(tr("products.article"),ProductRow::vendorArticle);column(tr("znack.registration.gender"),ProductRow::gender);column(tr("fbo.column.color"),ProductRow::color);
        column("ozonOriginalSize",tr("znack.registration.column_size_original"),ProductRow::size);
        column("ozonRussianSize",tr("znack.registration.column_size_ru"),row->RegistrationTableText.russianSize(row.russianSize()));
        column("ozonRawBarcodes",tr("znack.registration.barcode_ozon"),row->String.join(", ",row.barcodes())+(view(row).gtin().isBlank()?"":"\nGTIN: "+view(row).gtin()));
        column("ozonVerifiedGtin",tr("znack.registration.verified_gtin"),this::verifiedGtinText);
        column("ozonRegistrationStatus",tr("znack.registration.column_registration"),row->{var observed=view(row);String text=tr("znack.registration."+observed.status());return observed.status().equals("source.unverified")?text+" · "+RegistrationDiagnostics.reason(observed.detail()):text;});
        column("ozonModerationStatus",tr("znack.registration.column_moderation"),row->RegistrationTableText.moderation(
                cardIndex==null?Optional.empty():cardIndex.observation(view(row)),latestReadUnknown));
        column("ozonMarketplaceSyncStatus",java.text.MessageFormat.format(tr("znack.registration.column_marketplace_sync"),"Ozon"),
                row->RegistrationTableText.marketplace(verifiedGtinProof(row),row.barcodes(),latestReadUnknown,sourceReadAt,"znack.registration.awaiting_ozon_gtin"));
        column(tr("jobs.reason"),row->reason(view(row).detail())+(states.get(row.key())==null||safe(states.get(row.key()).error()).isBlank()?"":" · "+RegistrationDiagnostics.reason(states.get(row.key()).error())));
        var actions=new TableColumn<ProductRow,ProductRow>(tr("znack.registration.action"));actions.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        actions.setCellFactory(c->new TableCell<>() {
            final MenuButton menu=new MenuButton();final MenuItem create=new MenuItem(),resume=new MenuItem(),switchCard=new MenuItem(),newRejected=new MenuItem();
            {menu.getItems().setAll(create,resume,switchCard,newRejected);newRejected.setOnAction(e->prepare(getItem().key(),true));create.setOnAction(e->{var row=getItem();if(reviewableDraft(row)||notSubmitted(row))registerSelection(List.of(row.key()));else prepare(row.key(),state(row.key())==Status.PUBLISHED||groupReplacement(row));});
                resume.setOnAction(e->resume(getItem().key()));switchCard.setOnAction(e->complete(getItem().key()));}
            @Override protected void updateItem(ProductRow row,boolean empty) {
                super.updateItem(row,empty);if(empty||row==null){setGraphic(null);return;}
                Status state=state(row.key());var checkpoint=states.get(row.key());
                if(RegistrationCardIndex.catalogRejected(state,checkpoint==null?"":checkpoint.error(),view(row))){
                    var edit=new Button(tr("repair.edit"));edit.getStyleClass().add("btn-primary");edit.setDisable(busy||!ready());
                    edit.setOnAction(e->CatalogErrorRepairDialog.open((StackPane)table.getParent(),owner,Set.of(row.key()),false,OzonRegistrationPane.this::refreshLocal));
                    setGraphic(edit);return;
                }
                String pendingAction=reviewableDraft(row)?"":RegistrationDiagnostics.pendingCardAction(state,checkpoint==null?null:checkpoint.goodId(),checkpoint==null?"":checkpoint.feedId(),checkpoint==null?"":checkpoint.error(),view(row).status());
                if(!pendingAction.isBlank()){var waiting=new Label(tr(pendingAction));waiting.setWrapText(true);setGraphic(waiting);return;}
                boolean candidate=checkpoint!=null&&!safe(checkpoint.gtin()).isBlank()&&!checkpoint.activeGtin().equals(checkpoint.gtin());
                newRejected.setText(tr("znack.registration.create_new_card"));newRejected.setVisible(state==Status.ERROR);newRejected.setDisable(busy||!ready()||view(row).blocked()||checkpoint==null||checkpoint.feedId().isBlank()||checkpoint.gtin().isBlank());
                menu.setText(tr("znack.registration.action"));menu.setDisable(busy||!ready());create.setText(tr(reviewableDraft(row)?"znack.registration.review_existing_draft":state==Status.PUBLISHED||groupReplacement(row)?"znack.registration.create_new_card":"znack.registration.create"));
                create.setDisable(!ready()||busy||!reviewableDraft(row)&&(view(row).blocked()||(view(row).exists()&&state!=Status.PUBLISHED&&!groupReplacement(row))||(!Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.PUBLISHED).contains(state)&&!groupReplacement(row))||(state==Status.PUBLISHED&&candidate&&!groupReplacement(row))));
                resume.setText(tr("znack.registration.resume"));resume.setDisable(!ready()||view(row).blocked()||view(row).published()||busy||checkpoint==null||(safe(checkpoint.feedId()).isBlank()&&state!=Status.READY_TO_SUBMIT)||state==Status.PUBLISHED);
                switchCard.setText(tr("znack.registration.switch_card"));switchCard.setDisable(!ready()||!view(row).published()||busy||state!=Status.PUBLISHED||!candidate);setGraphic(menu);
            }
        });actions.setMinWidth(130);actions.setPrefWidth(134);table.getColumns().add(actions);
        for(var column:table.getColumns()){
            if(Set.of("ozonRussianSize","ozonVerifiedGtin","ozonModerationStatus","ozonMarketplaceSyncStatus").contains(java.util.Objects.toString(column.getId(),""))||Set.of(tr("znack.registration.review_product_offer_id"),"SKU",tr("jobs.reason")).contains(column.getText()))column.setVisible(false);
            if(column.getText().equals(tr("fbo.column.name")))column.setPrefWidth(250);
            if(column.getText().equals(tr("products.article"))){column.setPrefWidth(142);column.setText(tr("products.seller_article"));}
            if(column.getText().equals(tr("znack.registration.gender"))||column.getText().equals(tr("fbo.column.color")))column.setPrefWidth(80);
            if("ozonOriginalSize".equals(column.getId()))column.setPrefWidth(62);
            if("ozonRawBarcodes".equals(column.getId())){column.setText(tr("znack.registration.barcode_ozon")+" / GTIN");column.setPrefWidth(210);}
            if("ozonRegistrationStatus".equals(column.getId())){column.setText(tr("znack.registration.status"));column.setPrefWidth(154);}
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setRowFactory(t->new TableRow<>(){{setOnMouseClicked(e->{if(e.getClickCount()==2&&getItem()!=null)showRegistrationReport(getItem());});}@Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);var r=row==null?null:states.get(row.key());setTooltip(empty||row==null?null:new Tooltip(observation(row)+(r==null||safe(r.error()).isBlank()?"":"\n"+RegistrationDiagnostics.reason(r.error()))));
            if(empty||row==null){setContextMenu(null);return;}
            var report=new MenuItem(tr("znack.registration.report"));report.setOnAction(e->showRegistrationReport(row));setContextMenu(new ContextMenu(report));
        }});buttons();
    }
    private TableColumn<ProductRow,String> column(String text,java.util.function.Function<ProductRow,String> value){var column=new TableColumn<ProductRow,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(160);if(text.equals(tr("jobs.reason"))){column.setMinWidth(150);RegistrationTextCells.wrap(column);}table.getColumns().add(column);return column;}
    private void column(String id,String label,java.util.function.Function<ProductRow,String> value){var column=column(label,value);column.setId(id);RegistrationTextCells.wrap(column);}
    private String verifiedGtinText(ProductRow row){
        var nativeRow=states.get(row.key());var proof=verifiedGtinProof(row);
        String candidate=safe(view(row).gtin());if(candidate.isBlank())candidate=nativeRow==null?safe(row.verifiedGtin()):safe(nativeRow.gtin());return ZnackCardRegistrationController.verifiedGtinText(proof,candidate)+candidateText(row.key());
    }
    private Optional<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin> verifiedGtinProof(ProductRow row){
        var nativeRow=states.get(row.key());return cardIndex==null||latestReadUnknown?Optional.empty():cardIndex.verifiedOzon(row,state(row.key()),nativeRow==null?"":nativeRow.gtin(),nativeRow==null||cardIndex.ownsOzon(nativeRow.owner(),nativeRow.scopeJson()));
    }
    private ProductQuery query(String cursor){return new ProductQuery(owner.getId(),Marketplace.OZON,search.getText(),List.copyOf(categories.selectedCategories()),null,false,cursor,300);}
    private void reload(){if(disposed)return;if(batchReviewing){deferredReload=true;return;}debounce.stop();batchRowsRefresh.stop();++generation;batchRowsReadInFlight=false;cursor=null;frozen=List.of();readiness=null;latestReadUnknown=true;load(false);}
    private record Loaded(RegistrationSources.Readiness readiness,RegistrationCardIndex index,ProductPage page,Map<ProductKey,OzonCardRegistrationRepository.Row> states,Map<ProductKey,String> skus,List<String> categories){}
    private void load(boolean append) {
        load(append,false);
    }
    private void load(boolean append,boolean backgroundRefresh) {
        if(disposed||(append&&(busy||batchRowsReadInFlight||cursor==null)))return;
        String filter=statusFilter.getValue();boolean filtered=!"ALL".equals(filter);int errorStart=append?table.getItems().size():0;
        ProductQuery query=query(filtered?null:append?cursor:null);
        int visibleExtent=backgroundRefresh?Math.max(300,table.getItems().size()):300;
        var previous=cardIndex;var retain=new java.util.concurrent.atomic.AtomicBoolean();
        Callable<Loaded> read=()->{
            retain.set(previous!=null&&previous.currentScope(owner));
            var ready=sources.check(owner);if(!ready.ready())return new Loaded(ready,null,null,Map.of(),Map.of(),List.of());
            var index=new RegistrationCardIndex(owner);ProductPage page;
            if(filtered)page=filteredPage(query,ready,index,errorStart,filter,visibleExtent);
            else{
                page=products.loadPage(query,ready.run());
                if(backgroundRefresh&&visibleExtent>300){
                    var rows=new LinkedHashMap<ProductKey,ProductRow>();page.rows().forEach(row->rows.put(row.key(),row));String next=page.nextCursor();
                    while(next!=null&&rows.size()<visibleExtent){
                        var more=products.loadPage(new ProductQuery(query.shopId(),query.marketplace(),query.text(),query.categoryIds(),query.documentState(),query.missingGtin(),next,Math.min(300,visibleExtent-rows.size()),query.issue()),ready.run());
                        more.rows().forEach(row->rows.put(row.key(),row));next=more.nextCursor();if(more.rows().isEmpty())break;
                    }
                    page=new ProductPage(List.copyOf(rows.values()),page.total(),next);
                }
            }
            var keys=page.rows().stream().map(ProductRow::key).toList();var nativeSkus=new HashMap<ProductKey,String>();
            if(!keys.isEmpty())try(var c=com.vncode.app.config.Database.getConnection()){
                var selected=new HashSet<>(keys);
                for(int start=0;start<keys.size();start+=300){var chunk=keys.subList(start,Math.min(start+300,keys.size()));
                    try(var p=c.prepareStatement("SELECT product_id,offer_id,sku FROM ozon_products WHERE shop_id=? AND product_id IN ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+")")){
                        p.setInt(1,owner.getId());for(int n=0;n<chunk.size();n++)p.setString(n+2,chunk.get(n).cardId());
                        try(var rows=p.executeQuery()){while(rows.next()){var key=new ProductKey(owner.getId(),Marketplace.OZON,rows.getString(1),rows.getString(2));if(selected.contains(key))nativeSkus.put(key,rows.getString(3));}}
                    }
                }
            }
            if(!sources.require(owner).run().equals(ready.run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
            return new Loaded(ready,index,page,registrations.findMany(keys),Map.copyOf(nativeSkus),products.categories(owner.getId(),Marketplace.OZON));
        };
        Consumer<Loaded> apply=loaded->{
            var retained=backgroundRefresh?List.copyOf(selected()):List.<ProductKey>of();
            readiness=loaded.readiness();
            if(!ready()){sourceReadUnavailable(readiness.reason(),retain.get());return;}
            cardIndex=loaded.index();latestReadUnknown=false;sourceReadAt=readiness.completed();categories.rebuild(loaded.categories());
            skus.putAll(loaded.skus());
            if(!append)states.clear();states.putAll(loaded.states());total=loaded.page().total();cursor=loaded.page().nextCursor();
            programmaticSelection=true;try{
                boolean same=backgroundRefresh&&!append&&table.getItems().equals(loaded.page().rows());
                if(!append){if(backgroundRefresh)frozen=retained;if(!same)table.getItems().clear();}
                if(!same)table.getItems().addAll(loaded.page().rows());
                if(backgroundRefresh){var selectedKeys=new HashSet<>(retained);for(int n=0;n<table.getItems().size();n++)if(selectedKeys.contains(table.getItems().get(n).key()))table.getSelectionModel().select(n);}
            }finally{programmaticSelection=false;}
            status.setText(tr("znack.registration.sources_ready")+" · "+readiness.completed()+"\n"+tr("znack.registration.ozon_review_note"));table.refresh();buttons();
        };
        if(!backgroundRefresh){background(read,apply,false,error->sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get()));return;}
        long current=generation;batchRowsReadInFlight=true;
        var task=new Task<Loaded>(){protected Loaded call()throws Exception{return read.call();}};
        task.setOnSucceeded(e->{if(disposed||current!=generation)return;batchRowsReadInFlight=false;apply.accept(task.getValue());});
        Consumer<Throwable> failed=error->{if(disposed||current!=generation)return;batchRowsReadInFlight=false;sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get());showBatchProgressFailure(error);};
        task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void selectAll(){
        if(busy||!ready())return;if("ERROR".equals(statusFilter.getValue())){allFiltered.setSelected(false);openErrorRepair(true);return;}
        ProductQuery query=query(null);String filter=statusFilter.getValue();
        background(()->{var receipt=sources.require(owner);var keys="ALL".equals(filter)?products.freezeKeys(query,receipt.run()):filteredRows(query,receipt,new RegistrationCardIndex(owner),filter).stream().map(ProductRow::key).toList();return "NOT_CREATED".equals(filter)?inspectAllowedKeys(keys,false,false).keys():keys;},keys->{frozen=keys;var chosen=new HashSet<>(keys);programmaticSelection=true;try{table.getSelectionModel().clearSelection();for(var row:table.getItems())if(chosen.contains(row.key()))table.getSelectionModel().select(row);}finally{programmaticSelection=false;}buttons();});
    }
    private void openErrorRepair(boolean all){CatalogErrorRepairDialog.open((StackPane)table.getParent(),owner,new HashSet<>(selected()),all,this::refreshLocal);}
    private ProductPage filteredPage(ProductQuery query,RegistrationSources.Readiness ready,RegistrationCardIndex index,int offset,String filter){
        return filteredPage(query,ready,index,offset,filter,300);
    }
    private ProductPage filteredPage(ProductQuery query,RegistrationSources.Readiness ready,RegistrationCardIndex index,int offset,String filter,int limit){
        var matches=filteredRows(query,ready,index,filter);int start=Math.min(offset,matches.size()),end=Math.min(start+limit,matches.size());
        return new ProductPage(matches.subList(start,end),matches.size(),end<matches.size()?"filtered:"+end:null);
    }
    private List<ProductRow> filteredRows(ProductQuery query,RegistrationSources.Readiness ready,RegistrationCardIndex index,String filter){
        String next=null;var matches=new ArrayList<ProductRow>();int scanned=0;
        do{var page=products.loadPage(new ProductQuery(owner.getId(),Marketplace.OZON,query.text(),query.categoryIds(),null,false,next,1000),ready.run());var checkpoints=registrations.findMany(page.rows().stream().map(ProductRow::key).toList());
            for(var row:page.rows()){if(++scanned>100000)throw new IllegalArgumentException("SYNC_REPORT_LIMIT");var nativeRow=checkpoints.get(row.key());var state=nativeRow==null?Status.NOT_CREATED:nativeRow.status();var observed=index.viewOzon(state,nativeRow==null?"":nativeRow.gtin(),row,nativeRow==null||index.ownsOzon(nativeRow.owner(),nativeRow.scopeJson()));if(matchesFilter(filter,nativeRow,observed))matches.add(row);}
            next=page.nextCursor();
        }while(next!=null);return List.copyOf(matches);
    }
    static boolean matchesFilter(String filter,OzonCardRegistrationRepository.Row row,RegistrationCardIndex.View observed){
        Status state=row==null?Status.NOT_CREATED:row.status();
        return switch(filter){
            case "MATCH_REVIEW" -> RegistrationCardIndex.needsMatchReview(observed);
            case "GTIN_REVIEW_REQUIRED" -> row!=null&&row.gtin().isBlank()&&row.goodId()==null&&row.feedId().isBlank()&&Set.of(Status.CHECKING,Status.ALLOCATING_GTIN,Status.GTIN_REVIEW_REQUIRED,Status.ERROR).contains(state);
            case "FEED_REVIEW_REQUIRED" -> row!=null&&!row.gtin().isBlank()&&row.feedId().isBlank()&&Set.of(Status.FEED_SUBMITTING,Status.FEED_REVIEW_REQUIRED,Status.GTIN_GENERATED,Status.CHECKING,Status.ERROR).contains(state);
            case "ERROR" -> RegistrationCardIndex.catalogRejected(state,row==null?"":row.error(),observed);
            case "NOT_CREATED" -> !RegistrationCardIndex.needsMatchReview(observed)&&RegistrationCardIndex.awaitingCatalogCard(state,row==null?null:row.goodId(),row==null?"":row.error(),observed);
            case "IN_PROGRESS" -> RegistrationCardIndex.catalogCardInProgress(state,row==null?null:row.goodId(),row==null?"":row.error(),observed);
            case "COMPLETED" -> observed.published();
            default -> true;
        };
    }
    private List<ProductKey> selected(){return frozen.isEmpty()?table.getSelectionModel().getSelectedItems().stream().map(ProductRow::key).toList():frozen;}
    private boolean groupReplacement(ProductRow product){var row=states.get(product.key());return row!=null&&com.vncode.app.integration.znack.registration.TnvedGroupChange.replaceable(row.status())&&!row.feedId().isBlank()&&com.vncode.app.integration.znack.registration.TnvedGroupChange.changed(com.vncode.app.integration.znack.registration.TnvedGroupChange.payloadCode(row.payload()),product.tnved());}
    private boolean groupReplacement(ProductKey key){return table.getItems().stream().filter(p->p.key().equals(key)).anyMatch(this::groupReplacement);}
    private void registerSelection(){registerSelection(List.copyOf(selected()));}
    private void registerSelection(List<ProductKey> selected){
        if(selected.isEmpty()||busy||selectionPreparing||batchActive()||!ready())return;
        selectionPreparing=true;lastActionRun="";buttons();var existingDraft=new java.util.concurrent.atomic.AtomicBoolean();
        var allowed=new java.util.concurrent.atomic.AtomicReference<List<ProductKey>>(List.of());
        var task=new Task<RegistrationBatchPreflight.Review>(){protected RegistrationBatchPreflight.Review call()throws Exception{
            var inspected=inspectAllowedKeys(selected,false,false);existingDraft.set(inspected.existingDraft());allowed.set(inspected.keys());
            if(inspected.keys().isEmpty())throw new IllegalArgumentException("SOURCE_CARD_ACTION_CHANGED");
            return preflightReader.apply(owner,inspected.keys());
        }};
        registrationPreflightTask=task;var cancelledBySeller=new java.util.concurrent.atomic.AtomicBoolean();
        RegistrationBatchPreflight.readPill((StackPane)table.getParent(),"ozonRegistrationPreflightStatus",task,()->cancelledBySeller.set(true));
        task.setOnSucceeded(e->{if(disposed)return;registrationPreflightTask=null;selectionPreparing=false;buttons();
            boolean applied=RegistrationBatchPreflight.show(getScene()==null?null:getScene().getWindow(),owner,task.getValue(),existingDraft.get());
            if(applied&&!disposed)registerPreparedSelection(allowed.get());
        });
        var notified=new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<Throwable> failed=error->{if(!notified.compareAndSet(false,true)||disposed)return;registrationPreflightTask=null;selectionPreparing=false;buttons();status.setText(FriendlyErrorService.format(error));AlertService.showDetailedError(com.vncode.app.integration.znack.ZnackErrorDetails.summary(error),com.vncode.app.integration.znack.ZnackErrorDetails.format(error));};task.setOnFailed(e->failed.accept(task.getException()));task.setOnCancelled(e->{if(cancelledBySeller.get()){if(!disposed){registrationPreflightTask=null;selectionPreparing=false;buttons();status.setText(tr("znack.registration.preflight.cancelled"));}}else Platform.runLater(()->failed.accept(RegistrationBatchPreflight.readTimeout()));});AppTaskExecutor.executeCatalog(task,failed);
    }
    private void registerPreparedSelection(List<ProductKey> selected){
        if(selected.isEmpty()||selectionPreparing||batchActive())return;
        boolean automatic=autoSign.isSelected();selectionPreparing=true;lastActionRun="";buttons();
        var task=new Task<String>(){protected String call()throws Exception{return batches.start(owner,selected,automatic);}};
        task.setOnSucceeded(e->{if(disposed)return;selectionPreparing=false;lastActionRun=task.getValue();frozen=List.of();table.getSelectionModel().clearSelection();readBatchProgress();buttons();});
        Consumer<Throwable> failed=error->{if(disposed)return;selectionPreparing=false;buttons();status.setText(FriendlyErrorService.format(error));};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void registerBatch(boolean newCard) {
        registerBatch(newCard,List.copyOf(selected()));
    }
    private void registerBatch(boolean newCard,List<ProductKey> selection) {
        var keys=List.copyOf(selection);if(keys.isEmpty()||busy||!ready()||keys.size()>1000){status.setText(tr("znack.registration.batch_limit"));return;}
        cancelBatch.set(false);batchReviewing=true;buttons();
        background(()->{var allowed=allowedKeys(keys,newCard,false);var review=allowed.isEmpty()?new OzonCardRegistrationWorkflow.BatchReview(List.of(),newCard):workflow.prepareBatch(owner,allowed,newCard,n->{long now=System.nanoTime();if(n==keys.size()||now-lastBatchProgress>=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)){lastBatchProgress=now;Platform.runLater(()->{if(!disposed&&batchReviewing)status.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),n,keys.size()));});}});
            var items=new ArrayList<>(review.items());for(var key:keys)if(!allowed.contains(key))items.add(new OzonCardRegistrationWorkflow.BatchItem(key,null,null,"znack.registration.batch_not_eligible"));
            return new OzonCardRegistrationWorkflow.BatchReview(items,newCard);},review->{
            if(disposed)return;var approved=OzonRegistrationBatchDialog.review(review);
            if(approved.isEmpty()){batchReviewing=false;buttons();if(deferredReload){deferredReload=false;reload();}return;}
            busy=true;buttons();workflow.submitBatchReviewed(owner,approved.stream().map(item->new OzonCardRegistrationWorkflow.BatchItem(item.key(),item.preparation(),item.draft().withAutoSign(autoSign.isSelected()),item.issue())).toList(),newCard,true,listener(),cancelBatch::get).whenComplete((n,error)->Platform.runLater(()->{
                if(disposed)return;busy=false;batchReviewing=false;deferredReload=false;buttons();
                status.setText(error==null?java.text.MessageFormat.format(tr("znack.registration.batch_queued"),n):tr("znack.registration.batch_read_failed"));refreshDebounce.playFromStart();
            }));
        });
    }
    private void prepare(ProductKey key,boolean newCard) {
        if(busy||disposed||!ready())return;
        if(newCard&&!replacementConfirmation.test(key,true))return;
        preparationStarter.accept(key,newCard);
    }
    private void readPreparation(ProductKey key,boolean newCard){
        background(()->{allowedKeys(List.of(key),newCard);return workflow.prepare(owner,key);},prepared->continuePrepare(key,newCard,prepared));
    }
    private void continuePrepare(ProductKey key,boolean newCard,OzonCardRegistrationWorkflow.Preparation prepared) {
        if(!prepared.documents().issues().isEmpty()&&prepared.documents().candidates().isEmpty()){
            if(prepared.documents().issues().stream().anyMatch(issue->issue.contains("ZNACK_DOCUMENT_REGISTRY_PENDING"))){status.setText(tr("catalogsync.issue.REGISTRY_DOCUMENTS_PENDING"));return;}
            var review=new RegistrationBatchPreflight.Review(List.of(new RegistrationBatchPreflight.Finding("documents",key,prepared.snapshot().vendorArticle(),String.join("; ",prepared.documents().issues()),"")));
            if(RegistrationBatchPreflight.show(getScene()==null?null:getScene().getWindow(),owner,review)&&!disposed)prepare(key,newCard);
            return;
        }
        if(prepared.preflight()==null) {
            var dialog=new TextInputDialog(prepared.tnved());dialog.setTitle(tr("znack.registration.title"));dialog.setHeaderText(tr("znack.registration.ozon_tnved"));
            var value=dialog.showAndWait();if(value.isEmpty())return;
            if(!value.get().strip().matches("[0-9]{10}")){status.setText(tr("znack.registration.ozon_tnved"));return;}
            background(()->{sources.require(owner);return workflow.prepare(owner,key,value.get().strip());},next->continuePrepare(key,newCard,next));return;
        }
        if(prepared.categoryId()==0){status.setText(tr("znack.registration.category_auto_missing"));return;}
        var draft=OzonRegistrationReviewDialog.review(prepared);
        if(draft==null)return;
        var authorised=draft.withAutoSign(autoSign.isSelected());
        background(()->{allowedKeys(List.of(key),newCard);return workflow.registerReviewed(owner,prepared,authorised,true,newCard,listener());},accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});
    }
    private void resume(ProductKey key){if(!confirm(tr("znack.registration.confirm_resume")))return;boolean allowed=autoSign.isSelected();background(()->{sources.require(owner);return workflow.resume(owner,key,allowed,listener());},accepted->refreshDebounce.playFromStart());}
    private void complete(ProductKey key){if(!confirm(tr("znack.registration.confirm_switch")))return;background(()->{sources.require(owner);return workflow.complete(owner,key,true,listener());},accepted->refreshDebounce.playFromStart());}
    private void signApproved(){
        if(busy||!ready()||signingReviewTask!=null)return;var chosen=selected();ProductQuery query=query(null);
        background(()->{
            var keys=chosen.isEmpty()?products.freezeKeys(query,sources.require(owner).run()):chosen;
            var rows=registrations.findMany(keys);
            return keys.stream().filter(key->{var row=rows.get(key);return row!=null&&!row.feedId().isBlank()
                    &&Set.of(Status.READY_TO_SIGN,Status.FEED_SUBMITTED,Status.PROCESSING,Status.SIGNING).contains(row.status());}).toList();
        },keys->{
            if(keys.isEmpty()){status.setText(tr("znack.registration.no_approved"));return;}
            if(keys.size()>10000){status.setText(tr("znack.registration.batch_too_large"));return;}
            prepareSigningReview(keys);
        });
    }
    private void prepareSigningReview(List<ProductKey> keys){
        if(disposed||signingReviewTask!=null)return;var task=new Task<RegistrationSigningReview>(){protected RegistrationSigningReview call()throws Exception{return workflow.prepareApprovedSigning(owner,List.copyOf(keys));}};signingReviewTask=task;selectionPreparing=true;lastActionRun="";buttons();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();RegistrationBatchPreflight.readPill((StackPane)table.getParent(),"ozonRegistrationSigningReviewRead",task,()->cancelled.set(true));
        task.setOnSucceeded(event->{if(disposed)return;signingReviewTask=null;selectionPreparing=false;buttons();var reviewed=task.getValue();if(reviewed==null||reviewed.cards().isEmpty()){status.setText(tr("znack.registration.signing_none_ready"));return;}if(!RegistrationSigningReviewDialog.review(getScene()==null?null:getScene().getWindow(),reviewed)||disposed)return;background(()->workflow.signApprovedReviewed(owner,reviewed,listener()),accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});});
        var notified=new java.util.concurrent.atomic.AtomicBoolean();Consumer<Throwable> failed=error->{if(!notified.compareAndSet(false,true)||disposed)return;signingReviewTask=null;selectionPreparing=false;buttons();status.setText(FriendlyErrorService.format(error));AlertService.showDetailedError(ZnackErrorDetails.summary(error),ZnackErrorDetails.format(error));};task.setOnFailed(event->failed.accept(task.getException()));task.setOnCancelled(event->{if(cancelled.get()){if(!disposed){signingReviewTask=null;selectionPreparing=false;buttons();}}else Platform.runLater(()->failed.accept(RegistrationBatchPreflight.readTimeout()));});AppTaskExecutor.executeCatalog(task,failed);
    }
    private void configureDocuments() {
        if(busy)return;
        RegistrationDocumentConfigDialog.show(getScene()==null?null:getScene().getWindow(),owner,()->{if(!disposed)status.setText(tr("znack.registration.config_saved"));});
    }

    private void configureBrand(){
        if(busy)return;
        background(()->new com.vncode.app.integration.ozon.OzonShopBrand().load(owner),saved->{
            var dialog=new Dialog<String>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(tr("ozon.brand.configure"));
            var brand=new TextField(saved);brand.setId("ozonShopBrandValue");brand.setPromptText(tr("znack.registration.brand"));
            var hint=new Label(tr("ozon.brand.fallback_hint"));hint.setWrapText(true);hint.setMaxWidth(440);
            dialog.getDialogPane().setContent(new VBox(12,hint,brand));dialog.getDialogPane().setPrefWidth(480);
            var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
            DialogLayout.install(dialog.getDialogPane());dialog.setResultConverter(button->button==apply?brand.getText().strip():null);
            dialog.showAndWait().ifPresent(value->background(()->{new com.vncode.app.integration.ozon.OzonShopBrand().save(owner,value);return true;},ok->status.setText(tr("znack.status.saved"))));
        });
    }
    private void syncCatalog(){
        if(busy)return;++generation;readiness=null;latestReadUnknown=true;status.setText(tr("znack.registration.sources_syncing"));
        var previous=cardIndex;var retain=new java.util.concurrent.atomic.AtomicBoolean();
        background(()->{retain.set(previous!=null&&previous.currentScope(owner));return sources.refresh(owner,step->Platform.runLater(()->{if(!disposed)status.setText(tr("znack.registration.sources_syncing")+" · "+tr("znack.registration.step."+step));}));},done->reload(),true,error->sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get()));
    }
    private java.util.function.BiConsumer<Status,String> listener(){return (state,detail)->{
        if(detail!=null&&!detail.isBlank())reviewPending.set(true);
        if(progressQueued.compareAndSet(false,true))Platform.runLater(()->{
            progressQueued.set(false);if(disposed)return;
            if(reviewPending.getAndSet(false))status.setText(tr("znack.registration.bulk_review"));refreshDebounce.playFromStart();
        });
    };}
    private <T>void background(Callable<T> work,Consumer<T> accept) {
        background(work,accept,false);
    }
    private <T>void background(Callable<T> work,Consumer<T> accept,boolean catalog) {
        background(work,accept,catalog,null);
    }
    private <T>void background(Callable<T> work,Consumer<T> accept,boolean catalog,Consumer<Throwable> readFailure) {
        if(disposed)return;long current=generation;busy=true;table.setPlaceholder(new LoadingSkeleton(tr("znack.registration.loading")));buttons();Task<T> task=new Task<>(){@Override protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(e->{if(disposed||current!=generation)return;busy=false;table.setPlaceholder(new Label(tr("znack.registration.empty")));buttons();accept.accept(task.getValue());if(deferredReload&&!busy&&!batchReviewing){deferredReload=false;refreshDebounce.playFromStart();}});
        Consumer<Throwable> failure=error->{if(disposed||current!=generation)return;busy=false;table.setPlaceholder(new Label(tr("znack.registration.empty")));batchReviewing=false;deferredReload=false;if(readFailure!=null)readFailure.accept(error);else status.setText(tr("znack.registration.sources_required")+" · "+SyncFailure.of(error,0).code());buttons();AlertService.showDetailedError(com.vncode.app.integration.znack.ZnackErrorDetails.summary(error),com.vncode.app.integration.znack.ZnackErrorDetails.format(error));};
        task.setOnFailed(e->failure.accept(task.getException()));task.setOnCancelled(e->failure.accept(new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT")));if(catalog)AppTaskExecutor.executeCatalog(task,failure);else AppTaskExecutor.execute(task,failure);
    }
    private void sourceReadUnavailable(String code,boolean retain){
        readiness=null;latestReadUnknown=true;
        if(!retain){cardIndex=null;sourceReadAt="";table.getItems().clear();states.clear();skus.clear();cursor=null;total=0;frozen=List.of();}
        status.setText(RegistrationDiagnostics.readWarning(code,retain));buttons();table.refresh();
    }
    private void controlBatch(String action){
        if(disposed||batchState==null||batchControlBusy)return;batchControlBusy=true;buttons();
        var task=new Task<Boolean>(){protected Boolean call(){return batchControl.apply(owner,action);}};
        task.setOnSucceeded(e->{if(!disposed){batchControlBusy=false;buttons();readBatchProgress();}});
        Consumer<Throwable> failed=error->{if(!disposed){batchControlBusy=false;buttons();status.setText(FriendlyErrorService.format(error));}};
        task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void buttons(){allFiltered.setDisable(busy||batchReviewing||!ready());allFiltered.setSelected(total>0&&selected().size()==total);autoSign.setDisable(busy||batchReviewing);stopBatch.setVisible(batchReviewing&&busy);stopBatch.setManaged(stopBatch.isVisible());stopBatch.setDisable(cancelBatch.get());for(var button:List.of(documents,refresh,sync,sign,gtin,all,clear,register,newCards,more))button.setDisable(busy||batchReviewing);register.setText(tr("ERROR".equals(statusFilter.getValue())?"repair.edit":"znack.registration.register_short"));register.setDisable(busy||batchReviewing||!"ERROR".equals(statusFilter.getValue())&&selected().isEmpty());statusFilter.setDisable(busy||batchReviewing);if(batchActive()){register.setDisable(true);newCards.setDisable(true);}newCards.setDisable(busy||batchReviewing||selected().isEmpty());clear.setDisable(busy||batchReviewing||selected().isEmpty());for(var button:List.of(sign,gtin,all,register,newCards,more))if(!ready())button.setDisable(true);report.setDisable(false);search.setDisable(batchReviewing);more.setVisible(false);more.setManaged(false);count.setText(java.text.MessageFormat.format(tr("znack.registration.page_count"),table.getItems().size(),total));selection.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selected().size()));if(batchActive()||selectionPreparing){register.setDisable(true);newCards.setDisable(true);}pauseBatchAction.setDisable(batchControlBusy||!batchRunning());resumeBatchAction.setDisable(batchControlBusy||batchState==null||!"PAUSED".equals(batchState.state().name())||!ready());cancelPendingBatchAction.setDisable(batchControlBusy||!batchActive());}
    private List<ProductKey> allowedKeys(List<ProductKey> selected,boolean replacement)throws Exception {
        return allowedKeys(selected,replacement,true);
    }
    private List<ProductKey> allowedKeys(List<ProductKey> selected,boolean replacement,boolean strict)throws Exception {return inspectAllowedKeys(selected,replacement,strict).keys();}
    private record AllowedSelection(List<ProductKey> keys,boolean existingDraft){}
    private AllowedSelection inspectAllowedKeys(List<ProductKey> selected,boolean replacement,boolean strict)throws Exception {
        var receipt=sources.require(owner);var index=new RegistrationCardIndex(owner);var wanted=new HashSet<>(selected);var allowed=new ArrayList<ProductKey>();boolean existingDraft=false;
        String cursor=null;
        do {
            var page=products.loadPage(new ProductQuery(owner.getId(),Marketplace.OZON,"",List.of(),null,false,cursor,300),receipt.run());
            var checkpoints=registrations.findMany(page.rows().stream().map(ProductRow::key).toList());
            for(var row:page.rows())if(wanted.contains(row.key())){
                var nativeRow=checkpoints.get(row.key());Status state=nativeRow==null?Status.NOT_CREATED:nativeRow.status();
                var view=index.viewOzon(state,nativeRow==null?"":nativeRow.gtin(),row,nativeRow==null||index.ownsOzon(nativeRow.owner(),nativeRow.scopeJson()));
                boolean changed=nativeRow!=null&&com.vncode.app.integration.znack.registration.TnvedGroupChange.replaceable(state)&&com.vncode.app.integration.znack.registration.TnvedGroupChange.changed(com.vncode.app.integration.znack.registration.TnvedGroupChange.payloadCode(nativeRow.payload()),row.tnved());
                boolean eligible=replacement?(state==Status.PUBLISHED&&view.published()||state==Status.ERROR&&nativeRow!=null&&!nativeRow.gtin().isBlank()&&!nativeRow.feedId().isBlank()&&!Set.of("SENDING","RECONCILE_REQUIRED").contains(nativeRow.signatureState())):changed||!view.published()&&!(state==Status.NOT_CREATED&&view.exists())&&Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.READY_TO_SUBMIT,Status.CHECKING).contains(state);
                boolean draft=view.canReviewDraft()&&(nativeRow==null||nativeRow.feedId().isBlank());
                if(!replacement&&draft)existingDraft=true;
                if(!replacement&&draft||!view.blocked()&&eligible)allowed.add(row.key());
            }
            cursor=page.nextCursor();
        }while(cursor!=null);
        if(strict&&allowed.size()!=wanted.size())throw new IllegalArgumentException("SOURCE_CARD_ACTION_CHANGED");
        return new AllowedSelection(List.copyOf(allowed),existingDraft);
    }
    private boolean notSubmitted(ProductRow product){var row=states.get(product.key());return row==null||RegistrationCardIndex.notSubmitted(row.status(),row.gtin(),row.goodId(),row.feedId(),false);}
    private boolean reviewableDraft(ProductRow row){var checkpoint=row==null?null:states.get(row.key());return row!=null&&(checkpoint==null||checkpoint.feedId().isBlank())&&view(row).canReviewDraft();}
    private boolean ready(){return readiness!=null&&readiness.ready();}
    private RegistrationCardIndex.View view(ProductRow row){
        var checkpoint=states.get(row.key());
        return cardIndex==null?new RegistrationCardIndex.View("source.unverified","","SOURCE_SYNC_REQUIRED",false,false,true)
            :cardIndex.viewOzon(state(row.key()),checkpoint==null?"":checkpoint.gtin(),row,checkpoint==null||cardIndex.ownsOzon(checkpoint.owner(),checkpoint.scopeJson()));
    }
    private Status state(ProductKey key){var row=states.get(key);return row==null?Status.NOT_CREATED:row.status();}
    private String observation(ProductRow row){return RegistrationDiagnostics.observation(state(row.key()),cardIndex==null?Optional.empty():cardIndex.observation(view(row)),latestReadUnknown);}
    private void showRegistrationReport(ProductRow row){var r=states.get(row.key());RegistrationDiagnostics.show(row.vendorArticle(),r==null||safe(r.gtin()).isBlank()?view(row).gtin():r.gtin(),r==null?"":r.feedId(),tr("znack.registration."+view(row).status()),observation(row)+"\n"+reason(view(row).detail())+"\n"+(r==null?"":r.error()));}
    private String candidateText(ProductKey key){var row=states.get(key);return row==null||safe(row.activeGtin()).isBlank()||safe(row.activeGtin()).equals(safe(row.gtin()))?"":"\n"+tr("znack.registration.active_gtin")+": "+row.activeGtin()+" · "+tr("znack.registration.gtin_unverified");}
    private static boolean confirm(String message){var alert=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private static String reason(String code){String key="znack.registration.reason."+code;String text=tr(key);return text.equals(key)?code:text;}
    private static String safe(String value){return value==null?"":value;}
}
