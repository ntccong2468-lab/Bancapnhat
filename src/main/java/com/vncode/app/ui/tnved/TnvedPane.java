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
    private final Button more=new Button("Tải thêm"),importButton=new Button("Nhập CSV/JSON"),backupButton=new Button("Sao lưu");
    private final CheckBox activeOnly=new CheckBox("Đang hiệu lực");
    private int offset;
    private long searchEpoch,treeEpoch;
    private boolean writing;
    private Node displayedNode;
    private String submittedQuery="";
    private LocalDate submittedDate;
    public TnvedPane(TnvedCatalog catalog) {
        this.catalog=catalog;setPadding(new Insets(16));
        var title=new Label("Mã TN VED EAEU");title.getStyleClass().add("h2");
        var header=new HBox(12,title,importButton,backupButton);header.setPadding(new Insets(0,0,12,0));setTop(new VBox(8,header,status));
        tree.setShowRoot(false);tree.setMinWidth(160);
        tree.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null&&b.getValue()!=null)showDetail(b.getValue());});
        query.setPromptText("Mã số, tên tiếng Nga hoặc tiếng Việt");
        var searchButton=new Button("Tìm kiếm");searchButton.setOnAction(e->search(true));query.setOnAction(e->search(true));
        activeOnly.setSelected(true);activeOnly.setOnAction(e->search(true));
        for(String heading:List.of("Mã","Tên Nga","Tên Việt","Hiệu lực","Nguồn")) {
            var column=new TableColumn<Node,String>(heading);
            column.setCellValueFactory(row->new ReadOnlyStringWrapper(switch(heading){
                case "Mã"->row.getValue().code();case "Tên Nga"->row.getValue().nameRu();case "Tên Việt"->row.getValue().nameVi();
                case "Hiệu lực"->(row.getValue().validFrom()==null?"Chưa xác minh ngày":row.getValue().validFrom().toString())+(row.getValue().validTo()==null?"":" — "+row.getValue().validTo());
                default->row.getValue().sourceUrl();}));column.setPrefWidth(heading.equals("Mã")?110:200);results.getColumns().add(column);
        }
        results.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null)showDetail(b);});results.setPlaceholder(new Label(emptyBranchText()));
        var center=new VBox(10,new HBox(8,query,searchButton),activeOnly,results,more);HBox.setHgrow(query,Priority.ALWAYS);VBox.setVgrow(results,Priority.ALWAYS);
        detail.setEditable(false);detail.setWrapText(true);detail.setPromptText("Chọn một phần hoặc mã để xem nguồn và chú giải.");
        var copy=new Button("Sao chép mã");copy.setOnAction(e->{if(displayedNode!=null){var content=new ClipboardContent();content.putString(displayedNode.code());Clipboard.getSystemClipboard().setContent(content);}});
        var right=new VBox(10,new Label("Chi tiết — bản dịch Việt để tham khảo"),detail,copy);VBox.setVgrow(detail,Priority.ALWAYS);
        var split=new SplitPane(tree,center,right);split.setDividerPositions(.23,.70);setCenter(split);
        widthProperty().addListener((o,a,b)->split.setOrientation(b.doubleValue()>0&&b.doubleValue()<850?javafx.geometry.Orientation.VERTICAL:javafx.geometry.Orientation.HORIZONTAL));
        more.setOnAction(e->search(false));importButton.setOnAction(e->chooseImport());backupButton.setOnAction(e->backup());refresh();
    }
    public void refresh(){long epoch=++treeEpoch;background(()->{catalog.initialize();return catalog.children(null);},nodes->{if(epoch==treeEpoch){setRoots(nodes);search(true);}});}
    public void setRoots(List<Node> nodes){displayedNode=null;detail.clear();var root=new TreeItem<Node>();for(var node:nodes)root.getChildren().add(branch(node));tree.setRoot(root);status.setText("21 phần gốc; mã chi tiết cần nhập từ danh mục có nguồn xác minh.");}
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
    private void showDetail(Node n){displayedNode=n;detail.setText(n.code()+"\n"+n.nameRu()+"\n\n"+n.nameVi()+"\n\n"+Objects.toString(n.descriptionRu(),"")+"\n"+Objects.toString(n.notes(),"")+"\n\nNguồn: "+n.sourceUrl()+"\nNgày hiệu lực: "+(n.validFrom()==null?"Chưa xác minh":n.validFrom())+"\n\nKIZ: Cần xác minh — chưa đối chiếu đầy đủ quy định và dữ kiện sản phẩm.");}
    private void chooseImport() {
        if(writing)return;var chooser=new FileChooser();chooser.setTitle("Nhập danh mục TN VED có nguồn xác minh");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Danh mục CSV/JSON","*.csv","*.json"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;busy(true);
        background(()->TnvedImporter.read(file.toPath()),batch->{
            var confirm=new Alert(Alert.AlertType.CONFIRMATION,"Phiên bản: "+batch.version().label()+"\nNguồn: "+batch.version().sourceUrl()+"\nNgày hiệu lực: "+batch.version().validFrom()+"\nSố mã nhập: "+batch.nodes().size()+"\n\nBạn đã xác minh nguồn và ngày hiệu lực của danh mục này?",ButtonType.OK,ButtonType.CANCEL);com.vncode.app.shared.AlertService.applyTheme(confirm);
            if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK){busy(false);return;}
            background(()->{catalog.importVersion(batch.version(),batch.nodes(),true);return catalog.activeVersion();},version->{busy(false);refresh();status.setText("Đã nhập "+version);});
        });
    }
    private void backup(){if(writing)return;busy(true);background(catalog::snapshot,path->{busy(false);status.setText("Đã sao lưu: "+path.getFileName());});}
    private void busy(boolean value){writing=value;importButton.setDisable(value);backupButton.setDisable(value);}
    private <T> void background(Callable<T> work,Consumer<T> success){var task=new Task<T>(){@Override protected T call()throws Exception{return work.call();}};task.setOnSucceeded(e->success.accept(task.getValue()));task.setOnFailed(e->{busy(false);status.setText(FriendlyErrorService.format(task.getException()));});AppTaskExecutor.execute(task);}
    public int rootCount(){return tree.getRoot()==null?0:tree.getRoot().getChildren().size();}
    public String emptyBranchText(){return "Chưa tải dữ liệu";}
}
