package com.vncode.app.ui.workspace;

import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.I18nService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import com.vncode.app.models.Shop;
import java.util.List;
import java.util.function.Consumer;

public class WorkspaceHeaderController {
    @FXML private javafx.scene.layout.HBox headerRoot;
    @FXML private javafx.scene.layout.FlowPane secondaryActions;
    @FXML private javafx.scene.control.MenuButton moreButton;
    private boolean compact,productSyncLoading;
    private String syncCaption="",jobsCaption="";
    private javafx.scene.control.MenuItem balanceThresholdItem;
    private final javafx.scene.control.CustomMenuItem secondaryMenu=new javafx.scene.control.CustomMenuItem();
    private final java.util.EnumMap<com.vncode.app.features.sync.SyncCoordinator.Stream,com.vncode.app.features.sync.SyncCoordinator.Health> fbsHealth=new java.util.EnumMap<>(com.vncode.app.features.sync.SyncCoordinator.Stream.class);

    private void responsiveHeader(){
        if(headerRoot==null)return;
        boolean next=headerRoot.getWidth()>0&&headerRoot.getWidth()<1320;
        if(next!=compact){
            compact=next;
            if(compact){headerRoot.getChildren().remove(secondaryActions);secondaryActions.setOrientation(javafx.geometry.Orientation.VERTICAL);secondaryActions.setPrefWrapLength(350);secondaryActions.setPrefWidth(240);secondaryMenu.setContent(secondaryActions);moreButton.getItems().setAll(secondaryMenu);}
            else{moreButton.hide();secondaryMenu.setContent(null);moreButton.getItems().clear();secondaryActions.setOrientation(javafx.geometry.Orientation.HORIZONTAL);secondaryActions.setPrefWrapLength(500);secondaryActions.setPrefWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);headerRoot.getChildren().add(headerRoot.getChildren().size()-1,secondaryActions);}
        }
        moreButton.setVisible(compact);moreButton.setManaged(compact);shopComboBox.setPrefWidth(compact?190:220);syncHealthLabel.setMaxWidth(compact?175:220);
        syncButton.setText(compact?"":syncCaption);jobsButton.setText(compact?"":jobsCaption);
        syncButton.setTooltip(new javafx.scene.control.Tooltip(syncCaption));syncButton.setAccessibleText(syncCaption);
        jobsButton.setTooltip(new javafx.scene.control.Tooltip(jobsCaption));jobsButton.setAccessibleText(jobsCaption);
        syncLoadingLabel.setVisible(productSyncLoading&&!compact);syncLoadingLabel.setManaged(productSyncLoading&&!compact);
    }

    @FXML
    private ComboBox<Shop> shopComboBox;

    @FXML
    private Button syncButton;

    @FXML
    private Button editShopButton;

    @FXML
    private Button deleteShopButton;

    @FXML
    private ProgressIndicator syncLoadingIndicator;

    @FXML
    private Label syncLoadingLabel;

    @FXML private Button newsButton,supportButton;
    @FXML private Button updateButton;
    private String updateVersion;
    private Runnable onUpdate;
    @FXML private void onUpdate(){if(onUpdate!=null)onUpdate.run();}
    public void setOnUpdate(Runnable callback){onUpdate=callback;}
    public void setAvailableUpdate(String version){
        updateVersion=version;boolean visible=version!=null&&!version.isBlank();
        updateButton.setVisible(visible);updateButton.setManaged(visible);
        updateButton.setText(I18nService.getInstance().tr("update.notification")+(visible?" "+version:""));
    }
    @FXML private Label newsBadge;
    @FXML private ComboBox<com.vncode.app.integration.znack.BalanceSnapshot> balanceAccount;
    @FXML private Label balanceLabel;
    @FXML private Button jobsButton;
    @FXML private Label syncHealthLabel;
    private com.vncode.app.features.sync.SyncCoordinator.Health syncHealth;
    public void setSyncHealth(com.vncode.app.features.sync.SyncCoordinator.Health health){
        var selected=shopComboBox.getValue();if(selected!=null&&selected.getId()!=health.shopId())return;
        if(health.stream().ordinal()>com.vncode.app.features.sync.SyncCoordinator.Stream.SUPPLY_CONTENT.ordinal())return;
        fbsHealth.put(health.stream(),health);if(health.stream()==com.vncode.app.features.sync.SyncCoordinator.Stream.FBS_QUEUE)syncHealth=health;renderSyncHealth();
    }
    private void renderSyncHealth(){
        if(syncHealthLabel==null)return;var tr=I18nService.getInstance();var now=java.time.Instant.now();
        var failure=fbsHealth.values().stream().filter(h->h.error()!=null&&!h.error().isBlank()).findFirst().orElse(null);
        var primary=failure!=null?failure:syncHealth;
        String state=primary==null?"sync.health.starting":failure!=null?"sync.health.delayed":primary.running()?"sync.health.running":primary.success()!=null&&(primary.complete()==null||primary.success().isAfter(primary.complete()))?"sync.health.partial":primary.complete()==null?"sync.health.starting":primary.next()!=null&&now.isAfter(primary.next().plusSeconds(30))?"sync.health.stale":"sync.health.live";
        long seconds=primary==null||primary.complete()==null?-1:Math.max(0,java.time.Duration.between(primary.complete(),now).getSeconds());
        syncHealthLabel.setText(tr.tr(state)+(seconds<0?"":" · "+seconds+" s"));
        var format=java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(java.time.ZoneId.of("Europe/Moscow"));
        String details=fbsHealth.values().stream().map(h->tr.tr("sync.stream."+h.stream())+" · "+tr.tr(h.running()?"sync.health.running":"sync.health.checked")+" · "+tr.tr("sync.health.last_complete")+": "+(h.complete()==null?"—":format.format(h.complete()))+" · "+tr.tr("sync.health.next")+": "+(h.next()==null?"—":format.format(h.next()))+(h.error()==null||h.error().isBlank()?"":" · "+h.error())).collect(java.util.stream.Collectors.joining("\n"));
        syncHealthLabel.setTooltip(new javafx.scene.control.Tooltip(details.isBlank()?tr.tr(state):details));syncHealthLabel.setAccessibleText(syncHealthLabel.getText()+" "+details);
        syncHealthLabel.getStyleClass().remove("text-danger");if(failure!=null)syncHealthLabel.getStyleClass().add("text-danger");
    }

    private final javafx.animation.Timeline statusMonitor=new javafx.animation.Timeline();
    private long balanceEpoch;private boolean disposed;private int balanceWarningRub=500;
    private Consumer<com.vncode.app.features.jobs.JobCenterRepository.Row> onJobOpen;
    public void setOnJobOpen(Consumer<com.vncode.app.features.jobs.JobCenterRepository.Row> callback){onJobOpen=callback;}
    @FXML private void onJobs(){JobCenterDialog.show(jobsButton.getScene()==null?null:jobsButton.getScene().getWindow(),row->{shopComboBox.getItems().stream().filter(s->s.getId()==row.shopId()).findFirst().ifPresent(s->shopComboBox.setValue(s));if(onJobOpen!=null)onJobOpen.accept(row);});}
    private Runnable onSupport;
    public void setOnSupport(Runnable callback){onSupport=callback;}
    @FXML private void onSupport(){if(onSupport!=null)onSupport.run();}
    private Runnable onNews;
    private Runnable onSync;
    private Runnable onEditShop;
    private Runnable onDeleteShop;
    private Consumer<Shop> onShopSelected;
    private Marketplace marketplace = Marketplace.WILDBERRIES;
    private boolean updatingShops;

    @FXML
    public void initialize() {
        secondaryMenu.setHideOnClick(false);headerRoot.widthProperty().addListener((o,a,b)->responsiveHeader());
        balanceAccount.setConverter(new javafx.util.StringConverter<>(){public String toString(com.vncode.app.integration.znack.BalanceSnapshot value){return value==null?I18nService.getInstance().tr("header.balance_choose"):value.scope().productGroup()+" · "+value.scope().accountId();}public com.vncode.app.integration.znack.BalanceSnapshot fromString(String text){return null;}});
        balanceAccount.valueProperty().addListener((o,a,b)->renderBalance());
        balanceThresholdItem=new javafx.scene.control.MenuItem();
        balanceThresholdItem.setOnAction(e->{var dialog=new javafx.scene.control.TextInputDialog(Integer.toString(balanceWarningRub));dialog.setTitle(I18nService.getInstance().tr("header.balance_threshold"));dialog.setHeaderText(I18nService.getInstance().tr("header.balance_threshold_hint"));dialog.showAndWait().ifPresent(text->{try{int number=Integer.parseInt(text.strip());if(number<0||number>100000)throw new IllegalArgumentException();com.vncode.app.shared.ConfigService.setConfigValue("cz_balance_warning_rub",Integer.toString(number));balanceWarningRub=number;renderBalance();}catch(RuntimeException invalid){com.vncode.app.shared.AlertService.showError(I18nService.getInstance().tr("header.balance_threshold_hint"));}});});
        balanceLabel.setContextMenu(new javafx.scene.control.ContextMenu(balanceThresholdItem));
        statusMonitor.getKeyFrames().setAll(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(10),e->{renderBalance();renderSyncHealth();jobsCaption=I18nService.getInstance().tr("jobs.title")+" · "+(com.vncode.app.shared.SellerMutationExecutor.activeCount()+com.vncode.app.shared.AppTaskExecutor.getRunningTaskCount())+" / "+(com.vncode.app.shared.SellerMutationExecutor.pendingCount()+com.vncode.app.shared.AppTaskExecutor.pendingCount());responsiveHeader();}));statusMonitor.setCycleCount(javafx.animation.Animation.INDEFINITE);
        shopComboBox.setConverter(new javafx.util.StringConverter<Shop>(){
            public String toString(Shop value){return value==null?"":"["+value.getMarketplace().badge()+"] "+value.getName();}
            public Shop fromString(String ignored){return null;}
        });
        shopComboBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Shop item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : "[" + item.getMarketplace().badge() + "] " + item.getName());
                if (lv != null) {
                    getStyleClass().remove("shop-combo-button-cell");
                }
            }
        });
        ListCell<Shop> buttonCell = shopComboBox.getCellFactory().call(null);
        buttonCell.getStyleClass().add("shop-combo-button-cell");
        shopComboBox.setButtonCell(buttonCell);
        shopComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (!updatingShops && newVal != null && onShopSelected != null) {
                syncHealth=null;fbsHealth.clear();renderSyncHealth();refreshBalance(newVal);
                onShopSelected.accept(newVal);
            }
        });
        applyTranslations();
    }

    @FXML
    private void onSync() {
        if (onSync != null) {
            onSync.run();
        }
    }

    @FXML
    private void onEditShop() {
        if (onEditShop != null) {
            onEditShop.run();
        }
    }

    @FXML
    private void onDeleteShop() {
        if (onDeleteShop != null) {
            onDeleteShop.run();
        }
    }

    @FXML private void onNews() {if(onNews!=null)onNews.run();}
    public void setOnNews(Runnable callback){onNews=callback;}
    public void setUnreadNews(int count){newsBadge.setText(Integer.toString(Math.max(0,count)));newsBadge.setVisible(count>0);newsBadge.setManaged(count>0);newsButton.setAccessibleText(I18nService.getInstance().tr("news.title") + " " + count);}

    public void setOnSync(Runnable onSync) {
        this.onSync = onSync;
    }

    public void setOnEditShop(Runnable onEditShop) {
        this.onEditShop = onEditShop;
    }

    public void setOnDeleteShop(Runnable onDeleteShop) {
        this.onDeleteShop = onDeleteShop;
    }

    public void setOnShopSelected(Consumer<Shop> onShopSelected) {
        this.onShopSelected = onShopSelected;
    }

    public void setShops(List<Shop> shops, Shop selectedShop) {
        updatingShops = true;
        try {
            Shop currentSelection = shopComboBox.getValue();
            shopComboBox.getItems().setAll(shops);
            if (selectedShop != null) {
                shopComboBox.getSelectionModel().select(selectedShop);
            } else if (currentSelection != null) {
                shopComboBox.getSelectionModel().select(currentSelection);
            }
        } finally {
            updatingShops = false;
        }
        var selected=shopComboBox.getValue();if(syncHealth!=null&&(selected==null||syncHealth.shopId()!=selected.getId())){syncHealth=null;fbsHealth.clear();renderSyncHealth();}
        refreshBalance(selected);if(!disposed)statusMonitor.play();
    }
    private void refreshBalance(Shop selected) {
        long epoch=++balanceEpoch;balanceAccount.getItems().clear();balanceAccount.setValue(null);balanceAccount.setDisable(true);renderBalance();
        if(selected==null||disposed)return;
        var owner=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        com.vncode.app.shared.ShopReadCoordinator.shared().submitLocal(new com.vncode.app.shared.ShopReadCoordinator.Key(owner.getId(),"header-balance",com.vncode.app.integration.listing.ListingIdentity.fingerprint(owner)),()->{String saved=com.vncode.app.shared.ConfigService.getConfigValue("cz_balance_warning_rub");int threshold=500;try{threshold=Integer.parseInt(saved);if(threshold<0||threshold>100000)threshold=500;}catch(RuntimeException ignored){}return new HeaderBalanceUpdate(new com.vncode.app.features.gs1.HeaderBalanceService().cached(owner),threshold);})
          .whenComplete((values,error)->javafx.application.Platform.runLater(()->{if(disposed||epoch!=balanceEpoch)return;if(error!=null){renderBalance();return;}balanceWarningRub=values.threshold();balanceAccount.getItems().setAll(values.balances());balanceAccount.setDisable(values.balances().size()<2);if(values.balances().size()==1)balanceAccount.setValue(values.balances().getFirst());renderBalance();}));
    }
    private record HeaderBalanceUpdate(List<com.vncode.app.integration.znack.BalanceSnapshot> balances,int threshold){}
    private void renderBalance() {
        if(balanceLabel==null)return;var value=balanceAccount.getValue();var i18n=I18nService.getInstance();
        balanceLabel.getStyleClass().remove("text-danger");balanceAccount.setPromptText(i18n.tr("header.balance_choose"));
        if(value==null){balanceLabel.setText(i18n.tr("header.balance_unknown"));return;}
        boolean fresh=com.vncode.app.integration.znack.ZnackAccountInfo.freshBalance(value,java.time.Instant.now());
        balanceLabel.setText("CZ: "+value.amount().toPlainString()+" ₽"+(fresh?"":" · "+i18n.tr("header.balance_cached")));
        balanceLabel.setTooltip(new javafx.scene.control.Tooltip(value.scope().productGroup()+" · "+value.scope().accountId()+" · "+value.checkedAt()));
        if(fresh&&value.amount().compareTo(java.math.BigDecimal.valueOf(balanceWarningRub))<0)balanceLabel.getStyleClass().add("text-danger");
    }
    public void dispose(){disposed=true;++balanceEpoch;statusMonitor.stop();}

    public void setBusy(boolean busy) {
        // Header loading removed. Keep local loading indicators near the active content instead.
    }

    public void setProductKizSyncLoading(boolean loading) {
        syncLoadingIndicator.setVisible(loading);
        syncLoadingIndicator.setManaged(loading);
        productSyncLoading=loading;responsiveHeader();
    }

    public void setControls(boolean hasShop, boolean busy, boolean exportEnabled, boolean tokenValid) {
        syncButton.setDisable(!hasShop || busy || !tokenValid);
        editShopButton.setDisable(!hasShop || busy);
        deleteShopButton.setDisable(!hasShop || busy || !tokenValid);
    }

    public void setMarketplace(Marketplace marketplace) {
        syncHealth=null;fbsHealth.clear();renderSyncHealth();
        this.marketplace = marketplace == null ? Marketplace.WILDBERRIES : marketplace;
        applyTranslations();
    }

    public void applyTranslations() {
        setAvailableUpdate(updateVersion);
        renderBalance();jobsCaption=I18nService.getInstance().tr("jobs.title");
        I18nService i18n = I18nService.getInstance();
        if(balanceThresholdItem!=null)balanceThresholdItem.setText(i18n.tr("header.balance_threshold"));
        supportButton.setText(i18n.tr("header.support"));
        newsButton.setTooltip(new javafx.scene.control.Tooltip(i18n.tr("news.title")));
        shopComboBox.setPromptText(i18n.tr("header.shop_prompt"));
        syncCaption=i18n.tr(marketplace == Marketplace.OZON ? "ozon.dashboard.refresh" : "header.sync");
        syncLoadingLabel.setText(i18n.tr("header.sync_products_kiz"));
        moreButton.setTooltip(new javafx.scene.control.Tooltip(i18n.tr("header.more")));moreButton.setAccessibleText(i18n.tr("header.more"));
        editShopButton.setTooltip(new javafx.scene.control.Tooltip(i18n.tr("header.edit_shop")));deleteShopButton.setTooltip(new javafx.scene.control.Tooltip(i18n.tr("header.delete_shop")));
        responsiveHeader();renderSyncHealth();
    }
}
