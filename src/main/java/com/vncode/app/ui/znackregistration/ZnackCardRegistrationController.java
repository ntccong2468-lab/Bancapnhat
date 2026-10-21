package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.fbo.FboProductImageService;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackRepository;
import com.vncode.app.integration.znack.ZnackErrorDetails;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.SearchCriteria;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Sku;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Status;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.WbCharacteristic;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationRepository;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow;
import com.vncode.app.integration.znack.registration.ZnackRegistrationBatchService;
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService;
import com.vncode.app.integration.znack.registration.ZnackWbAttributeMapper;
import com.vncode.app.integration.znack.registration.ZnackRegistrationDocuments;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.integration.znack.registration.CatalogFeedback;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.FriendlyErrorService;
import com.vncode.app.shared.I18nService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import com.vncode.app.shared.DialogLayout;
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

public final class ZnackCardRegistrationController {
    private static final int PAGE_SIZE = ZnackRegistrationQueryRepository.PAGE_SIZE;
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final ZnackCardRegistrationRepository repository = new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow workflow = new ZnackCardRegistrationWorkflow(repository);
    private final ZnackRegistrationBatchService batches = new ZnackRegistrationBatchService(repository);
    @FunctionalInterface interface BatchStarter { String start(Shop owner,List<Sku> selection,boolean autoSign); }
    private BatchStarter batchStarter=batches::start;
    @FunctionalInterface interface ReplacementPreparation {void prepare(Shop owner,long epoch,Sku row,boolean fresh,boolean replacement,Runnable completed);}
    private ReplacementPreparation replacementPreparation=this::prepareCard;
    private java.util.function.Predicate<Sku> replacementConfirmation=row->confirm(tr(row.status()==Status.ERROR?"znack.registration.confirm_rejected_replacement":"znack.registration.confirm_replacement"));
    private java.util.function.BiFunction<Shop,String,Boolean> batchControl=(owner,action)->switch(action){case "PAUSE"->batches.pause(owner);case "RESUME"->batches.resumePaused(owner);case "CANCEL"->batches.cancel(owner);default->false;};
    private java.util.function.BooleanSupplier cancelPendingConfirmation=RegistrationDiagnostics::confirmCancelPending;
    private final MenuItem pauseBatchAction=new MenuItem(),resumeBatchAction=new MenuItem(),cancelPendingBatchAction=new MenuItem();
    private boolean batchControlBusy;
    private java.util.function.BiFunction<Shop,List<com.vncode.app.features.products.ProductKey>,RegistrationBatchPreflight.Review> preflightReader=RegistrationBatchPreflight::readComplete;
    private final PauseTransition batchRefresh = new PauseTransition(Duration.millis(1200));
    private java.util.function.Function<Shop,ZnackRegistrationBatchService.Progress> batchReader = batches::latest;
    private ZnackRegistrationBatchService.Progress batchProgress;
    private boolean batchReadInFlight;
    private String batchReadFailureText="";
    private final PauseTransition batchRowsRefresh = new PauseTransition(Duration.millis(400));
    private long lastBatchRowsRefreshNanos;
    private boolean batchRowsReadInFlight;
    private final ZnackRegistrationQueryRepository queries = new ZnackRegistrationQueryRepository();
    private final FboProductImageService imageService = new FboProductImageService();
    private final PauseTransition workflowRefresh = new PauseTransition(Duration.millis(400));
    private final java.util.Set<Long> bulkCards=new java.util.HashSet<>();
    private final PauseTransition preparationRetry=new PauseTransition();
    private final java.util.Map<Long,Integer> preparationAttempts=new java.util.HashMap<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectChecks = new ArrayList<>();
    private final RegistrationSources sources=new RegistrationSources();
    private RegistrationSources.Readiness readiness;
    private RegistrationCardIndex cardIndex;
    private boolean latestReadUnknown;
    private String sourceReadAt="";
    private RegistrationAttemptReports registrationReports;
    private volatile String lastRegistrationRun="";
    private Shop shop;
    private boolean loading;
    private boolean preparing;
    private Task<?> registrationPreflightTask;
    private Task<?> signingReviewTask;
    private record DocumentChoice(List<ZnackModels.GoodsDocument> candidates,String tnved,List<String> issues){}
    private final java.util.Map<DocumentChoice,ZnackModels.GoodsDocument> reviewedDocuments=new java.util.HashMap<>();
    private long shopEpoch;
    private long loadSequence;
    private long filteredCount;
    private List<Sku> frozenSelection=List.of();
    private Set<Long> frozenSelectionIds=Set.of();
    private record SelectionActions(boolean register,boolean repair,boolean replace){}
    private SelectionActions frozenActions;
    private record PreparedSelection(List<Sku> rows,Set<Long> ids,SelectionActions actions){}
    private boolean selectingProgrammatically;
    private final java.util.Map<Long,ZnackRegistrationQueryRepository.Details> rowDetails=new java.util.HashMap<>();
    private ZnackRegistrationQueryRepository.Summary summary;
    private java.util.function.Function<Shop,javafx.scene.Node> ozonRegistrationFactory;
    @FXML private VBox wbRegistrationContent;
    @FXML private StackPane ozonRegistrationHost;

    @FXML private Label readinessLabel;
    @FXML private Label operationLabel;
    @FXML private VBox extraActions;
    @FXML private MenuButton moreActionsButton;
    @FXML private Hyperlink reportLink;
    @FXML private CheckBox allFilteredCheckBox;
    @FXML private TableColumn<Sku,Sku> selectionColumn;
    @FXML private Button reportButton;
    @FXML private TableColumn<Sku,String> detailColumn;
    @FXML private Label titleLabel;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label summaryLabel;
    @FXML private Label batchProgressLabel;
    @FXML private Label paginationLabel;
    @FXML private Label selectionLabel;
    @FXML private TextField searchField;
    @FXML private MenuButton categoryMenuButton;
    @FXML private ComboBox<StatusFilter> statusFilter;
    @FXML private Button clearFiltersButton;
    @FXML private Button refreshButton;
    @FXML private Button configButton;
    @FXML private Button signApprovedButton;
    @FXML private Button updateWbButton;
    @FXML private Button syncZnackButton;
    @FXML private Button loadMoreButton;
    @FXML private Button clearSelectionButton;
    @FXML private Button selectAllButton;
    @FXML private Button registerSelectedButton;
    @FXML private CheckBox autoSignCheckBox;
    @FXML private Button newCardsSelectedButton;
    @FXML private TableView<Sku> productTable;
    @FXML private TableColumn<Sku, Sku> imageColumn;
    @FXML private TableColumn<Sku, String> nameColumn;
    @FXML private TableColumn<Sku, String> articleColumn;
    @FXML private TableColumn<Sku, String> colorColumn;
    @FXML private TableColumn<Sku, String> genderColumn;
    @FXML private TableColumn<Sku, String> sizeColumn;
    @FXML private TableColumn<Sku, String> russianSizeColumn;
    @FXML private TableColumn<Sku, String> barcodeColumn;
    @FXML private TableColumn<Sku, String> verifiedGtinColumn;
    @FXML private TableColumn<Sku, String> statusColumn;
    @FXML private TableColumn<Sku, String> moderationColumn;
    @FXML private TableColumn<Sku, String> marketplaceSyncColumn;
    @FXML private TableColumn<Sku, Sku> actionColumn;

    @FXML
    private void initialize() {
        configureColumns();
        batchProgressLabel.setCursor(javafx.scene.Cursor.HAND);batchProgressLabel.setOnMouseClicked(event->onReport());
        extraActions.getChildren().clear();
        var extraMenu=new ContextMenu(new CustomMenuItem(refreshButton,false),new CustomMenuItem(reportButton,false),new CustomMenuItem(newCardsSelectedButton,false),
                new CustomMenuItem(autoSignCheckBox,false),new CustomMenuItem(clearSelectionButton,false),new CustomMenuItem(selectionLabel,false));
        pauseBatchAction.setId("znackPauseBatch");resumeBatchAction.setId("znackResumeBatch");cancelPendingBatchAction.setId("znackCancelPendingBatch");
        pauseBatchAction.setOnAction(e->controlBatch("PAUSE"));resumeBatchAction.setOnAction(e->controlBatch("RESUME"));cancelPendingBatchAction.setOnAction(e->{if(cancelPendingConfirmation.getAsBoolean())controlBatch("CANCEL");});
        extraMenu.getItems().addAll(new SeparatorMenuItem(),pauseBatchAction,resumeBatchAction,cancelPendingBatchAction);
        titleLabel.setContextMenu(extraMenu);titleLabel.setFocusTraversable(true);titleLabel.setTooltip(new Tooltip(tr("znack.registration.more_actions_hint")));
        com.vncode.app.shared.TableScrollLoader.install(productTable,()->shop!=null&&!loading&&productTable.getItems().size()<filteredCount,this::onLoadMore);
        workflowRefresh.setOnFinished(event->reload());
        batchRefresh.setOnFinished(event->pollBatchProgress());
        batchRowsRefresh.setOnFinished(event->refreshBatchRows());
        productTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        productTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Sku>) change -> {
                    if(selectingProgrammatically)return;
                    frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;updateActionState();
                });
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.getSelectionModel().select(StatusFilter.ALL);
        statusFilter.valueProperty().addListener((obs, old, value) -> reload());
        searchField.textProperty().addListener((obs, old, value) -> {
            debounce.setOnFinished(event -> reload());
            debounce.playFromStart();
        });
        applyTranslations();
        reportLink.setVisible(true);reportLink.setManaged(true);
    }

    public void dispose(){workflowRefresh.stop();debounce.stop();setShop(null);}

    public void setOzonRegistrationFactory(java.util.function.Function<Shop,javafx.scene.Node> factory) {
        ozonRegistrationFactory=Objects.requireNonNull(factory);
    }

    public void setShop(Shop shop) {
        shopEpoch++;if(registrationPreflightTask!=null){registrationPreflightTask.cancel(true);registrationPreflightTask=null;}if(signingReviewTask!=null){signingReviewTask.cancel(true);signingReviewTask=null;}workflowRefresh.stop();batchRefresh.stop();batchRowsRefresh.stop();batchReadInFlight=false;batchRowsReadInFlight=false;lastBatchRowsRefreshNanos=0;batchProgress=null;batchReadFailureText="";batchControlBusy=false;
        batchProgressLabel.setText("");batchProgressLabel.setVisible(false);batchProgressLabel.setManaged(false);
        ((StackPane)productTable.getParent()).getChildren().removeIf(node->"registrationBackgroundPill".equals(node.getId()));
        preparationRetry.stop();preparationAttempts.clear();bulkCards.clear();reviewedDocuments.clear();
        boolean ozon=shop!=null&&shop.getMarketplace()==Marketplace.OZON;
        wbRegistrationContent.setVisible(!ozon);wbRegistrationContent.setManaged(!ozon);
        ozonRegistrationHost.setVisible(ozon);ozonRegistrationHost.setManaged(ozon);
        for(var previous:ozonRegistrationHost.getChildren())if(previous instanceof OzonRegistrationPane pane)pane.dispose();
        ozonRegistrationHost.getChildren().clear();
        if(ozon&&ozonRegistrationFactory!=null)ozonRegistrationHost.getChildren().setAll(ozonRegistrationFactory.apply(shop));
        preparing=false;readiness=null;cardIndex=null;latestReadUnknown=false;sourceReadAt="";
        registrationReports=null;lastRegistrationRun="";
        this.shop = shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null;
        productTable.getItems().clear();
        allFilteredCheckBox.setSelected(false);operationLabel.setText("");frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;rowDetails.clear();summary=null;filteredCount=0;
        selectedSubjects.clear();
        loadSubjects();
        reload();
        pollBatchProgress();
    }

    public void applyTranslations() {
        if(ozonRegistrationHost!=null)for(var child:ozonRegistrationHost.getChildren())if(child instanceof OzonRegistrationPane pane)pane.applyTranslations();
        I18nService i18n = I18nService.getInstance();
        pauseBatchAction.setText(tr("catalogsync.pause"));resumeBatchAction.setText(tr("catalogsync.resume"));cancelPendingBatchAction.setText(tr("gtinsync.cancel"));
        titleLabel.setText(i18n.tr("znack.registration.title"));
        searchField.setPromptText(i18n.tr("znack.registration.search"));
        refreshButton.setText(i18n.tr("znack.registration.refresh"));
        configButton.setText(i18n.tr("znack.registration.documents"));
        signApprovedButton.setText(i18n.tr("znack.registration.sign_approved"));
        updateWbButton.setText(i18n.tr("znack.registration.update_wb"));
        syncZnackButton.setText(i18n.tr("znack.registration.sync_znack"));
        reportButton.setText(i18n.tr("znack.registration.report"));detailColumn.setText(i18n.tr("jobs.reason"));
        loadMoreButton.setText(i18n.tr("znack.registration.load_more"));autoSignCheckBox.setText(i18n.tr("znack.registration.auto_sign"));
        clearSelectionButton.setText(i18n.tr("znack.registration.clear_selection"));
        genderColumn.setText(i18n.tr("znack.registration.gender"));
        clearFiltersButton.setText(i18n.tr("znack.registration.clear"));
        selectAllButton.setText(i18n.tr("znack.registration.select_all"));
        allFilteredCheckBox.setText(i18n.tr("znack.registration.select_all_filtered"));
        reportLink.setText(i18n.tr("znack.registration.report"));
        registerSelectedButton.setText(i18n.tr("znack.registration.register_short"));newCardsSelectedButton.setText(i18n.tr("znack.registration.batch_new_cards"));
        loadingLabel.setText(i18n.tr("znack.registration.loading"));
        emptyLabel.setText(i18n.tr("znack.registration.empty"));
        productTable.setPlaceholder(new Label(i18n.tr("znack.registration.empty")));
        statusFilter.setButtonCell(statusFilterCell());
        statusFilter.setCellFactory(ignored->statusFilterCell());
        imageColumn.setText(i18n.tr("fbo.column.image"));
        nameColumn.setText(i18n.tr("fbo.column.name"));
        articleColumn.setText("Article");
        colorColumn.setText(i18n.tr("fbo.column.color"));
        sizeColumn.setText(tr("znack.registration.column_size_original"));
        russianSizeColumn.setText(tr("znack.registration.column_size_ru"));
        barcodeColumn.setText(tr("znack.registration.barcode_wb"));verifiedGtinColumn.setText(tr("znack.registration.verified_gtin"));
        statusColumn.setText(tr("znack.registration.column_registration"));
        moderationColumn.setText(tr("znack.registration.column_moderation"));
        marketplaceSyncColumn.setText(java.text.MessageFormat.format(tr("znack.registration.column_marketplace_sync"),"WB"));
        actionColumn.setText(i18n.tr("znack.registration.action"));
        updateCategoryText();
        updateCounts();
        productTable.refresh();
    }
    private ListCell<StatusFilter> statusFilterCell() {
        return new ListCell<>() {
            @Override protected void updateItem(StatusFilter item,boolean empty) {
                super.updateItem(item,empty);setText(empty||item==null?"":item.toString());
            }
        };
    }

    @FXML private void onRefresh() { reload(); }

    @FXML private void onSelectAll() {
        if(shop==null || !ready() || preparing || loading)return;
        if(statusFilter.getValue()==StatusFilter.ERROR){allFilteredCheckBox.setSelected(false);openErrorRepair(true);return;}
        selectingProgrammatically=true;
        try {
            productTable.getSelectionModel().clearSelection();
            int[] indices=java.util.stream.IntStream.range(0,productTable.getItems().size())
                    .filter(index->selectable(productTable.getItems().get(index))).toArray();
            if(indices.length>0)productTable.getSelectionModel().selectIndices(indices[0],java.util.Arrays.copyOfRange(indices,1,indices.length));
        } finally {selectingProgrammatically=false;}
        if(filteredCount<=productTable.getItems().size()) {
            frozenSelection=productTable.getSelectionModel().getSelectedItems().stream().filter(this::selectable).toList();
            updateActionState();return;
        }
        Shop owner=shop;long epoch=shopEpoch;long sequence=loadSequence;
        SearchCriteria criteria=criteria(0);loading=true;setLoading(true);
        Task<PreparedSelection> task=new Task<>() {@Override protected PreparedSelection call(){
            var rows=readyQueries(owner).allSelectable(criteria).stream().filter(row->!workflow.isRunning(owner,row)).toList();
            boolean register=false,repair=false,replace=false;var ids=new HashSet<Long>();
            for(var row:rows){ids.add(row.chrtId());register|=reviewableDraft(row)||row.canRegister()||row.canResume()||row.needsGtinReview()||row.tnvedGroupChanged();repair|=row.status()==Status.ERROR&&row.hasGtin()&&(row.hasFeed()||row.goodId()!=null);replace|=row.canRegisterNew();}
            return new PreparedSelection(rows,Set.copyOf(ids),new SelectionActions(register,repair,replace));
        }};
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch)||sequence!=loadSequence)return;
            loading=false;var selected=task.getValue();frozenSelection=selected.rows();frozenSelectionIds=selected.ids();frozenActions=selected.actions();setLoading(false);
        });
        java.util.function.Consumer<Throwable> failure=error->{
            if(!isCurrent(owner,epoch)||sequence!=loadSequence)return;
            loading=false;frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;setLoading(false);showError(error);
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    @FXML private void onToggleAllFiltered(){if(allFilteredCheckBox.isSelected())onSelectAll();else onClearSelection();}

    @FXML private void onClearSelection() {
        allFilteredCheckBox.setSelected(false);frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;productTable.getSelectionModel().clearSelection();updateActionState();
    }

    private List<Sku> selectedRows() {
        return frozenSelection.isEmpty()?productTable.getSelectionModel().getSelectedItems():frozenSelection;
    }

    @FXML private void onSignApproved() {
        if(shop==null||!ready()||preparing||loading)return;
        var selected=selectedRows().stream().filter(row->row.canSignApproved()&&!view(row).blocked()&&!view(row).published()).toList();
        if(!selected.isEmpty()){confirmAndSign(shop,shopEpoch,selected);return;}
        Shop owner=shop;long epoch=shopEpoch;long sequence=loadSequence;
        SearchCriteria criteria=criteria(0);loading=true;setLoading(true);
        Task<List<Sku>> task=new Task<>(){@Override protected List<Sku> call(){return readyQueries(owner).allSignable(criteria);}};
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)&&sequence==loadSequence){loading=false;setLoading(false);confirmAndSign(owner,epoch,task.getValue());}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)&&sequence==loadSequence){loading=false;setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void confirmAndSign(Shop owner,long epoch,List<Sku> selected) {
        if(selected.isEmpty()){showInfo(tr("znack.registration.no_approved"));return;}
        if(!isCurrent(owner,epoch)||signingReviewTask!=null)return;
        var task=new Task<com.vncode.app.integration.znack.registration.RegistrationSigningReview>(){protected com.vncode.app.integration.znack.registration.RegistrationSigningReview call()throws Exception{return workflow.prepareApprovedSigning(owner,List.copyOf(selected));}};
        signingReviewTask=task;loading=true;setLoading(true);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();RegistrationBatchPreflight.readPill((StackPane)productTable.getParent(),"registrationSigningReviewRead",task,()->cancelled.set(true));
        task.setOnSucceeded(event->{if(!isCurrent(owner,epoch))return;signingReviewTask=null;loading=false;setLoading(false);var reviewed=task.getValue();if(reviewed==null||reviewed.cards().isEmpty()){operationLabel.setText(tr("znack.registration.signing_none_ready"));showInfo(operationLabel.getText());return;}if(!RegistrationSigningReviewDialog.review(productTable.getScene()==null?null:productTable.getScene().getWindow(),reviewed)||!isCurrent(owner,epoch))return;
            submitWorkflow(owner,epoch,()->workflow.signApprovedReviewed(owner,reviewed,(status,detail)->Platform.runLater(()->{if(!isCurrent(owner,epoch))return;if(status==Status.ERROR)summaryLabel.setText(tr("znack.registration.bulk_review"));scheduleReload();})),()->{});
        });
        var notified=new java.util.concurrent.atomic.AtomicBoolean();java.util.function.Consumer<Throwable> failed=error->{if(!notified.compareAndSet(false,true)||!isCurrent(owner,epoch))return;signingReviewTask=null;loading=false;setLoading(false);showError(error);};task.setOnFailed(event->failed.accept(task.getException()));task.setOnCancelled(event->{if(cancelled.get()){if(isCurrent(owner,epoch)){signingReviewTask=null;loading=false;setLoading(false);}}else Platform.runLater(()->failed.accept(RegistrationBatchPreflight.readTimeout()));});AppTaskExecutor.executeCatalog(task,failed);
    }

    private void submitWorkflow(Shop owner,long epoch,java.util.concurrent.Callable<Boolean> action,Runnable completed) {
        submitWorkflow(owner,epoch,action,null,completed);
    }

    private void submitWorkflow(Shop owner,long epoch,java.util.concurrent.Callable<Boolean> action,Sku awaiting,Runnable completed) {
        if(!isCurrent(owner,epoch))return;
        Task<Boolean> task=new Task<>() {@Override protected Boolean call()throws Exception{sources.require(owner);return action.call();}};
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;
            if(!task.getValue())showInfo(tr("znack.registration.already_running"));
            if(task.getValue()&&awaiting!=null) {
                workflow.completion(owner,awaiting).whenComplete((ignored,error)->Platform.runLater(()->{
                    if(!isCurrent(owner,epoch))return;
                    if(error!=null)showError(error);
                    completed.run();scheduleReload();
                }));
            }else{completed.run();scheduleReload();}
        });
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){showError(error);completed.run();}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    @FXML private void onUpdateWb(){
        if(shop==null||!ready()||loading||preparing)return;
        var selected=selectedRows().stream().map(row->new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).collect(java.util.stream.Collectors.toSet());
        GtinBackgroundStatus.start((javafx.scene.layout.StackPane)productTable.getParent(),shop,selected,this::scheduleReload,result->{lastRegistrationRun=result.run();operationLabel.setText(GtinBackgroundStatus.summary(result));});
    }

    @FXML private void onSyncZnack() {
        if(shop==null||preparing||loading)return;
        Shop owner=shop;long epoch=shopEpoch;++loadSequence;readiness=null;latestReadUnknown=true;loading=true;setLoading(true);
        var previous=cardIndex;var retain=new java.util.concurrent.atomic.AtomicBoolean();
        readinessLabel.setText(tr("znack.registration.sources_syncing"));
        Task<Boolean> task=new Task<>() {
            @Override protected Boolean call() throws Exception {
                retain.set(previous!=null&&previous.currentScope(owner));
                sources.refresh(owner,step->Platform.runLater(()->{if(isCurrent(owner,epoch))readinessLabel.setText(tr("znack.registration.sources_syncing")+" · "+tr("znack.registration.step."+step));}));
                return true;
            }
        };
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;reload();}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get());setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));task.setOnCancelled(event->failure.accept(new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT")));AppTaskExecutor.executeCatalog(task,failure);
    }

    @FXML private void onRegisterSelected() {
        if(shop==null || !ready() || preparing || loading || batchProgress!=null&&batchProgress.running()) return;
        if(statusFilter.getValue()==StatusFilter.ERROR||selectionActions().repair()){openErrorRepair(selectedRows().isEmpty());return;}
        List<Sku> selected=frozenActions==null?selectedRows().stream().filter(this::eligible).toList():frozenSelection.stream().filter(row->reviewableDraft(row)||row.canRegister()||row.canResume()||row.needsGtinReview()||row.tnvedGroupChanged()).toList();
        if(selected.isEmpty())return;
        long fresh=selected.stream().filter(row->!RegistrationCardIndex.notSubmitted(row)&&row.needsGtinReview()).count();
        String message=java.text.MessageFormat.format(tr("znack.registration.confirm_selected"),selected.size())+"\n"+tr("znack.registration.marketplace_after_publication");
        if(fresh>0)message+="\n\n"+java.text.MessageFormat.format(tr("znack.registration.confirm_new_gtin"),fresh);
        long changed=selected.stream().filter(this::groupReplacement).count();if(changed>0)message+="\n\n"+java.text.MessageFormat.format(tr("znack.registration.group_change_confirm"),changed);
        if((fresh>0||changed>0)&&!confirm(message))return;
        Shop owner=shop;long epoch=shopEpoch;
        reviewBeforeRegistration(owner,epoch,selected);
    }
    private void reviewBeforeRegistration(Shop owner,long epoch,List<Sku> selected){
        // The selected identities are frozen; browsing/filter changes cannot cancel dispatch.
        preparing=true;lastRegistrationRun="";updateActionState();
        boolean autoSign=autoSignCheckBox.isSelected();boolean existingDraft=selected.stream().anyMatch(this::reviewableDraft);
        var keys=selected.stream().map(row->new com.vncode.app.features.products.ProductKey(owner.getId(),owner.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).toList();
        Task<RegistrationBatchPreflight.Review> task=new Task<>(){protected RegistrationBatchPreflight.Review call(){
            sources.require(owner);return preflightReader.apply(owner,keys);
        }};
        registrationPreflightTask=task;var cancelledBySeller=new java.util.concurrent.atomic.AtomicBoolean();
        RegistrationBatchPreflight.readPill((StackPane)productTable.getParent(),"registrationPreflightStatus",task,()->cancelledBySeller.set(true));
        task.setOnSucceeded(e->{
            if(!isCurrent(owner,epoch))return;registrationPreflightTask=null;
            var review=task.getValue();boolean applied=RegistrationBatchPreflight.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),owner,review,existingDraft);
            if(!applied||!isCurrent(owner,epoch)){preparing=false;updateActionState();return;}
            if(selected.stream().anyMatch(row->!RegistrationCardIndex.notSubmitted(row)&&!reviewableDraft(row)||groupReplacement(row))){
                // Explicit identity replacement/reconciliation retains its confirmed baseline workflow.
                registrationReports=new RegistrationAttemptReports(owner,selected);
                var reports=registrationReports;
                Task<String> legacy=new Task<>(){protected String call(){return reports.queue();}};
                legacy.setOnSucceeded(done->{if(!isCurrent(owner,epoch))return;lastRegistrationRun=legacy.getValue();
                    selected.forEach(row->bulkCards.add(row.chrtId()));reviewedDocuments.clear();prepareNext(selected,0,owner,epoch);});
                legacy.setOnFailed(done->{if(isCurrent(owner,epoch)){preparing=false;updateActionState();showError(legacy.getException());}});
                AppTaskExecutor.execute(legacy,ZnackCardRegistrationController::showError);return;
            }
            Task<String> start=new Task<>(){protected String call(){return batchStarter.start(owner,selected,autoSign);}};
            start.setOnSucceeded(done->{if(!isCurrent(owner,epoch))return;lastRegistrationRun=start.getValue();preparing=false;
                registrationPill(tr("znack.registration.batch_start"),true);updateActionState();pollBatchProgress();reload();});
            java.util.function.Consumer<Throwable> failed=error->{if(isCurrent(owner,epoch)){preparing=false;updateActionState();showError(error);}};
            start.setOnFailed(done->failed.accept(start.getException()));AppTaskExecutor.execute(start,failed);
        });
        var notified=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Throwable> failed=error->{if(!notified.compareAndSet(false,true))return;if(isCurrent(owner,epoch)){registrationPreflightTask=null;preparing=false;updateActionState();operationLabel.setText(FriendlyErrorService.format(error));showError(error);}};
        task.setOnFailed(e->failed.accept(task.getException()));task.setOnCancelled(e->{if(cancelledBySeller.get()){if(isCurrent(owner,epoch)){registrationPreflightTask=null;preparing=false;updateActionState();operationLabel.setText(tr("znack.registration.preflight.cancelled"));}}else Platform.runLater(()->failed.accept(RegistrationBatchPreflight.readTimeout()));});AppTaskExecutor.executeCatalog(task,failed);
    }

    private void pollBatchProgress(){
        if(shop==null||batchReadInFlight)return;
        Shop owner=shop;long epoch=shopEpoch;batchReadInFlight=true;
        com.vncode.app.shared.ShopReadCoordinator.shared().submitLocal(
                new com.vncode.app.shared.ShopReadCoordinator.Key(owner.getId(),"znack-batch-progress",Integer.toString(System.identityHashCode(this))),
                ()->batchReader.apply(owner)).whenComplete((progress,error)->Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;
            batchReadInFlight=false;
            if(error==null)renderBatchProgress(progress);else showBatchProgressFailure(error);
            batchRefresh.playFromStart();
        }));
    }
    private void showBatchProgressFailure(Throwable error){
        String code=SyncFailure.of(error,0).code();
        String detail=reason(code);
        if(detail.equals(code))detail=tr(code.equals("SYNC_STORAGE")?"error.database_storage":code.equals("SYNC_STORAGE_BUSY")?"error.database_busy":code.startsWith("NETWORK_")?"error.network":"znack.registration.batch_progress_read_unknown");
        String text=java.text.MessageFormat.format(tr("znack.registration.batch_progress_read_failed"),detail,code);
        if(batchProgress!=null)text=batchSummary(batchProgress)+" · "+text;
        batchProgressLabel.setText(text);batchProgressLabel.setVisible(true);batchProgressLabel.setManaged(true);
        if(!text.equals(batchReadFailureText)){batchReadFailureText=text;registrationPill(text,false);}
    }
    private String batchSummary(ZnackRegistrationBatchService.Progress progress){
        boolean paused="PAUSED".equals(progress.state().name()),cancelled="CANCELLED".equals(progress.state().name());
        String key=progress.waitingOffline()?"znack.registration.batch_submission_waiting":progress.running()||paused
                ?"znack.registration.batch_submission_progress":"znack.registration.batch_submission_finished";
        return tr("znack.registration.report_scope_batch")+" · "+(paused?tr("znack.registration.batch_paused")+" · ":cancelled?tr("znack.status_value.cancelled")+" · ":"")+java.text.MessageFormat.format(tr(key),progress.submitted(),progress.total(),progress.remaining(),progress.failed())+" · "+java.text.MessageFormat.format(tr("znack.registration.batch_receipts"),progress.acceptedFeeds(),progress.total(),progress.awaitingReadback());
    }
    private void renderBatchProgress(ZnackRegistrationBatchService.Progress progress){
        boolean recovered=!batchReadFailureText.isBlank();batchReadFailureText="";
        var previous=batchProgress;batchProgress=progress;
        batchProgressLabel.setVisible(progress!=null);batchProgressLabel.setManaged(progress!=null);
        if(progress==null){batchProgressLabel.setText("");((StackPane)productTable.getParent()).getChildren().removeIf(node->"registrationBackgroundPill".equals(node.getId()));updateActionState();return;}
        if(previous==null||!previous.runId().equals(progress.runId())||progress.running())lastRegistrationRun=progress.runId();
        reportLink.setText(tr("znack.registration.report")+" ("+progress.total()+")");
        String text=batchSummary(progress);
        if((progress.state()==ZnackRegistrationBatchService.State.NEEDS_ACTION||"PAUSED".equals(progress.state().name()))&&!progress.detail().isBlank())text+=" · "+RegistrationDiagnostics.reason(progress.detail());
        batchProgressLabel.setText(text);
        if(!progress.equals(previous)||recovered){
            String article=progress.submitted()>0&&(previous==null||progress.submitted()>previous.submitted())
                    ?(progress.lastSubmittedArticle()+" · "+progress.lastSubmittedSize()).strip():progress.currentArticle();
            String detail=progress.waitingOffline()?tr("znack.registration.batch_waiting_connection")
                    :progress.running()||progress.state()==ZnackRegistrationBatchService.State.NEEDS_ACTION?RegistrationDiagnostics.reason(progress.detail()):"";
            RegistrationStatusPill.show((StackPane)productTable.getParent(),"registrationBackgroundPill",text+(article.isBlank()?"":" · "+article)+(detail.isBlank()?"":" · "+detail),progress.running(),!progress.running()&&!"PAUSED".equals(progress.state().name()));
            updateActionState();
        }
        boolean changed=previous==null||progress.submitted()!=previous.submitted()||progress.failed()!=previous.failed()||progress.state()!=previous.state()||!progress.detail().equals(previous.detail());
        boolean watchingProcessing=statusFilter.getValue()==StatusFilter.IN_PROGRESS&&progress.running()
                &&System.nanoTime()-lastBatchRowsRefreshNanos>=java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        if(changed||watchingProcessing)requestBatchRowsRefresh();
    }

    private void controlBatch(String action){
        if(shop==null||batchProgress==null||batchControlBusy)return;
        Shop owner=shop;long epoch=shopEpoch;batchControlBusy=true;updateActionState();
        var task=new Task<Boolean>(){protected Boolean call(){return batchControl.apply(owner,action);}};
        task.setOnSucceeded(e->{if(isCurrent(owner,epoch)){batchControlBusy=false;updateActionState();pollBatchProgress();}});
        java.util.function.Consumer<Throwable> failed=error->{if(isCurrent(owner,epoch)){batchControlBusy=false;updateActionState();showError(error);}};
        task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }

    private void requestBatchRowsRefresh(){
        if(shop==null||loading||preparing||batchRowsReadInFlight)return;
        if(batchRowsRefresh.getStatus()!=javafx.animation.Animation.Status.RUNNING)batchRowsRefresh.playFromStart();
    }
    private void refreshBatchRows(){
        if(shop==null||loading||preparing||batchRowsReadInFlight)return;
        lastBatchRowsRefreshNanos=System.nanoTime();++loadSequence;loadPage(false,true);
    }

    private void stopReviewedBatch(RegistrationAttemptReports batch){
        Task<Void> task=new Task<>(){protected Void call(){batch.stopPending("ZNACK_REGISTRATION_REVIEW_CANCELLED",true);return null;}};
        task.setOnFailed(e->showError(task.getException()));AppTaskExecutor.execute(task,ZnackCardRegistrationController::showError);
    }
    @FXML private void onNewCardsSelected() {
        if(shop==null||!ready()||loading||preparing)return;var ids=selectedRows().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());if(ids.isEmpty())return;
        Shop owner=shop;long epoch=shopEpoch;var criteria=criteria(0);loading=true;setLoading(true);
        Task<List<Sku>> task=new Task<>(){protected List<Sku> call(){return readyQueries(owner).allReplaceable(criteria).stream().filter(s->ids.contains(s.chrtId())).toList();}};
        task.setOnSucceeded(e->{if(!isCurrent(owner,epoch))return;loading=false;setLoading(false);var selected=task.getValue();if(selected.isEmpty())return;
            if(selected.size()>1000){showInfo(tr("znack.registration.batch_limit"));return;}
            if(!confirm(java.text.MessageFormat.format(tr("znack.registration.batch_confirm"),selected.size(),ids.size())))return;
            selected.forEach(s->bulkCards.add(s.chrtId()));preparing=true;reviewedDocuments.clear();registrationReports=new RegistrationAttemptReports(owner,selected);updateActionState();prepareReplacementNext(selected,0,owner,epoch);
        });
        java.util.function.Consumer<Throwable> failed=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void prepareReplacementNext(List<Sku> selected,int index,Shop owner,long epoch) {
        if(!isCurrent(owner,epoch))return;
        if(index>=selected.size()){preparing=false;registrationPill(tr("znack.registration.request_finished"),false);updateActionState();reload();return;}
        summaryLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),index,selected.size()));
        prepareCard(owner,epoch,selected.get(index),false,true,()->prepareReplacementNext(selected,index+1,owner,epoch));
    }
    private boolean selectable(Sku sku){return errorRow(sku)||eligible(sku)||(sku!=null&&replaceable(sku)&&(!rowDetails.getOrDefault(sku.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement()||sku.status()==Status.ERROR)&&!workflow.isRunning(shop,sku));}
    private boolean errorRow(Sku row){return row!=null&&(RegistrationCardIndex.rejectedCard(view(row),row.status(),row.goodId(),row.feedId()));}
    private void openErrorRepair(boolean all){
        var keys=selectedRows().stream().map(row->new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).collect(java.util.stream.Collectors.toSet());
        CatalogErrorRepairDialog.open((StackPane)productTable.getParent(),shop,keys,all,this::scheduleReload);
    }

    private void prepareNext(List<Sku> selected,int index,Shop owner,long epoch) {
        if(!isCurrent(owner,epoch))return;
        if(index==selected.size()) {preparing=false;operationLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_finished"),index,selected.size()));registrationPill(operationLabel.getText(),false);updateActionState();reload();return;}
        Sku sku=selected.get(index);
        summaryLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),index,selected.size())+" · "+sku.vendorCode()+" · "+sku.wbSize());
        Runnable next=()->prepareNext(selected,index+1,owner,epoch);
        if(sku.canResume()&&!groupReplacement(sku)) {
            submitWorkflow(owner,epoch,()->workflow.resume(owner,sku,null,registrationListener(owner,epoch,sku,registrationReports.begin(sku))),sku,next);
        }else prepareCard(owner,epoch,sku,sku.needsGtinReview(),next);
    }

    @FXML
    private void onConfig() {
        if(shop==null||!ready()||loading||preparing)return;
        Shop owner=shop;long epoch=shopEpoch;loading=true;setLoading(true);
        Task<ZnackModels.Settings> task=new Task<>() {@Override protected ZnackModels.Settings call(){return settings(owner);}};
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);openDocumentConfig(owner,epoch,task.getValue());}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void openDocumentConfig(Shop owner,long epoch,ZnackModels.Settings current) {
        RegistrationDocumentConfigDialog.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),owner,current,()->{
            if(isCurrent(owner,epoch))showInfo(tr("znack.registration.config_saved"));
        });
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        selectedSubjects.clear();
        subjectChecks.forEach(check -> check.setSelected(false));
        statusFilter.getSelectionModel().select(StatusFilter.ALL);
        updateCategoryText();
        reload();
    }

    private void loadSubjects() {
        categoryMenuButton.getItems().clear();
        subjectChecks.clear();
        if (shop == null) return;
        Shop owner=shop;long epoch=shopEpoch;
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return repository.findSubjects(owner.getId()); }
        };
        task.setOnSucceeded(event -> {if(isCurrent(owner,epoch))buildSubjectMenu(task.getValue());});
        java.util.function.Consumer<Throwable> taskFailure = rejection -> {if(isCurrent(owner,epoch))showError(rejection);};
        task.setOnFailed(event -> taskFailure.accept(task.getException()));
        AppTaskExecutor.execute(task, taskFailure);
    }

    private void buildSubjectMenu(List<String> subjects) {
        VBox box = new VBox(4);
        box.setPadding(new javafx.geometry.Insets(8));
        for (String subject : subjects) {
            CheckBox check = new CheckBox(subject);
            check.setMinHeight(30);
            check.setMaxWidth(Double.MAX_VALUE);
            check.selectedProperty().addListener((obs, old, selected) -> {
                if (selected) selectedSubjects.add(subject); else selectedSubjects.remove(subject);
                updateCategoryText();
                reload();
            });
            subjectChecks.add(check);
            box.getChildren().add(check);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(260);
        scroll.setPrefViewportHeight(Math.min(360, Math.max(50, subjects.size() * 28)));
        categoryMenuButton.getItems().setAll(new CustomMenuItem(scroll, false));
        updateCategoryText();
    }

    private SearchCriteria criteria(int offset) {
        StatusFilter filter=statusFilter.getValue()==null?StatusFilter.ALL:statusFilter.getValue();
        return new SearchCriteria(shop.getId(),searchField.getText(),List.copyOf(selectedSubjects),filter.code,PAGE_SIZE,offset);
    }

    public void refreshLocal(){
        if(ozonRegistrationHost!=null&&ozonRegistrationHost.isVisible()){for(var child:ozonRegistrationHost.getChildren())if(child instanceof OzonRegistrationPane pane)pane.refreshLocal();return;}
        if(loading)return;reload();
    }
    private void reload() {
        debounce.stop();batchRowsRefresh.stop();++loadSequence;batchRowsReadInFlight=false;frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;
        if(shop==null) {
            loading=false;productTable.getItems().clear();summary=null;setLoading(false);updateEmpty();updateCounts();return;
        }
        readiness=null;latestReadUnknown=true;readinessLabel.setText(tr("znack.registration.sources_checking"));
        loadPage(false);
    }

    @FXML private void onLoadMore() {
        if(shop==null||loading||batchRowsReadInFlight||productTable.getItems().size()>=filteredCount)return;
        loadPage(true);
    }

    private void loadPage(boolean append) {
        loadPage(append,false);
    }
    private void loadPage(boolean append,boolean backgroundRefresh) {
        long sequence=loadSequence;Shop owner=shop;long epoch=shopEpoch;
        SearchCriteria criteria=criteria(append?productTable.getItems().size():0);
        int visibleExtent=backgroundRefresh?Math.max(PAGE_SIZE,productTable.getItems().size()):PAGE_SIZE;
        var previous=cardIndex;var retain=new java.util.concurrent.atomic.AtomicBoolean();
        if(backgroundRefresh)batchRowsReadInFlight=true;else{loading=true;setLoading(true);}
        Task<LoadedRegistration> task=new Task<>() {
            @Override protected LoadedRegistration call(){
                retain.set(previous!=null&&previous.currentScope(owner));
                var ready=sources.check(owner);
                if(!ready.ready())return new LoadedRegistration(ready,null,null);
                var index=new RegistrationCardIndex(owner);
                var query=new ZnackRegistrationQueryRepository(ready.run(),index);
                var page=query.page(criteria);
                if(backgroundRefresh&&visibleExtent>PAGE_SIZE){
                    var rows=new java.util.LinkedHashMap<Long,Sku>();var details=new java.util.LinkedHashMap<Long,ZnackRegistrationQueryRepository.Details>();
                    page.rows().forEach(row->rows.put(row.chrtId(),row));details.putAll(page.details());
                    for(int offset=PAGE_SIZE;offset<visibleExtent&&offset<page.filtered();offset+=PAGE_SIZE){
                        var next=query.page(new SearchCriteria(criteria.shopId(),criteria.query(),criteria.subjects(),criteria.status(),Math.min(PAGE_SIZE,visibleExtent-offset),offset));
                        next.rows().forEach(row->rows.put(row.chrtId(),row));details.putAll(next.details());
                        if(next.rows().isEmpty())break;
                    }
                    page=new ZnackRegistrationQueryRepository.Page(List.copyOf(rows.values()),java.util.Map.copyOf(details),page.filtered(),page.summary());
                }
                var current=sources.require(owner);
                if(!current.run().equals(ready.run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
                return new LoadedRegistration(ready,index,page);
            }
        };
        task.setOnSucceeded(event->{
            if(sequence!=loadSequence||!isCurrent(owner,epoch))return;
            var retained=backgroundRefresh?List.copyOf(selectedRows()):List.<Sku>of();
            var result=task.getValue();if(backgroundRefresh)batchRowsReadInFlight=false;else loading=false;readiness=result.readiness();
            readinessLabel.setText(readiness.ready()?tr("znack.registration.sources_ready")+" · "+readiness.completed():tr("znack.registration.sources_required")+" · "+readiness.reason());
            if(!readiness.ready()){sourceReadUnavailable(readiness.reason(),retain.get());setLoading(false);updateEmpty();return;}
            cardIndex=result.index();latestReadUnknown=false;sourceReadAt=readiness.completed();
            var page=result.page();summary=page.summary();filteredCount=page.filtered();if(!append)rowDetails.clear();rowDetails.putAll(page.details());
            selectingProgrammatically=true;
            try {
                if(append) {
                    var existing=productTable.getItems().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());
                    productTable.getItems().addAll(page.rows().stream().filter(row->!existing.contains(row.chrtId())).toList());
                } else {
                    if(backgroundRefresh){frozenSelection=retained;frozenSelectionIds=retained.stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());frozenActions=null;}
                    productTable.getItems().setAll(page.rows());
                    if(backgroundRefresh)for(int n=0;n<productTable.getItems().size();n++)if(frozenSelectionIds.contains(productTable.getItems().get(n).chrtId()))productTable.getSelectionModel().select(n);
                }
            } finally {selectingProgrammatically=false;}
            setLoading(false);updateEmpty();updateCounts();
        });
        java.util.function.Consumer<Throwable> failure=error->{
            if(sequence!=loadSequence||!isCurrent(owner,epoch))return;
            if(backgroundRefresh){batchRowsReadInFlight=false;sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get());showBatchProgressFailure(error);return;}
            loading=false;sourceReadUnavailable(SyncFailure.of(error,0).code(),retain.get());setLoading(false);showError(error);
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void sourceReadUnavailable(String code,boolean retain){
        readiness=null;latestReadUnknown=true;
        if(!retain){cardIndex=null;sourceReadAt="";productTable.getItems().clear();rowDetails.clear();summary=null;filteredCount=0;frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;}
        readinessLabel.setText(RegistrationDiagnostics.readWarning(code,retain));
        updateActionState();updateEmpty();
    }

    private void createCard(Sku sku) {
        if(shop==null || !ready() || preparing || loading || sku==null || view(sku).blocked()&&!reviewableDraft(sku) || view(sku).published()&&!groupReplacement(sku))return;
        if(groupReplacement(sku)){replaceCard(sku);return;}
        if(reviewableDraft(sku)||RegistrationCardIndex.notSubmitted(sku)&&!groupReplacement(sku)){
            if(batchProgress!=null&&batchProgress.running())return;
            reviewBeforeRegistration(shop,shopEpoch,List.of(sku));return;
        }
        if(sku.canResume()&&!groupReplacement(sku)) {
            if(!confirm(tr("znack.registration.confirm_resume")))return;
            Shop owner=shop;long epoch=shopEpoch;boolean automatic=autoSignCheckBox.isSelected();
            submitWorkflow(owner,epoch,()->workflow.resume(owner,sku,automatic,(status,detail)->Platform.runLater(()->{
                if(!isCurrent(owner,epoch))return;if(status==Status.ERROR)showWorkflowError(detail);reload();
            })),()->{});return;
        }
        if(!eligible(sku))return;
        boolean fresh=sku.needsGtinReview();
        if(fresh && !confirm(java.text.MessageFormat.format(tr("znack.registration.confirm_new_gtin"),1)))return;
        Shop owner=shop;long epoch=shopEpoch;
        preparing=true;registrationReports=new RegistrationAttemptReports(owner);updateActionState();
        prepareCard(owner,epoch,sku,fresh,()->{preparing=false;registrationPill(tr("znack.registration.request_finished"),false);updateActionState();reload();});
    }

    private void prepareCard(Shop owner,long epoch,Sku sku,boolean fresh,Runnable completed) {
        prepareCard(owner,epoch,sku,fresh,false,completed);
    }

    private void prepareCard(Shop owner,long epoch,Sku sku,boolean fresh,boolean replacement,Runnable completed) {
        if(!isCurrent(owner,epoch))return;
        if(!(replacement?sku.canRegisterNew():eligible(sku))){completed.run();return;}
        setLoading(true);
        var batch=registrationReports==null?new RegistrationAttemptReports(owner):registrationReports;
        Task<RegistrationInputs> task=new Task<>() {
            @Override protected RegistrationInputs call()throws Exception {
                try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){
                var attempt=batch.begin(sku);lastRegistrationRun=attempt.run();
                try {
                String tnved="";
                ZnackModels.Settings current=settings(owner);
                var key=new com.vncode.app.features.products.ProductKey(owner.getId(),owner.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
                var snapshot=new com.vncode.app.features.products.ProductRepository().localSnapshot(key).orElse(null);
                if(snapshot==null||snapshot.checkedAt()==null||snapshot.checkedAt().isBefore(java.time.Instant.now().minusSeconds(60))
                        ||!com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner).equals(snapshot.metadata().get("owner")))
                    snapshot=new com.vncode.app.integration.wb.WbProductSnapshotReader().readForSynchronization(owner,key);
                var productInputs=com.vncode.app.integration.znack.registration.RegistrationProductInputs.from(sku,snapshot);
                List<WbCharacteristic> characteristics=productInputs.characteristics();tnved=ZnackWbAttributeMapper.findTnved(characteristics);
                var code=snapshot.attributes().stream().filter(a->com.vncode.app.features.products.ProductFieldNames.matches("tnved",a.name())).flatMap(a->a.values().stream()).filter(v->v.matches("[0-9]{4,10}")).distinct().toList();
                if(code.size()==1)tnved=code.getFirst();
                if(tnved.isBlank())throw new RegistrationInputException("ZNACK_MISSING_TNVED",java.text.MessageFormat.format(tr("znack.registration.missing_tnved"),sku.vendorCode(),first(sku.sourceBarcode(),Long.toString(sku.chrtId())),sku.subjectName()));
                var documents=ZnackRegistrationDocuments.select(snapshot,current,LocalDate.now());
                if(documents.candidates().isEmpty())throw new RegistrationInputException("ZNACK_MISSING_PRODUCT_DOCUMENT",tr("znack.registration.missing_product_document"));
                var scope=ZnackRegistrationScope.fromSettings(current);
                ZnackSignatureProvider signer=scope.verifyingSigner(owner,signer(current));
                ZnackApiClient api=com.vncode.app.integration.znack.CatalogConnectionService.apiForShop(owner,current);ZnackAuthService auth=new ZnackAuthService(api,signer);
                var service=new ZnackNationalCatalogService(api,auth,signer,current);
                scope.assertCurrent(owner);var preflight=service.preflight(tnved);scope.assertCurrent(owner);
                documents=service.reviewDocuments(preflight.token(),documents,tnved);scope.assertCurrent(owner);
                return new RegistrationInputs(productInputs.sku(),snapshot,characteristics,scope,documents,service,preflight,attempt);
                }catch(Exception error){if(error instanceof RegistrationInputException input)attempt.blocked(input.code);else if(CatalogFeedback.retryableRead(error))attempt.waiting(error);else attempt.failed(error);throw error;}
                }
            }
        };
        registrationPreflightTask=task;
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;registrationPreflightTask=null;
            setLoading(false);var inputs=task.getValue();
            if(inputs.documents().candidates().isEmpty()){
                if(inputs.documents().issues().stream().anyMatch(issue->issue.contains("ZNACK_DOCUMENT_REGISTRY_PENDING"))){
                    summaryLabel.setText(sku.vendorCode()+" · "+tr("catalogsync.issue.REGISTRY_DOCUMENTS_PENDING"));
                    recordBlocked(inputs.attempt(),"ZNACK_DOCUMENT_REGISTRY_PENDING");completed.run();return;
                }
                var key=new com.vncode.app.features.products.ProductKey(owner.getId(),owner.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
                var review=new RegistrationBatchPreflight.Review(List.of(new RegistrationBatchPreflight.Finding("documents",key,sku.vendorCode(),String.join("; ",inputs.documents().issues()),"")));
                if(RegistrationBatchPreflight.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),owner,review)&&isCurrent(owner,epoch))prepareCard(owner,epoch,sku,fresh,replacement,completed);
                else {recordBlocked(inputs.attempt(),"ZNACK_DOCUMENT_TNVED_MISMATCH");completed.run();}
                return;
            }
            ZnackModels.GoodsDocument document=chooseDocument(inputs.documents(),inputs.preflight().tnved());
            if(document==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_DOCUMENT_SELECTION_CANCELLED");completed.run();return;}
            Category category=chooseCategory(inputs.sku(),inputs.preflight().categories());
            if(category==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_CATEGORY_UNRESOLVED");completed.run();return;}
            buildAutomaticDraft(owner,epoch,sku,fresh,replacement,inputs,document,category,completed);
        });
        var notified=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Throwable> failure=error->{
            if(!notified.compareAndSet(false,true))return;
            if(!isCurrent(owner,epoch))return;registrationPreflightTask=null;setLoading(false);
            if(retryPreparation(owner,epoch,sku,fresh,replacement,completed,error))return;
            if(bulkCards.remove(sku.chrtId()))operationLabel.setText(com.vncode.app.shared.FriendlyErrorService.format(error));else if(error instanceof RegistrationInputException)showWarning(error.getMessage());else showError(error);completed.run();
        };
        task.setOnFailed(event->failure.accept(task.getException()));task.setOnCancelled(event->Platform.runLater(()->failure.accept(RegistrationBatchPreflight.readTimeout())));AppTaskExecutor.executeCatalog(task,failure);
    }

    private boolean retryPreparation(Shop owner,long epoch,Sku sku,boolean fresh,boolean replacement,Runnable completed,Throwable failure){
        if(!CatalogFeedback.retryableRead(failure)||failure instanceof java.io.InterruptedIOException&&!(failure instanceof java.net.SocketTimeoutException))return false;
        int attempt=preparationAttempts.merge(sku.chrtId(),1,Integer::sum);
        if(attempt>=3){preparationRetry.stop();preparationAttempts.remove(sku.chrtId());return false;}
        var delay=SyncFailure.of(failure,attempt-1).delay();
        String next=java.time.LocalTime.now().plusNanos(delay.toNanos()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        operationLabel.setText(java.text.MessageFormat.format(tr("znack.registration.preparation_retry"),sku.vendorCode(),next));
        registrationPill(operationLabel.getText(),true);
        preparationRetry.setDuration(Duration.millis(Math.max(1000,delay.toMillis())));
        preparationRetry.setOnFinished(event->{if(isCurrent(owner,epoch))prepareCard(owner,epoch,sku,fresh,replacement,completed);});
        preparationRetry.playFromStart();return true;
    }

    private ZnackModels.GoodsDocument chooseDocument(ZnackRegistrationDocuments.Selection selection,String tnved) {
        if(selection.automatic()!=null&&!selection.requiresReview())return selection.automatic();
        var choicesKey=new DocumentChoice(List.copyOf(selection.candidates()),tnved,List.copyOf(selection.issues()));
        if(preparing&&reviewedDocuments.containsKey(choicesKey))return reviewedDocuments.get(choicesKey);
        Dialog<ZnackModels.GoodsDocument> dialog=new Dialog<>();dialog.setTitle(tr("znack.registration.documents"));
        dialog.setHeaderText(tr("znack.registration.perproduct_review"));
        ComboBox<ZnackModels.GoodsDocument> choices=new ComboBox<>();choices.getItems().setAll(selection.candidates());
        choices.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ZnackModels.GoodsDocument document){return document==null?"":document.type()+" · "+document.number()+" · "+document.date();}
            public ZnackModels.GoodsDocument fromString(String ignored){throw new UnsupportedOperationException();}
        });
        choices.getSelectionModel().selectFirst();choices.setPrefWidth(620);
        var note=new Label(tr("znack.registration.document_scope_advisory")+"\nTN VED: "+tnved+"\n"+String.join("\n",selection.issues()));note.setWrapText(true);note.setMaxWidth(680);
        dialog.getDialogPane().setContent(new VBox(12,note,choices));
        var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);
        dialog.setResultConverter(button->button==apply?choices.getValue():null);
        DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
        var result=dialog.showAndWait().orElse(null);if(preparing)reviewedDocuments.put(choicesKey,result);return result;
    }

    private Category chooseCategory(Sku sku,List<Category> categories) {
        var selected=com.vncode.app.integration.znack.registration.ZnackCategoryResolver.resolve(sku.subjectName(),sku.title(),categories);
        if(selected.isEmpty())summaryLabel.setText(sku.vendorCode()+" · "+tr("znack.registration.category_auto_missing"));
        return selected.orElse(null);
    }

    private void buildAutomaticDraft(Shop owner,long epoch,Sku sku,boolean fresh,boolean replacement,
            RegistrationInputs inputs,ZnackModels.GoodsDocument document,Category category,Runnable completed) {
        setLoading(true);
        Task<AutomaticDraft> task=new Task<>() {
            @Override protected AutomaticDraft call()throws Exception {
                try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true);var countries=inputs.service().registrationCountryReadScope()){
                inputs.scope().assertCurrent(owner);
                var required=inputs.service().registrationAttributes(category.id(),inputs.preflight().token());
                inputs.scope().assertCurrent(owner);
                var mapped=new ZnackWbAttributeMapper().map(inputs.sku(),inputs.characteristics(),required,
                        inputs.preflight().tnved(),inputs.preflight().categoryTnved(),document);
                var draft=new Draft(inputs.preflight().tnved(),inputs.preflight().categoryTnved(),category.id(),
                        mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes()).withProductCategory(inputs.sku().subjectName());
                draft=com.vncode.app.integration.znack.registration.RegistrationVerifiedDefaults.applyAutomatic(
                        draft,required,inputs.service(),document,inputs.snapshot());
                return new AutomaticDraft(draft,required);
                }catch(Exception error){if(CatalogFeedback.retryableRead(error))inputs.attempt().waiting(error);else inputs.attempt().failed(error);throw error;}
            }
        };
        registrationPreflightTask=task;
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;registrationPreflightTask=null;setLoading(false);var result=task.getValue();
            var draft=RegistrationDraftCompletionDialog.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),result.draft(),result.required(),inputs.sku().wbSize());
            if(draft==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_REQUIRED_ATTRIBUTES_MISSING");completed.run();return;}
            startWorkflow(owner,epoch,sku,draft,fresh,replacement,inputs.attempt(),completed);
        });
        var notified=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Throwable> failure=error->{if(!notified.compareAndSet(false,true))return;if(isCurrent(owner,epoch)){registrationPreflightTask=null;setLoading(false);if(retryPreparation(owner,epoch,sku,fresh,replacement,completed,error))return;if(!bulkCards.remove(sku.chrtId()))showError(error);completed.run();}};
        task.setOnFailed(event->failure.accept(task.getException()));task.setOnCancelled(event->Platform.runLater(()->failure.accept(RegistrationBatchPreflight.readTimeout())));AppTaskExecutor.executeCatalog(task,failure);
    }

    private void startWorkflow(Shop owner,long epoch,Sku sku,Draft draft,boolean fresh,boolean replacement,RegistrationAttemptReports.Attempt attempt,Runnable completed) {
        preparationAttempts.remove(sku.chrtId());
        var listener=registrationListener(owner,epoch,sku,attempt);
        var authorised=draft.withAutoSign(autoSignCheckBox.isSelected());
        submitWorkflow(owner,epoch,()->{
            try {
                boolean accepted=replacement?workflow.registerNew(owner,sku,authorised,true,listener)
                    :fresh?workflow.requestNewGtin(owner,sku,authorised,true,listener):workflow.start(owner,sku,authorised,listener);
                if(!accepted)attempt.blocked("ZNACK_REGISTRATION_ALREADY_RUNNING");
                return accepted;
            }catch(Exception error){attempt.failed(error);throw error;}
        },sku,completed);
    }

    private java.util.function.BiConsumer<Status,String> registrationListener(Shop owner,long epoch,Sku sku,RegistrationAttemptReports.Attempt attempt) {
        return (status,detail)->{
            try{attempt.observe(status,detail);}catch(RuntimeException reportError){
                Platform.runLater(()->{if(isCurrent(owner,epoch))summaryLabel.setText("SYNC_STORAGE · "+tr("znack.registration.bulk_review"));});
            }
            Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;
            registrationPill(sku.vendorCode()+" · "+tr("znack.registration.report_step."+(status==Status.PUBLISHED?"CARD_PUBLISHED":status.name())),true);
            scheduleReload();
            if (status == Status.ERROR || status == Status.GTIN_REVIEW_REQUIRED
                    || status == Status.FEED_REVIEW_REQUIRED || status == Status.PRE_SUBMIT_ERROR
                    || status == Status.PREFLIGHT_ERROR) {
                if(bulkCards.remove(sku.chrtId()))summaryLabel.setText(tr("znack.registration.bulk_review"));else showWorkflowError(detail);
            } else if (status == Status.PUBLISHED && !detail.isBlank() && !bulkCards.remove(sku.chrtId())) {
                showInfo(tr("znack.registration.completed") + " " + detail);
            }
        });};
    }
    private void registrationPill(String text,boolean running){
        RegistrationStatusPill.show((StackPane)productTable.getParent(),"registrationBackgroundPill",text,running);
    }

    private void recordBlocked(RegistrationAttemptReports.Attempt attempt,String code) {
        Task<Void> task=new Task<>(){protected Void call(){attempt.blocked(code);return null;}};
        task.setOnFailed(e->showError(task.getException()));AppTaskExecutor.execute(task,ZnackCardRegistrationController::showError);
    }

    private void replaceCard(Sku sku) {
        if(shop==null||!ready()||loading||preparing||sku==null||!replaceable(sku))return;
        if(!replacementConfirmation.test(sku))return;
        Shop owner=shop;long epoch=shopEpoch;preparing=true;registrationReports=new RegistrationAttemptReports(owner);updateActionState();
        replacementPreparation.prepare(owner,epoch,sku,false,true,()->{preparing=false;registrationPill(tr("znack.registration.request_finished"),false);updateActionState();reload();});
    }

    private void completeReplacement(Sku sku) {
        if(shop==null||!ready()||loading||preparing||sku==null||sku.status()!=Status.PUBLISHED||!view(sku).published())return;
        if(!confirm(tr("znack.registration.confirm_switch")))return;
        Shop owner=shop;long epoch=shopEpoch;
        submitWorkflow(owner,epoch,()->workflow.completeReplacement(owner,sku,true,(status,detail)->Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;if(detail!=null&&!detail.isBlank())showWorkflowError(detail);reload();
        })),()->{});
    }

    private void readStatus(Sku sku) {
        if(shop==null||loading||preparing||sku==null)return;
        Shop owner=shop;long epoch=shopEpoch;loading=true;setLoading(true);
        Task<ZnackNationalCatalogService.FeedProgress> task=new Task<>(){@Override protected ZnackNationalCatalogService.FeedProgress call()throws Exception{
            if(repository.scope(owner.getId(),sku.chrtId())!=null)workflow.refreshStatus(owner,sku);
            return workflow.readOnlyProgress(owner,sku);
        }};
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);var result=task.getValue();
            Status status=result.signed()?Status.PUBLISHED:result.readyToSign()?Status.READY_TO_SIGN:result.failed()?Status.ERROR:Status.PROCESSING;
            showInfo(statusText(status)+(result.errorMessage().isBlank()?"":"\n"+result.errorMessage()));scheduleReload();
        }});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private record LoadedRegistration(RegistrationSources.Readiness readiness,RegistrationCardIndex index,ZnackRegistrationQueryRepository.Page page) {}
    private boolean ready(){return readiness!=null&&readiness.ready();}
    private ZnackRegistrationQueryRepository readyQueries(Shop owner){return new ZnackRegistrationQueryRepository(sources.require(owner).run(),new RegistrationCardIndex(owner));}
    private RegistrationCardIndex.View view(Sku row){return cardIndex==null?new RegistrationCardIndex.View("source.unverified",value(row.gtin()),"SOURCE_SYNC_REQUIRED",false,false,true)
            :cardIndex.view(row.status(),row.gtin(),row.barcodes(),row.vendorCode(),row.color(),row.size(),row.wbSize(),cardIndex.ownsWb(row.chrtId()));}
    private boolean replaceable(Sku row){return row!=null&&row.canRegisterNew()&&!view(row).blocked()&&(view(row).published()||row.status()==Status.ERROR);}
    @FXML private void onReport(){if(shop!=null)RegistrationSyncReportDialog.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),shop,lastRegistrationRun);}

    private void scheduleReload(){workflowRefresh.playFromStart();}

    private ZnackModels.Settings settings() {
        return settings(shop);
    }
    private ZnackModels.Settings settings(Shop owner) {
        return new ZnackRepository(new ZnackModels.ShopContext(owner.getId(), owner.getName())).getSettings();
    }

    private static ZnackSignatureProvider signer(ZnackModels.Settings settings) {
        return new CryptoProSignatureProvider(settings.cryptcpPath(), settings.signerCertificate(),
                java.time.Duration.ofSeconds(settings.resolvedCryptoProTimeoutSeconds()));
    }

    private void configureColumns() {
        productTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        selectionColumn.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        selectionColumn.setCellFactory(column->new TableCell<>(){
            private final CheckBox check=new CheckBox();
            {setAlignment(Pos.CENTER);check.setOnAction(event->{var row=getItem();if(row==null)return;frozenSelection=List.of();frozenSelectionIds=Set.of();frozenActions=null;
                if(check.isSelected())productTable.getSelectionModel().select(row);else productTable.getSelectionModel().clearSelection(getIndex());updateActionState();event.consume();});
             check.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);}
            protected void updateItem(Sku row,boolean empty){super.updateItem(row,empty);setGraphic(empty||row==null?null:check);
                if(!empty&&row!=null){check.setSelected(frozenSelectionIds.isEmpty()?productTable.getSelectionModel().isSelected(getIndex()):frozenSelectionIds.contains(row.chrtId()));check.setDisable(preparing||loading||!selectable(row));}}
        });
        imageColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        imageColumn.setCellFactory(column -> new TableCell<>() {
            private final ImageView imageView = new ImageView();
            private final Region placeholder = new Region();
            private final StackPane pane = new StackPane(placeholder, imageView);
            private String currentUrl;
            {
                imageView.setFitWidth(44);
                imageView.setFitHeight(58);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                placeholder.setMinSize(44, 58);
                placeholder.setPrefSize(44, 58);
                placeholder.setMaxSize(44, 58);
                placeholder.getStyleClass().add("fbo-image-placeholder");
                pane.setMinHeight(62);
            }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    currentUrl = null;
                    imageView.setImage(null);
                    setGraphic(null);
                    return;
                }
                currentUrl = item.imageUrl() == null ? "" : item.imageUrl().strip();
                imageView.setImage(null);
                imageView.setVisible(false);
                placeholder.setVisible(true);
                setGraphic(pane);
                if (currentUrl.isBlank()) return;
                String requestedUrl = currentUrl;
                imageService.loadThumbnailBytes(requestedUrl).whenComplete((bytes, error) -> Platform.runLater(() -> {
                    if (!Objects.equals(currentUrl, requestedUrl) || bytes == null || bytes.length == 0) return;
                    imageView.setImage(new Image(new ByteArrayInputStream(bytes)));
                    imageView.setVisible(true);
                    placeholder.setVisible(false);
                }));
            }
        });
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(productName(cell.getValue())));
        articleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().vendorCode()));
        colorColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().color()));
        genderColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                rowDetails.getOrDefault(cell.getValue().chrtId(),new ZnackRegistrationQueryRepository.Details("","")).gender()));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().size()));
        russianSizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(RegistrationTableText.russianSize(cell.getValue().wbSize())));
        barcodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(String.join(", ",cell.getValue().barcodes())));
        RegistrationTextCells.wrap(barcodeColumn);RegistrationTextCells.wrap(verifiedGtinColumn);
        verifiedGtinColumn.setCellValueFactory(cell->new ReadOnlyStringWrapper(verifiedGtinText(cell.getValue())));
        RegistrationTextCells.wrap(detailColumn);
        detailColumn.setCellValueFactory(cell->new ReadOnlyStringWrapper(reason(view(cell.getValue()).detail())
                +(value(cell.getValue().errorMessage()).isBlank()?"":" · "+RegistrationDiagnostics.reason(cell.getValue().errorMessage()))));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(registrationStatus(cell.getValue())));
        moderationColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(RegistrationTableText.moderation(
                cardIndex==null?java.util.Optional.empty():cardIndex.observation(view(cell.getValue())),latestReadUnknown)));
        marketplaceSyncColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(RegistrationTableText.marketplace(
                verifiedGtinProof(cell.getValue()),cell.getValue().barcodes(),latestReadUnknown,sourceReadAt,"znack.registration.awaiting_wb_gtin")));
        RegistrationTextCells.wrap(russianSizeColumn);RegistrationTextCells.wrap(moderationColumn);RegistrationTextCells.wrap(marketplaceSyncColumn);
        statusColumn.setCellFactory(column -> new TableCell<>() {
            {setOnMouseClicked(event->{var row=getTableRow()==null?null:getTableRow().getItem();if(row!=null){showRegistrationReport(row);event.consume();}});}
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(empty?"":"-fx-font-weight: 700;");setWrapText(true);
                var row=getTableRow()==null?null:getTableRow().getItem();
                setTooltip(row==null?null:new Tooltip(reason(view(row).detail())+"\n"+value(row.errorMessage())));
            }
        });
        actionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        actionColumn.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button();
            private final Label waiting = new Label();
            private final MenuButton publishedActions=new MenuButton();
            private final MenuItem newCard=new MenuItem(),switchCard=new MenuItem(),retryOriginal=new MenuItem();
            { button.getStyleClass().add("btn-primary");button.setOnAction(event->createCard(getItem()));
              publishedActions.getStyleClass().add("btn-secondary");publishedActions.getItems().setAll(newCard,switchCard,retryOriginal);retryOriginal.setOnAction(event->createCard(getItem()));
              newCard.setOnAction(event->replaceCard(getItem()));switchCard.setOnAction(event->completeReplacement(getItem())); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                String pendingAction=reviewableDraft(item)?"":RegistrationDiagnostics.pendingCardAction(item.status(),item.goodId(),item.feedId(),item.errorMessage(),view(item).status());
                if(!pendingAction.isBlank()){
                    waiting.setText(tr(pendingAction));waiting.setWrapText(true);
                    waiting.setStyle("-fx-font-weight: 600;");setAlignment(Pos.CENTER);setGraphic(waiting);return;
                }
                if(view(item).published()&&!groupReplacement(item)){
                    boolean present=com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(item.barcodes(),view(item).gtin());
                    button.setText(tr(present?"znack.registration.create_new_card":"znack.registration.update_wb_short"));
                    button.setOnAction(event->{if(present)replaceCard(getItem());else updateOneGtin(getItem());});
                    button.setDisable(!ready()||preparing||loading||present&&!replaceable(item));
                    var newCandidate=new MenuItem(tr("znack.registration.create_new_card"));newCandidate.setOnAction(e->replaceCard(item));newCandidate.setDisable(!replaceable(item));
                    var switchCandidate=new MenuItem(tr("znack.registration.switch_card"));switchCandidate.setOnAction(e->completeReplacement(item));switchCandidate.setDisable(!present||!rowDetails.getOrDefault(item.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement());
                    button.setContextMenu(new ContextMenu(newCandidate,switchCandidate));setAlignment(Pos.CENTER);setGraphic(button);return;
                }
                button.setContextMenu(null);button.setOnAction(event->createCard(getItem()));
                if(item.canRegisterNew()&&!groupReplacement(item)&&!reviewableDraft(item)) {
                    publishedActions.setText(tr("znack.registration.action"));newCard.setText(tr("znack.registration.create_new_card"));
                    switchCard.setText(tr("znack.registration.switch_card"));
                    boolean pending=rowDetails.getOrDefault(item.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement();
                    retryOriginal.setText(tr("znack.registration.retry_existing_gtin"));retryOriginal.setVisible(item.status()==Status.ERROR);retryOriginal.setDisable(preparing||loading||!eligible(item));
                    newCard.setDisable(!ready()||preparing||loading||pending&&item.status()!=Status.ERROR||!replaceable(item)||workflow.isRunning(shop,item));
                    switchCard.setDisable(!ready()||!view(item).published()||preparing||loading||!pending||workflow.isRunning(shop,item));
                    setAlignment(Pos.CENTER);setGraphic(publishedActions);return;
                }
                button.setText(reviewableDraft(item)?tr("znack.registration.review_existing_draft"):groupReplacement(item)?tr("znack.registration.create_new_card"):view(item).exists()&&item.status()==Status.NOT_CREATED?tr("znack.registration.card_exists"):item.canResume()?tr("znack.registration.resume")
                        : item.needsGtinReview()?tr("znack.registration.new_gtin")
                        : item.status()==Status.ERROR || item.status()==Status.PRE_SUBMIT_ERROR || item.status()==Status.PREFLIGHT_ERROR
                        ?tr("znack.registration.retry"):tr("znack.registration.create"));
                button.setDisable(!ready() || view(item).blocked()&&!reviewableDraft(item) || view(item).published()&&!groupReplacement(item) || groupReplacement(item)&&!replaceable(item) || preparing || loading || (!eligible(item)&&!item.canResume()) || workflow.isRunning(shop,item));
                setAlignment(Pos.CENTER);
                setGraphic(button);
            }
        });
        productTable.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if(!empty&&item!=null&&item.hasFeed()&&item.hasGtin()) {
                    var read=new MenuItem(tr("znack.registration.read_status"));read.setOnAction(event->readStatus(item));
                    var report=new MenuItem(tr("znack.registration.report"));report.setOnAction(event->onReport());setContextMenu(new ContextMenu(read,report));
                } else if(!empty&&item!=null){var error=new MenuItem(tr("znack.registration.report"));error.setOnAction(event->showRegistrationReport(item));setContextMenu(new ContextMenu(error));}else setContextMenu(null);
                setTooltip(empty || item == null?null:new Tooltip(observation(item)+(value(item.errorMessage()).isBlank()?"":"\n"+RegistrationDiagnostics.reason(item.errorMessage()))));
            }
        });
    }

    private static String normalizedDocumentDate(String input) {
        String value = input == null ? "" : input.trim();
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeParseException ignored) {
            try { return LocalDate.parse(value, RU_DATE).toString(); }
            catch (DateTimeParseException error) {
                throw new IllegalArgumentException(tr("znack.registration.invalid_document_date"));
            }
        }
    }

    private void setLoading(boolean value) {
        productTable.setPlaceholder(value?new com.vncode.app.shared.LoadingSkeleton(tr("znack.registration.loading")):new Label(tr("znack.registration.empty")));
        loadingLabel.setVisible(value); loadingLabel.setManaged(value); refreshButton.setDisable(value);
        updateActionState();
    }
    private boolean isCurrent(Shop owner,long epoch) {return shop==owner && shopEpoch==epoch;}
    private boolean groupReplacement(Sku sku){return sku!=null&&sku.tnvedGroupChanged();}
    private boolean reviewableDraft(Sku sku){return sku!=null&&!sku.hasFeed()&&view(sku).canReviewDraft();}
    private boolean eligible(Sku sku) {
        if(reviewableDraft(sku))return ready()&&shop!=null&&!workflow.isRunning(shop,sku);
        if(sku!=null&&sku.canReviewExistingDraft())return false;
        return ready() && shop!=null && sku!=null && !view(sku).blocked() && (!view(sku).published()||groupReplacement(sku)) && !workflow.isRunning(shop,sku)
                && (sku.canRegister() || sku.canResume() || sku.needsGtinReview()||groupReplacement(sku));
    }
    private SelectionActions selectionActions(){
        if(frozenActions!=null)return frozenActions;
        boolean register=false,repair=false,replace=false;
        for(var row:selectedRows()){
            var observed=view(row);repair|=RegistrationCardIndex.rejectedCard(observed,row.status(),row.goodId(),row.feedId());
            register|=eligible(row)||errorRow(row);replace|=replaceable(row);
            if(register&&repair&&replace)break;
        }
        return new SelectionActions(register,repair,replace);
    }
    private void updateActionState() {
        boolean unavailable=shop==null||preparing||loading;
        boolean disabled=unavailable||!ready();
        allFilteredCheckBox.setDisable(disabled);reportLink.setDisable(shop==null);
        var actions=selectionActions();
        autoSignCheckBox.setDisable(disabled);selectAllButton.setDisable(disabled);
        registerSelectedButton.setText(tr(statusFilter.getValue()==StatusFilter.ERROR||actions.repair()?"repair.edit":"znack.registration.register_short"));
        registerSelectedButton.setDisable(disabled||statusFilter.getValue()!=StatusFilter.ERROR&&!actions.register()&&!actions.repair());
        newCardsSelectedButton.setDisable(disabled||!actions.replace());
        searchField.setDisable(false);categoryMenuButton.setDisable(false);statusFilter.setDisable(false);clearFiltersButton.setDisable(false);
        boolean paused=batchProgress!=null&&"PAUSED".equals(batchProgress.state().name());
        if(batchProgress!=null&&(batchProgress.running()||paused)&&statusFilter.getValue()!=StatusFilter.ERROR)registerSelectedButton.setDisable(true);
        if(paused)newCardsSelectedButton.setDisable(true);
        pauseBatchAction.setDisable(shop==null||batchControlBusy||batchProgress==null||!batchProgress.running());resumeBatchAction.setDisable(shop==null||batchControlBusy||!paused||!ready());cancelPendingBatchAction.setDisable(shop==null||batchControlBusy||batchProgress==null||!batchProgress.running()&&!paused);
        configButton.setDisable(unavailable);signApprovedButton.setDisable(disabled);updateWbButton.setDisable(disabled);syncZnackButton.setDisable(unavailable);reportButton.setDisable(shop==null);
        loadMoreButton.setDisable(shop==null||loading||!ready()||productTable.getItems().size()>=filteredCount);
        clearSelectionButton.setDisable(disabled||selectedRows().isEmpty());updateCounts();productTable.refresh();
    }
    private void updateCounts() {
        if(paginationLabel==null)return;
        paginationLabel.setText(tr("znack.registration.report_scope_filter")+" · "+java.text.MessageFormat.format(tr("znack.registration.page_count"),productTable.getItems().size(),filteredCount));
        selectionLabel.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selectedRows().size()));
        allFilteredCheckBox.setSelected(filteredCount>0&&selectedRows().size()==filteredCount);
        boolean more=productTable.getItems().size()<filteredCount;loadMoreButton.setVisible(false);loadMoreButton.setManaged(false);
        if(summary==null){summaryLabel.setText(tr("znack.registration.summary_empty"));return;}
        String pipeline=java.text.MessageFormat.format(tr("znack.registration.pipeline_summary"),summary.total(),summary.published(),summary.readyToSign(),summary.waiting(),summary.errors());
        String catalog=summary.syncedAt()==null?tr("znack.registration.catalog_unknown"):
                java.text.MessageFormat.format(tr("znack.registration.catalog_summary"),summary.catalog(),summary.catalogPublished(),summary.catalog()-summary.catalogPublished(),summary.catalogUnlinked());
        summaryLabel.setText(tr("znack.registration.report_scope_store")+" · "+pipeline+"\n"+catalog+(cardIndex==null?"":" · "+tr("znack.registration.catalog_error_total")+": "+cardIndex.catalogErrorCount()));
    }
    private String productName(Sku sku) {
        var detail=rowDetails.get(sku.chrtId());
        return first(sku.title(),sku.subjectName())+"\n"+first(sku.brand(),sku.subjectName())
                +(detail==null||detail.tnved().isBlank()?"":"\nTN VED: "+detail.tnved());
    }
    private static boolean confirm(String message) {
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);
        alert.setHeaderText(tr("znack.registration.register_selected"));
        return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
    }
    private void updateEmpty() {
        boolean empty = productTable.getItems().isEmpty(); emptyLabel.setVisible(empty); emptyLabel.setManaged(empty);
    }
    private void updateCategoryText() {
        categoryMenuButton.setText(selectedSubjects.isEmpty() ? tr("znack.registration.categories")
                : tr("znack.registration.categories") + " (" + selectedSubjects.size() + ")");
    }
    private String registrationStatus(Sku row){
        return tr("znack.registration."+view(row).status());
    }
    private void updateOneGtin(Sku row){
        if(row==null||shop==null||!ready()||preparing||loading)return;
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()));
        GtinBackgroundStatus.start((StackPane)productTable.getParent(),shop,java.util.Set.of(key),this::scheduleReload,result->{lastRegistrationRun=result.run();operationLabel.setText(GtinBackgroundStatus.summary(result));});
    }
    private static boolean isBusy(Status status) {
        return status != Status.NOT_CREATED && status != Status.ERROR
                && status != Status.PUBLISHED;
    }
    private String verifiedGtinText(Sku row){
        return verifiedGtinText(verifiedGtinProof(row),value(view(row).gtin()));
    }
    private java.util.Optional<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin> verifiedGtinProof(Sku row){
        return cardIndex==null||latestReadUnknown?java.util.Optional.empty():cardIndex.verifiedWb(row);
    }
    static String verifiedGtinText(java.util.Optional<com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin> proof,String candidate){
        if(proof.isPresent()){var card=proof.get();String checked=java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(card.syncedAt());return card.gtin()+"\n"+java.text.MessageFormat.format(tr("znack.registration.gtin_verified_at"),checked);}
        return (candidate==null||candidate.isBlank()?"":candidate+"\n")+tr("znack.registration.gtin_unverified");
    }
    private static String statusText(Status status) { return tr("znack.registration.status." + status.name().toLowerCase(Locale.ROOT)); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String value(String value) { return value == null ? "" : value; }
    private static String reason(String code){String key="znack.registration.reason."+code;String text=tr(key);return text.equals(key)?code:text;}
    private static String tr(String key) { return I18nService.getInstance().tr(key); }
    private static void showError(Throwable error) {
        AlertService.showDetailedError(ZnackErrorDetails.summary(error), ZnackErrorDetails.format(error));
    }
    private static void showWorkflowError(String detail) {
        String full = ZnackErrorDetails.formatStored(detail);
        String summary = detail == null || detail.isBlank() ? "Unknown error"
                : detail.lines().filter(line -> !line.isBlank()).findFirst().orElse(detail).replaceFirst("^Summary:\\s*", "");
        AlertService.showDetailedError(summary, full);
    }
    private String observation(Sku row){return RegistrationDiagnostics.observation(row.status(),cardIndex==null?java.util.Optional.empty():cardIndex.observation(view(row)),latestReadUnknown);}
    private void showRegistrationReport(Sku sku){RegistrationDiagnostics.show(sku.vendorCode(),sku.gtin(),sku.feedId(),registrationStatus(sku),observation(sku)+"\n"+reason(view(sku).detail())+"\n"+reason(value(sku.errorMessage())));}
    private static void showWarning(String message) { Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK); alert.setHeaderText(null); alert.showAndWait(); }
    private static void showInfo(String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); alert.setHeaderText(null); alert.showAndWait(); }

    private enum DocumentType {
        DECLARATION("Декларация о соответствии", "CONFORMITY_DECLARATION"),
        CERTIFICATE("Сертификат соответствия", "CONFORMITY_CERTIFICATE");
        private final String label; private final String settingsCode;
        DocumentType(String label, String settingsCode) { this.label = label; this.settingsCode = settingsCode; }
        static DocumentType from(String value) {
            return value != null && value.toUpperCase(Locale.ROOT).contains("CERTIFICATE")
                    ? CERTIFICATE : DECLARATION;
        }
        @Override public String toString() { return label; }
    }

    private enum StatusFilter {
        ALL("ALL"), NOT_CREATED("NOT_CREATED"), IN_PROGRESS("IN_PROGRESS"),
        GTIN_REVIEW_REQUIRED("GTIN_REVIEW_REQUIRED"), FEED_REVIEW_REQUIRED("FEED_REVIEW_REQUIRED"),
        COMPLETED("COMPLETED"), ERROR("ERROR");
        private final String code;
        StatusFilter(String code) { this.code = code; }
        @Override public String toString() { return tr("znack.registration.filter." + name().toLowerCase(Locale.ROOT)); }
    }

    private static final class RegistrationInputException extends IllegalArgumentException {final String code;RegistrationInputException(String code,String text){super(text);this.code=code;}}

    private record RegistrationInputs(Sku sku,com.vncode.app.features.products.ProductSnapshot snapshot,List<WbCharacteristic> characteristics,ZnackRegistrationScope scope,
            ZnackRegistrationDocuments.Selection documents,ZnackNationalCatalogService service,ZnackNationalCatalogService.Preflight preflight,RegistrationAttemptReports.Attempt attempt) {
        @Override public String toString(){return "RegistrationInputs[redacted]";}
    }
    private record AutomaticDraft(Draft draft, List<Attribute> required) { }
}
