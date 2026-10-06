package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.*;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.*;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class ZnackIntroductionService {
    private final ZnackApiClient api;private final ZnackAuthService auth;private final ZnackSignatureProvider signer;private final ZnackRepository repository;
    public ZnackIntroductionService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public long submit(Settings s,KizOrder order,Product product,List<KizCode> codes)throws Exception{
        ZnackSafety.requireSigned(s,true);
        if(product.tnVed()==null||product.tnVed().isBlank())throw new IllegalStateException("TN VED is required before introduction.");
        String participant=required(auth.resolvedParticipantInn(s),"Participant INN is required for introduction.");
        String producer=valueOr(s.producerInn(),participant),owner=valueOr(s.ownerInn(),participant);
        String registryOwner=s.ownerInn()==null?"":s.ownerInn().trim();
        String token=auth.trueApiToken(s);
        List<GoodsDocument> goodsDocuments;
        try{
            JsonElement registry=api.permitDocuments(s.resolvedTrueApiBaseUrl(),token,product.gtin(),registryOwner);
            if(ZnackPermitDocumentParser.registryLookupPending(registry))throw new PermitDocumentsUnavailableException(
                    "National Catalog is still resolving documents for GTIN "+product.gtin()+". The introduction will retry automatically.",
                    new IllegalStateException("National Catalog registry lookup is pending"));
            goodsDocuments=ZnackPermitDocumentParser.selectForCirculation(
                    ZnackPermitDocumentParser.activeFromRegistry(registry));
        }catch(PermitDocumentsUnavailableException error){
            throw error;
        }catch(Exception error){
            throw new PermitDocumentsUnavailableException(
                    "Could not verify active National Catalog documents for GTIN "+product.gtin()+". The introduction will retry automatically.",error);
        }
        repository.updateProductDocuments(product.gtin(),goodsDocuments);
        if(goodsDocuments.isEmpty())throw new PermitDocumentsUnavailableException(
                "GTIN "+product.gtin()+" has no active National Catalog declaration or certificate. Publish or reactivate the GTIN document; the introduction will retry automatically.");
        JsonObject payload=new JsonObject();payload.addProperty("participant_inn",participant);payload.addProperty("producer_inn",producer);payload.addProperty("owner_inn",owner);payload.addProperty("production_type","OWN_PRODUCTION");
        if(product.productionDate()!=null&&!product.productionDate().isBlank())payload.addProperty("production_date",product.productionDate());
        JsonArray items=new JsonArray();for(KizCode code:codes){JsonObject item=new JsonObject();item.addProperty("uit_code",ZnackCisNormalizer.forTrueApi(code.rawCode()));item.addProperty("tnved_code",product.tnVed());
            JsonArray certificates=new JsonArray();for(GoodsDocument goodsDocument:goodsDocuments){JsonObject certificate=new JsonObject();certificate.addProperty("certificate_type",goodsDocument.type().trim());certificate.addProperty("certificate_number",goodsDocument.number().trim());certificate.addProperty("certificate_date",goodsDocument.date().trim());certificates.add(certificate);}
            item.add("certificate_document_data",certificates);items.add(item);}
        payload.add("products",items);byte[] documentBytes=payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sig=signer.sign(documentBytes, ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
        JsonObject request=new JsonObject();request.addProperty("document_format","MANUAL");request.addProperty("type","LP_INTRODUCE_GOODS");request.addProperty("product_document",Base64.getEncoder().encodeToString(documentBytes));request.addProperty("signature",Base64.getEncoder().encodeToString(sig));
        long documentId=repository.createDocument(order.id(),payload.toString());
        try{String external=required(api.createDocument(s.resolvedTrueApiBaseUrl(),token,request),"Znack document response did not contain a document ID.");
            repository.updateDocument(documentId,external,"SUBMITTED",null);repository.markCodes(order.id(),KizLegalStatus.INTRO_SENT,null,documentId);repository.updateOrder(order.id(),null,null,OrderStatus.INTRO_SENT,null);return documentId;
        }catch(Exception e){repository.updateDocument(documentId,null,e instanceof ZnackApiClient.ZnackApiException?"REJECTED":"FAILED",e.getMessage());throw e;}
    }
    public static final class PermitDocumentsUnavailableException extends Exception{
        private final boolean confirmedMissing;
        public PermitDocumentsUnavailableException(String message){super(message);this.confirmedMissing=true;}
        public PermitDocumentsUnavailableException(String message,Throwable cause){super(message,cause);this.confirmedMissing=false;}
        public boolean confirmedMissing(){return confirmedMissing;}
    }
    /** Outcome of a document confirmation poll. */
    public enum ConfirmStatus{INTRODUCED,PENDING,FAILED}
    public record ConfirmResult(ConfirmStatus status,String message){
        public boolean introduced(){return status==ConfirmStatus.INTRODUCED;}
        public boolean failed(){return status==ConfirmStatus.FAILED;}
    }
    public ConfirmResult confirm(Settings s,KizOrder order,List<KizCode> codes)throws Exception{
        ZnackSafety.requireSigned(s,true);
        Document document=repository.findLatestDocument(order.id()).orElseThrow();
        JsonElement docs=api.document(s.resolvedTrueApiBaseUrl(),auth.trueApiToken(s),document.externalDocumentId());
        if(!documentCheckedOk(docs,document.externalDocumentId())){
            // Only classify as failed when the document did not succeed anywhere in the response;
            // nested per-item failure statuses must not override a CHECKED_OK document.
            String failedStatus=failedDocumentStatus(docs,document.externalDocumentId());
            if(!failedStatus.isBlank()){
                String error=("Znack document "+document.externalDocumentId()+" status: "+failedStatus+documentErrors(docs)).trim();
                repository.updateDocument(document.id(),null,"CHECKED_NOT_OK",error);
                return new ConfirmResult(ConfirmStatus.FAILED,error);
            }
            return new ConfirmResult(ConfirmStatus.PENDING,null);
        }
        if(codes==null||codes.isEmpty())throw new IllegalStateException("No KIZ codes are available for introduction confirmation.");
        String token=auth.trueApiToken(s);
        for(int start=0;start<codes.size();start+=ZnackApiClient.CISES_MAX_CODES_PER_REQUEST){
            List<KizCode> batch=codes.subList(start,
                    Math.min(start+ZnackApiClient.CISES_MAX_CODES_PER_REQUEST,codes.size()));
            JsonArray values=new JsonArray();
            batch.forEach(c->values.add(ZnackCisNormalizer.forTrueApi(c.rawCode())));
            JsonElement info=api.cisesInfo(s.resolvedTrueApiBaseUrl(),token,values);
            if(!allIntroduced(info,batch.size()))return new ConfirmResult(ConfirmStatus.PENDING,null);
        }
        repository.updateDocument(document.id(),null,"CHECKED_OK",null);
        repository.markCodes(order.id(),KizLegalStatus.IN_CIRCULATION,null,null);repository.updateOrder(order.id(),null,null,OrderStatus.INTRODUCED,null);
        return new ConfirmResult(ConfirmStatus.INTRODUCED,null);
    }
    private boolean documentCheckedOk(JsonElement e,String externalId){
        if(e==null||externalId==null||externalId.isBlank())return false;
        if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();String id=text(o,"document_id","documentId","id");String status=text(o,"status","documentStatus");
            if((id.isBlank()||externalId.equals(id))&&"CHECKED_OK".equalsIgnoreCase(status))return true;for(var x:o.entrySet())if(documentCheckedOk(x.getValue(),externalId))return true;}
        else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())if(documentCheckedOk(x,externalId))return true;return false;
    }
    /** True API statuses that mean the document was definitively processed with errors ("Обработан с ошибками"). */
    private static final java.util.Set<String> FAILED_DOCUMENT_STATUSES=
            java.util.Set.of("CHECKED_NOT_OK","PROCESSING_ERROR","PARSE_ERROR","CANCELLED","REJECTED");
    private String failedDocumentStatus(JsonElement e,String externalId){
        if(e==null||externalId==null||externalId.isBlank())return "";
        if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();String id=text(o,"document_id","documentId","id");String status=text(o,"status","documentStatus");
            if((id.isBlank()||externalId.equals(id))&&FAILED_DOCUMENT_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT)))return status;
            for(var x:o.entrySet()){String found=failedDocumentStatus(x.getValue(),externalId);if(!found.isBlank())return found;}}
        else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray()){String found=failedDocumentStatus(x,externalId);if(!found.isBlank())return found;}
        return "";
    }
    private String documentErrors(JsonElement e){
        java.util.List<String> found=new java.util.ArrayList<>();collectErrors(e,found);
        return found.isEmpty()?"":" ("+String.join("; ",found)+")";
    }
    private void collectErrors(JsonElement e,java.util.List<String> out){
        if(e==null)return;
        if(e.isJsonObject()){for(var x:e.getAsJsonObject().entrySet()){
            if("errors".equals(x.getKey())||"error_message".equals(x.getKey())||"errorMessage".equals(x.getKey())||"downloadDesc".equals(x.getKey()))collectErrorValues(x.getValue(),out);
            else collectErrors(x.getValue(),out);}}
        else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())collectErrors(x,out);
    }
    private void collectErrorValues(JsonElement e,java.util.List<String> out){
        if(e==null||e.isJsonNull())return;
        if(e.isJsonPrimitive()){String value=e.getAsString();if(!value.isBlank()&&!out.contains(value))out.add(value);}
        else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())collectErrorValues(x,out);
        else if(e.isJsonObject())for(var x:e.getAsJsonObject().entrySet())collectErrorValues(x.getValue(),out);
    }
    private boolean allIntroduced(JsonElement e,int expected){int[] count={0};walk(e,count);return count[0]>=expected;}
    private void walk(JsonElement e,int[] count){if(e==null)return;if(e.isJsonObject()){JsonObject o=e.getAsJsonObject();if(o.has("status")&&!o.get("status").isJsonNull()&&"INTRODUCED".equalsIgnoreCase(o.get("status").getAsString())){count[0]++;return;}for(var x:o.entrySet())walk(x.getValue(),count);}else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())walk(x,count);}
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private String valueOr(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value.trim();}
}
