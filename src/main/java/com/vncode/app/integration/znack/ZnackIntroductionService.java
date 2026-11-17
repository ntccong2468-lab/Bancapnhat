package com.vncode.app.integration.znack;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackModels.*;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class ZnackIntroductionService {
    private static final java.util.regex.Pattern HTTP_STATUS=java.util.regex.Pattern.compile("(?i)\\bHTTP\\s+(\\d{3})(?!\\d)");
    private final ZnackApiClient api;private final ZnackAuthService auth;private final ZnackSignatureProvider signer;private final ZnackRepository repository;
    public ZnackIntroductionService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,ZnackRepository repository){this.api=api;this.auth=auth;this.signer=signer;this.repository=repository;}
    public long submit(Settings original,KizOrder order,Product product,List<KizCode> codes)throws Exception{
        ZnackPurchaseContext context=ZnackPurchaseProtocolContext.resolve(repository,original,order.id());
        Settings s=context.settings();
        ZnackSafety.requireSigned(s,true);
        if(codes==null||codes.isEmpty())throw new IllegalArgumentException("No KIZ codes are available for introduction.");
        if(context.method().requiresProductionMetadata()){
            if(product==null||product.tnVed()==null||product.tnVed().isBlank())
                throw new IllegalStateException("TN VED is required before introduction.");
            if(ZnackProductGroup.fromProduct(product)!=context.group())
                throw new PermitDocumentsUnavailableException(
                        "Nhóm sản phẩm hiện tại khác với lô mã đã mua; kiểm tra lại mã hàng/TN VED trước khi lưu thông.",
                        new IllegalStateException("KIZ_PURCHASE_PRODUCT_GROUP_CHANGED"));
        }
        LicenseRuntime.require(Permission.KIZ);
        var admission=ZnackSigningSession.captureMutationPermit(repository.shop().shopId(),s);
        String participant=required(auth.resolvedParticipantInn(s),"Participant INN is required for introduction.");
        JsonObject payload=context.method()==ZnackPurchaseMethod.COMMISSION
                ?commissionDocument(participant,order,codes):productionDocument(s,context,order,product,codes,participant);
        String token=auth.trueApiToken(s);
        byte[] documentBytes=payload.toString().getBytes(StandardCharsets.UTF_8);
        LicenseRuntime.require(Permission.KIZ);
        byte[] sig=signer.sign(documentBytes,ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
        JsonObject request=new JsonObject();request.addProperty("document_format","MANUAL");
        request.addProperty("type",context.method().documentType());
        request.addProperty("product_document",Base64.getEncoder().encodeToString(documentBytes));
        request.addProperty("signature",Base64.getEncoder().encodeToString(sig));
        // A signature is not a claim that the following HTTP request was admitted or accepted.
        LicenseRuntime.require(Permission.KIZ);
        long documentId=repository.createDocument(order.id(),context.method().documentType(),payload.toString());
        var attempted=new java.util.concurrent.atomic.AtomicBoolean();
        try{
            String external=admission.execute(()->{
                LicenseRuntime.require(Permission.KIZ);
                attempted.set(true);
                return required(api.createDocument(s.resolvedTrueApiBaseUrl(),token,request,context.group().apiCode()),
                        "Znack document response did not contain a document ID.");
            });
            repository.updateDocument(documentId,external,"SUBMITTED",null);
            repository.markCodes(order.id(),KizLegalStatus.INTRO_SENT,null,documentId);
            repository.updateOrder(order.id(),null,null,OrderStatus.INTRO_SENT,null);
            return documentId;
        }catch(Exception error){
            // Local generation/license rejection before execute() entered the work is known-unsent.
            // Once HTTP was attempted, timeout/conflict/gateway outcomes remain ambiguous.
            boolean rejected=error instanceof ZnackApiClient.ZnackApiException response&&definitiveRejection(response.statusCode());
            String diagnostic=error instanceof ZnackApiClient.ZnackApiException response
                    ?"HTTP "+response.statusCode()+": "+error.getMessage():error.getMessage();
            repository.updateDocument(documentId,null,!attempted.get()?"NOT_SUBMITTED":rejected?"REJECTED":"FAILED",diagnostic);
            throw error;
        }
    }

    private JsonObject productionDocument(Settings settings,ZnackPurchaseContext context,KizOrder order,
                                          Product product,List<KizCode> codes,String participant)throws Exception{
        List<GoodsDocument> documents;
        try{
            var lookup=auth.readWithTrueApiRecovery(settings,token->ZnackPurchasePermitResolver.resolve(
                    api,settings.resolvedTrueApiBaseUrl(),token,product.gtin(),settings.ownerInn(),participant));
            if(lookup.pending())
                throw new PermitDocumentsUnavailableException(
                        "National Catalog is still resolving documents for GTIN "+product.gtin()+". The introduction will retry automatically.",
                        new IllegalStateException("National Catalog registry lookup is pending"));
            documents=ZnackPermitDocumentParser.selectForCirculation(lookup.documents());
        }catch(PermitDocumentsUnavailableException error){throw error;}
        catch(ZnackSigningSession.SigningDeferredException deferred){throw deferred;}
        catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){throw denied;}
        catch(Exception error){throw new PermitDocumentsUnavailableException(
                "Could not verify active National Catalog documents for GTIN "+product.gtin()+". The introduction will retry automatically.",error);}
        repository.updateProductDocuments(product.gtin(),documents);
        if(documents.isEmpty())throw new PermitDocumentsUnavailableException(
                "GTIN "+product.gtin()+" has no active National Catalog declaration or certificate. Publish or reactivate the GTIN document; the introduction will retry automatically.");
        JsonObject payload=new JsonObject();payload.addProperty("participant_inn",participant);
        payload.addProperty("producer_inn",valueOr(settings.producerInn(),participant));
        payload.addProperty("owner_inn",valueOr(settings.ownerInn(),participant));
        payload.addProperty("production_type","OWN_PRODUCTION");
        if(product.productionDate()!=null&&!product.productionDate().isBlank())payload.addProperty("production_date",product.productionDate());
        else if(context.group()==ZnackProductGroup.FOOTWEAR)
            payload.addProperty("production_date",documentDate(order,"production_date",java.time.ZoneId.of("Europe/Moscow")).toString());
        JsonArray items=new JsonArray();
        for(KizCode code:codes){
            JsonObject item=new JsonObject();item.addProperty("uit_code",ZnackCisNormalizer.forTrueApi(code.rawCode()));
            item.addProperty("tnved_code",product.tnVed());
            JsonArray certificates=new JsonArray();
            for(GoodsDocument document:documents){
                JsonObject certificate=new JsonObject();certificate.addProperty("certificate_type",document.type().trim());
                certificate.addProperty("certificate_number",document.number().trim());certificate.addProperty("certificate_date",document.date().trim());
                certificates.add(certificate);
            }
            item.add("certificate_document_data",certificates);items.add(item);
        }
        payload.add("products",items);return payload;
    }

    private JsonObject commissionDocument(String participant,KizOrder order,List<KizCode> codes){
        JsonObject payload=new JsonObject();payload.addProperty("participant_inn",participant);
        var date=documentDate(order,"product_receiving_date",java.time.ZoneId.systemDefault());
        payload.addProperty("product_receiving_date",java.time.format.DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").format(date.atStartOfDay(java.time.ZoneOffset.UTC)));
        JsonArray items=new JsonArray();
        for(KizCode code:codes){JsonObject item=new JsonObject();item.addProperty("uit",ZnackCisNormalizer.forTrueApi(code.rawCode()));items.add(item);}
        payload.add("products_list",items);return payload;
    }

    private java.time.LocalDate documentDate(KizOrder order,String field,java.time.ZoneId zone){
        var previous=repository.findLatestDocument(order.id());
        if(previous.isPresent()){
            JsonObject payload=JsonParser.parseString(previous.get().payloadJson()).getAsJsonObject();
            if(payload.has(field)&&!payload.get(field).isJsonNull()){
                String saved=payload.get(field).getAsString();
                return "product_receiving_date".equals(field)
                        ?java.time.OffsetDateTime.parse(saved).toLocalDate():java.time.LocalDate.parse(saved);
            }
        }
        return ZnackPurchaseProtocolContext.purchasedAt(repository,order).atZone(zone).toLocalDate();
    }
    private static boolean definitiveRejection(int status){
        return switch(status){
            case 400,401,403,404,405,406,410,411,413,414,415,422,429->true;
            default->false;
        };
    }
    /** A stored legacy label alone is not evidence that a remote POST was rejected. */
    static boolean isDefinitivelyRejectedSubmission(Document document){
        if(document==null)return false;
        if("CHECKED_NOT_OK".equals(document.status()))return true;
        if(document.externalDocumentId()!=null&&!document.externalDocumentId().isBlank())return false;
        if("NOT_SUBMITTED".equals(document.status()))return true;
        boolean rejected="REJECTED".equals(document.status()),legacy="FAILED".equals(document.status());
        if(!rejected&&!legacy)return false;
        var matcher=HTTP_STATUS.matcher(document.errorMessage()==null?"":document.errorMessage());
        boolean observed=false;
        while(matcher.find()){
            int status=Integer.parseInt(matcher.group(1));
            if(!definitiveRejection(status)||legacy&&status!=422)return false;
            observed=true;
        }
        return observed;
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
        ZnackPurchaseContext context=ZnackPurchaseProtocolContext.resolve(repository,s,order.id());
        s=context.settings();
        final Settings original=s;
        ZnackSafety.requireSigned(s,true);
        Document document=repository.findLatestDocument(order.id()).orElseThrow();
        JsonElement docs=auth.readWithTrueApiRecovery(original,token->api.document(
                original.resolvedTrueApiBaseUrl(),token,document.externalDocumentId(),context.group().apiCode()));
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
        if(codes.size()!=order.quantity()||!ZnackCisReadback.validRequests(codes,order.gtin(),order.id()))
            return new ConfirmResult(ConfirmStatus.PENDING,null);
        String owner=s.ownerInn()==null||s.ownerInn().isBlank()?auth.resolvedParticipantInn(s):s.ownerInn();
        if(owner==null||!owner.matches("[0-9]{10}|[0-9]{12}"))
            return new ConfirmResult(ConfirmStatus.PENDING,"KIZ_CIRCULATION_OWNER_UNVERIFIED");
        for(int start=0;start<codes.size();start+=ZnackApiClient.CISES_MAX_CODES_PER_REQUEST){
            List<KizCode> batch=codes.subList(start,
                    Math.min(start+ZnackApiClient.CISES_MAX_CODES_PER_REQUEST,codes.size()));
            JsonArray values=new JsonArray();
            batch.forEach(c->values.add(ZnackCisNormalizer.forTrueApi(c.rawCode())));
            JsonElement info=auth.readWithTrueApiRecovery(original,token->api.cisesInfo(
                    original.resolvedTrueApiBaseUrl(),token,values,context.group().apiCode()));
            var observed=ZnackCisReadback.inspectOwned(info,batch,order.gtin(),owner);
            if(observed.introduced()!=batch.size())return new ConfirmResult(ConfirmStatus.PENDING,observed.reason());
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
    private String text(JsonObject o,String...k){for(String x:k)if(o.has(x)&&!o.get(x).isJsonNull())return o.get(x).getAsString();return "";}
    private String valueOr(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
    private String required(String value,String message){if(value==null||value.isBlank())throw new IllegalStateException(message);return value.trim();}
}
