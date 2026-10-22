package com.vncode.app.ui.tnved;

import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.tnved.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;
import java.util.function.Consumer;

/** Global code or a reviewed, complete per-product proposal; neither path writes a seller API. */
public final class ProductTnvedPicker {
    private ProductTnvedPicker(){}
    public record Choice(String globalCode,Map<ProductKey,List<String>> perProduct,Map<ProductKey,String> expectedRevisions){
        public Choice(String globalCode,Map<ProductKey,List<String>> perProduct){this(globalCode,perProduct,Map.of());}
        public Choice{perProduct=Map.copyOf(perProduct);expectedRevisions=Map.copyOf(expectedRevisions);}
    }
    public static void show(Window owner,Shop shop,List<ProductKey> selected,Consumer<Choice> onSelect){
        var dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);dialog.setTitle(tr("tnved.picker.title"));dialog.getDialogPane().setId("productTnvedPicker");
        var browser=new FashionTnvedBrowser();browser.useCompactPicker();var hint=new Label(java.text.MessageFormat.format(tr("tnved.picker.heading"),selected.stream().map(ProductKey::cardId).distinct().count()));hint.setWrapText(true);hint.getStyleClass().add("h3");hint.setMinHeight(46);
        var applyEach=new Button(tr("tnved.use_each"));applyEach.setId("tnvedApplyEach");applyEach.setDisable(true);var progress=new Label(tr("tnved.proposals_loading"));progress.setWrapText(true);
        var chosen=new LinkedHashMap<String,FashionTnvedCatalog.Entry>();
        Runnable updateApply=()->{boolean complete=selected.stream().map(ProductKey::cardId).distinct().allMatch(chosen::containsKey);applyEach.setDisable(!complete);browser.setDefaultApply(applyEach::fire,complete);};
        var table=new TableView<ProductTnvedAdvisor.Proposal>();table.setId("tnvedProposals");table.setPrefHeight(170);table.setMinHeight(90);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("jobs.product"),p->p.title(),240);column(table,tr("edit.before"),ProductTnvedAdvisor.Proposal::current,110);
        var proposalColumn=new TableColumn<ProductTnvedAdvisor.Proposal,ProductTnvedAdvisor.Proposal>(tr("tnved.proposal"));proposalColumn.setMinWidth(180);proposalColumn.setPrefWidth(300);proposalColumn.setCellValueFactory(v->new javafx.beans.property.ReadOnlyObjectWrapper<>(v.getValue()));
        proposalColumn.setCellFactory(v->new TableCell<>(){
            private final ComboBox<FashionTnvedCatalog.Entry> picker=new ComboBox<>();private boolean updating;
            {picker.setMinWidth(0);picker.setMaxWidth(Double.MAX_VALUE);picker.prefWidthProperty().bind(proposalColumn.widthProperty().subtract(20));
             picker.setConverter(new javafx.util.StringConverter<>(){public String toString(FashionTnvedCatalog.Entry e){return e==null?"":e.code()+" · "+(e.name().length()>55?e.name().substring(0,55)+"…":e.name());}public FashionTnvedCatalog.Entry fromString(String s){return null;}});
             picker.setPromptText(tr("tnved.choose_proposal"));picker.setOnAction(e->{var item=getItem();if(!updating&&item!=null){if(picker.getValue()==null)chosen.remove(item.key().cardId());else chosen.put(item.key().cardId(),picker.getValue());updateApply.run();}});}
            protected void updateItem(ProductTnvedAdvisor.Proposal item,boolean empty){super.updateItem(item,empty);if(empty||item==null){setGraphic(null);return;}updating=true;picker.getItems().setAll(item.candidates());picker.setValue(chosen.get(item.key().cardId()));picker.setDisable(item.candidates().isEmpty());updating=false;setGraphic(picker);}
        });table.getColumns().add(proposalColumn);
        column(table,tr("znack.registration.status"),p->tr("tnved.proposal_reason."+p.reason()),180);
        var proposals=new TitledPane(tr("tnved.proposal_title"),table);proposals.setExpanded(false);proposals.setVisible(false);proposals.setManaged(false);
        var chips=new HBox(8);chips.setId("tnvedSuggestionChips");
        var suggestions=new ScrollPane(chips);suggestions.setId("tnvedSuggestionScroll");suggestions.setFitToHeight(true);suggestions.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);suggestions.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);suggestions.setPrefViewportHeight(48);suggestions.setMinHeight(64);suggestions.setMinWidth(0);
        var suggestionHeading=new Label(tr("tnved.picker.suggestions"));suggestionHeading.getStyleClass().add("text-bold");progress.getStyleClass().add("text-muted");
        var suggestionPanel=new VBox(10,suggestionHeading,progress,suggestions);suggestionPanel.setId("tnvedSuggestionPanel");suggestionPanel.getStyleClass().add("card");suggestionPanel.setPadding(new javafx.geometry.Insets(16,18,12,18));
        var body=new VBox(12,hint,suggestionPanel,proposals,browser);body.setMinSize(0,0);VBox.setVgrow(browser,Priority.ALWAYS);dialog.getDialogPane().setContent(body);dialog.getDialogPane().setPrefSize(1205,745);dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);DialogLayout.install(dialog.getDialogPane());
        browser.setPickerLeadingAction(applyEach);AlertService.applyTheme(dialog);
        applyEach.getStyleClass().add("btn-primary");var cancel=new Button(tr("common.cancel"));cancel.setId("tnvedPickerCancel");cancel.setOnAction(e->dialog.close());browser.setPickerTrailingAction(cancel);
        var nativeCancel=dialog.getDialogPane().lookupButton(ButtonType.CANCEL);nativeCancel.setVisible(false);nativeCancel.setManaged(false);
        browser.setOnSelect(entry->{onSelect.accept(new Choice(entry.code(),Map.of()));dialog.close();});boolean[] closed={false};dialog.setOnHidden(e->closed[0]=true);
        var frozen=List.copyOf(selected);Shop captured=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        ShopReadCoordinator.shared().submitLocal(new ShopReadCoordinator.Key(shop.getId(),"tnved-proposals",UUID.randomUUID().toString()),()->new ProductTnvedReviewService().review(captured,frozen)).whenComplete((review,error)->Platform.runLater(()->{
            if(closed[0])return;
            if(error!=null){progress.setText(tr("tnved.proposals_failed"));return;}
            for(var proposal:review.proposals())if(proposal.unique())chosen.put(proposal.key().cardId(),proposal.candidates().getFirst());
            var categoryFilter=new LinkedHashSet<String>();var groups=new LinkedHashMap<String,java.util.List<ProductTnvedAdvisor.Proposal>>();
            for(var proposal:review.proposals()){
                proposal.candidates().forEach(candidate->categoryFilter.addAll(candidate.categories()));
                if(proposal.unique())groups.computeIfAbsent(proposal.candidates().getFirst().code(),k->new ArrayList<>()).add(proposal);
            }
            if(!categoryFilter.isEmpty())browser.setCategoryFilter(categoryFilter);
            for(var group:groups.entrySet()){var entry=group.getValue().getFirst().candidates().getFirst();var chip=new Button(entry.code()+" · "+(entry.name().length()>50?entry.name().substring(0,50)+"…":entry.name())+" ("+group.getValue().size()+")");chip.setTooltip(new Tooltip(entry.name()));chip.setOnAction(e->browser.selectCode(entry.code()));chips.getChildren().add(chip);}
            applyEach.setText(tr("tnved.use_each")+" ("+review.proposals().size()+")");
            table.getItems().setAll(review.proposals());progress.setText(tr("tnved.proposal_hint"));updateApply.run();
            boolean ambiguous=review.proposals().stream().anyMatch(proposal->!proposal.unique());proposals.setVisible(ambiguous);proposals.setManaged(ambiguous);proposals.setExpanded(ambiguous);
            applyEach.setOnAction(e->{if(applyEach.isDisabled())return;var values=new LinkedHashMap<ProductKey,List<String>>();for(var key:frozen)values.put(key,List.of(chosen.get(key.cardId()).code()));onSelect.accept(new Choice("",values,review.expectedRevisions()));dialog.close();});
        }));dialog.show();
    }
    private static <T> void column(TableView<T> table,String name,java.util.function.Function<T,String> value,double width){var c=new TableColumn<T,String>(name);c.setMinWidth(90);c.setPrefWidth(width);c.setCellValueFactory(v->new ReadOnlyStringWrapper(value.apply(v.getValue())));c.setCellFactory(v->new TableCell<>(){protected void updateItem(String text,boolean empty){super.updateItem(text,empty);setText(empty?null:text);setTooltip(empty||text==null?null:new Tooltip(text));}});table.getColumns().add(c);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
