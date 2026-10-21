package com.vncode.app.ui.tnved;

import com.vncode.app.features.tnved.*;
import com.vncode.app.features.tnved.TnvedModels.Node;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.FriendlyErrorService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Offline catalog view. Browsing and importing never writes to a marketplace. */
public final class TnvedPane extends BorderPane {
    private final TnvedCatalog catalog;
    private final TreeView<Node> tree=new TreeView<>();
    private final TableView<Node> results=new TableView<>();
    private final TextField query=new TextField();
    private final TextArea detail=new TextArea();
    private final Label status=new Label();
    private final Button more=new Button(tr("products.more")),importButton=new Button(tr("tnved.import")),backupButton=new Button(tr("tnved.backup"));
    private final CheckBox activeOnly=new CheckBox(tr("tnved.active"));
    private int offset;
    private long searchEpoch,treeEpoch;
    private boolean writing;
    private Node displayedNode;private Consumer<Node> onSelect;
    private final Button useSelected=new Button();
    public void setOnSelect(Consumer<Node> callback){onSelect=callback;updateSelectionButton();}
    private static String tr(String key){return com.vncode.app.shared.I18nService.getInstance().tr(key);}
    private void updateSelectionButton(){useSelected.setVisible(onSelect!=null);useSelected.setManaged(onSelect!=null);var n=displayedNode;var today=LocalDate.now();useSelected.setDisable(n==null||!n.leaf()||!n.code().matches("[0-9]{10}")||!n.active()||n.validFrom()==null||n.validFrom().isAfter(today)||(n.validTo()!=null&&n.validTo().isBefore(today)));}
    private String submittedQuery="";
    private LocalDate submittedDate;
    public TnvedPane(TnvedCatalog catalog) {
        this.catalog=catalog;setPadding(new Insets(16));
        var title=new Label(tr("tnved.title"));title.getStyleClass().add("h2");
        var header=new HBox(12,title,importButton,backupButton);header.setPadding(new Insets(0,0,12,0));setTop(new VBox(8,header,status));
        tree.setShowRoot(false);tree.setMinWidth(160);
        tree.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&b.getValue()!=null)showDetail(b.getValue());});
        query.setPromptText(tr("tnved.search"));
        var searchButton=new Button(tr("tnved.search_button"));searchButton.setOnAction(e->search(true));query.setOnAction(e->search(true));
        activeOnly.setSelected(true);activeOnly.setOnAction(e->search(true));
        for(String heading:List.of("Mã","Tên Nga","Tên Việt","Hiệu lực","Nguồn")) {
            var column=new TableColumn<Node,String>(tr(switch(heading){case "Mã"->"tnved.code";case "Tên Nga"->"tnved.name_ru";case "Tên Việt"->"tnved.name_vi";case "Hiệu lực"->"tnved.validity";default->"tnved.source";}));
            column.setCellValueFactory(row->new ReadOnlyStringWrapper(switch(heading){
                case "Mã"->row.getValue().code();case "Tên Nga"->row.getValue().nameRu();case "Tên Việt"->row.getValue().nameVi();
                case "Hiệu lực"->(row.getValue().validFrom()==null?tr("tnved.date_unknown"):row.getValue().validFrom().toString())+(row.getValue().validTo()==null?"":" — "+row.getValue().validTo());
                default->row.getValue().sourceUrl();}));column.setPrefWidth(heading.equals("Mã")?110:200);results.getColumns().add(column);
        }
        results.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null)showDetail(b);});results.setPlaceholder(new Label(emptyBranchText()));
        var center=new VBox(10,new HBox(8,query,searchButton),activeOnly,results,more);HBox.setHgrow(query,Priority.ALWAYS);VBox.setVgrow(results,Priority.ALWAYS);
        detail.setEditable(false);detail.setWrapText(true);detail.setPromptText(tr("tnved.select_hint"));
        var copy=new Button(tr("tnved.copy"));copy.setOnAction(e->{if(displayedNode!=null){var content=new ClipboardContent();content.putString(displayedNode.code());Clipboard.getSystemClipboard().setContent(content);}});
        useSelected.setText(tr("tnved.use_selected"));useSelected.setOnAction(e->{if(onSelect!=null&&!useSelected.isDisabled())onSelect.accept(displayedNode);});updateSelectionButton();
        var right=new VBox(10,new Label(tr("tnved.detail_hint")),detail,new FlowPane(8,8,copy,useSelected));VBox.setVgrow(detail,Priority.ALWAYS);
        var split=new SplitPane(tree,center,right);split.setDividerPositions(.23,.70);
        var reference=new Tab(tr("tnved.reference_tab"),new FashionTnvedBrowser());var imported=new Tab(tr("tnved.imported_tab"),split);var tabs=new TabPane(reference,imported);tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);setCenter(tabs);
        Runnable controls=()->{boolean advanced=tabs.getSelectionModel().getSelectedItem()==imported;for(var node:List.of(importButton,backupButton,status)){node.setVisible(advanced);node.setManaged(advanced);}};
        tabs.getSelectionModel().selectedItemProperty().addListener((o,a,b)->controls.run());controls.run();
        widthProperty().addListener((o,a,b)->split.setOrientation(b.doubleValue()>0&&b.doubleValue()<850?javafx.geometry.Orientation.VERTICAL:javafx.geometry.Orientation.HORIZONTAL));
        more.setOnAction(e->search(false));importButton.setOnAction(e->chooseImport());backupButton.setOnAction(e->backup());refresh();
    }
    public void refresh(){
        long epoch=++treeEpoch,searchAtStart=searchEpoch;
        background(()->{catalog.initialize();return catalog.children(null);},nodes->{
            if(epoch==treeEpoch){setRoots(nodes);if(searchEpoch==searchAtStart)search(true);}
        });
    }
    public void setRoots(List<Node> nodes){displayedNode=null;detail.clear();var root=new TreeItem<Node>();for(var node:nodes)root.getChildren().add(branch(node));tree.setRoot(root);status.setText(tr("tnved.root_hint"));}
    private TreeItem<Node> branch(Node node) {
        var item=new TreeItem<>(node);
        if(!node.leaf()) {
            item.getChildren().add(new TreeItem<>());final boolean[] loaded={false};
            item.expandedProperty().addListener((o,a,b)->{
                if(!b||loaded[0])return;loaded[0]=true;long epoch=treeEpoch;
                background(()->catalog.children(node.code()),children->{if(epoch!=treeEpoch)return;item.getChildren().clear();for(var child:children)item.getChildren().add(branch(child));if(children.isEmpty()){status.setText(emptyBranchText());item.setExpanded(false);}});
            });
        }
        return item;
    }
    private void search(boolean reset) {
        if(reset){offset=0;results.getItems().clear();submittedQuery=query.getText();submittedDate=activeOnly.isSelected()?LocalDate.now():null;}
        int expectedOffset=offset;long epoch=++searchEpoch;String text=submittedQuery;LocalDate date=submittedDate;more.setDisable(true);
        background(()->catalog.search(text,expectedOffset,100,date),rows->{if(epoch!=searchEpoch||offset!=expectedOffset)return;results.getItems().addAll(rows);offset+=rows.size();more.setDisable(rows.size()<100);});
    }
    private void showDetail(Node n){displayedNode=n;updateSelectionButton();detail.setText(n.code()+"\n"+n.nameRu()+"\n\n"+n.nameVi()+"\n\n"+Objects.toString(n.descriptionRu(),"")+"\n"+Objects.toString(n.notes(),"")+"\n\n"+tr("tnved.source")+": "+n.sourceUrl()+"\n"+tr("tnved.validity")+": "+(n.validFrom()==null?tr("tnved.date_unknown"):n.validFrom())+"\n\n"+tr("tnved.kiz_unknown"));}
    private void chooseImport() {
        if(writing)return;var chooser=new FileChooser();chooser.setTitle(tr("tnved.import_title"));chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("tnved.import_files"),"*.csv","*.json"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;busy(true);
        background(()->TnvedImporter.read(file.toPath()),batch->{
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,"Phiên bản: "+batch.version().label()+"\nNguồn: "+batch.version().sourceUrl()+"\n"+tr("tnved.validity")+": "+batch.version().validFrom()+"\nSố mã nhập: "+batch.nodes().size()+"\n\nBạn đã xác minh nguồn và ngày hiệu lực của danh mục này?",ButtonType.OK,ButtonType.CANCEL);com.vncode.app.shared.AlertService.applyTheme(confirm);
            if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK){busy(false);return;}
            background(()->{catalog.importVersion(batch.version(),batch.nodes(),true);return catalog.activeVersion();},version->{busy(false);refresh();status.setText(tr("tnved.imported")+" "+version);});
        });
    }
    private void backup(){if(writing)return;busy(true);background(catalog::snapshot,path->{busy(false);status.setText(tr("tnved.backed_up")+" "+path.getFileName());});}
    private void busy(boolean value){writing=value;importButton.setDisable(value);backupButton.setDisable(value);}
    private <T> void background(Callable<T> work,Consumer<T> success){var task=new Task<T>(){@Override protected T call()throws Exception{return work.call();}};task.setOnSucceeded(e->success.accept(task.getValue()));java.util.function.Consumer<Throwable> taskFailure = rejection ->{busy(false);status.setText(FriendlyErrorService.format(rejection));};
        task.setOnFailed(e -> taskFailure.accept(task.getException()));AppTaskExecutor.execute(task, taskFailure);}
    public int rootCount(){return tree.getRoot()==null?0:tree.getRoot().getChildren().size();}
    public String emptyBranchText(){return tr("tnved.empty");}
}
