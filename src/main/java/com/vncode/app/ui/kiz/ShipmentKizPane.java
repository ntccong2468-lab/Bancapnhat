package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Consumer;

/** Shipment-local selection and virtualized category stock; all file/SQL work stays off FX. */
public class ShipmentKizPane extends VBox {
    private final ShipmentKizRepository repository=new ShipmentKizRepository();
    private final CheckBox imported=new CheckBox(),purchased=new CheckBox();
    private final Label title=new Label(),hint=new Label(),status=new Label();
    private final Button refresh=new Button();
    private final TableView<ShipmentKizRepository.CategoryStock> table=new TableView<>();
    private final ProgressIndicator progress=new ProgressIndicator();
    private final javafx.animation.Timeline stockRefresh=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(3),e->{if(getScene()!=null&&isVisible()&&!this.locked)refreshStock();}));
    private Shop shop;private String shipment;private long generation;private boolean busy,locked,known;
    private KizSourceSelection selection=KizSourceSelection.SELLER_ONLY;
    private Consumer<KizSourceSelection> changed=value->{};

    public ShipmentKizPane() {
        setSpacing(8);title.getStyleClass().add("h3");hint.setWrapText(true);hint.getStyleClass().add("text-muted");status.setWrapText(true);
        imported.setId("shipment-kiz-imported");purchased.setId("shipment-kiz-purchased");refresh.setId("shipment-kiz-refresh");
        progress.setMaxSize(18,18);var spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        getChildren().addAll(new HBox(8,title,spacer,progress,refresh),new FlowPane(8,6,imported,purchased),hint,table,status);
        table.setId("shipment-kiz-categories");table.setFixedCellSize(52);table.setPrefHeight(235);table.setMinHeight(120);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("category",ShipmentKizRepository.CategoryStock::name,145);
        column("ready",r->r.ready(),65);
        var upload=new TableColumn<ShipmentKizRepository.CategoryStock,Void>();upload.setUserData("upload");upload.setMinWidth(85);upload.setPrefWidth(100);
        upload.setCellFactory(col->new TableCell<>() {
            private final Button button=new Button();
            {button.setId("shipment-kiz-upload");button.setOnAction(event->{var row=getTableRow().getItem();if(row!=null)chooseFile(row);});}
            @Override protected void updateItem(Void value,boolean empty){super.updateItem(value,empty);button.setText(tr("upload"));setGraphic(empty?null:button);}
        });table.getColumns().add(upload);
        imported.setOnAction(event->saveSelection());purchased.setOnAction(event->saveSelection());refresh.setOnAction(event->reload());
        stockRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty().addListener((o,a,b)->{if(b==null)stockRefresh.stop();else if(shop!=null)stockRefresh.play();});
        render(selection);applyTranslations();buttons();
    }
    private void column(String name,java.util.function.Function<ShipmentKizRepository.CategoryStock,Object> value,double width) {
        var column=new TableColumn<ShipmentKizRepository.CategoryStock,String>();column.setUserData(name);column.setPrefWidth(width);column.setMinWidth(width==145?90:42);
        column.setCellValueFactory(cell->new javafx.beans.property.ReadOnlyStringWrapper(String.valueOf(value.apply(cell.getValue()))));table.getColumns().add(column);
        if(name.equals("category"))column.setCellFactory(c->new TableCell<>(){
            {setWrapText(true);}
            @Override protected void updateItem(String text,boolean empty){super.updateItem(text,empty);setText(empty?null:text);setTooltip(empty?null:new Tooltip(text));}
        });
    }
    public void setContext(Shop selected,String shipmentId) {
        // FBS refreshes the same shipment continuously. Do not discard an import callback
        // or clear its totals merely because a fresh Shop DTO was received.
        if(shop!=null&&selected!=null&&Objects.equals(shipment,shipmentId)&&
                com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop).equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(selected)))return;
        generation++;busy=false;known=false;locked=false;shipment=shipmentId;
        shop=selected==null?null:new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        table.getItems().clear();status.setText("");render(KizSourceSelection.SELLER_ONLY);buttons();
        if(shop!=null&&shipment!=null&&!shipment.isBlank()){reload();if(getScene()!=null)stockRefresh.play();}else stockRefresh.stop();
    }
    public void setOnSelectionChanged(Consumer<KizSourceSelection> listener){changed=Objects.requireNonNull(listener);changed.accept(selection);}
    public boolean purchaseSelected(){return selection.purchased();}
    public KizSourceSelection snapshot(){if(!known||busy||shop==null)throw new IllegalStateException("KIZ_SOURCE_LOADING");return selection;}
    public void setLocked(boolean value){boolean finished=locked&&!value;locked=value;buttons();if(finished)reload();}
    public void dispose(){stockRefresh.stop();generation++;shop=null;known=false;table.getItems().clear();buttons();}
    public void reload() {
        if(shop==null||shipment==null||shipment.isBlank()||busy)return;
        Shop owner=shop;String target=shipment;
        run(()->new Snapshot(repository.selection(owner,target),repository.refreshCategories(owner)),data->{
            known=true;render(data.selection());table.getItems().setAll(data.categories());buttons();
        });
    }
    private boolean refreshingStock;
    private void refreshStock(){
        if(shop==null||!known||busy||refreshingStock||locked)return;
        refreshingStock=true;Shop owner=shop;long epoch=generation;
        var task=new Task<List<ShipmentKizRepository.CategoryStock>>(){protected List<ShipmentKizRepository.CategoryStock> call(){return repository.stocks(owner);}};
        task.setOnSucceeded(e->{refreshingStock=false;if(shop==owner&&generation==epoch&&!busy)table.getItems().setAll(task.getValue());});
        java.util.function.Consumer<Throwable> failed=error->{refreshingStock=false;};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void saveSelection() {
        if(busy||locked||!known||shop==null){render(selection);return;}
        Shop owner=shop;String target=shipment;var desired=new KizSourceSelection(imported.isSelected(),purchased.isSelected());
        run(()->{repository.saveSelection(owner,target,desired);return desired;},saved->{render(saved);status.setText(tr(saved.anySelected()?"sources_saved":"select_source"));});
    }
    private void render(KizSourceSelection value) {
        selection=value;imported.setSelected(value.sellerImported());purchased.setSelected(value.purchased());
        table.setVisible(value.sellerImported());table.setManaged(value.sellerImported());changed.accept(value);
    }
    private void chooseFile(ShipmentKizRepository.CategoryStock category) {
        if(busy||locked||shop==null||!known)return;
        var chooser=new FileChooser();chooser.setTitle(tr("upload")+" · "+category.name());
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("KIZ","*.txt","*.csv","*.tsv","*.json","*.xml","*.xlsx","*.xls","*.docx","*.rtf","*.md","*.html","*.htm","*.pdf","*.png","*.jpg","*.jpeg","*.bmp","*.gif","*.tif","*.tiff","*.zip"),new FileChooser.ExtensionFilter("All files","*.*"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        importFile(category,file.toPath());
    }
    void importFile(ShipmentKizRepository.CategoryStock category,java.nio.file.Path file) {
        if(busy||locked||shop==null||!known)return;
        Shop owner=shop;long epoch=generation;
        run(()->{var preview=KizImportReader.read(file);return new ImportPreview(preview,preview.valid()?new KizWarehouseRepository().review(owner,preview,category.id()):null);},data->{
            var preview=data.preview();
            if(!preview.valid()){
                status.setText(tr("invalid_file")+"\n"+preview.issues().stream().limit(20).map(issue->issue.row()+": "+KizErrorMessages.message(issue.code())).collect(java.util.stream.Collectors.joining("\n")));return;
            }
            var choice=data.review().otherCategory()==0?Optional.of(new KizImportDialog.Choice(false)):KizImportDialog.confirm(getScene()==null?null:getScene().getWindow(),owner.getName()+" · "+category.name(),preview,data.review());
            if(choice.isEmpty()||shop!=owner||generation!=epoch)return;
            run(()->{var result=new KizWarehouseService().importFile(owner,preview,true,category.id(),choice.get().moveAvailable());return new ImportedStock(result,repository.stocks(owner));},dataAfter->{
                table.getItems().setAll(dataAfter.categories());
                int ready=dataAfter.categories().stream().filter(c->c.id().equals(category.id())).mapToInt(ShipmentKizRepository.CategoryStock::ready).sum();
                status.setText(category.name()+" · "+tr("ready")+": "+ready+"\n"+KizImportDialog.resultText(dataAfter.result()));
            });
        });
    }
    private <T>void run(Work<T> work,Consumer<T> completed) {
        Shop owner=shop;long epoch=generation;busy=true;buttons();
        var task=new Task<T>(){protected T call()throws Exception{return work.run();}};
        task.setOnSucceeded(event->{if(shop!=owner||generation!=epoch)return;busy=false;buttons();completed.accept(task.getValue());});
        Consumer<Throwable> failed=error->{if(shop!=owner||generation!=epoch)return;busy=false;render(selection);buttons();status.setText(FriendlyErrorService.format(error));};
        task.setOnFailed(event->failed.accept(task.getException()));AppTaskExecutor.execute(task,failed);
    }
    private void buttons() {
        boolean unavailable=shop==null||shipment==null||shipment.isBlank();
        imported.setDisable(unavailable||busy||locked||!known);purchased.setDisable(unavailable||busy||locked||!known);
        table.setDisable(unavailable||busy||locked||!known);refresh.setDisable(unavailable||busy||locked);progress.setVisible(busy);progress.setManaged(busy);
    }
    public void applyTranslations() {
        title.setText(tr("title"));imported.setText(tr("seller_imported"));purchased.setText(tr("purchase"));refresh.setText(tr("refresh"));hint.setText(tr("help"));
        table.setPlaceholder(new Label(tr("empty")));table.getColumns().forEach(column->column.setText(tr(String.valueOf(column.getUserData()))));table.refresh();
    }
    private static String tr(String key){return I18nService.getInstance().tr("kiz.shipment."+key);}
    private record ImportPreview(KizImportReader.Preview preview,KizWarehouseRepository.ImportReview review){}
    private record ImportedStock(KizWarehouseRepository.Imported result,List<ShipmentKizRepository.CategoryStock> categories){}
    private record Snapshot(KizSourceSelection selection,List<ShipmentKizRepository.CategoryStock> categories){}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
}
