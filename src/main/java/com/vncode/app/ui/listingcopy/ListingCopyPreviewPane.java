package com.vncode.app.ui.listingcopy;

import com.google.gson.*;
import com.vncode.app.features.listingcopy.*;
import com.vncode.app.features.products.ProductVariant;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.shared.I18nService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.util.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** A read-only view of the actual prepared destination payload, rather than editable source controls. */
final class ListingCopyPreviewPane extends VBox {
    private record Row(String source,String article,String barcode,String price){}
    ListingCopyPreviewPane(CopyJob job){
        super(8);setId("copyPreparedData");
        if(job.preview()==null)return;
        var preview=job.preview();var source=job.source();var options=preview.options();
        var selected=source.variants().stream().filter(v->options.variantIds().contains(v.id())).toList();
        var rows=new ArrayList<Row>();var attributes=new VBox(6);JsonObject listing=new JsonObject();
        try{
            var payload=JsonParser.parseString(preview.requestJson());
            if(job.marketplace()==Marketplace.WILDBERRIES&&payload.isJsonArray()&&!payload.getAsJsonArray().isEmpty()){
                var root=object(payload.getAsJsonArray().get(0));var variants=array(root,"variants");listing=variants.isEmpty()?root:object(variants.get(0));
                var sizes=array(listing,"sizes");for(int i=0;i<selected.size()&&i<sizes.size();i++){
                    var size=object(sizes.get(i));var variant=selected.get(i);
                    rows.add(new Row(origin(variant),text(listing,"vendorCode")+" · "+text(size,"techSize")+" / "+text(size,"wbSize"),String.join(", ",strings(array(size,"skus"))),text(size,"price")+" "+options.currency()));
                }
                for(var value:array(listing,"characteristics")){var attribute=object(value);addAttribute(attributes,job,text(attribute,"id"),attribute.get("value"));}
            }else if(payload.isJsonObject()){
                var items=array(payload.getAsJsonObject(),"items");if(!items.isEmpty())listing=object(items.get(0));
                for(int i=0;i<items.size();i++){
                    var item=object(items.get(i));var variant=i<selected.size()?selected.get(i):null;
                    rows.add(new Row(variant==null?"":origin(variant),text(item,"offer_id"),text(item,"barcode"),text(item,"price")+" "+text(item,"currency_code")));
                    var variantData=new VBox(4);variantData.getChildren().add(wrapped(text(item,"offer_id")));
                    for(var value:array(item,"attributes")){var attribute=object(value);addAttribute(variantData,job,text(attribute,"id"),attribute.get("values"));}
                    int group=0;for(var value:array(item,"complex_attributes")){
                        variantData.getChildren().add(wrapped(tr("complex_group")+" "+(++group)));
                        for(var nested:array(object(value),"attributes")){var attribute=object(nested);addAttribute(variantData,job,text(attribute,"id"),attribute.get("values"));}
                    }
                    attributes.getChildren().add(variantData);
                }
            }
        }catch(RuntimeException invalid){getChildren().add(wrapped(tr("preview_data_unavailable")));}
        String title=text(listing,job.marketplace()==Marketplace.OZON?"name":"title");
        getChildren().add(wrapped(tr("field.title")+": "+(title.isBlank()?source.title():title)));
        if(job.marketplace()==Marketplace.WILDBERRIES){getChildren().add(wrapped(tr("field.brand")+": "+text(listing,"brand")));getChildren().add(wrapped(tr("field.description")+": "+text(listing,"description")));}
        getChildren().add(wrapped(tr("category")+": "+preview.diff().getOrDefault("category_name",options.targetCategoryId())+" · "+tr("field.images")+": "+JsonParser.parseString(preview.mediaJson()).getAsJsonArray().size()+" · "+tr("documents")+": "+options.documents().size()));
        if(!rows.isEmpty()){
            var table=new TableView<Row>();table.setId("copyPreviewVariants");table.getItems().setAll(rows);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);table.setFixedCellSize(34);table.setPrefHeight(Math.min(230,rows.size()*34+38));
            column(table,"source_variant",Row::source);column(table,"new_article",Row::article);column(table,"field.gtin",Row::barcode);column(table,"variant_price",Row::price);
            getChildren().add(table);
        }
        var values=new TitledPane(tr("prepared_attributes"),attributes);values.setAnimated(false);values.setExpanded(false);getChildren().add(values);
        var dimensions=listing.get(job.marketplace()==Marketplace.WILDBERRIES?"dimensions":"depth");
        if(dimensions!=null){String value=job.marketplace()==Marketplace.WILDBERRIES?text(object(dimensions),"length")+" × "+text(object(dimensions),"width")+" × "+text(object(dimensions),"height")+" cm · "+text(object(dimensions),"weightBrutto")+" kg":text(listing,"depth")+" × "+text(listing,"width")+" × "+text(listing,"height")+" "+text(listing,"dimension_unit")+" · "+text(listing,"weight")+" "+text(listing,"weight_unit");getChildren().add(wrapped(tr("field.dimensions")+": "+value));}
    }
    private static String origin(ProductVariant variant){return variant.id()+" · "+Objects.toString(variant.color(),"")+" · "+Objects.toString(variant.size(),"")+" / "+Objects.toString(variant.russianSize(),"");}
    private static void column(TableView<Row> table,String key,java.util.function.Function<Row,String> value){var column=new TableColumn<Row,String>(tr(key));column.setCellValueFactory(cell->new ReadOnlyStringWrapper(value.apply(cell.getValue())));table.getColumns().add(column);}
    private static void addAttribute(VBox into,CopyJob job,String id,JsonElement value){
        String name=job.source().attributes().stream().filter(a->id.equals(job.preview().options().attributeMapping().get(a.mappingKey()))).map(a->a.name()).filter(n->n!=null&&!n.isBlank()).findFirst().orElse(id);
        name=job.preview().diff().getOrDefault("attribute."+id,name);
        if(id.equals(job.preview().options().attributeMapping().get("@brand")))name=tr("field.brand");if(id.equals(job.preview().options().attributeMapping().get("@description")))name=tr("field.description");
        var text=new ArrayList<String>();if(value!=null&&value.isJsonArray())for(var entry:value.getAsJsonArray())text.add(entry.isJsonObject()?text(entry.getAsJsonObject(),"value"):entry.getAsString());else if(value!=null&&!value.isJsonNull())text.add(value.getAsString());
        into.getChildren().add(wrapped(name+": "+String.join(" | ",text)));
    }
    private static Label wrapped(String text){var label=new Label(text);label.setWrapText(true);return label;}
    private static String tr(String key){return I18nService.getInstance().tr("copy."+key);}
}
