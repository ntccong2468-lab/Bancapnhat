package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.RegistrationSources;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shows origins for freshly reviewed facts, never treating an origin as Catalog acceptance. */
final class RegistrationProvenanceDialog {
    record Row(String product,String field,String value,String source) {}
    static void show(Window owner,Shop selected,List<ProductKey> selection){
        if(selected==null)return;
        Shop shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        var keys=List.copyOf(new LinkedHashSet<>(selection));
        var dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);dialog.initModality(Modality.NONE);dialog.setTitle(tr("znack.provenance.title"));
        var status=new Label(keys.isEmpty()?tr("znack.provenance.choose"):tr("znack.provenance.loading"));status.setWrapText(true);
        var hint=new Label(tr("znack.provenance.hint"));hint.setWrapText(true);
        var table=new TableView<Row>();table.setId("registrationProvenanceTable");table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table,tr("products.article"),Row::product,150);column(table,tr("znack.registration.complete_missing_field"),Row::field,170);
        column(table,tr("znack.registration.complete_missing_value"),Row::value,240);column(table,tr("znack.provenance.source"),Row::source,180);
        var content=new VBox(10,hint,status,table);content.setPadding(new Insets(16));VBox.setVgrow(table,Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(900,500);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);DialogLayout.install(dialog.getDialogPane());
        var closed=new AtomicBoolean();
        Task<List<Row>> task=new Task<>(){@Override protected List<Row> call()throws Exception{
            try(var lane=MarketplaceRequestBudget.lane(true)){
                new RegistrationSources().require(shop);
                var snapshots=new RegistrationSnapshotReview().read(shop,keys);
                var review=new RegistrationDynamicPreflight().read(shop,snapshots,Map.of());var rows=new ArrayList<Row>();
                var evidenceByKey=RegistrationFieldProvenance.readBatch(shop,review.plans());
                for(var plan:review.plans()){
                    if(isCancelled())throw new InterruptedException("ZNACK_SYNC_CANCELLED");
                    var evidence=evidenceByKey.getOrDefault(plan.snapshot().key(),List.of());
                    if(evidence.isEmpty()){rows.add(new Row(plan.snapshot().vendorArticle(),tr("znack.provenance.unavailable"),"",tr("znack.provenance.source.unknown")));continue;}
                    for(var field:evidence){var source=matchesEvidence(plan,field)?field.source():RegistrationFieldProvenance.Source.UNKNOWN;rows.add(new Row(product(plan),fieldName(plan,field.field()),value(plan,field.field()),tr("znack.provenance.source."+source.name().toLowerCase(Locale.ROOT))));}
                }
                for(var block:review.blocked())rows.add(new Row(block.article(),block.field(),"",tr("znack.provenance.source.unknown")+" · "+RegistrationDiagnostics.reason(block.detail())));
                return List.copyOf(rows);
            }
        }};
        task.setOnSucceeded(event->{if(!closed.get()){table.getItems().setAll(task.getValue());status.setText(task.getValue().isEmpty()?tr("znack.provenance.unavailable"):"");}});
        java.util.function.Consumer<Throwable> failed=error->{if(!closed.get())status.setText(tr("znack.provenance.failed"));};
        task.setOnFailed(event->failed.accept(task.getException()));task.setOnCancelled(event->failed.accept(null));dialog.setOnHidden(event->{closed.set(true);task.cancel(true);});dialog.show();
        if(!keys.isEmpty())AppTaskExecutor.executeCatalog(task,error->Platform.runLater(()->failed.accept(error)));
    }
    static String product(RegistrationDynamicPreflight.Plan plan){
        return plan.snapshot().vendorArticle()+" · "+plan.snapshot().key().variantId();
    }
    private static ProductVariant variant(RegistrationDynamicPreflight.Plan plan){
        var matches=plan.snapshot().variants().stream().filter(v->plan.snapshot().key().variantId().equals(v.id())).limit(2).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    static String fieldName(RegistrationDynamicPreflight.Plan plan,String field){
        if(field.startsWith("catalog.attribute."))try{
            long id=Long.parseLong(field.substring("catalog.attribute.".length()));return plan.schema().stream().filter(a->a.id()==id).map(a->a.name()+" ["+id+"]").findFirst().orElse("#"+id);
        }catch(NumberFormatException ignored){return field;}
        return tr("znack.provenance.field."+field);
    }
    static String value(RegistrationDynamicPreflight.Plan plan,String field){
        var draft=plan.draft();var variant=variant(plan);
        if(field.startsWith("catalog.attribute."))try{long id=Long.parseLong(field.substring("catalog.attribute.".length()));return Objects.toString(draft.attributes().get(id),id==2478?draft.goodName():id==2504?draft.brand():"");}catch(NumberFormatException ignored){return "";}
        return switch(field){
            case "product.name"->draft.goodName();case "product.brand"->draft.brand();case "product.article"->variant==null?"":variant.sellerArticle();
            case "variant.color"->variant==null?"":variant.color();case "variant.size"->variant==null?"":variant.size();case "variant.russian_size"->variant==null?"":variant.russianSize();
            case "product.tnved"->draft.tnved();case "product.material"->draft.attributes().getOrDefault(2483L,"");case "product.gender"->draft.attributes().getOrDefault(14013L,"");case "product.origin"->draft.attributes().getOrDefault(2630L,"");
            case "product.document"->plan.documents()==null||plan.documents().automatic()==null?"":plan.documents().automatic().type()+" · "+plan.documents().automatic().number()+" · "+plan.documents().automatic().date();
            case "catalog.category"->Long.toString(draft.categoryId());default->"";
        };
    }
    static boolean matchesEvidence(RegistrationDynamicPreflight.Plan plan,RegistrationFieldProvenance.Evidence evidence){
        String field=evidence.field();String actual;
        if(field.equals("product.document"))actual=plan.documents()==null||plan.documents().automatic()==null?"":new com.google.gson.Gson().toJson(plan.documents().automatic());
        else actual=value(plan,field);
        String clean=Objects.toString(actual,"").strip();String expected=clean.isBlank()?"":ListingIdentity.hash(clean);
        return expected.equals(evidence.valueHash());
    }
    private static void column(TableView<Row> table,String title,java.util.function.Function<Row,String> value,double width){
        var column=new TableColumn<Row,String>(title);column.setCellValueFactory(cell->new ReadOnlyStringWrapper(Objects.toString(value.apply(cell.getValue()),"")));column.setPrefWidth(width);RegistrationTextCells.wrap(column);table.getColumns().add(column);
    }
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    private RegistrationProvenanceDialog(){}
}
