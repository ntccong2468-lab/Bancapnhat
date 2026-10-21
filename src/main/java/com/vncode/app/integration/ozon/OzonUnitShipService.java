package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiFunction;

/** One native v4 ship request, followed by authoritative per-child readback. */
final class OzonUnitShipService {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(OzonUnitShipService.class);
    private final OzonPostingRepository postings;
    private final OzonExemplarJobRepository jobs;
    private final BiFunction<Integer,OzonCredentials,OzonApiClient> clients;
    private final OzonUnitShipRepository receipts=new OzonUnitShipRepository();
    OzonUnitShipService(OzonPostingRepository postings,OzonExemplarJobRepository jobs,BiFunction<Integer,OzonCredentials,OzonApiClient> clients){this.postings=postings;this.jobs=jobs;this.clients=clients;}

    OzonShipResult ship(Shop shop,String number,boolean confirmed)throws IOException {
        MarketplaceGuard.requireOzon(shop);if(!confirmed)throw new IllegalArgumentException("Explicit confirmation is required before accepting Ozon postings.");
        ListingIdentity.require(shop);number=OzonApiClient.requireExternalId(number,"posting number");
        var receipt=receipts.find(shop,number);
        var api=clients.apply(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()));
        if(receipt!=null&&!receipt.state().equals("REJECTED"))return reconcile(shop,api,receipt,true);
        LicenseRuntime.require(Permission.PACKING);
        var raw=api.getPosting(number,true,true);
        var source=OzonJson.requirePostingDetail(raw,number);
        if(!source.canShip()||!"awaiting_packaging".equals(source.status()))throw new IllegalStateException("OZON_SPLIT_NOT_SHIPPABLE");
        if(source.orderId().isBlank()||source.warehouseId().isBlank())throw new IllegalStateException("OZON_SPLIT_MISSING_IDENTITY");
        int units=OzonPackageBuilder.unitCount(source);
        if(units>1&&containsMarks(OzonJson.object(raw,"result")))throw new IllegalStateException("OZON_SPLIT_HAS_BOUND_KIZ");
        var request=OzonPackageBuilder.perUnitPackages(source);String fingerprint=hash(request.toString());
        if(!receipts.begin(shop,source,request.toString(),fingerprint,units))return reconcile(shop,api,receipts.find(shop,number),true);
        jobs.logAction(shop.getId(),"ship_units",number,"pending",null,fingerprint);
        try {
            ListingIdentity.require(shop);LicenseRuntime.require(Permission.PACKING);
            var response=api.ship(request);
            var children=responseChildren(response,units);
            receipts.update(shop,number,"ACCEPTED",children);
            jobs.logAction(shop.getId(),"ship_units",number,"accepted",null,fingerprint);
        }catch(OzonApiException rejected){
            if(!rejected.ambiguousMutation()){
                receipts.update(shop,number,"REJECTED",null);jobs.logAction(shop.getId(),"ship_units",number,"rejected",rejected.safeErrorCode(),fingerprint);throw rejected;
            }
            receipts.update(shop,number,"AMBIGUOUS",null);
        }catch(com.vncode.app.features.license.LicenseGate.LicenseDeniedException denied){receipts.update(shop,number,"REJECTED",null);throw denied;
        }catch(IOException malformed){receipts.update(shop,number,"AMBIGUOUS",null);}
        return reconcile(shop,api,receipts.find(shop,number),false);
    }

    private OzonShipResult reconcile(Shop shop,OzonApiClient api,OzonUnitShipRepository.Receipt receipt,boolean resumed)throws IOException {
        if(receipt==null)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
        var source=receipt.source();int expected=OzonPackageBuilder.unitCount(source);
        var numbers=new LinkedHashSet<>(receipt.children());
        var fresh=new LinkedHashMap<String,OzonPostingDto>();
        try{
            if(numbers.size()!=expected){
                var raw=api.getPosting(source.postingNumber(),true,true);
                var parent=OzonJson.requirePostingDetail(raw,source.postingNumber());
                if(!parent.orderId().equals(source.orderId()))throw pending("OZON_SPLIT_FOREIGN_CHILD",null);
                var related=OzonJson.object(OzonJson.object(raw,"result"),"related_postings").get("related_posting_numbers");
                if(related!=null){
                    if(!related.isJsonArray()||related.getAsJsonArray().size()>OzonPackageBuilder.MAX_UNIT_PACKAGES)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
                    for(var value:related.getAsJsonArray())numbers.add(childNumber(value));
                }
                if(OzonPackageBuilder.unitCount(parent)==1){numbers.add(parent.postingNumber());fresh.put(parent.postingNumber(),parent);}
                if(numbers.size()!=expected)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
                receipts.update(shop,source.postingNumber(),"ACCEPTED",List.copyOf(numbers));
            }
            var actual=new LinkedHashMap<String,Integer>();
            for(String child:numbers){
                ListingIdentity.require(shop);
                var posting=fresh.get(child);
                if(posting==null)posting=OzonJson.requirePostingDetail(api.getPosting(child,true,true),child);
                if(!source.orderId().equals(posting.orderId())||!source.warehouseId().equals(posting.warehouseId())||OzonPackageBuilder.unitCount(posting)!=1)throw pending("OZON_SPLIT_FOREIGN_CHILD",null);
                if("ship_failed".equals(posting.substatus()))throw pending("OZON_SPLIT_SHIP_FAILED",null);
                if(!Set.of("awaiting_deliver","delivering","delivered").contains(posting.status()))throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
                for(var item:posting.items())actual.merge(sku(item),item.quantity(),Math::addExact);
                fresh.put(child,posting);
            }
            if(!quantities(source).equals(actual))throw pending("OZON_SPLIT_QUANTITY_MISMATCH",null);
            // Actual children only; their native IDs feed the existing label and KIZ flow.
            receipts.complete(shop,source.postingNumber(),List.copyOf(fresh.values()));
            jobs.logAction(shop.getId(),"ship_units",source.postingNumber(),"reconciled",null,receipt.fingerprint());
            return new OzonShipResult(source.postingNumber(),"awaiting_deliver",resumed,List.copyOf(numbers));
        }catch(OzonApiException error){if(error.ambiguousMutation())throw error;throw pending("OZON_SPLIT_READBACK_REQUIRED",error);
        }catch(IOException|RuntimeException error){throw pending("OZON_SPLIT_READBACK_REQUIRED",error);}
    }

    /** At most three GETs per ordinary turn. Never calls ship or allocates/reassigns KIZ. */
    int reconcilePendingReadOnly(Shop shop,OzonApiClient api,int maximumReads){
        MarketplaceGuard.requireOzon(shop);ListingIdentity.require(shop);
        if(maximumReads<1||maximumReads>3)throw new IllegalArgumentException("Readback budget must be between 1 and 3");
        int completed=0,remaining=maximumReads;
        for(var receipt:receipts.pending(shop)){
            var source=receipt.source();var numbers=new LinkedHashSet<>(receipt.children());
            var readbacks=new LinkedHashMap<>(receipt.readbacks());int next=receipt.nextChild();
            try{
                int expected=OzonPackageBuilder.unitCount(source);
                if(numbers.size()!=expected){
                    remaining--;
                    var raw=api.getPosting(source.postingNumber(),true,true);
                    var parent=OzonJson.requirePostingDetail(raw,source.postingNumber());
                    if(!source.orderId().equals(parent.orderId()))throw pending("OZON_SPLIT_FOREIGN_CHILD",null);
                    var related=OzonJson.object(OzonJson.object(raw,"result"),"related_postings").get("related_posting_numbers");
                    if(related!=null){
                        if(!related.isJsonArray()||related.getAsJsonArray().size()>OzonPackageBuilder.MAX_UNIT_PACKAGES)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
                        for(var value:related.getAsJsonArray())numbers.add(childNumber(value));
                    }
                    if(OzonPackageBuilder.unitCount(parent)==1){numbers.add(parent.postingNumber());if(validReadyChild(source,parent))readbacks.put(parent.postingNumber(),parent);}
                    if(numbers.size()!=expected)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
                    receipts.update(shop,source.postingNumber(),"ACCEPTED",List.copyOf(numbers));
                }
                var ordered=List.copyOf(numbers);int visited=0;
                while(remaining>0&&visited<ordered.size()){
                    String number=ordered.get(Math.floorMod(next,ordered.size()));next=Math.floorMod(next+1,ordered.size());visited++;
                    if(readbacks.containsKey(number))continue;
                    remaining--;ListingIdentity.require(shop);
                    var child=OzonJson.requirePostingDetail(api.getPosting(number,true,true),number);
                    if(validReadyChild(source,child))readbacks.put(number,child);
                }
                if(readbacks.keySet().equals(numbers)){
                    var actual=new TreeMap<String,Integer>();for(var child:readbacks.values())for(var item:child.items())actual.merge(sku(item),item.quantity(),Math::addExact);
                    if(!quantities(source).equals(actual)){
                        // A complete but inconsistent snapshot must be read again:
                        // retaining it would skip every GET forever after Ozon
                        // corrects its asynchronous child data.
                        readbacks.clear();
                        throw pending("OZON_SPLIT_QUANTITY_MISMATCH",null);
                    }
                    receipts.complete(shop,source.postingNumber(),ordered.stream().map(readbacks::get).toList());
                    jobs.logAction(shop.getId(),"ship_units",source.postingNumber(),"reconciled",null,receipt.fingerprint());completed++;
                }
            }catch(IOException|RuntimeException waiting){
                String code=waiting instanceof OzonApiException error?error.safeErrorCode():"OZON_SPLIT_READBACK_REQUIRED";
                LOGGER.warn("Ozon unit shipment readback remains pending: {}",code);
            }finally{
                // Advance even on bad/foreign data. The independent normal FBS status
                // batch still runs after this bounded read-only reconciliation turn.
                receipts.readbackProgress(shop,source.postingNumber(),readbacks,next);
            }
            if(remaining<=0)break;
        }return completed;
    }
    private static boolean validReadyChild(OzonPostingDto source,OzonPostingDto child)throws IOException {
        if(!source.orderId().equals(child.orderId())||!source.warehouseId().equals(child.warehouseId())||OzonPackageBuilder.unitCount(child)!=1)throw pending("OZON_SPLIT_FOREIGN_CHILD",null);
        if("ship_failed".equals(child.substatus()))throw pending("OZON_SPLIT_SHIP_FAILED",null);
        return Set.of("awaiting_deliver","delivering","delivered").contains(child.status());
    }
    private static List<String> responseChildren(JsonObject response,int expected)throws IOException {
        var result=response.get("result");if(result==null||!result.isJsonArray()||result.getAsJsonArray().isEmpty()||result.getAsJsonArray().size()>expected)throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
        var numbers=new LinkedHashSet<String>();for(var value:result.getAsJsonArray())if(!numbers.add(childNumber(value)))throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
        return List.copyOf(numbers);
    }
    private static String childNumber(JsonElement value)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw pending("OZON_SPLIT_READBACK_REQUIRED",null);
        try{return OzonApiClient.requireExternalId(value.getAsString(),"child posting");}catch(RuntimeException invalid){throw pending("OZON_SPLIT_READBACK_REQUIRED",invalid);}
    }
    private static Map<String,Integer> quantities(OzonPostingDto posting){var quantities=new TreeMap<String,Integer>();for(var item:posting.items())quantities.merge(sku(item),item.quantity(),Math::addExact);return quantities;}
    private static String sku(OzonPostingItemDto item){return Long.toString(Long.parseLong(item.sku().isBlank()?item.productId():item.sku()));}
    private static boolean containsMarks(JsonElement value){
        if(value==null||value.isJsonNull())return false;
        if(value.isJsonArray()){for(var item:value.getAsJsonArray())if(containsMarks(item))return true;}
        if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()){
            if(entry.getKey().equals("marks")&&!entry.getValue().isJsonNull()&&!entry.getValue().isJsonArray())throw new IllegalStateException("OZON_SPLIT_MARK_DATA_INVALID");
            if(Set.of("mandatory_mark","mark").contains(entry.getKey())&&hasCode(entry.getValue()))return true;
            if(containsMarks(entry.getValue()))return true;
        }return false;
    }
    private static boolean hasCode(JsonElement value){
        if(value==null||value.isJsonNull())return false;
        if(value.isJsonPrimitive()){
            if(!value.getAsJsonPrimitive().isString())throw new IllegalStateException("OZON_SPLIT_MARK_DATA_INVALID");
            return !value.getAsString().isBlank();
        }
        if(value.isJsonArray())for(var code:value.getAsJsonArray())if(hasCode(code))return true;
        if(value.isJsonObject())throw new IllegalStateException("OZON_SPLIT_MARK_DATA_INVALID");return false;
    }
    private static OzonApiException pending(String code,Throwable cause){return new OzonApiException("reconcile_required",0,false,true,cause,null,code);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
