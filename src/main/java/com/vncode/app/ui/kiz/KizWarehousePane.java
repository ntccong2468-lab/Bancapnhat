package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Consumer;

/** Virtualized totals only: full paid marking codes never enter a UI cell or error dialog. */
public final class KizWarehousePane extends BorderPane {
    private Shop shop;private long generation;private boolean busy,rendering;
    private final Label hint=new Label(),status=new Label(),details=new Label();
    private final ProgressIndicator indicator=new ProgressIndicator();
    private final Button add=new Button(),refresh=new Button(),archive=new Button(),restore=new Button();
    private final CheckBox automatic=new CheckBox();
    private final ComboBox<KizStockCategoryRepository.Category> category=new ComboBox<>();
    private final Button newCategory=new Button(),classify=new Button(),linkProducts=new Button();
    private final KizStockCategoryRepository categoryRepository=new KizStockCategoryRepository();
    private final TableView<KizWarehouseRepository.Summary> table=new TableView<>();
    private final KizWarehouseRepository repository=new KizWarehouseRepository();
    private final List<TableColumn<KizWarehouseRepository.Summary,?>> columns=new ArrayList<>();
    public KizWarehousePane(){
        add.getStyleClass().add("btn-primary");setPadding(new Insets(12));automatic.setWrapText(true);hint.setWrapText(true);status.setWrapText(true);hint.getStyleClass().add("text-muted");
        indicator.setMaxSize(24,24);details.setWrapText(true);setBottom(details);
        var actions=new FlowPane(8,8,add,refresh,archive,restore,indicator);category.setPrefWidth(260);category.setId("kiz-stock-category");
        var grouping=new FlowPane(8,8,category,newCategory,classify,linkProducts);
        var top=new VBox(10,hint,grouping,automatic,actions,status);setTop(top);BorderPane.setMargin(top,new Insets(0,0,10,0));
        table.setFixedCellSize(36);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);table.setPlaceholder(new Label(tr("empty")));setCenter(table);
        column("category",KizWarehouseRepository.Summary::category);column("gtin",KizWarehouseRepository.Summary::gtin);column("total",r->r.total());column("ready",r->r.ready());column("held",r->r.held());column("consumed",r->r.consumed());column("unassigned",r->r.unverified());
        add.setId("kiz-warehouse-import");automatic.setId("kiz-warehouse-auto-purchase");table.setId("kiz-warehouse-table");
        archive.setOnAction(e->archive(false));restore.setOnAction(e->archive(true));table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->buttons());
        newCategory.setOnAction(e->createCategory());classify.setOnAction(e->classifySelected());linkProducts.setOnAction(e->linkProducts());category.valueProperty().addListener((o,a,b)->buttons());
        add.setOnAction(e->chooseFile());refresh.setOnAction(e->reload());
        automatic.setOnAction(e->{if(rendering||shop==null||busy)return;boolean enabled=automatic.isSelected();Shop owner=shop;run(()->{repository.autoPurchase(owner,enabled);return enabled;},result->status.setText(tr(result?"auto_on":"auto_off")));});
        applyTranslations();buttons();
    }
    private void column(String key,java.util.function.Function<KizWarehouseRepository.Summary,Object> value){
        var column=new TableColumn<KizWarehouseRepository.Summary,String>();column.setUserData(key);column.setCellValueFactory(c->new javafx.beans.property.ReadOnlyStringWrapper(String.valueOf(value.apply(c.getValue()))));columns.add(column);table.getColumns().add(column);
    }
    public void applyTranslations(){if(table.getPlaceholder() instanceof Label label)label.setText(tr("empty"));hint.setText(tr("category_help"));category.setPromptText(tr("select_category"));newCategory.setText(tr("new_category"));classify.setText(tr("classify"));linkProducts.setText(tr("link_products"));automatic.setText(tr("automatic"));add.setText(tr("import"));refresh.setText(tr("refresh"));archive.setText(tr("archive"));restore.setText(tr("restore"));columns.forEach(c->c.setText(tr(String.valueOf(c.getUserData()))));}
    public void setShop(Shop selected){generation++;busy=false;shop=selected==null?null:new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());table.getItems().clear();category.getItems().clear();category.setValue(null);automatic.setSelected(false);status.setText("");details.setText("");buttons();}
    public void reload(){if(shop==null||busy)return;Shop owner=shop;run(()->new Snapshot(repository.summaries(owner),repository.autoPurchase(owner),repository.issues(owner),categoryRepository.list(owner)),data->{String prior=category.getValue()==null?"":category.getValue().id();category.getItems().setAll(data.categories());category.setValue(data.categories().stream().filter(c->c.id().equals(prior)).findFirst().orElse(null));table.getItems().setAll(data.rows());rendering=true;automatic.setSelected(data.automatic());rendering=false;details.setText(data.issues().entrySet().stream().map(e->e.getValue()+": "+KizErrorMessages.message(e.getKey())).collect(java.util.stream.Collectors.joining("\n")));});}
    private void chooseFile(){
        if(shop==null||busy)return;if(category.getValue()==null){status.setText(tr("select_category"));return;}var chooser=new FileChooser();chooser.setTitle(tr("import"));chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("KIZ", "*.txt","*.csv","*.json","*.xlsx","*.xls","*.pdf","*.png","*.jpg","*.jpeg","*.bmp","*.gif","*.tif","*.tiff","*.tsv","*.docx","*.rtf","*.md","*.html","*.htm","*.xml","*.zip"),new FileChooser.ExtensionFilter("All files","*.*"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;Shop owner=shop;var chosenCategory=category.getValue();
        run(()->KizImportReader.read(file.toPath()),preview->{
            if(!preview.valid()){
                String issues=preview.issues().stream().limit(20).map(i->tr("row")+" "+i.row()+": "+KizErrorMessages.message(i.code())).collect(java.util.stream.Collectors.joining("\n"));
                AlertService.showError(tr("invalid")+"\n"+issues);return;
            }
            long shortCodes=preview.marks().stream().filter(m->!m.fullDataMatrix()).count();
            String counts=MessageFormat.format(tr("formats"),shortCodes,preview.marks().size()-shortCodes)+"\n"+preview.counts().entrySet().stream().limit(100).map(e->e.getKey()+": "+e.getValue()).collect(java.util.stream.Collectors.joining("\n"));
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,MessageFormat.format(tr("confirm"),preview.marks().size(),preview.duplicates())+"\n\n"+counts,ButtonType.YES,ButtonType.NO);AlertService.applyTheme(confirm);confirm.setHeaderText(owner.getName());if(getScene()!=null)confirm.initOwner(getScene().getWindow());
            if(confirm.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES||shop!=owner)return;
            run(()->{var imported=new KizWarehouseService().importFile(owner,preview,true);if(chosenCategory!=null)categoryRepository.classifyBatch(owner,chosenCategory.id(),imported.batch());return imported;},result->{status.setText(MessageFormat.format(tr("imported"),result.inserted(),result.duplicates()));reload();});
        });
    }
    private void createCategory(){
        if(shop==null||busy)return;var dialog=new TextInputDialog();dialog.setTitle(tr("new_category"));dialog.setHeaderText(tr("category_name"));if(getScene()!=null)dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().filter(name->!name.isBlank()).ifPresent(name->{Shop owner=shop;run(()->categoryRepository.create(owner,name),created->{category.getItems().add(created);category.setValue(created);reload();});});
    }
    private void classifySelected(){
        var row=table.getSelectionModel().getSelectedItem();var group=category.getValue();if(shop==null||busy||row==null||group==null)return;Shop owner=shop;
        run(()->categoryRepository.classifyGtin(owner,group.id(),row.gtin()),count->{status.setText(MessageFormat.format(tr("classified"),count,group.name()));reload();});
    }
    private void linkProducts(){
        Shop owner=shop;var group=category.getValue();if(owner==null||busy||group==null)return;
        var dialog=new Dialog<Void>();dialog.setTitle(tr("link_products")+" · "+group.name());if(getScene()!=null)dialog.initOwner(getScene().getWindow());
        var picker=new com.vncode.app.ui.products.ProductsPane();picker.useAsPicker();picker.setShop(owner);
        var apply=new ButtonType(tr("link_products"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,ButtonType.CANCEL);dialog.getDialogPane().setContent(picker);dialog.getDialogPane().setPrefSize(1120,700);
        var button=(Button)dialog.getDialogPane().lookupButton(apply);
        button.addEventFilter(javafx.event.ActionEvent.ACTION,event->{event.consume();var keys=picker.selectedKeys();if(keys.isEmpty())return;button.setDisable(true);
            var task=new Task<Integer>(){protected Integer call(){categoryRepository.assignProducts(owner,group.id(),keys);return (int)keys.stream().map(com.vncode.app.features.products.ProductKey::cardId).distinct().count();}};
            task.setOnSucceeded(e->{dialog.close();if(shop==owner){status.setText(MessageFormat.format(tr("linked"),task.getValue(),group.name()));reload();}});
            task.setOnFailed(e->{button.setDisable(false);status.setText(FriendlyErrorService.format(task.getException()));});AppTaskExecutor.execute(task,error->{button.setDisable(false);status.setText(FriendlyErrorService.format(error));});
        });dialog.setOnHidden(e->picker.dispose());dialog.show();
    }
    private void archive(boolean restoreStock){
        var row=table.getSelectionModel().getSelectedItem();if(row==null||shop==null||busy)return;Shop owner=shop;
        var confirm=new Alert(Alert.AlertType.CONFIRMATION,tr(restoreStock?"restore_confirm":"archive_confirm"),ButtonType.YES,ButtonType.NO);AlertService.applyTheme(confirm);confirm.setHeaderText(owner.getName()+" · "+row.gtin());if(getScene()!=null)confirm.initOwner(getScene().getWindow());
        if(confirm.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES||shop!=owner)return;
        run(()->repository.changeArchive(owner,row.gtin(),restoreStock),count->{status.setText(MessageFormat.format(tr("archive_changed"),count));reload();});
    }
    private <T> void run(Work<T> work,Consumer<T> completed){
        Shop owner=shop;long epoch=generation;busy=true;buttons();var task=new Task<T>(){@Override protected T call()throws Exception{return work.run();}};
        task.setOnSucceeded(e->{if(epoch!=generation||shop!=owner)return;busy=false;buttons();completed.accept(task.getValue());});
        Consumer<Throwable> failed=error->{if(epoch!=generation||shop!=owner)return;busy=false;buttons();status.setText(FriendlyErrorService.format(error));};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void buttons(){category.setDisable(shop==null||busy);newCategory.setDisable(shop==null||busy);classify.setDisable(shop==null||busy||category.getValue()==null||table.getSelectionModel().getSelectedItem()==null);linkProducts.setDisable(shop==null||busy||category.getValue()==null);archive.setDisable(shop==null||busy||table.getSelectionModel().getSelectedItem()==null);restore.setDisable(shop==null||busy||table.getSelectionModel().getSelectedItem()==null);indicator.setVisible(busy);indicator.setManaged(busy);add.setDisable(shop==null||busy);refresh.setDisable(shop==null||busy);automatic.setDisable(shop==null||busy);}
    private static String tr(String key){return I18nService.getInstance().tr("kiz.warehouse."+key);}
    private record Snapshot(List<KizWarehouseRepository.Summary> rows,boolean automatic,Map<String,Integer> issues,List<KizStockCategoryRepository.Category> categories){}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
}
