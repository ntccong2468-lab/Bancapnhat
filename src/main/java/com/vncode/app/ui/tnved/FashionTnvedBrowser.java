package com.vncode.app.ui.tnved;

import com.vncode.app.features.tnved.FashionTnvedCatalog;
import com.vncode.app.features.tnved.FashionTnvedCatalog.Entry;
import com.vncode.app.shared.I18nService;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Virtualized reference browser with a visible explicit Apply action in picker mode. */
public final class FashionTnvedBrowser extends BorderPane {
    private final FashionTnvedCatalog catalog=new FashionTnvedCatalog();
    private final TextField query=new TextField();
    private final MenuButton categories=new MenuButton();
    private final Set<String> selectedCategories=new LinkedHashSet<>();
    private final ComboBox<String> gender=new ComboBox<>(),material=new ComboBox<>(),fabric=new ComboBox<>();
    private final ListView<Entry> rows=new ListView<>();
    private final Label count=new Label();private final Button apply=new Button();
    private final Label copyStatus=new Label();
    private Consumer<Entry> onSelect;
    private Runnable defaultApply;
    private boolean defaultApplyAvailable;
    private final FlowPane pickerActions=new FlowPane(8,8);
    public FashionTnvedBrowser(){
        setId("fashionTnvedBrowser");setMinSize(0,0);setPadding(new Insets(12));
        query.setId("tnvedReferenceSearch");query.setPromptText(tr("tnved.search"));query.setPrefWidth(300);
        categories.setId("tnvedReferenceCategories");categories.setText(tr("products.categories"));
        for(String value:catalog.categories()){var item=new CheckMenuItem(value);item.setOnAction(e->{if(item.isSelected())selectedCategories.add(value);else selectedCategories.remove(value);filter();});categories.getItems().add(item);}
        configure(gender,catalog.genders(),"tnved.filter_gender");configure(material,catalog.materials(),"tnved.filter_material");configure(fabric,catalog.fabrics(),"tnved.filter_fabric");
        gender.setId("tnvedReferenceGender");material.setId("tnvedReferenceMaterial");fabric.setId("tnvedReferenceFabric");
        var reset=new Button(tr("znack.registration.clear"));reset.setOnAction(e->{query.clear();selectedCategories.clear();for(var menu:categories.getItems())((CheckMenuItem)menu).setSelected(false);gender.setValue("");material.setValue("");fabric.setValue("");filter();});
        var source=new Label(tr("tnved.reference_source"));source.setWrapText(true);source.getStyleClass().add("text-muted");
        var top=new VBox(8,source,new FlowPane(8,8,query,categories,gender,material,fabric,reset),count);top.setPadding(new Insets(0,0,10,0));setTop(top);
        rows.setId("tnvedReferenceRows");rows.setFixedCellSize(100);rows.setMinHeight(80);rows.setCellFactory(v->new ListCell<>(){
            private final Label code=new Label(),name=new Label(),tags=new Label();private final Button copy=new Button("⧉");private final VBox description=new VBox(4,name,tags);private final HBox box=new HBox(12,code,copy,description);
            {code.setMinWidth(100);code.getStyleClass().add("text-bold");name.setWrapText(true);name.setMaxHeight(48);tags.setMaxHeight(28);tags.getStyleClass().add("text-muted");description.setMinWidth(0);HBox.setHgrow(description,Priority.ALWAYS);box.setMinWidth(0);box.prefWidthProperty().bind(rows.widthProperty().subtract(30));box.setMaxWidth(Double.MAX_VALUE);name.setMinWidth(0);name.setMaxWidth(Double.MAX_VALUE);tags.setMinWidth(0);tags.setMaxWidth(Double.MAX_VALUE);box.setPadding(new Insets(10,8,10,8));copy.setMinSize(36,36);copy.setPrefSize(36,36);copy.setMaxSize(36,36);copy.setAccessibleText(tr("tnved.copy"));copy.setTooltip(new Tooltip(tr("tnved.copy")));copy.setOnAction(e->{e.consume();var item=getItem();if(item!=null)copyCode(item.code(),0);});copy.addEventFilter(MouseEvent.MOUSE_CLICKED,e->e.consume());}
            protected void updateItem(Entry entry,boolean empty){super.updateItem(entry,empty);setText(null);if(empty||entry==null){copy.setId(null);setGraphic(null);return;}copy.setId("tnvedCopy_"+entry.code());code.setText(entry.code());name.setText(entry.name());name.setTooltip(new Tooltip(entry.name()));tags.setText(String.join(", ",entry.categories())+" · "+entry.gender()+" · "+String.join(", ",entry.materials())+(entry.fabric().isBlank()?"":" · "+entry.fabric()));tags.setTooltip(new Tooltip(tags.getText()));setGraphic(box);}
        });
        rows.getSelectionModel().selectedItemProperty().addListener((o,a,b)->apply.setDisable(b==null&&!defaultApplyAvailable));rows.setOnMouseClicked(e->{if(e.getClickCount()==2)use();});setCenter(rows);
        rows.addEventFilter(KeyEvent.KEY_PRESSED,e->{if(new KeyCodeCombination(KeyCode.C,KeyCombination.SHORTCUT_DOWN).match(e)){var item=rows.getSelectionModel().getSelectedItem();if(item!=null){copyCode(item.code(),0);e.consume();}}});
        copyStatus.setId("tnvedCopyStatus");copyStatus.setWrapText(true);apply.setId("tnvedReferenceApply");apply.setText(tr("tnved.use_selected"));apply.setDisable(true);apply.setVisible(false);apply.setManaged(false);apply.setOnAction(e->use());pickerActions.getChildren().setAll(copyStatus,apply);pickerActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);pickerActions.setPadding(new Insets(10,0,0,0));setBottom(pickerActions);
        var delay=new PauseTransition(Duration.millis(180));delay.setOnFinished(e->filter());query.textProperty().addListener((o,a,b)->delay.playFromStart());filter();
    }
    private void configure(ComboBox<String> box,List<String> values,String key){box.getItems().add("");box.getItems().addAll(values);box.setValue("");box.setPrefWidth(165);box.setConverter(new javafx.util.StringConverter<>(){public String toString(String v){return v==null||v.isBlank()?tr(key):v;}public String fromString(String v){return v;}});box.valueProperty().addListener((o,a,b)->filter());}
    private void filter(){rows.getItems().setAll(catalog.search(query.getText(),selectedCategories,gender.getValue(),material.getValue(),fabric.getValue()));count.setText(rows.getItems().size()+" / "+catalog.entries().size());categories.setText(tr("products.categories")+(selectedCategories.isEmpty()?"":" ("+selectedCategories.size()+")"));}
    public void setOnSelect(Consumer<Entry> action){onSelect=action;apply.setVisible(action!=null);apply.setManaged(action!=null);}
    public void setPickerLeadingAction(javafx.scene.Node action){pickerActions.getChildren().setAll(copyStatus,action,apply);}
    public void setDefaultApply(Runnable action,boolean available){defaultApply=action;defaultApplyAvailable=available;apply.setDisable(rows.getSelectionModel().getSelectedItem()==null&&!available);}
    public void setCategoryFilter(Set<String> names){selectedCategories.clear();selectedCategories.addAll(names);for(var item:categories.getItems())if(item instanceof CheckMenuItem check)check.setSelected(names.contains(check.getText()));filter();}
    public void selectCode(String code){for(var entry:rows.getItems())if(entry.code().equals(code)){rows.getSelectionModel().select(entry);rows.scrollTo(entry);return;}}
    private void use(){var selected=rows.getSelectionModel().getSelectedItem();if(onSelect!=null&&selected!=null)onSelect.accept(selected);else if(defaultApplyAvailable&&defaultApply!=null)defaultApply.run();}
    private void copyCode(String code,int attempt){
        try{
            var content=new ClipboardContent();content.putString(code);var clipboard=Clipboard.getSystemClipboard();
            if(clipboard.setContent(content)&&code.equals(clipboard.getString())){copyStatus.setText(tr("tnved.copied")+" · "+code);return;}
        }catch(RuntimeException unavailable){/* Clipboard can be temporarily owned by Windows/RDP. */}
        if(attempt==0){javafx.application.Platform.runLater(()->copyCode(code,1));return;}
        copyStatus.setText(tr("tnved.copy_failed"));
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
