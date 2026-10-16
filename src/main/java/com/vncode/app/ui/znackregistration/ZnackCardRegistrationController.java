package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.fbo.FboProductImageService;
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
    private Shop shop;
    private boolean loading;
    private boolean preparing;
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
        shopEpoch++;workflowRefresh.stop();bulkCards.clear();
        boolean ozon=shop!=null&&shop.getMarketplace()==Marketplace.OZON;
        wbRegistrationContent.setVisible(!ozon);wbRegistrationContent.setManaged(!ozon);
        ozonRegistrationHost.setVisible(ozon);ozonRegistrationHost.setManaged(ozon);
        for(var previous:ozonRegistrationHost.getChildren())if(previous instanceof OzonRegistrationPane pane)pane.dispose();
        ozonRegistrationHost.getChildren().clear();
        if(ozon&&ozonRegistrationFactory!=null)ozonRegistrationHost.getChildren().setAll(ozonRegistrationFactory.apply(shop));
        preparing=false;
        this.shop = shop != null && shop.getMarketplace() == Marketplace.WILDBERRIES ? shop : null;
        productTable.getItems().clear();
        frozenSelection=List.of();rowDetails.clear();summary=null;filteredCount=0;
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
        loadMoreButton.setText(i18n.tr("znack.registration.load_more"));
        clearSelectionButton.setText(i18n.tr("znack.registration.clear_selection"));
        genderColumn.setText(i18n.tr("znack.registration.gender"));
        clearFiltersButton.setText(i18n.tr("znack.registration.clear"));
        selectAllButton.setText(i18n.tr("znack.registration.select_all"));
        registerSelectedButton.setText(i18n.tr("znack.registration.register_selected"));newCardsSelectedButton.setText(i18n.tr("znack.registration.batch_new_cards"));
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
        if(shop==null || preparing || loading)return;
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
        Task<List<Sku>> task=new Task<>() {@Override protected List<Sku> call(){return queries.allSelectable(criteria);}};
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

    @FXML private void onClearSelection() {
        frozenSelection=List.of();productTable.getSelectionModel().clearSelection();updateActionState();
    }

    private List<Sku> selectedRows() {
        return frozenSelection.isEmpty()?List.copyOf(productTable.getSelectionModel().getSelectedItems()):frozenSelection;
    }

    @FXML private void onSignApproved() {
        if(shop==null||preparing||loading)return;
        var selected=selectedRows().stream().filter(Sku::canSignApproved).toList();
        if(!selected.isEmpty()){confirmAndSign(shop,shopEpoch,selected);return;}
        Shop owner=shop;long epoch=shopEpoch;long sequence=loadSequence;
        SearchCriteria criteria=criteria(0);loading=true;setLoading(true);
        Task<List<Sku>> task=new Task<>(){@Override protected List<Sku> call(){return queries.allSignable(criteria);}};
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
        if(!isCurrent(owner,epoch))return;
        Task<Boolean> task=new Task<>() {@Override protected Boolean call()throws Exception{return action.call();}};
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){if(!task.getValue())showInfo(tr("znack.registration.already_running"));completed.run();scheduleReload();}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){showError(error);completed.run();}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    @FXML private void onUpdateWb(){
        if(shop==null||loading||preparing)return;
        ZnackGtinReviewDialog.show(productTable.getScene()==null?null:productTable.getScene().getWindow(),shop);
    }

    @FXML private void onSyncZnack() {
        if(shop==null||preparing||loading)return;
        Shop owner=shop;long epoch=shopEpoch;loading=true;setLoading(true);
        Task<Boolean> task=new Task<>() {
            @Override protected Boolean call() throws Exception {
                new com.vncode.app.features.sync.ZnackCatalogSyncService().refresh(owner,true,p->{});
                return true;
            }
        };
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;reload();}});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));task.setOnCancelled(event->failure.accept(new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT")));AppTaskExecutor.executeCatalog(task,failure);
    }

    @FXML private void onRegisterSelected() {
        if(shop==null || preparing) return;
        List<Sku> selected=selectedRows().stream().filter(this::eligible).toList();
        if(selected.isEmpty())return;
        long fresh=selected.stream().filter(Sku::needsGtinReview).count();
        String message=java.text.MessageFormat.format(tr("znack.registration.confirm_selected"),selected.size());
        if(fresh>0)message+="\n\n"+java.text.MessageFormat.format(tr("znack.registration.confirm_new_gtin"),fresh);
        if(!confirm(message))return;
        Shop owner=shop;long epoch=shopEpoch;
        if(selected.size()>1)selected.forEach(row->bulkCards.add(row.chrtId()));
        preparing=true;updateActionState();
        prepareNext(selected,0,owner,epoch);
    }

    @FXML private void onNewCardsSelected() {
        if(shop==null||loading||preparing)return;var ids=selectedRows().stream().map(Sku::chrtId).collect(java.util.stream.Collectors.toSet());if(ids.isEmpty())return;
        Shop owner=shop;long epoch=shopEpoch;var criteria=criteria(0);loading=true;setLoading(true);
        Task<List<Sku>> task=new Task<>(){protected List<Sku> call(){return queries.allReplaceable(criteria).stream().filter(s->ids.contains(s.chrtId())).toList();}};
        task.setOnSucceeded(e->{if(!isCurrent(owner,epoch))return;loading=false;setLoading(false);var selected=task.getValue();if(selected.isEmpty())return;
            if(selected.size()>1000){showInfo(tr("znack.registration.batch_limit"));return;}
            if(!confirm(java.text.MessageFormat.format(tr("znack.registration.batch_confirm"),selected.size(),ids.size())))return;
            selected.forEach(s->bulkCards.add(s.chrtId()));preparing=true;updateActionState();prepareReplacementNext(selected,0,owner,epoch);
        });
        java.util.function.Consumer<Throwable> failed=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void prepareReplacementNext(List<Sku> selected,int index,Shop owner,long epoch) {
        if(!isCurrent(owner,epoch))return;
        if(index>=selected.size()){preparing=false;updateActionState();reload();return;}
        summaryLabel.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),index,selected.size()));
        prepareCard(owner,epoch,selected.get(index),false,true,()->prepareReplacementNext(selected,index+1,owner,epoch));
    }
    private boolean selectable(Sku sku){return eligible(sku)||(sku!=null&&sku.canRegisterNew()&&!rowDetails.getOrDefault(sku.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement()&&!workflow.isRunning(shop,sku));}

    private void prepareNext(List<Sku> selected,int index,Shop owner,long epoch) {
        if(!isCurrent(owner,epoch))return;
        if(index==selected.size()) {preparing=false;updateActionState();reload();return;}
        Sku sku=selected.get(index);
        prepareCard(owner,epoch,sku,sku.needsGtinReview(),()->prepareNext(selected,index+1,owner,epoch));
    }

    @FXML
    private void onConfig() {
        if(shop==null||loading||preparing)return;
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

    public void refreshLocal(){reload();}
    private void reload() {
        debounce.stop();++loadSequence;frozenSelection=List.of();rowDetails.clear();filteredCount=0;
        if(shop==null) {
            loading=false;productTable.getItems().clear();summary=null;setLoading(false);updateEmpty();updateCounts();return;
        }
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
        Task<ZnackRegistrationQueryRepository.Page> task=new Task<>() {
            @Override protected ZnackRegistrationQueryRepository.Page call(){return queries.page(criteria);}
        };
        task.setOnSucceeded(event->{
            if(sequence!=loadSequence||!isCurrent(owner,epoch))return;
            var page=task.getValue();loading=false;summary=page.summary();filteredCount=page.filtered();rowDetails.putAll(page.details());
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
            loading=false;setLoading(false);showError(error);
        };
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void createCard(Sku sku) {
        if(shop==null || preparing || loading || sku==null)return;
        if(sku.canResume()) {
            if(!confirm(tr("znack.registration.confirm_resume")))return;
            Shop owner=shop;long epoch=shopEpoch;
            submitWorkflow(owner,epoch,()->workflow.resume(owner,sku,(status,detail)->Platform.runLater(()->{
                if(!isCurrent(owner,epoch))return;if(status==Status.ERROR)showWorkflowError(detail);reload();
            })),()->{});return;
        }
        if(!eligible(sku))return;
        boolean fresh=sku.needsGtinReview();
        if(fresh && !confirm(java.text.MessageFormat.format(tr("znack.registration.confirm_new_gtin"),1)))return;
        Shop owner=shop;long epoch=shopEpoch;
        preparing=true;updateActionState();
        prepareCard(owner,epoch,sku,fresh,()->{preparing=false;updateActionState();reload();});
    }

    private void prepareCard(Shop owner,long epoch,Sku sku,boolean fresh,Runnable completed) {
        prepareCard(owner,epoch,sku,fresh,false,completed);
    }

    private void prepareCard(Shop owner,long epoch,Sku sku,boolean fresh,boolean replacement,Runnable completed) {
        if(!isCurrent(owner,epoch))return;
        if(!(replacement?sku.canRegisterNew():eligible(sku))){completed.run();return;}
        setLoading(true);
        Task<RegistrationInputs> task=new Task<>() {
            @Override protected RegistrationInputs call()throws Exception {
                List<WbCharacteristic> characteristics=repository.characteristics(owner.getId(),sku.nmId());
                String tnved=ZnackWbAttributeMapper.findTnved(characteristics);
                if(tnved.isBlank())throw new RegistrationInputException(java.text.MessageFormat.format(tr("znack.registration.missing_tnved"),
                        sku.vendorCode(),first(sku.sourceBarcode(),Long.toString(sku.chrtId())),sku.subjectName()));
                ZnackModels.Settings current=settings(owner);
                var key=new com.vncode.app.features.products.ProductKey(owner.getId(),owner.getMarketplace(),Long.toString(sku.nmId()),Long.toString(sku.chrtId()));
                var snapshot=new com.vncode.app.features.products.ProductRepository().localSnapshot(key).orElse(null);
                if(snapshot==null)snapshot=new com.vncode.app.integration.wb.WbProductSnapshotReader().read(owner,key);
                var documents=ZnackRegistrationDocuments.select(snapshot,current,LocalDate.now());
                if(documents.candidates().isEmpty())throw new RegistrationInputException(tr("znack.registration.missing_product_document"));
                var scope=ZnackRegistrationScope.fromSettings(current);
                ZnackSignatureProvider signer=scope.verifyingSigner(owner,signer(current));
                ZnackApiClient api=com.vncode.app.integration.znack.CatalogConnectionService.apiForShop(owner,current);ZnackAuthService auth=new ZnackAuthService(api,signer);
                var service=new ZnackNationalCatalogService(api,auth,signer,current);
                scope.assertCurrent(owner);var preflight=service.preflight(tnved);scope.assertCurrent(owner);
                return new RegistrationInputs(characteristics,scope,documents,service,preflight);
            }
        };
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;
            setLoading(false);var inputs=task.getValue();
            ZnackModels.GoodsDocument document=chooseDocument(inputs.documents());
            if(document==null||!isCurrent(owner,epoch)){completed.run();return;}
            Category category=chooseCategory(inputs.preflight().categories());
            if(category==null||!isCurrent(owner,epoch)){completed.run();return;}
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
        return dialog.showAndWait().orElse(null);
    }

    private Category chooseCategory(List<Category> categories) {
        if(categories==null||categories.isEmpty()){showWarning(tr("znack.registration.category_missing"));return null;}
        if(categories.size()==1)return categories.getFirst();
        ChoiceDialog<Category> dialog=new ChoiceDialog<>(categories.getFirst(),categories);
        dialog.setTitle(tr("znack.registration.title"));dialog.setHeaderText(tr("znack.registration.choose_category"));
        return dialog.showAndWait().orElse(null);
    }

    private void buildAutomaticDraft(Shop owner,long epoch,Sku sku,boolean fresh,boolean replacement,
            RegistrationInputs inputs,ZnackModels.GoodsDocument document,Category category,Runnable completed) {
        setLoading(true);
        Task<AutomaticDraft> task=new Task<>() {
            @Override protected AutomaticDraft call()throws Exception {
                inputs.scope().assertCurrent(owner);
                var required=inputs.service().requiredAttributes(category.id(),inputs.preflight().token());
                inputs.scope().assertCurrent(owner);
                var mapped=new ZnackWbAttributeMapper().map(sku,inputs.characteristics(),required,
                        inputs.preflight().tnved(),inputs.preflight().categoryTnved(),document);
                var draft=new Draft(inputs.preflight().tnved(),inputs.preflight().categoryTnved(),category.id(),
                        mapped.goodName(),mapped.brand(),mapped.attributes(),mapped.attributeTypes());
                return new AutomaticDraft(draft,mapped.missingFields());
            }
        };
        task.setOnSucceeded(event->{
            if(!isCurrent(owner,epoch))return;setLoading(false);var result=task.getValue();
            if(!result.missingFields().isEmpty()) {
                showWarning(java.text.MessageFormat.format(tr("znack.registration.missing_wb_fields"),sku.vendorCode(),String.join("\n• ",result.missingFields())));
                completed.run();return;
            }
            startWorkflow(owner,epoch,sku,result.draft(),fresh,replacement,completed);
        });
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){setLoading(false);showError(error);completed.run();}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

    private void startWorkflow(Shop owner,long epoch,Sku sku,Draft draft,boolean fresh,boolean replacement,Runnable completed) {
        java.util.function.BiConsumer<Status,String> listener=(status,detail)->Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;
            scheduleReload();
            if (status == Status.ERROR || status == Status.GTIN_REVIEW_REQUIRED
                    || status == Status.FEED_REVIEW_REQUIRED || status == Status.PRE_SUBMIT_ERROR
                    || status == Status.PREFLIGHT_ERROR) {
                if(bulkCards.remove(sku.chrtId()))summaryLabel.setText(tr("znack.registration.bulk_review"));else showWorkflowError(detail);
            } else if (status == Status.PUBLISHED && !detail.isBlank() && !bulkCards.remove(sku.chrtId())) {
                showInfo(tr("znack.registration.completed") + " " + detail);
            }
        });
        submitWorkflow(owner,epoch,()->replacement?workflow.registerNew(owner,sku,draft,true,listener)
                :fresh?workflow.requestNewGtin(owner,sku,draft,true,listener):workflow.start(owner,sku,draft,listener),completed);
    }

    private void replaceCard(Sku sku) {
        if(shop==null||loading||preparing||sku==null||!sku.canRegisterNew())return;
        if(!confirm(tr("znack.registration.confirm_replacement")))return;
        Shop owner=shop;long epoch=shopEpoch;preparing=true;updateActionState();
        prepareCard(owner,epoch,sku,false,true,()->{preparing=false;updateActionState();reload();});
    }

    private void completeReplacement(Sku sku) {
        if(shop==null||loading||preparing||sku==null||sku.status()!=Status.PUBLISHED)return;
        if(!confirm(tr("znack.registration.confirm_switch")))return;
        Shop owner=shop;long epoch=shopEpoch;
        submitWorkflow(owner,epoch,()->workflow.completeReplacement(owner,sku,true,(status,detail)->Platform.runLater(()->{
            if(!isCurrent(owner,epoch))return;if(detail!=null&&!detail.isBlank())showWorkflowError(detail);reload();
        })),()->{});
    }

    private void readStatus(Sku sku) {
        if(shop==null||loading||preparing||sku==null)return;
        Shop owner=shop;long epoch=shopEpoch;loading=true;setLoading(true);
        Task<ZnackNationalCatalogService.FeedProgress> task=new Task<>(){@Override protected ZnackNationalCatalogService.FeedProgress call()throws Exception{return workflow.readOnlyProgress(owner,sku);}};
        task.setOnSucceeded(event->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);var result=task.getValue();
            Status status=result.signed()?Status.PUBLISHED:result.readyToSign()?Status.READY_TO_SIGN:result.failed()?Status.ERROR:Status.PROCESSING;
            showInfo(statusText(status)+(result.errorMessage().isBlank()?"":"\n"+result.errorMessage()));
        }});
        java.util.function.Consumer<Throwable> failure=error->{if(isCurrent(owner,epoch)){loading=false;setLoading(false);showError(error);}};
        task.setOnFailed(event->failure.accept(task.getException()));AppTaskExecutor.execute(task,failure);
    }

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
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().needsGtinReview() && !workflow.isRunning(shop,cell.getValue())
                        ? statusText(Status.GTIN_REVIEW_REQUIRED)
                        : cell.getValue().needsFeedReview() && !workflow.isRunning(shop,cell.getValue())
                        ? statusText(Status.FEED_REVIEW_REQUIRED) : statusText(cell.getValue().status())));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty) setStyle("-fx-font-weight: 700;");
            }
        });
        actionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        actionColumn.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button();
            private final MenuButton publishedActions=new MenuButton();
            private final MenuItem newCard=new MenuItem(),switchCard=new MenuItem();
            { button.getStyleClass().add("btn-primary");button.setOnAction(event->createCard(getItem()));
              publishedActions.getStyleClass().add("btn-secondary");publishedActions.getItems().setAll(newCard,switchCard);
              newCard.setOnAction(event->replaceCard(getItem()));switchCard.setOnAction(event->completeReplacement(getItem())); }
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                if(item.status()==Status.PUBLISHED) {
                    publishedActions.setText(tr("znack.registration.action"));newCard.setText(tr("znack.registration.create_new_card"));
                    switchCard.setText(tr("znack.registration.switch_card"));
                    boolean pending=rowDetails.getOrDefault(item.chrtId(),new ZnackRegistrationQueryRepository.Details("","")).pendingReplacement();
                    newCard.setDisable(preparing||loading||pending||!item.canRegisterNew()||workflow.isRunning(shop,item));
                    switchCard.setDisable(preparing||loading||!pending||workflow.isRunning(shop,item));
                    setAlignment(Pos.CENTER);setGraphic(publishedActions);return;
                }
                button.setText(item.canResume()?tr("znack.registration.resume")
                        : item.needsGtinReview()?tr("znack.registration.new_gtin")
                        : item.status()==Status.ERROR || item.status()==Status.PRE_SUBMIT_ERROR || item.status()==Status.PREFLIGHT_ERROR
                        ?tr("znack.registration.retry"):tr("znack.registration.create"));
                button.setDisable(preparing || loading || (!eligible(item)&&!item.canResume()) || workflow.isRunning(shop,item));
                setAlignment(Pos.CENTER);
                setGraphic(button);
            }
        });
        productTable.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(Sku item, boolean empty) {
                super.updateItem(item, empty);
                if(!empty&&item!=null&&item.hasFeed()&&item.hasGtin()) {
                    var read=new MenuItem(tr("znack.registration.read_status"));read.setOnAction(event->readStatus(item));setContextMenu(new ContextMenu(read));
                } else setContextMenu(null);
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
        loadingLabel.setVisible(value); loadingLabel.setManaged(value); refreshButton.setDisable(value);
        updateActionState();
    }
    private boolean isCurrent(Shop owner,long epoch) {return shop==owner && shopEpoch==epoch;}
    private boolean eligible(Sku sku) {
        return shop!=null && sku!=null && !workflow.isRunning(shop,sku)
                && (sku.canRegister() || sku.needsGtinReview());
    }
    private void updateActionState() {
        boolean disabled=shop==null||preparing||loading;
        selectAllButton.setDisable(disabled);registerSelectedButton.setDisable(disabled||selectedRows().stream().noneMatch(this::eligible));newCardsSelectedButton.setDisable(disabled||selectedRows().stream().noneMatch(Sku::canRegisterNew));
        configButton.setDisable(disabled);signApprovedButton.setDisable(disabled);updateWbButton.setDisable(disabled);syncZnackButton.setDisable(disabled);
        loadMoreButton.setDisable(disabled||productTable.getItems().size()>=filteredCount);
        clearSelectionButton.setDisable(disabled||selectedRows().isEmpty());updateCounts();productTable.refresh();
    }
    private void updateCounts() {
        if(paginationLabel==null)return;
        paginationLabel.setText(java.text.MessageFormat.format(tr("znack.registration.page_count"),productTable.getItems().size(),filteredCount));
        selectionLabel.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selectedRows().size()));
        boolean more=productTable.getItems().size()<filteredCount;loadMoreButton.setVisible(more);loadMoreButton.setManaged(more);
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
    private static boolean isBusy(Status status) {
        return status != Status.NOT_CREATED && status != Status.ERROR
                && status != Status.PUBLISHED;
    }
    private static String barcodes(Sku sku) {
        String source = String.join(", ", sku.barcodes());
        return sku.gtin() == null || sku.gtin().isBlank() ? source : source + (source.isBlank() ? "" : "\n") + "GTIN: " + sku.gtin();
    }
    private static String statusText(Status status) { return tr("znack.registration.status." + status.name().toLowerCase(Locale.ROOT)); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String value(String value) { return value == null ? "" : value; }
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

    private static final class RegistrationInputException extends IllegalArgumentException {RegistrationInputException(String text){super(text);}}

    private record RegistrationInputs(List<WbCharacteristic> characteristics,ZnackRegistrationScope scope,
            ZnackRegistrationDocuments.Selection documents,ZnackNationalCatalogService service,ZnackNationalCatalogService.Preflight preflight) {
        @Override public String toString(){return "RegistrationInputs[redacted]";}
    }
    private record AutomaticDraft(Draft draft, List<String> missingFields) { }
}
