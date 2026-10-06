package com.tuandev.fbsbarcode.ui.gtinsync;

import com.tuandev.fbsbarcode.features.gtinsync.*;
import com.tuandev.fbsbarcode.integration.license.LicenseService;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import java.util.*;
import java.util.function.*;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;

public final class GtinSyncController {
    @FXML private Label titleLabel,scopeLabel,statusLabel,capabilityLabel;
    @FXML private Button syncButton,loadButton,selectButton,clearButton,mappingButton,previewButton,confirmButton,pauseButton,resumeButton,cancelButton,reconcileButton,csvButton,xlsxButton;
    @FXML private TextField searchField;
    @FXML private ComboBox<RegisteredGtin> gtinBox;
    @FXML private ComboBox<Operation> operationBox;
    @FXML private ComboBox<String> oldBarcodeBox;
    @FXML private TableView<GtinSyncViewModel.Row> productTable;
    @FXML private TableView<JobItem> historyTable;
    @FXML private TextArea previewArea;
    private final GtinSyncViewModel model=new GtinSyncViewModel();
    private final GtinSyncWorkspace workspace;
    private List<Preview> preview=List.of();
    private List<JobItem> history=List.of();
    private boolean busy,disposed;
    private final Set<Task<?>> reads=new HashSet<>();
    private record Scope(int shopId,com.tuandev.fbsbarcode.integration.marketplace.Marketplace marketplace) {}
    private record Confirmed(UUID job,List<JobItem> items) {}
    private final Set<Scope> runningQueues=new HashSet<>();
    private String shopName="";
    public GtinSyncController(){this(new GtinSyncWorkspace());}
    GtinSyncController(GtinSyncWorkspace workspace){this.workspace=Objects.requireNonNull(workspace);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    @FXML public void initialize() {
        productTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        operationBox.setItems(FXCollections.observableArrayList(Operation.values()));operationBox.setValue(Operation.ADD);
        operationBox.setConverter(new StringConverter<>(){public String toString(Operation op){return op==null?"":tr("gtinsync.op."+op);}public Operation fromString(String s){throw new UnsupportedOperationException();}});
        gtinBox.setConverter(new StringConverter<>(){public String toString(RegisteredGtin g){return g==null?"":g.gtin()+" · "+g.article()+" · "+g.color()+" · "+g.size();}public RegisteredGtin fromString(String s){throw new UnsupportedOperationException();}});
        productTable.getSelectionModel().selectedItemProperty().addListener((o,a,b)-> {
            invalidatePreview();
            if(b!=null){oldBarcodeBox.setItems(FXCollections.observableArrayList(b.product().barcodes()));oldBarcodeBox.setValue(b.oldGtin().isBlank()?null:b.oldGtin());operationBox.setValue(b.operation());
                gtinBox.setValue(model.catalog().stream().filter(g->g.gtin().equals(b.gtin())).findFirst().orElse(null));}
            updateButtons();
        });
        searchField.textProperty().addListener((o,a,b)->renderRows());
        operationBox.valueProperty().addListener((o,a,b)->invalidatePreview());
        oldBarcodeBox.valueProperty().addListener((o,a,b)->invalidatePreview());
        gtinBox.valueProperty().addListener((o,a,b)->invalidatePreview());
        historyTable.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateButtons());
        applyTranslations();updateButtons();
    }
    public void setShop(Shop shop) {
        for(var task:List.copyOf(reads))task.cancel();reads.clear();busy=false;
        model.load(shop);shopName=shop==null?"":shop.getName();history=List.of();historyTable.getItems().clear();
        gtinBox.getItems().clear();oldBarcodeBox.getItems().clear();invalidatePreview();renderRows();applyTranslations();
        statusLabel.setText(tr("gtinsync.load_hint"));updateButtons();
        if(shop!=null) {
            var token=model.token();
            background(token,()->workspace.repository.history(token.shopId(),token.marketplace()),data->{history=data;historyTable.setItems(FXCollections.observableArrayList(data));},false);
        }
    }
    public void dispose(){disposed=true;model.load(null);for(var task:List.copyOf(reads))task.cancel();reads.clear();}
    public void applyTranslations() {
        titleLabel.setText(tr("gtinsync.title"));scopeLabel.setText(shopName.isBlank()?tr("gtinsync.no_shop"):shopName+" · "+model.token().marketplace().badge());
        capabilityLabel.setText(tr("gtinsync.contract_unverified"));
        var buttons=List.of(syncButton,loadButton,selectButton,clearButton,mappingButton,previewButton,confirmButton,pauseButton,resumeButton,cancelButton,reconcileButton,csvButton,xlsxButton);
        var keys=List.of("sync","load","select_exact","clear","mapping","preview","confirm","pause","resume","cancel","reconcile","csv","xlsx");
        for(int i=0;i<keys.size();i++)buttons.get(i).setText(tr("gtinsync."+keys.get(i)));
        searchField.setPromptText(tr("gtinsync.search"));gtinBox.setPromptText(tr("gtinsync.target"));oldBarcodeBox.setPromptText(tr("gtinsync.old"));
        productTable.getColumns().clear();
        column(productTable,"product",r->r.product().key().productId()+" / "+r.product().key().variantId());
        column(productTable,"article",r->r.product().article());column(productTable,"color",r->r.product().color());column(productTable,"size",r->r.product().size());
        column(productTable,"barcodes",r->String.join(", ",r.product().barcodes()));column(productTable,"target",GtinSyncViewModel.Row::gtin);
        column(productTable,"operation",r->tr("gtinsync.op."+r.operation()));column(productTable,"status",r->tr("gtinsync.reason."+(r.reason().isBlank()?"exact":r.reason())));
        historyTable.getColumns().clear();
        column(historyTable,"product",r->r.preview().before().key().productId()+" / "+r.preview().before().key().variantId());
        column(historyTable,"operation",r->tr("gtinsync.op."+r.preview().operation()));column(historyTable,"old",r->r.preview().oldGtin());
        column(historyTable,"target",r->r.preview().newGtin());column(historyTable,"status",r->tr("gtinsync.status."+r.status()));
        operationBox.setButtonCell(new ListCell<>(){@Override protected void updateItem(Operation op,boolean empty){super.updateItem(op,empty);setText(empty||op==null?"":tr("gtinsync.op."+op));}});
    }
    private static <T> void column(TableView<T> table,String key,Function<T,String> value) {
        var col=new TableColumn<T,String>(tr("gtinsync."+key));col.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));col.setPrefWidth(key.equals("barcodes")?240:150);table.getColumns().add(col);
    }
    private void renderRows() {
        String query=searchField.getText()==null?"":searchField.getText().strip().toLowerCase(Locale.ROOT);
        productTable.setItems(FXCollections.observableArrayList(model.rows().stream().filter(r->(r.product().article()+" "+r.product().color()+" "+r.product().size()+" "+r.product().key().productId()+" "+String.join(" ",r.product().barcodes())+" "+r.gtin()).toLowerCase(Locale.ROOT).contains(query)).toList()));
        invalidatePreview();
    }
    @FXML private void onLoad(){var token=model.token();invalidatePreview();background(token,()->workspace.load(token),data->accept(token,data),true);}
    @FXML private void onSync(){var token=model.token();invalidatePreview();background(token,()->{workspace.syncCatalog(token);return workspace.load(token);},data->accept(token,data),true);}
    private void accept(GtinSyncViewModel.LoadToken token,GtinSyncWorkspace.Data data) {
        if(!model.accept(token,data.catalog(),data.products()))return;
        for(var entry:data.mappings().entrySet())try{model.choose(entry.getKey(),entry.getValue(),Operation.ADD,"");}catch(IllegalArgumentException ignored){}
        gtinBox.setItems(FXCollections.observableArrayList(data.catalog().stream().filter(g->g.published()&&g.tradeUnit()).toList()));
        history=data.history();historyTable.setItems(FXCollections.observableArrayList(history));renderRows();statusLabel.setText(data.products().size()+" "+tr("gtinsync.products")+" · "+data.catalog().size()+" GTIN");
    }
    @FXML private void onSelectExact(){productTable.getSelectionModel().clearSelection();var exact=Set.copyOf(model.exactKeys());for(int i=0;i<productTable.getItems().size();i++)if(exact.contains(productTable.getItems().get(i).product().key()))productTable.getSelectionModel().select(i);updateButtons();}
    @FXML private void onClear(){productTable.getSelectionModel().clearSelection();invalidatePreview();updateButtons();}
    @FXML private void onMapping() {
        var row=productTable.getSelectionModel().getSelectedItem();var gtin=gtinBox.getValue();if(row==null||gtin==null)return;
        try{model.choose(row.product().key(),gtin.gtin(),operationBox.getValue(),operationBox.getValue()==Operation.ADD?"":oldBarcodeBox.getValue());}
        catch(IllegalArgumentException e){statusLabel.setText(tr("gtinsync.invalid_selection"));return;}
        var token=model.token();background(token,()->{workspace.repository.saveMapping(row.product().key(),gtin.gtin());return true;},done->{renderRows();statusLabel.setText(tr("gtinsync.mapping_saved"));},true);
    }
    @FXML private void onPreview() {
        List<GtinSyncViewModel.Row> selected=List.copyOf(productTable.getSelectionModel().getSelectedItems());if(selected.isEmpty())return;
        if(selected.size()==1) {
            var target=gtinBox.getValue();
            try{selected=List.of(model.selection(selected.getFirst().product().key(),target==null?"":target.gtin(),operationBox.getValue(),operationBox.getValue()==Operation.ADD?"":oldBarcodeBox.getValue()));}
            catch(IllegalArgumentException e){statusLabel.setText(tr("gtinsync.invalid_selection"));return;}
        }
        var selection=selected;
        var token=model.token();background(token,()-> {
            var results=new ArrayList<Preview>();var service=new GtinPreviewService(workspace.repository,workspace::adapter);
            for(var row:selection) {
                if(row.gtin().isBlank())throw new IllegalArgumentException("invalid_selection");
                var cap=workspace.adapter(row.product().key()).capability(row.product().key());
                results.add(cap.supports(row.operation())?service.create(row.product().key(),row.operation(),row.oldGtin(),row.gtin()):new Preview(row.product(),row.operation(),row.oldGtin(),row.gtin(),cap));
            }
            return List.copyOf(results);
        },results->{preview=results;previewArea.setText(results.stream().map(p->p.before().key().productId()+" / "+p.before().key().variantId()+" · "+tr("gtinsync.op."+p.operation())+" · "+p.oldGtin()+" → "+p.newGtin()+"\n"+tr("gtinsync.barcodes")+": "+String.join(", ",p.before().barcodes())).collect(java.util.stream.Collectors.joining("\n\n")));statusLabel.setText(results.stream().allMatch(p->p.capability().supports(p.operation()))?tr("gtinsync.confirm_hint"):tr("gtinsync.contract_unverified"));},true);
    }
    @FXML private void onConfirm() {
        if(preview.isEmpty()||busy||runningQueues.contains(scope(model.token()))||preview.stream().anyMatch(p->!p.capability().supports(p.operation())))return;
        if(!LicenseService.getInstance().getState().kizAllowed()){statusLabel.setText(tr("gtinsync.license_required"));return;}
        var selected=preview;var token=model.token();invalidatePreview();
        background(token,()->new Confirmed(workspace.coordinator.confirm(selected),workspace.repository.history(token.shopId(),token.marketplace())),result->{
            showHistory(result.items());
            historyTable.getItems().stream().filter(i->i.jobId().equals(result.job())).findFirst().ifPresent(i->historyTable.getSelectionModel().select(i));
            runQueue(token);
        },true,false);
    }
    private void showHistory(List<JobItem> items){
        var selected=historyTable.getSelectionModel().getSelectedItem();
        history=items;historyTable.setItems(FXCollections.observableArrayList(items));
        if(selected!=null)items.stream().filter(i->i.id().equals(selected.id())).findFirst().ifPresent(i->historyTable.getSelectionModel().select(i));
        statusLabel.setText(tr("gtinsync.history_updated"));updateButtons();
    }
    private static Scope scope(GtinSyncViewModel.LoadToken token){return new Scope(token.shopId(),token.marketplace());}
    private void runQueue(GtinSyncViewModel.LoadToken token) {
        var scope=scope(token);if(disposed||!runningQueues.add(scope))return;updateButtons();
        Task<List<JobItem>> task=new Task<>() {
            @Override protected List<JobItem> call() {
                workspace.coordinator.runPending(token.shopId(),token.marketplace(),item->updateValue(workspace.repository.history(token.shopId(),token.marketplace())));
                return workspace.repository.history(token.shopId(),token.marketplace());
            }
        };
        task.valueProperty().addListener((o,a,b)->{if(b!=null&&!disposed&&model.token().equals(token))showHistory(b);});
        Runnable finish=()->{runningQueues.remove(scope);if(!disposed)updateButtons();};
        task.setOnSucceeded(e->finish.run());
        task.setOnFailed(e->{if(!disposed&&model.token().equals(token))statusLabel.setText(tr("gtinsync.error"));finish.run();});
        task.setOnCancelled(e->finish.run());AppTaskExecutor.execute(task);
    }
    private void jobAction(Consumer<UUID> action) {
        var item=historyTable.getSelectionModel().getSelectedItem();if(item==null)return;var token=model.token();
        background(token,()->{action.accept(item.jobId());return workspace.repository.history(token.shopId(),token.marketplace());},this::showHistory,true,false);
    }
    @FXML private void onPause(){jobAction(workspace.coordinator::pause);}
    @FXML private void onCancel(){jobAction(workspace.coordinator::cancelQueued);}
    @FXML private void onResume(){
        var item=historyTable.getSelectionModel().getSelectedItem();var token=model.token();
        if(item==null||runningQueues.contains(scope(token)))return;
        background(token,()->{workspace.coordinator.resume(item.jobId());return workspace.repository.history(token.shopId(),token.marketplace());},items->{showHistory(items);runQueue(token);},true,false);
    }
    @FXML private void onReconcile(){var item=historyTable.getSelectionModel().getSelectedItem();if(item==null)return;var token=model.token();background(token,()->{workspace.coordinator.reconcile(item.id());return workspace.repository.history(token.shopId(),token.marketplace());},this::showHistory,true,false);}
    @FXML private void onCsv(){export(false);}
    @FXML private void onXlsx(){export(true);}
    private void export(boolean xlsx) {
        var chooser=new FileChooser();chooser.setTitle(tr("gtinsync.export"));chooser.setInitialFileName("gtin-history."+(xlsx?"xlsx":"csv"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(xlsx?"Excel":"CSV",xlsx?"*.xlsx":"*.csv"));
        var file=chooser.showSaveDialog(productTable.getScene().getWindow());if(file==null)return;
        var rows=history;var token=model.token();background(token,()->{var exporter=new GtinSyncHistoryExporter();if(xlsx)exporter.writeXlsx(rows,file.toPath());else exporter.writeCsv(rows,file.toPath());return true;},done->statusLabel.setText(tr("gtinsync.exported")),true);
    }
    private void invalidatePreview(){preview=List.of();if(previewArea!=null)previewArea.clear();if(confirmButton!=null)confirmButton.setDisable(true);}
    private void updateButtons() {
        boolean noShop=model.token().shopId()==0;boolean selected=productTable.getSelectionModel().getSelectedItem()!=null;boolean job=historyTable.getSelectionModel().getSelectedItem()!=null;
        boolean single=productTable.getSelectionModel().getSelectedItems().size()==1;boolean executing=runningQueues.contains(scope(model.token()));
        gtinBox.setDisable(busy||!single);operationBox.setDisable(busy||!single);oldBarcodeBox.setDisable(busy||!single);
        syncButton.setDisable(busy||noShop);loadButton.setDisable(busy||noShop);mappingButton.setDisable(busy||!single);previewButton.setDisable(busy||!selected);
        selectButton.setDisable(busy||productTable.getItems().isEmpty());clearButton.setDisable(busy||!selected);
        confirmButton.setDisable(busy||executing||preview.isEmpty()||preview.stream().anyMatch(p->!p.capability().supports(p.operation())));
        pauseButton.setDisable(busy||!job);resumeButton.setDisable(busy||executing||!job);cancelButton.setDisable(busy||!job);reconcileButton.setDisable(busy||executing||!job);csvButton.setDisable(busy||history.isEmpty());xlsxButton.setDisable(busy||history.isEmpty());
    }
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T> void background(GtinSyncViewModel.LoadToken token,Work<T> work,Consumer<T> done,boolean blocking){background(token,work,done,blocking,true);}
    private <T> void background(GtinSyncViewModel.LoadToken token,Work<T> work,Consumer<T> done,boolean blocking,boolean cancellable) {
        if(disposed||token.shopId()==0||(blocking&&busy))return;
        if(blocking){busy=true;updateButtons();statusLabel.setText(tr("gtinsync.loading"));}
        Task<T> task=new Task<>(){@Override protected T call()throws Exception{return work.run();}};
        if(cancellable)reads.add(task);
        Runnable finish=()->{reads.remove(task);if(!disposed&&model.token().equals(token)){if(blocking)busy=false;updateButtons();}};
        task.setOnSucceeded(e->{try{if(!disposed&&model.token().equals(token))done.accept(task.getValue());}finally{finish.run();}});
        task.setOnFailed(e->{if(!disposed&&model.token().equals(token))statusLabel.setText(tr("gtinsync.error"));finish.run();});
        task.setOnCancelled(e->finish.run());AppTaskExecutor.execute(task);
    }
}
