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
import com.vncode.app.integration.znack.registration.ZnackNationalCatalogService;
import com.vncode.app.integration.znack.registration.ZnackWbAttributeMapper;
import com.vncode.app.integration.znack.registration.ZnackRegistrationDocuments;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.integration.znack.signature.CryptoProSignatureProvider;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.AlertService;
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
import javafx.util.Duration;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ZnackCardRegistrationController {
    private static final int PAGE_SIZE = ZnackRegistrationQueryRepository.PAGE_SIZE;
    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final ZnackCardRegistrationRepository repository = new ZnackCardRegistrationRepository();
    private final ZnackCardRegistrationWorkflow workflow = new ZnackCardRegistrationWorkflow(repository);
    private final ZnackRegistrationQueryRepository queries = new ZnackRegistrationQueryRepository();
    private final FboProductImageService imageService = new FboProductImageService();
    private final PauseTransition workflowRefresh = new PauseTransition(Duration.millis(400));
    private final java.util.Set<Long> bulkCards=new java.util.HashSet<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final List<String> selectedSubjects = new ArrayList<>();
    private final List<CheckBox> subjectChecks = new ArrayList<>();
    private final RegistrationSources sources=new RegistrationSources();
    private RegistrationSources.Readiness readiness;
    private RegistrationCardIndex cardIndex;
    private RegistrationAttemptReports registrationReports;
    private volatile String lastRegistrationRun="";
    private Shop shop;
    private boolean loading;
    private boolean preparing;
    private final java.util.Map<List<ZnackModels.GoodsDocument>,ZnackModels.GoodsDocument> reviewedDocuments=new java.util.HashMap<>();
    private long shopEpoch;
    private long loadSequence;
    private long filteredCount;
    private List<Sku> frozenSelection=List.of();
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
    @FXML private TableColumn<Sku, String> barcodeColumn;
    @FXML private TableColumn<Sku, String> statusColumn;
    @FXML private TableColumn<Sku, Sku> actionColumn;

    @FXML
    private void initialize() {
        configureColumns();
        extraActions.getChildren().clear();
        var extraMenu=new ContextMenu(new CustomMenuItem(refreshButton,false),new CustomMenuItem(reportButton,false),new CustomMenuItem(newCardsSelectedButton,false),
                new CustomMenuItem(autoSignCheckBox,false),new CustomMenuItem(clearSelectionButton,false),new CustomMenuItem(selectionLabel,false));
        titleLabel.setContextMenu(extraMenu);titleLabel.setFocusTraversable(true);titleLabel.setTooltip(new Tooltip(tr("znack.registration.more_actions_hint")));
        com.vncode.app.shared.TableScrollLoader.install(productTable,()->shop!=null&&!loading&&!preparing&&productTable.getItems().size()<filteredCount,this::onLoadMore);
        workflowRefresh.setOnFinished(event->reload());
        productTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        productTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Sku>) change -> {
                    if(selectingProgrammatically)return;
                    frozenSelection=List.of();updateActionState();
                });
        statusFilter.getItems().setAll(StatusFilter.values());
        statusFilter.getSelectionModel().select(StatusFilter.ALL);
        statusFilter.valueProperty().addListener((obs, old, value) -> reload());
        searchField.textProperty().addListener((obs, old, value) -> {
            debounce.setOnFinished(event -> reload());
            debounce.playFromStart();
        });
        applyTranslations();
    }

    public void dispose(){workflowRefresh.stop();debounce.stop();setShop(null);}

    public void setOzonRegistrationFactory(java.util.function.Function<Shop,javafx.scene.Node> factory) {
        ozonRegistrationFactory=Objects.requireNonNull(factory);
    }

    public void setShop(Shop shop) {
        shopEpoch++;workflowRefresh.stop();bulkCards.clear();reviewedDocuments.clear();
        boolean ozon=shop!=null&&shop.getMarketplace()==Marketplace.OZON;
        wbRegistrationContent.setVisible(!ozon);wbRegistrationContent.setManaged(!ozon);
        ozonRegistrationHost.setVisible(ozon);ozonRegistrationHost.setManaged(ozon);
        for(var previous:ozonRegistrationHost.getChildren())if(previous instanceof OzonRegistrationPane pane)pane.dispose();
        ozonRegistrationHost.getChildren().clear();
        if(ozon&&ozonRegistrationFactory!=null)ozonRegistrationHost.getChildren().setAll(ozonRegistrationFactory.apply(shop));
        preparing=false;readiness=null;cardIndex=null;
        registrationReports=null;lastRegistrationRun="";
        this.shop = shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null;
        productTable.getItems().clear();
        allFilteredCheckBox.setSelected(false);operationLabel.setText("");frozenSelection=List.of();rowDetails.clear();summary=null;filteredCount=0;
        selectedSubjects.clear();
        loadSubjects();
        reload();
    }

    public void applyTranslations() {
        if(ozonRegistrationHost!=null)for(var child:ozonRegistrationHost.getChildren())if(child instanceof OzonRegistrationPane pane)pane.applyTranslations();
        I18nService i18n = I18nService.getInstance();
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
        sizeColumn.setText(i18n.tr("fbo.column.size"));
        barcodeColumn.setText("Barcode WB / GTIN");
        statusColumn.setText(i18n.tr("znack.registration.status"));
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
            for(int index=0;index<productTable.getItems().size();index++)
                if(selectable(productTable.getItems().get(index)))productTable.getSelectionModel().select(index);
        } finally {selectingProgrammatically=false;}
        if(filteredCount<=productTable.getItems().size()) {
            frozenSelection=productTable.getSelectionModel().getSelectedItems().stream().filter(this::selectable).toList();
            updateActionState();return;
        }
        Shop owner=shop;long epoch=shopEpoch;long sequence=loadSequence;
        SearchCriteria criteria=criteria(0);loading=true;setLoading(true);
        Task<List<Sku>> task=new Task<>() {@Override protected List<Sku> call(){return readyQueries(owner).allSelectable(criteria);}};
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch)||sequence!=loadSequence)return;
            loading=false;frozenSelection=task.getValue().stream().filter(this::selectable).toList();setLoading(false);
        });
        java.util.function.Consumer<Throwable> failure=error->{
            if(!isCurrent(owner,epoch)||sequence!=loadSequence)return;
            loading=false;frozenSelection=List.of();setLoading(false);showError(error);
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    @FXML private void onToggleAllFiltered(){if(allFilteredCheckBox.isSelected())onSelectAll();else onClearSelection();}

    @FXML private void onClearSelection() {
        allFilteredCheckBox.setSelected(false);frozenSelection=List.of();productTable.getSelectionModel().clearSelection();updateActionState();
    }

    private List<Sku> selectedRows() {
        return frozenSelection.isEmpty()?List.copyOf(productTable.getSelectionModel().getSelectedItems()):frozenSelection;
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
        if(!isCurrent(owner,epoch)||!confirm(java.text.MessageFormat.format(tr("znack.registration.confirm_sign"),selected.size())))return;
        submitWorkflow(owner,epoch,()->workflow.signApproved(owner,selected,true,(status,detail)->Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;
            if(status==Status.ERROR)summaryLabel.setText(tr("znack.registration.bulk_review"));
            scheduleReload();
        })),()->{});
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
        GtinBackgroundStatus.start((javafx.scene.layout.StackPane)productTable.getParent(),shop,selected,this::scheduleReload);
    }

    @FXML private void onSyncZnack() {
        if(shop==null||preparing||loading)return;
        Shop owner=shop;long epoch=shopEpoch;++loadSequence;readiness=null;cardIndex=null;productTable.getItems().clear();frozenSelection=List.of();loading=true;setLoading(true);
        readinessLabel.setText(tr("znack.registration.sources_syncing"));
        Task<Boolean> task=new Task<>() {
            @Override protected Boolean call() throws Exception {
                sources.refresh(owner,step->Platform.runLater(()->{if(isCurrent(owner,epoch))readinessLabel.setText(tr("znack.registration.sources_syncing")+" · "+tr("znack.registration.step."+step));}));
                return true;
            }
        };
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;reload();}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;reload();showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));task.setOnCancelled(event->failure.accept(new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT")));AppTaskExecutor.executeCatalog(task,failure);
    }

    @FXML private void onRegisterSelected() {
        if(shop==null || !ready() || preparing || loading) return;
        if(statusFilter.getValue()==StatusFilter.ERROR||selectedRows().stream().anyMatch(row->view(row).status().equals("source.errors")||row.status()==Status.ERROR&&row.hasGtin())){openErrorRepair(selectedRows().isEmpty());return;}
        List<Sku> selected=selectedRows().stream().filter(this::eligible).toList();
        if(selected.isEmpty())return;
        long fresh=selected.stream().filter(Sku::needsGtinReview).count();
        String message=java.text.MessageFormat.format(tr("znack.registration.confirm_selected"),selected.size())+"\n"+tr("znack.registration.marketplace_after_publication");
        if(fresh>0)message+="\n\n"+java.text.MessageFormat.format(tr("znack.registration.confirm_new_gtin"),fresh);
        long changed=selected.stream().filter(this::groupReplacement).count();if(changed>0)message+="\n\n"+java.text.MessageFormat.format(tr("znack.registration.group_change_confirm"),changed);
        if(!confirm(message))return;
        Shop owner=shop;long epoch=shopEpoch;
        reviewBeforeRegistration(owner,epoch,selected);
    }
    private void reviewBeforeRegistration(Shop owner,long epoch,List<Sku> selected){
        preparing=true;updateActionState();
        var keys=selected.stream().map(row->new com.vncode.app.features.products.ProductKey(owner.getId(),owner.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).toList();
        Task<RegistrationBatchPreflight.Review> task=new Task<>(){protected RegistrationBatchPreflight.Review call(){sources.require(owner);return RegistrationBatchPreflight.read(owner,keys);}};
        task.setOnSucceeded(e->{
            if(!isCurrent(owner,epoch))return;
            var review=task.getValue();boolean applied=RegistrationBatchPreflight.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),owner,review);
            if(!applied||!isCurrent(owner,epoch)){preparing=false;updateActionState();return;}
            if(selected.size()>1)selected.forEach(row->bulkCards.add(row.chrtId()));
            reviewedDocuments.clear();registrationReports=new RegistrationAttemptReports(owner,selected);prepareNext(selected,0,owner,epoch);
        });
        java.util.function.Consumer<Throwable> failed=error->{if(isCurrent(owner,epoch)){preparing=false;updateActionState();showError(error);}};
        task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
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
        if(index>=selected.size()){preparing=false;updateActionState();reload();return;}
        summaryLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),index,selected.size()));
        prepareCard(owner,epoch,selected.get(index),false,true,()->prepareReplacementNext(selected,index+1,owner,epoch));
    }
    private boolean selectable(Sku sku){return errorRow(sku)||eligible(sku)||(sku!=null&&replaceable(sku)&&(!rowDetails.getOrDefault(sku.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement()||sku.status()==Status.ERROR)&&!workflow.isRunning(shop,sku));}
    private boolean errorRow(Sku row){return row!=null&&(view(row).status().equals("source.errors")||isError(row.status()));}
    private void openErrorRepair(boolean all){
        var keys=selectedRows().stream().map(row->new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()))).collect(java.util.stream.Collectors.toSet());
        CatalogErrorRepairDialog.open((StackPane)productTable.getParent(),shop,keys,all,this::scheduleReload);
    }

    private void prepareNext(List<Sku> selected,int index,Shop owner,long epoch) {
        if(!isCurrent(owner,epoch))return;
        if(index==selected.size()) {preparing=false;updateActionState();reload();return;}
        Sku sku=selected.get(index);
        summaryLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),index,selected.size())+" · "+sku.vendorCode()+" · "+sku.wbSize());
        prepareCard(owner,epoch,sku,sku.needsGtinReview(),()->prepareNext(selected,index+1,owner,epoch));
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
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(tr("znack.registration.config"));
        dialog.setHeaderText(tr("znack.registration.config_header"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ComboBox<DocumentType> documentType = new ComboBox<>();
        documentType.getItems().setAll(DocumentType.values());
        documentType.setValue(DocumentType.from(current.documentType()));
        TextField documentNumber = new TextField(value(current.documentNumber()));
        TextField documentDate = new TextField(value(current.documentDate()));
        documentDate.setPromptText("dd.MM.yyyy");

        grid.addRow(0, new Label(tr("znack.registration.document_type") + " *"), documentType);
        grid.addRow(1, new Label(tr("znack.registration.document_number") + " *"), documentNumber);
        grid.addRow(2, new Label(tr("znack.registration.document_date") + " *"), documentDate);
        Label note = new Label(tr("znack.registration.config_note"));
        note.setWrapText(true);
        note.setMaxWidth(560);
        grid.add(note, 0, 3, 2, 1);
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(220),
                new javafx.scene.layout.ColumnConstraints(360));
        dialog.getDialogPane().setContent(grid);

        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                if (documentNumber.getText().isBlank() || documentDate.getText().isBlank()) {
                    throw new IllegalArgumentException(tr("znack.registration.config_required"));
                }
                LocalDate parsed = LocalDate.parse(normalizedDocumentDate(documentDate.getText()));
                documentDate.setText(parsed.format(RU_DATE));
            } catch (IllegalArgumentException error) {
                event.consume();
                showWarning(error.getMessage());
            }
        });
        dialog.setResultConverter(button -> button == save);
        if (dialog.showAndWait().orElse(false)) {
            if(!isCurrent(owner,epoch))return;
            DocumentType type = documentType.getValue();
            var revised=current.withDefaultGoodsDocument(type.settingsCode,documentNumber.getText().trim(),documentDate.getText().trim());
            loading=true;setLoading(true);
            Task<Boolean> task=new Task<>() {@Override protected Boolean call(){
                var target=new ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));
                target.saveSettings(target.getSettings().withDefaultGoodsDocument(type.settingsCode,revised.documentNumber(),revised.documentDate()));return true;
            }};
            task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showInfo(tr("znack.registration.config_saved"));}});
            java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};
            task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
        }
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
        if(loading||preparing)return;reload();
    }
    private void reload() {
        if(preparing)return; // Never discard the frozen batch while preflight workers are still running.
        debounce.stop();++loadSequence;frozenSelection=List.of();rowDetails.clear();filteredCount=0;
        if(shop==null) {
            loading=false;productTable.getItems().clear();summary=null;setLoading(false);updateEmpty();updateCounts();return;
        }
        readiness=null;cardIndex=null;productTable.getItems().clear();readinessLabel.setText(tr("znack.registration.sources_checking"));
        loadPage(false);
    }

    @FXML private void onLoadMore() {
        if(shop==null||loading||preparing||productTable.getItems().size()>=filteredCount)return;
        loadPage(true);
    }

    private void loadPage(boolean append) {
        long sequence=loadSequence;Shop owner=shop;long epoch=shopEpoch;
        SearchCriteria criteria=criteria(append?productTable.getItems().size():0);
        loading=true;setLoading(true);
        Task<LoadedRegistration> task=new Task<>() {
            @Override protected LoadedRegistration call(){
                var ready=sources.check(owner);
                if(!ready.ready())return new LoadedRegistration(ready,null,null);
                var index=new RegistrationCardIndex(owner);
                var page=new ZnackRegistrationQueryRepository(ready.run(),index).page(criteria);
                var current=sources.require(owner);
                if(!current.run().equals(ready.run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
                return new LoadedRegistration(ready,index,page);
            }
        };
        task.setOnSucceeded(event->{
            if(sequence!=loadSequence||!isCurrent(owner,epoch))return;
            var result=task.getValue();loading=false;readiness=result.readiness();cardIndex=result.index();
            readinessLabel.setText(readiness.ready()?tr("znack.registration.sources_ready")+" · "+readiness.completed():tr("znack.registration.sources_required")+" · "+readiness.reason());
            if(!readiness.ready()){productTable.getItems().clear();summary=null;filteredCount=0;setLoading(false);updateEmpty();return;}
            var page=result.page();summary=page.summary();filteredCount=page.filtered();rowDetails.putAll(page.details());
            selectingProgrammatically=true;
            try {
                if(append) {
                    var existing=productTable.getItems().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());
                    productTable.getItems().addAll(page.rows().stream().filter(row->!existing.contains(row.chrtId())).toList());
                } else productTable.getItems().setAll(page.rows());
            } finally {selectingProgrammatically=false;}
            setLoading(false);updateEmpty();updateCounts();
        });
        java.util.function.Consumer<Throwable> failure=error->{
            if(sequence!=loadSequence||!isCurrent(owner,epoch))return;
            loading=false;readiness=null;cardIndex=null;productTable.getItems().clear();readinessLabel.setText(tr("znack.registration.sources_required")+" · "+SyncFailure.of(error,0).code());setLoading(false);showError(error);
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void createCard(Sku sku) {
        if(shop==null || !ready() || preparing || loading || sku==null || view(sku).blocked() || view(sku).published()&&!groupReplacement(sku))return;
        if(groupReplacement(sku)&&!confirm(tr("znack.registration.confirm_replacement")))return;
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
        prepareCard(owner,epoch,sku,fresh,()->{preparing=false;updateActionState();reload();});
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
                return new RegistrationInputs(productInputs.sku(),characteristics,scope,documents,service,preflight,attempt);
                }catch(Exception error){if(error instanceof RegistrationInputException input)attempt.blocked(input.code);else attempt.failed(error);throw error;}
                }
            }
        };
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;
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
            ZnackModels.GoodsDocument document=chooseDocument(inputs.documents());
            if(document==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_DOCUMENT_SELECTION_CANCELLED");completed.run();return;}
            Category category=chooseCategory(inputs.sku(),inputs.preflight().categories());
            if(category==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_CATEGORY_UNRESOLVED");completed.run();return;}
            buildAutomaticDraft(owner,epoch,sku,fresh,replacement,inputs,document,category,completed);
        });
        java.util.function.Consumer<Throwable> failure=error->{
            if(!isCurrent(owner,epoch))return;setLoading(false);
            if(error instanceof RegistrationInputException)showWarning(error.getMessage());else showError(error);completed.run();
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private ZnackModels.GoodsDocument chooseDocument(ZnackRegistrationDocuments.Selection selection) {
        if(selection.automatic()!=null&&!selection.requiresReview())return selection.automatic();
        var choicesKey=List.copyOf(selection.candidates());
        if(preparing&&reviewedDocuments.containsKey(choicesKey))return reviewedDocuments.get(choicesKey);
        Dialog<ZnackModels.GoodsDocument> dialog=new Dialog<>();dialog.setTitle(tr("znack.registration.documents"));
        dialog.setHeaderText(tr("znack.registration.perproduct_review"));
        ComboBox<ZnackModels.GoodsDocument> choices=new ComboBox<>();choices.getItems().setAll(selection.candidates());
        choices.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ZnackModels.GoodsDocument document){return document==null?"":document.type()+" · "+document.number()+" · "+document.date();}
            public ZnackModels.GoodsDocument fromString(String ignored){throw new UnsupportedOperationException();}
        });
        choices.getSelectionModel().selectFirst();choices.setPrefWidth(620);dialog.getDialogPane().setContent(choices);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK,ButtonType.CANCEL);
        dialog.setResultConverter(button->button==ButtonType.OK?choices.getValue():null);
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
                try(var lane=com.vncode.app.shared.MarketplaceRequestBudget.lane(true)){
                inputs.scope().assertCurrent(owner);
                var required=inputs.service().requiredAttributes(category.id(),inputs.preflight().token());
                inputs.scope().assertCurrent(owner);
                var mapped=new ZnackWbAttributeMapper().map(inputs.sku(),inputs.characteristics(),required,
                        inputs.preflight().tnved(),inputs.preflight().categoryTnved(),document);
                var draft=new Draft(inputs.preflight().tnved(),inputs.preflight().categoryTnved(),category.id(),
                        mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes()).withProductCategory(inputs.sku().subjectName());
                return new AutomaticDraft(draft,required);
                }catch(Exception error){inputs.attempt().failed(error);throw error;}
            }
        };
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;setLoading(false);var result=task.getValue();
            var draft=RegistrationDraftCompletionDialog.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),result.draft(),result.required(),inputs.sku().wbSize());
            if(draft==null||!isCurrent(owner,epoch)){recordBlocked(inputs.attempt(),"ZNACK_REQUIRED_ATTRIBUTES_MISSING");completed.run();return;}
            startWorkflow(owner,epoch,sku,draft,fresh,replacement,inputs.attempt(),completed);
        });
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){setLoading(false);showError(error);completed.run();}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void startWorkflow(Shop owner,long epoch,Sku sku,Draft draft,boolean fresh,boolean replacement,RegistrationAttemptReports.Attempt attempt,Runnable completed) {
        java.util.function.BiConsumer<Status,String> listener=(status,detail)->{
            try{attempt.observe(status,detail);}catch(RuntimeException reportError){
                Platform.runLater(()->{if(isCurrent(owner,epoch))summaryLabel.setText("SYNC_STORAGE · "+tr("znack.registration.bulk_review"));});
            }
            Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;
            scheduleReload();
            if (status == Status.ERROR || status == Status.GTIN_REVIEW_REQUIRED
                    || status == Status.FEED_REVIEW_REQUIRED || status == Status.PRE_SUBMIT_ERROR
                    || status == Status.PREFLIGHT_ERROR) {
                if(bulkCards.remove(sku.chrtId()))summaryLabel.setText(tr("znack.registration.bulk_review"));else showWorkflowError(detail);
            } else if (status == Status.PUBLISHED && !detail.isBlank() && !bulkCards.remove(sku.chrtId())) {
                showInfo(tr("znack.registration.completed") + " " + detail);
            }
        });};
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

    private void recordBlocked(RegistrationAttemptReports.Attempt attempt,String code) {
        Task<Void> task=new Task<>(){protected Void call(){attempt.blocked(code);return null;}};
        task.setOnFailed(e->showError(task.getException()));AppTaskExecutor.execute(task,ZnackCardRegistrationController::showError);
    }

    private void replaceCard(Sku sku) {
        if(shop==null||!ready()||loading||preparing||sku==null||!replaceable(sku))return;
        if(!confirm(tr(sku.status()==Status.ERROR?"znack.registration.confirm_rejected_replacement":"znack.registration.confirm_replacement")))return;
        Shop owner=shop;long epoch=shopEpoch;preparing=true;registrationReports=new RegistrationAttemptReports(owner);updateActionState();
        prepareCard(owner,epoch,sku,false,true,()->{preparing=false;updateActionState();reload();});
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
            {setAlignment(Pos.CENTER);check.setOnAction(event->{var row=getItem();if(row==null)return;frozenSelection=List.of();
                if(check.isSelected())productTable.getSelectionModel().select(row);else productTable.getSelectionModel().clearSelection(getIndex());updateActionState();event.consume();});
             check.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);}
            protected void updateItem(Sku row,boolean empty){super.updateItem(row,empty);setGraphic(empty||row==null?null:check);
                if(!empty&&row!=null){check.setSelected(selectedRows().stream().anyMatch(s->s.chrtId()==row.chrtId()));check.setDisable(preparing||loading||!selectable(row));}}
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
        barcodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(barcodes(cell.getValue())));
        RegistrationTextCells.wrap(detailColumn);
        detailColumn.setCellValueFactory(cell->new ReadOnlyStringWrapper(reason(view(cell.getValue()).detail())
                +(value(cell.getValue().errorMessage()).isBlank()?"":" · "+RegistrationDiagnostics.reason(cell.getValue().errorMessage()))));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(registrationStatus(cell.getValue())));
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
            private final MenuButton publishedActions=new MenuButton();
            private final MenuItem newCard=new MenuItem(),switchCard=new MenuItem(),retryOriginal=new MenuItem();
            { button.getStyleClass().add("btn-primary");button.setOnAction(event->createCard(getItem()));
              publishedActions.getStyleClass().add("btn-secondary");publishedActions.getItems().setAll(newCard,switchCard,retryOriginal);retryOriginal.setOnAction(event->createCard(getItem()));
              newCard.setOnAction(event->replaceCard(getItem()));switchCard.setOnAction(event->completeReplacement(getItem())); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
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
                if(item.canRegisterNew()&&!groupReplacement(item)) {
                    publishedActions.setText(tr("znack.registration.action"));newCard.setText(tr("znack.registration.create_new_card"));
                    switchCard.setText(tr("znack.registration.switch_card"));
                    boolean pending=rowDetails.getOrDefault(item.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement();
                    retryOriginal.setText(tr("znack.registration.retry_existing_gtin"));retryOriginal.setVisible(item.status()==Status.ERROR);retryOriginal.setDisable(preparing||loading||!eligible(item));
                    newCard.setDisable(!ready()||preparing||loading||pending&&item.status()!=Status.ERROR||!replaceable(item)||workflow.isRunning(shop,item));
                    switchCard.setDisable(!ready()||!view(item).published()||preparing||loading||!pending||workflow.isRunning(shop,item));
                    setAlignment(Pos.CENTER);setGraphic(publishedActions);return;
                }
                button.setText(groupReplacement(item)?tr("znack.registration.create_new_card"):view(item).exists()&&item.status()==Status.NOT_CREATED?tr("znack.registration.card_exists"):item.canResume()?tr("znack.registration.resume")
                        : item.needsGtinReview()?tr("znack.registration.new_gtin")
                        : item.status()==Status.ERROR || item.status()==Status.PRE_SUBMIT_ERROR || item.status()==Status.PREFLIGHT_ERROR
                        ?tr("znack.registration.retry"):tr("znack.registration.create"));
                button.setDisable(!ready() || view(item).blocked() || view(item).published()&&!groupReplacement(item) || preparing || loading || (!eligible(item)&&!item.canResume()) || workflow.isRunning(shop,item));
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
                setTooltip(empty || item == null || item.errorMessage() == null || item.errorMessage().isBlank()
                        ? null : new Tooltip(item.errorMessage()));
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
    private boolean eligible(Sku sku) {
        return ready() && shop!=null && sku!=null && !view(sku).blocked() && (!view(sku).published()||groupReplacement(sku)) && !workflow.isRunning(shop,sku)
                && (sku.canRegister() || sku.needsGtinReview()||groupReplacement(sku));
    }
    private void updateActionState() {
        boolean unavailable=shop==null||preparing||loading;
        boolean disabled=unavailable||!ready();
        allFilteredCheckBox.setDisable(disabled);reportLink.setDisable(shop==null);
        autoSignCheckBox.setDisable(disabled);selectAllButton.setDisable(disabled);registerSelectedButton.setText(tr(statusFilter.getValue()==StatusFilter.ERROR||selectedRows().stream().anyMatch(row->view(row).status().equals("source.errors")||row.status()==Status.ERROR&&row.hasGtin())?"repair.report":"znack.registration.register_short"));registerSelectedButton.setDisable(disabled||statusFilter.getValue()!=StatusFilter.ERROR&&selectedRows().stream().noneMatch(row->eligible(row)||errorRow(row)));newCardsSelectedButton.setDisable(disabled||selectedRows().stream().noneMatch(this::replaceable));
        searchField.setDisable(preparing);categoryMenuButton.setDisable(preparing);statusFilter.setDisable(preparing);clearFiltersButton.setDisable(preparing);
        configButton.setDisable(unavailable);signApprovedButton.setDisable(disabled);updateWbButton.setDisable(disabled);syncZnackButton.setDisable(unavailable);reportButton.setDisable(shop==null);
        loadMoreButton.setDisable(disabled||productTable.getItems().size()>=filteredCount);
        clearSelectionButton.setDisable(disabled||selectedRows().isEmpty());updateCounts();productTable.refresh();
    }
    private void updateCounts() {
        if(paginationLabel==null)return;
        paginationLabel.setText(java.text.MessageFormat.format(tr("znack.registration.page_count"),productTable.getItems().size(),filteredCount));
        selectionLabel.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selectedRows().size()));
        allFilteredCheckBox.setSelected(filteredCount>0&&selectedRows().size()==filteredCount);
        boolean more=productTable.getItems().size()<filteredCount;loadMoreButton.setVisible(false);loadMoreButton.setManaged(false);
        if(summary==null){summaryLabel.setText(tr("znack.registration.summary_empty"));return;}
        String pipeline=java.text.MessageFormat.format(tr("znack.registration.pipeline_summary"),summary.total(),summary.published(),summary.readyToSign(),summary.waiting(),summary.errors());
        String catalog=summary.syncedAt()==null?tr("znack.registration.catalog_unknown"):
                java.text.MessageFormat.format(tr("znack.registration.catalog_summary"),summary.catalog(),summary.catalogPublished(),summary.catalog()-summary.catalogPublished(),summary.catalogUnlinked());
        summaryLabel.setText(pipeline+"\n"+catalog);
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
        var observed=view(row);
        if(observed.published()&&!com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(row.barcodes(),observed.gtin()))return tr("znack.registration.awaiting_wb_gtin");
        return tr("znack.registration."+observed.status());
    }
    private void updateOneGtin(Sku row){
        if(row==null||shop==null||!ready()||preparing||loading)return;
        var key=new com.vncode.app.features.products.ProductKey(shop.getId(),shop.getMarketplace(),Long.toString(row.nmId()),Long.toString(row.chrtId()));
        GtinBackgroundStatus.start((StackPane)productTable.getParent(),shop,java.util.Set.of(key),this::scheduleReload);
    }
    private static boolean isError(Status status){return java.util.Set.of(Status.ERROR,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.GTIN_REVIEW_REQUIRED,Status.FEED_REVIEW_REQUIRED).contains(status);}
    private static boolean isBusy(Status status) {
        return status != Status.NOT_CREATED && status != Status.ERROR
                && status != Status.PUBLISHED;
    }
    private String barcodes(Sku sku) {
        String source = String.join(", ", sku.barcodes());
        String gtin=view(sku).gtin();return gtin.isBlank()?source:source+(source.isBlank()?"":"\n")+"GTIN: "+gtin;
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
    private void showRegistrationReport(Sku sku){RegistrationDiagnostics.show(sku.vendorCode(),sku.gtin(),sku.feedId(),registrationStatus(sku),reason(view(sku).detail())+"\n"+reason(value(sku.errorMessage())));}
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

    private record RegistrationInputs(Sku sku,List<WbCharacteristic> characteristics,ZnackRegistrationScope scope,
            ZnackRegistrationDocuments.Selection documents,ZnackNationalCatalogService service,ZnackNationalCatalogService.Preflight preflight,RegistrationAttemptReports.Attempt attempt) {
        @Override public String toString(){return "RegistrationInputs[redacted]";}
    }
    private record AutomaticDraft(Draft draft, List<Attribute> required) { }
}
