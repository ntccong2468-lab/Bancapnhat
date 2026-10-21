package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.ZnackErrorDetails;
import com.vncode.app.integration.znack.registration.CatalogErrorRepairService.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.*;

/** Report → Edit → one bulk proposal table → Apply closes and runs beneath the source table. */
final class CatalogErrorRepairDialog {
    private static final String BUSY="catalogErrorRepairBusy";
    static void open(StackPane host,Shop shop,Set<ProductKey> selected,boolean all,Runnable refresh){
        if(Boolean.TRUE.equals(host.getProperties().get(BUSY)))return;
        host.getProperties().put(BUSY,true);var service=new CatalogErrorRepairService();
        work(()->service.report(shop,selected,all),cards->{
            host.getProperties().remove(BUSY);
            var choices=report(host, cards);if(choices.isEmpty())return;
            host.getProperties().put(BUSY,true);
            work(()->{
                var prepared=new ArrayList<Proposal>();var blocked=new ArrayList<String>();
                for(var entry:choices.entrySet())try{prepared.add(service.prepare(shop,entry.getKey(),entry.getValue()));}catch(Exception error){blocked.add(entry.getKey().article()+" · "+entry.getKey().gtin()+" · "+FriendlyErrorService.format(error));}
                return new Prepared(prepared,blocked);
            },prepared->{host.getProperties().remove(BUSY);var approved=proposals(host,prepared);if(approved==null)return;start(host,shop,service,prepared.proposals(),approved,refresh);},error->{host.getProperties().remove(BUSY);failure(error);});
        },error->{host.getProperties().remove(BUSY);failure(error);});
    }
    record Prepared(List<Proposal> proposals,List<String> blocked){}
    static Map<ErrorCard,ProductKey> report(StackPane host,List<ErrorCard> cards){
        var dialog=new Dialog<Map<ErrorCard,ProductKey>>();owner(dialog,host);dialog.setTitle(tr("repair.report"));dialog.setHeaderText(tr("repair.report_hint")+" · "+cards.size());dialog.getDialogPane().setId("catalogErrorReportDialog");
        var table=new TableView<ErrorCard>();table.setId("catalogErrorReportTable");table.getItems().setAll(cards);table.setPrefHeight(400);
        var included=new HashMap<ErrorCard,Boolean>();var selected=new LinkedHashMap<ErrorCard,ProductKey>();
        for(var card:cards){included.put(card,!card.candidates().isEmpty());if(card.candidates().size()==1)selected.put(card,card.candidates().getFirst().key());}
        var use=new TableColumn<ErrorCard,ErrorCard>("✓");use.setPrefWidth(50);use.setCellValueFactory(v->new ReadOnlyObjectWrapper<>(v.getValue()));use.setCellFactory(c->new TableCell<>(){protected void updateItem(ErrorCard card,boolean empty){super.updateItem(card,empty);if(empty||card==null){setGraphic(null);return;}var check=new CheckBox();check.setSelected(included.get(card));check.setDisable(card.candidates().isEmpty());check.setOnAction(e->included.put(card,check.isSelected()));setGraphic(check);}});table.getColumns().add(use);
        column(table,"GTIN",ErrorCard::gtin,135);column(table,"Article",ErrorCard::article,150);column(table,tr("repair.old_variant"),c->c.color()+" · "+c.size(),145);
        var product=new TableColumn<ErrorCard,ErrorCard>(tr("repair.market_variant"));product.setPrefWidth(300);product.setCellValueFactory(v->new ReadOnlyObjectWrapper<>(v.getValue()));product.setCellFactory(c->new TableCell<>(){protected void updateItem(ErrorCard card,boolean empty){super.updateItem(card,empty);if(empty||card==null){setGraphic(null);return;}if(card.candidates().isEmpty()){var label=new Label(tr("repair.no_match"));label.setWrapText(true);setGraphic(label);return;}var box=new ComboBox<ProductRow>();box.setId("catalogErrorVariant");box.setMaxWidth(Double.MAX_VALUE);box.getItems().setAll(card.candidates());box.setPromptText(tr("repair.choose_variant"));box.setConverter(new javafx.util.StringConverter<>(){public String toString(ProductRow p){return p==null?"":p.vendorArticle()+" · "+p.color()+" · "+p.size()+" / "+p.russianSize()+" · "+p.key().variantId();}public ProductRow fromString(String ignored){throw new UnsupportedOperationException();}});box.setValue(card.candidates().stream().filter(p->p.key().equals(selected.get(card))).findFirst().orElse(null));box.valueProperty().addListener((o,a,b)->{if(b!=null)selected.put(card,b.key());});setGraphic(box);}});table.getColumns().add(product);
        column(table,tr("repair.reason"),c->c.reason().isBlank()||c.reason().equals("CATALOG_CARD_ERRORS")?tr("repair.catalog_rejected"):RegistrationDiagnostics.reason(c.reason()),210);
        var message=new Label(tr("repair.report_note"));message.setWrapText(true);var content=new VBox(10,table,message);VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1040,560);
        var edit=new ButtonType(tr("repair.edit"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(edit,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));
        var button=(Button)dialog.getDialogPane().lookupButton(edit);button.setId("catalogErrorEdit");button.setDisable(cards.isEmpty());button.addEventFilter(javafx.event.ActionEvent.ACTION,e->{if(cards.stream().noneMatch(c->included.get(c))||cards.stream().anyMatch(c->included.get(c)&&!selected.containsKey(c))){e.consume();message.setText(tr("repair.choose_variant"));}});
        dialog.setResultConverter(b->{if(b!=edit)return Map.of();var result=new LinkedHashMap<ErrorCard,ProductKey>();cards.stream().filter(c->included.get(c)).forEach(c->result.put(c,selected.get(c)));return result;});theme(dialog);return dialog.showAndWait().orElse(Map.of());
    }
    private record Field(Proposal proposal,long id,String name,String before,List<String> options,List<String> types,boolean locked){}
    static List<Draft> proposals(StackPane host,Prepared prepared){
        var dialog=new Dialog<List<Draft>>();owner(dialog,host);dialog.setTitle(tr("repair.proposals"));dialog.setHeaderText(tr("repair.proposals_hint"));dialog.getDialogPane().setId("catalogErrorProposalDialog");
        var fields=new ArrayList<Field>();var values=new HashMap<Field,String>();var types=new HashMap<Field,String>();
        for(var p:prepared.proposals()){
            var active=RegistrationAttributeRules.required(p.required(),p.draft().attributes());var ids=new LinkedHashSet<Long>(List.of(2478L,2504L,13914L,13933L,35L,36L,2483L,14013L));active.forEach(a->ids.add(a.id()));ids.removeAll(Set.of(3959L,23557L,23561L,23765L));
            for(long id:ids){var a=active.stream().filter(s->s.id()==id).findFirst().orElse(null);var f=new Field(p,id,a==null?fieldName(id):a.name(),p.before().getOrDefault(id,""),a==null?List.of():a.presets(),a==null?List.of():a.valueTypes(),Set.of(13914L,13933L,35L,36L).contains(id));fields.add(f);values.put(f,p.draft().attributes().getOrDefault(id,""));types.put(f,p.draft().attributeTypes().getOrDefault(id,""));}
            var selected=p.documents().size()==1?p.documents().getFirst():null;
            var prior=p.before().getOrDefault(23557L,p.before().getOrDefault(23561L,"")).split(":::",2);
            var docType=new Field(p,-1,tr("znack.registration.document_type"),p.before().containsKey(23561L)?"Сертификат":"Декларация",List.of("Декларация","Сертификат"),List.of(),false);
            var docNumber=new Field(p,-2,tr("znack.registration.document_number"),prior[0],p.documents().stream().map(d->d.number()).distinct().toList(),List.of(),false);
            var docDate=new Field(p,-3,tr("znack.registration.document_date"),prior.length==2?prior[1]:"",List.of(),List.of(),false);
            fields.addAll(List.of(docType,docNumber,docDate));values.put(docType,selected==null?"":selected.type().equals("CONFORMITY_CERTIFICATE")?"Сертификат":"Декларация");values.put(docNumber,selected==null?"":selected.number());values.put(docDate,selected==null?"":selected.date());
        }
        var table=new TableView<Field>();table.setId("catalogErrorProposalTable");table.getItems().setAll(fields);table.setPrefHeight(400);column(table,"Article / GTIN",f->f.proposal().card().article()+"\n"+f.proposal().card().gtin(),180);column(table,tr("repair.field"),Field::name,170);column(table,tr("edit.before"),Field::before,220);
        var after=new TableColumn<Field,Field>(tr("repair.suggestion"));after.setPrefWidth(330);after.setCellValueFactory(v->new ReadOnlyObjectWrapper<>(v.getValue()));after.setCellFactory(c->new TableCell<>(){protected void updateItem(Field f,boolean empty){super.updateItem(f,empty);if(empty||f==null){setGraphic(null);return;}if(f.locked()){var label=new Label(values.get(f));label.setWrapText(true);setGraphic(label);return;}var box=new ComboBox<String>();box.setId("catalogRepairValue");box.setMaxWidth(Double.MAX_VALUE);box.setEditable(f.id()==-2||f.id()==-3||f.id()>0&&f.options().isEmpty());box.getItems().setAll(f.options());box.setValue(values.get(f));box.getEditor().textProperty().addListener((o,a,b)->{if(box.isEditable())values.put(f,b);});box.valueProperty().addListener((o,a,b)->{if(b!=null){values.put(f,b);if(f.id()==-2){var matches=f.proposal().documents().stream().filter(d->d.number().equals(b)).toList();if(matches.size()==1){var doc=matches.getFirst();for(var other:fields)if(other.proposal()==f.proposal()){if(other.id()==-3)values.put(other,doc.date());if(other.id()==-1)values.put(other,doc.type().equals("CONFORMITY_CERTIFICATE")?"Сертификат":"Декларация");}table.refresh();}}}});setGraphic(box);}});table.getColumns().add(after);
        var type=new TableColumn<Field,Field>(tr("repair.value_type"));type.setPrefWidth(170);type.setCellValueFactory(v->new ReadOnlyObjectWrapper<>(v.getValue()));type.setCellFactory(c->new TableCell<>(){protected void updateItem(Field f,boolean empty){super.updateItem(f,empty);if(empty||f==null||f.types().isEmpty()){setGraphic(null);return;}var box=new ComboBox<String>();box.getItems().setAll(f.types());box.setValue(types.get(f));box.valueProperty().addListener((o,a,b)->types.put(f,b));setGraphic(box);}});table.getColumns().add(type);
        var warnings=new ArrayList<>(prepared.blocked());for(var p:prepared.proposals())if(!p.issues().isEmpty())warnings.add(p.card().article()+" · "+p.card().gtin()+" · "+String.join("; ",p.issues()));var message=new Label(String.join("\n",warnings));message.setWrapText(true);message.setMaxHeight(130);var notes=new ScrollPane(message);notes.setFitToWidth(true);notes.setMaxHeight(140);notes.setVisible(!warnings.isEmpty());notes.setManaged(notes.isVisible());var content=new VBox(10,table,notes);VBox.setVgrow(table,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(1120,620);
        var apply=new ButtonType(tr("common.apply"),ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().setAll(apply,new ButtonType(tr("common.cancel"),ButtonBar.ButtonData.CANCEL_CLOSE));var button=(Button)dialog.getDialogPane().lookupButton(apply);button.setId("catalogRepairApply");button.getStyleClass().add("btn-primary");button.setDisable(fields.isEmpty());
        var result=new ArrayList<Draft>();button.addEventFilter(javafx.event.ActionEvent.ACTION,e->{try{result.clear();for(var p:prepared.proposals()){
            var attrs=new LinkedHashMap<>(p.draft().attributes());var attrTypes=new LinkedHashMap<>(p.draft().attributeTypes());attrs.remove(23557L);attrs.remove(23561L);
            for(var f:fields)if(f.proposal()==p){String value=Objects.requireNonNullElse(values.get(f),"").strip();if(f.id()>0){attrs.put(f.id(),value);attrTypes.put(f.id(),Objects.requireNonNullElse(types.get(f),""));}}
            String docType=fields.stream().filter(f->f.proposal()==p&&f.id()==-1).map(values::get).findFirst().orElse("");String docNumber=fields.stream().filter(f->f.proposal()==p&&f.id()==-2).map(values::get).findFirst().orElse("").strip();String date=fields.stream().filter(f->f.proposal()==p&&f.id()==-3).map(values::get).findFirst().orElse("").strip();
            if(!Set.of("Декларация","Сертификат").contains(docType)||docNumber.isBlank()||date.isBlank())throw new IllegalArgumentException(tr("repair.choose_document"));
            if(date.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}"))date=java.time.LocalDate.parse(date,java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString();
            attrs.put(docType.equals("Сертификат")?23561L:23557L,docNumber+":::"+date);
            var d=p.draft();var candidate=new Draft(d.tnved(),d.feedTnved(),d.categoryId(),attrs.get(2478L),attrs.get(2504L),attrs,attrTypes,false,d.isKit());
            String russianSize=p.snapshot().variants().stream().filter(v->v.id().equals(p.snapshot().key().variantId())).map(com.vncode.app.features.products.ProductVariant::russianSize).findFirst().orElse("");
            var completed=RegistrationDraftCompletionDialog.show(dialog.getDialogPane().getScene().getWindow(),candidate,p.required(),russianSize);if(completed==null){e.consume();return;}
            result.add(CatalogErrorRepairService.validate(p,completed));
        }}catch(RuntimeException invalid){e.consume();message.setText(FriendlyErrorService.format(invalid));notes.setVisible(true);notes.setManaged(true);}});
        dialog.setResultConverter(b->b==apply?List.copyOf(result):null);theme(dialog);return dialog.showAndWait().orElse(null);
    }
    private record Completed(String run,Map<String,Long> counts){}
    private static void start(StackPane host,Shop shop,CatalogErrorRepairService service,List<Proposal> proposals,List<Draft> drafts,Runnable refresh){
        host.getProperties().put(BUSY,true);var text=new Label(tr("repair.applying"));text.setWrapText(true);var spinner=new ProgressIndicator();spinner.setPrefSize(18,18);var pill=new HBox(10,spinner,text);pill.setAlignment(Pos.CENTER);pill.setId("catalogRepairPill");pill.getStyleClass().add("task-status-pill");pill.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);StackPane.setAlignment(pill,Pos.BOTTOM_CENTER);StackPane.setMargin(pill,new Insets(12));host.getChildren().removeIf(n->"catalogRepairPill".equals(n.getId()));host.getChildren().add(pill);
        Task<Completed> task=new Task<>(){protected Completed call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){String run=service.apply(shop,proposals,drafts,p->updateMessage(tr("repair.applying")+" "+p.completed()+"/"+p.total()+" · "+p.gtin()+" · "+tr("znack.registration.status."+p.status().name().toLowerCase(Locale.ROOT))));return new Completed(run,new com.vncode.app.features.sync.SyncRunRepository().counts(run));}}};text.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e->{var result=task.getValue();complete(host,pill,text,spinner,result.counts(),()->RegistrationSyncReportDialog.show(host.getScene()==null?null:host.getScene().getWindow(),shop,result.run()),refresh,javafx.util.Duration.seconds(8));});
        Consumer<Throwable> failed=error->{host.getProperties().remove(BUSY);text.textProperty().unbind();text.setText(FriendlyErrorService.format(error));pill.getChildren().remove(spinner);RegistrationStatusPill.dismissAfter(host,pill,javafx.util.Duration.seconds(15));refresh.run();};task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.executeCatalog(task,failed);
    }
    static void complete(StackPane host,HBox pill,Label text,ProgressIndicator spinner,Map<String,Long> counts,Runnable openReport,Runnable refresh,javafx.util.Duration dismiss){
        host.getProperties().remove(BUSY);text.textProperty().unbind();
        text.setText(java.text.MessageFormat.format(tr("repair.complete_counts"),counts.getOrDefault("SUCCEEDED",0L),counts.getOrDefault("WAITING_REMOTE",0L),counts.getOrDefault("NEEDS_ACTION",0L)));
        pill.getChildren().remove(spinner);var report=new Button(tr("repair.report"));report.setId("catalogRepairResultReport");report.setOnAction(event->openReport.run());pill.getChildren().add(report);
        RegistrationStatusPill.dismissAfter(host,pill,dismiss);refresh.run();
    }
    private static <T>void work(java.util.concurrent.Callable<T> work,Consumer<T> done,Consumer<Throwable> failed){var task=new Task<T>(){protected T call()throws Exception{try(var lane=MarketplaceRequestBudget.lane(true)){return work.call();}}};task.setOnSucceeded(e->done.accept(task.getValue()));task.setOnFailed(e->failed.accept(task.getException()));AppTaskExecutor.executeCatalog(task,failed);}
    private static <T>void column(TableView<T> table,String name,Function<T,String> text,double width){var col=new TableColumn<T,String>(name);col.setPrefWidth(width);col.setCellValueFactory(v->new ReadOnlyStringWrapper(text.apply(v.getValue())));RegistrationTextCells.wrap(col);table.getColumns().add(col);}
    private static String fieldName(long id){return switch((int)id){case 2478->tr("fbo.column.name");case 2504->tr("products.edit_brand");case 13914->"Article";case 13933->"TN VED";case 35->tr("fbo.column.size");case 36->tr("fbo.column.color");case 2483->tr("products.edit_material");case 14013->tr("products.edit_gender");default->Long.toString(id);};}
    private static void owner(Dialog<?> dialog,StackPane host){if(host.getScene()!=null)dialog.initOwner(host.getScene().getWindow());}
    private static void theme(Dialog<?> dialog){DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    private static void failure(Throwable error){AlertService.showDetailedError(FriendlyErrorService.format(error),ZnackErrorDetails.format(error));}
}
