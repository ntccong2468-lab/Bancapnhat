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
    private final Button add=new Button(),verify=new Button(),refresh=new Button(),archive=new Button(),restore=new Button();
    private final CheckBox automatic=new CheckBox();
    private final TableView<KizWarehouseRepository.Summary> table=new TableView<>();
    private final KizWarehouseRepository repository=new KizWarehouseRepository();
    private final List<TableColumn<KizWarehouseRepository.Summary,?>> columns=new ArrayList<>();
    public KizWarehousePane(){
        add.getStyleClass().add("btn-primary");verify.getStyleClass().add("btn-primary");setPadding(new Insets(12));automatic.setWrapText(true);hint.setWrapText(true);status.setWrapText(true);hint.getStyleClass().add("text-muted");
        indicator.setMaxSize(24,24);details.setWrapText(true);setBottom(details);
        var actions=new FlowPane(8,8,add,verify,refresh,archive,restore,indicator);var top=new VBox(10,hint,automatic,actions,status);setTop(top);BorderPane.setMargin(top,new Insets(0,0,10,0));
        table.setFixedCellSize(36);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);table.setPlaceholder(new Label(tr("empty")));setCenter(table);
        column("gtin",KizWarehouseRepository.Summary::gtin);column("total",r->r.total());column("ready",r->r.ready());column("held",r->r.held());column("consumed",r->r.consumed());column("unverified",r->r.unverified());
        add.setId("kiz-warehouse-import");verify.setId("kiz-warehouse-verify");automatic.setId("kiz-warehouse-auto-purchase");table.setId("kiz-warehouse-table");
        archive.setOnAction(e->archive(false));restore.setOnAction(e->archive(true));table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->buttons());
        add.setOnAction(e->chooseFile());verify.setOnAction(e->verifyStock());refresh.setOnAction(e->reload());
        automatic.setOnAction(e->{if(rendering||shop==null||busy)return;boolean enabled=automatic.isSelected();Shop owner=shop;run(()->{repository.autoPurchase(owner,enabled);return enabled;},result->status.setText(tr(result?"auto_on":"auto_off")));});
        applyTranslations();buttons();
    }
    private void column(String key,java.util.function.Function<KizWarehouseRepository.Summary,Object> value){
        var column=new TableColumn<KizWarehouseRepository.Summary,String>();column.setUserData(key);column.setCellValueFactory(c->new javafx.beans.property.ReadOnlyStringWrapper(String.valueOf(value.apply(c.getValue()))));columns.add(column);table.getColumns().add(column);
    }
    public void applyTranslations(){if(table.getPlaceholder() instanceof Label label)label.setText(tr("empty"));hint.setText(tr("hint"));automatic.setText(tr("automatic"));add.setText(tr("import"));verify.setText(tr("verify"));refresh.setText(tr("refresh"));archive.setText(tr("archive"));restore.setText(tr("restore"));columns.forEach(c->c.setText(tr(String.valueOf(c.getUserData()))));}
    public void setShop(Shop selected){generation++;busy=false;shop=selected==null?null:new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());table.getItems().clear();automatic.setSelected(false);status.setText("");details.setText("");buttons();}
    public void reload(){if(shop==null||busy)return;Shop owner=shop;run(()->new Snapshot(repository.summaries(owner),repository.autoPurchase(owner),repository.issues(owner)),data->{table.getItems().setAll(data.rows());rendering=true;automatic.setSelected(data.automatic());rendering=false;details.setText(data.issues().entrySet().stream().map(e->e.getValue()+": "+KizErrorMessages.message(e.getKey())).collect(java.util.stream.Collectors.joining("\n")));});}
    private void chooseFile(){
        if(shop==null||busy)return;var chooser=new FileChooser();chooser.setTitle(tr("import"));chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("KIZ", "*.txt","*.csv","*.json","*.xlsx","*.xls","*.pdf","*.png","*.jpg","*.jpeg","*.bmp","*.gif","*.tif","*.tiff","*.tsv","*.docx","*.rtf","*.md","*.html","*.htm","*.xml","*.zip"),new FileChooser.ExtensionFilter("All files","*.*"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;Shop owner=shop;
        run(()->KizImportReader.read(file.toPath()),preview->{
            if(!preview.valid()){
                String issues=preview.issues().stream().limit(20).map(i->tr("row")+" "+i.row()+": "+KizErrorMessages.message(i.code())).collect(java.util.stream.Collectors.joining("\n"));
                AlertService.showError(tr("invalid")+"\n"+issues);return;
            }
            long shortCodes=preview.marks().stream().filter(m->!m.fullDataMatrix()).count();
            String counts=MessageFormat.format(tr("formats"),shortCodes,preview.marks().size()-shortCodes)+"\n"+preview.counts().entrySet().stream().limit(100).map(e->e.getKey()+": "+e.getValue()).collect(java.util.stream.Collectors.joining("\n"));
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,MessageFormat.format(tr("confirm"),preview.marks().size(),preview.duplicates())+"\n\n"+counts,ButtonType.YES,ButtonType.NO);AlertService.applyTheme(confirm);confirm.setHeaderText(owner.getName());if(getScene()!=null)confirm.initOwner(getScene().getWindow());
            if(confirm.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES||shop!=owner)return;
            run(()->new KizWarehouseService().importFile(owner,preview,true),result->{status.setText(MessageFormat.format(tr("imported"),result.inserted(),result.duplicates()));reload();});
        });
    }
    private void verifyStock(){if(shop==null||busy)return;Shop owner=shop;run(()->new KizWarehouseService().verify(owner,n->{}),ready->{status.setText(MessageFormat.format(tr("verified"),ready));reload();});}
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
    private void buttons(){archive.setDisable(shop==null||busy||table.getSelectionModel().getSelectedItem()==null);restore.setDisable(shop==null||busy||table.getSelectionModel().getSelectedItem()==null);indicator.setVisible(busy);indicator.setManaged(busy);add.setDisable(shop==null||busy);verify.setDisable(shop==null||busy);refresh.setDisable(shop==null||busy);automatic.setDisable(shop==null||busy);}
    private static String tr(String key){return I18nService.getInstance().tr("kiz.warehouse."+key);}
    private record Snapshot(List<KizWarehouseRepository.Summary> rows,boolean automatic,Map<String,Integer> issues){}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
}
