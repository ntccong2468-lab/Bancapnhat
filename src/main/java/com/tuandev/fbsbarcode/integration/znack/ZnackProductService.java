package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.Product;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

public class ZnackProductService {
    private static final int PAGE_SIZE = 10_000;
    private static final int CATALOG_BATCH_SIZE = 25;
    private final ZnackApiClient api; private final ZnackAuthService auth; private final ZnackRepository repository;
    public ZnackProductService(ZnackApiClient api,ZnackAuthService auth,ZnackRepository repository){this.api=api;this.auth=auth;this.repository=repository;}
    public List<Product> sync(ZnackModels.Settings settings)throws Exception{
        ZnackSafety.requireSigned(settings,false);
        String token=auth.trueApiToken(settings);Map<String,Product> byGtin=new LinkedHashMap<>();int page=0,fetched=0,technical=0;Integer total=null;
        do{
            JsonElement response=page==0?api.products(settings.resolvedTrueApiBaseUrl(),token):
                    api.products(settings.resolvedTrueApiBaseUrl(),token,page,PAGE_SIZE);
            JsonArray array=response.isJsonArray()?response.getAsJsonArray():response.getAsJsonObject().getAsJsonArray("results");
            if(response.isJsonObject()&&response.getAsJsonObject().has("total")&&!response.getAsJsonObject().get("total").isJsonNull())total=response.getAsJsonObject().get("total").getAsInt();
            if(array!=null)for(JsonElement e:array){JsonObject o=e.getAsJsonObject();String gtin=text(o,"gtin","productGtin");if(!gtin.isBlank()){String normalized=GtinNormalizer.normalize(gtin);if(GtinNormalizer.isTechnicalRange(normalized)){technical++;continue;}byGtin.put(normalized,new Product(
                    normalized,text(o,"productName","name"),tnVed(o),
                    null,null,null,text(o,"productionDate","production_date"),
                    bool(o,"goodMarkFlag","good_mark_flag"),bool(o,"goodTurnFlag","good_turn_flag"),
                    text(o,"goodStatus","good_status","cardStatus"),text(o,"goodDetailedStatus","good_detailed_status"),
                    "",null,cisType(o)));}}
            int received=array==null?0:array.size();fetched+=received;page++;
            if(total==null&&received<PAGE_SIZE)break;
            if(received==0)break;
        }while(total==null||fetched<total);
        Set<String> incompleteGtins=new HashSet<>();
        for(Product persisted:repository.findProducts()){
            if(needsCatalogMetadata(persisted))incompleteGtins.add(persisted.gtin());
        }
        Map<String, Product> existingProductsByGtin = new LinkedHashMap<>();
        for (Product p : repository.findProducts()) {
            existingProductsByGtin.put(p.gtin(), p);
        }
        for (Product p : repository.findDeletedProducts()) {
            existingProductsByGtin.put(p.gtin(), p);
        }
        Map<String,List<GoodsDocument>> permitDocumentSnapshots=new LinkedHashMap<>();
        Map<String,ZnackKizLabelMetadata> labelMetadataSnapshots=new LinkedHashMap<>();
        CatalogEnrichment catalogResult=enrichFromNationalCatalog(
                settings,token,byGtin,incompleteGtins,permitDocumentSnapshots,labelMetadataSnapshots);
        int failedCatalogBatches=catalogResult.failedBatches();
        List<Product> publishable=new ArrayList<>();List<String> unpublished=new ArrayList<>();
        for(Product p:byGtin.values()){if(ZnackCardStatus.isErrored(p.cardStatus(),p.cardDetailedStatus()))unpublished.add(p.gtin());else publishable.add(p);}
        RegistryEnrichment registryEnrichment=enrichWaitingDocumentsFromRegistry(
                settings,token,publishable,permitDocumentSnapshots);
        List<Product> catalogVerified=publishable.stream()
                .filter(product->permitDocumentSnapshots.containsKey(product.gtin())).toList();
        repository.upsertProducts(catalogVerified, settings);
        List<String> missingDocuments=new ArrayList<>();
        for(Product product:catalogVerified){
            List<GoodsDocument> documents=permitDocumentSnapshots.get(product.gtin());
            Product existing=existingProductsByGtin.get(product.gtin());
            boolean hasExistingDocs=existing!=null&&existing.permitDocuments()!=null&&!existing.permitDocuments().isEmpty();
            boolean isBatchFailed=catalogResult.failedBatchGtins().contains(product.gtin());
            if(documents==null||documents.isEmpty()){
                if(hasExistingDocs||isBatchFailed){
                    if(hasExistingDocs){
                        documents=existing.permitDocuments();
                        permitDocumentSnapshots.put(product.gtin(),documents);
                    }
                }else{
                    missingDocuments.add(product.gtin());
                }
            }
            repository.updateProductDocuments(product.gtin(),documents==null?List.of():documents);
            ZnackKizLabelMetadata labelMetadata=labelMetadataSnapshots.get(product.gtin());
            if(labelMetadata!=null)repository.updateProductLabelMetadata(
                    product.gtin(),labelMetadata.gender(),labelMetadata.size());
        }
        List<String> withDocuments=catalogVerified.stream()
                .filter(product->{
                    List<GoodsDocument> docs=permitDocumentSnapshots.get(product.gtin());
                    return docs!=null&&!docs.isEmpty();
                })
                .map(Product::gtin).toList();
        repository.restoreProducts(withDocuments);
        repository.softDeleteProducts(missingDocuments);
        int removed=repository.pruneTechnicalProducts();int unpublishedRemoved=repository.deleteUnpublishedProducts(unpublished);
        String message="Verified "+catalogVerified.size()+" catalog GTINs; moved "+missingDocuments.size()+
                " GTINs without goods documents to trash; ignored "+technical+
                " technical GTINs; skipped "+unpublished.size()+" non-published cards; removed "+removed+
                " unreferenced technical GTINs and "+unpublishedRemoved+" unreferenced non-published GTINs";
        if(failedCatalogBatches>0)message+="; partial catalog enrichment: "+failedCatalogBatches+
                " catalog batch"+(failedCatalogBatches==1?"":"es")+" failed after retries";
        if(registryEnrichment.failed()>0)message+="; "+registryEnrichment.failed()+
                " waiting GTIN document lookup"+(registryEnrichment.failed()==1?"":"s")+" failed";
        repository.log("GTIN_SYNC",null,failedCatalogBatches==0&&registryEnrichment.failed()==0?"INFO":"WARN",message,200);
        return repository.findProducts();
    }

    /**
     * The feed-product card is useful for names and attributes, but it is not authoritative for
     * permit documents and can omit an existing certificate. For GTINs with already-purchased KIZ
     * waiting for circulation, query the documented registry endpoint before deciding that the
     * product has no declaration/certificate. One lookup is made per distinct GTIN, not per order.
     */
    private RegistryEnrichment enrichWaitingDocumentsFromRegistry(
            ZnackModels.Settings settings,String token,List<Product> publishable,
            Map<String,List<GoodsDocument>> permitDocumentSnapshots){
        Set<String> publishableGtins=publishable.stream().map(Product::gtin)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> waitingGtins=new java.util.LinkedHashSet<>();
        repository.findWaitingIntroductionDocumentPipelines().stream()
                .map(ZnackPurchasePipelineState::gtin)
                .filter(publishableGtins::contains)
                .filter(gtin->permitDocumentSnapshots.getOrDefault(gtin,List.of()).isEmpty())
                .sorted()
                .forEach(waitingGtins::add);
        int failed=0;
        for(String gtin:waitingGtins){
            try{
                JsonElement response=api.permitDocuments(settings.resolvedTrueApiBaseUrl(),token,gtin,
                        settings.ownerInn()==null?"":settings.ownerInn().trim());
                if(ZnackPermitDocumentParser.registryLookupPending(response)){
                    permitDocumentSnapshots.remove(gtin);
                    repository.log("GTIN_DOCUMENT_ENRICH",gtin,"INFO",
                            "National Catalog document lookup is pending; keeping the previous local state",null);
                    continue;
                }
                permitDocumentSnapshots.put(gtin,ZnackPermitDocumentParser.activeFromRegistry(response));
            }catch(Exception error){
                failed++;
                // Do not erase a previously valid document or move the product to trash when the
                // authoritative lookup itself failed. A later/manual sync can safely retry it.
                permitDocumentSnapshots.remove(gtin);
                repository.log("GTIN_DOCUMENT_ENRICH",gtin,"WARN",error.getMessage(),null);
            }
        }
        return new RegistryEnrichment(failed);
    }

    private record RegistryEnrichment(int failed) {}
    private record CatalogEnrichment(int failedBatches, Set<String> failedBatchGtins) {}
    private CatalogEnrichment enrichFromNationalCatalog(ZnackModels.Settings settings,String token,Map<String,Product> byGtin,
                                          Set<String> incompleteGtins,
                                          Map<String,List<GoodsDocument>> permitDocumentSnapshots,
                                          Map<String,ZnackKizLabelMetadata> labelMetadataSnapshots){
        int failedBatches=0;
        Set<String> failedBatchGtins=new HashSet<>();
        List<String> gtins=new ArrayList<>(byGtin.keySet());
        gtins.sort(Comparator.comparing((String gtin)->!incompleteGtins.contains(gtin)));
        for(int start=0;start<gtins.size();start+=CATALOG_BATCH_SIZE){
            List<String> batch=gtins.subList(start,Math.min(start+CATALOG_BATCH_SIZE,gtins.size()));
            if(start>0){
                try{Thread.sleep(200);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
            try{
                JsonElement response=api.productCards(settings.resolvedTrueApiBaseUrl(),token,String.join(";",batch));
                JsonArray cards=array(response,"result");
                if(cards==null)throw new IllegalStateException("National Catalog response is missing result");
                for(JsonElement element:cards){
                    if(!element.isJsonObject())continue;
                    JsonObject card=element.getAsJsonObject();
                    String name=text(card,"good_name","productName","name");
                    String tnVed=tnVed(card);
                    String category=categories(card);
                    List<GoodsDocument> permitDocuments=ZnackPermitDocumentParser.fromProductCard(card);
                    ZnackKizLabelMetadata labelMetadata=ZnackProductLabelMetadataParser.fromProductCard(card);
                    List<String> cardGtins=new ArrayList<>();
                    String directGtin=text(card,"gtin","productGtin");
                    if(!directGtin.isBlank())cardGtins.add(directGtin);
                    JsonArray identifiers=array(card,"identified_by");
                    if(identifiers!=null)for(JsonElement identifier:identifiers){
                        if(!identifier.isJsonObject())continue;
                        JsonObject object=identifier.getAsJsonObject();
                        String type=text(object,"type");
                        String value=text(object,"value","gtin");
                        if(!type.isBlank()&&!"gtin".equalsIgnoreCase(type))continue;
                        if(!value.isBlank())cardGtins.add(value);
                    }
                    for(String value:cardGtins){
                        try{
                            String gtin=GtinNormalizer.normalize(value);
                            Product current=byGtin.get(gtin);
                            if(current!=null){
                                permitDocumentSnapshots.put(gtin,permitDocuments);
                                labelMetadataSnapshots.put(gtin,labelMetadata);
                                byGtin.put(gtin,new Product(gtin,first(name,current.productName()),
                                    first(tnVed,current.tnVed()),current.certificateType(),current.certificateNumber(),
                                    current.certificateDate(),current.productionDate(),
                                    first(bool(card,"goodMarkFlag","good_mark_flag"),current.goodMarkFlag()),
                                    first(bool(card,"goodTurnFlag","good_turn_flag"),current.goodTurnFlag()),
                                    first(text(card,"goodStatus","good_status","cardStatus"),current.cardStatus()),
                                    first(text(card,"goodDetailedStatus","good_detailed_status"),current.cardDetailedStatus()),
                                    first(category,current.category()),
                                    Instant.now(),current.cisType()));
                            }
                        }catch(IllegalArgumentException ignored){}
                    }
                }
            }catch(Exception error){
                failedBatches++;
                failedBatchGtins.addAll(batch);
                repository.log("GTIN_CATALOG_ENRICH",null,"WARN",error.getMessage(),null);
            }
        }
        return new CatalogEnrichment(failedBatches,failedBatchGtins);
    }
    private boolean needsCatalogMetadata(Product product){
        return missing(product.productName())||missing(product.tnVed());
    }
    private boolean missing(String value){return value==null||value.isBlank()||"-".equals(value.trim());}
    /** Joins the {@code categories[].cat_name} values of a National Catalog card ("Обувь домашняя", ...). */
    private String categories(JsonObject card){
        JsonArray categories=array(card,"categories");
        if(categories==null)return "";
        List<String> names=new ArrayList<>();
        for(JsonElement element:categories){
            if(!element.isJsonObject())continue;
            String name=text(element.getAsJsonObject(),"cat_name","catName","name");
            if(!name.isBlank()&&!names.contains(name))names.add(name);
        }
        return String.join(", ",names);
    }
    private JsonArray array(JsonElement response,String key){
        if(response==null||response.isJsonNull())return null;
        if(response.isJsonArray())return response.getAsJsonArray();
        JsonObject object=response.getAsJsonObject();
        return object.has(key)&&object.get(key).isJsonArray()?object.getAsJsonArray(key):null;
    }
    private String first(String preferred,String fallback){return preferred==null||preferred.isBlank()||"-".equals(preferred.trim())?fallback:preferred;}
    private Boolean first(Boolean preferred,Boolean fallback){return preferred==null?fallback:preferred;}
    private String tnVed(JsonObject product){
        String direct=text(product,"tnVedCode10","tnvedCode10","tnVed10","tnved10","tnved_code_10","tnved_10",
                "tnVedEaes","productTnved","product_tnved","goodsTnvedCode","tnVed","tnved","tnVedCode",
                "tnvedCode","tnved_code","tnVedEaesGroup");
        String fullCode=attribute(product,13933,"Код ТНВЭД","FEACN code");
        String group=attribute(product,3959,"Группа ТНВЭД","FEACN group");
        return compact(first(fullCode,first(direct,group)));
    }
    private String attribute(JsonObject product,int id,String...names){
        for(JsonObject attribute:ZnackProductCardAttributes.from(product)){
            String attrId=ZnackProductCardAttributes.id(attribute);
            String attrName=ZnackProductCardAttributes.name(attribute);
            boolean matchesId=String.valueOf(id).equals(attrId);
            boolean matchesName=java.util.Arrays.stream(names).anyMatch(name->name.equalsIgnoreCase(attrName));
            if(matchesId||matchesName)return text(attribute,"attr_value","attrValue","value");
        }
        return "";
    }
    private String compact(String value){return value==null?"":value.replaceAll("\\s+","").trim();}
    private String cisType(JsonObject product){
        String explicit=text(product,"cisType","cis_type");
        if(!explicit.isBlank())return explicit;
        if(Boolean.TRUE.equals(bool(product,"isKit","is_kit")))return "BUNDLE";
        return "UNIT";
    }
    private Boolean bool(JsonObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull()){JsonElement value=o.get(k);if(value.isJsonPrimitive()){JsonPrimitive primitive=value.getAsJsonPrimitive();if(primitive.isBoolean())return primitive.getAsBoolean();String text=primitive.getAsString();if("true".equalsIgnoreCase(text)||"1".equals(text))return true;if("false".equalsIgnoreCase(text)||"0".equals(text))return false;}}return null;}
    private String text(JsonObject o,String...keys){for(String k:keys)if(o.has(k)&&!o.get(k).isJsonNull()){JsonElement value=o.get(k);return value.isJsonPrimitive()?value.getAsString():value.toString();}return "";}
}
