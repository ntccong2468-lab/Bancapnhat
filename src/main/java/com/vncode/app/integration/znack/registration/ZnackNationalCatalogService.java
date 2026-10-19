package com.vncode.app.integration.znack.registration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.ZnackApiClient;
import com.vncode.app.integration.znack.ZnackAuthService;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.ZnackSanitizer;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Category;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Draft;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Gs1Status;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** National Catalog card workflow primitives. All methods are blocking and must run off the FX thread. */
public final class ZnackNationalCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackNationalCatalogService.class);
    private static final Object GTIN_ALLOCATION_LOCK = new Object();
    public static final long DECLARATION_ATTRIBUTE_ID = 23_557L;
    public static final long CERTIFICATE_ATTRIBUTE_ID = 23_561L;

    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackSignatureProvider signer;
    private final ZnackModels.Settings settings;
    private record PermitReadback(JsonElement response,java.time.Instant checkedAt){}
    private final java.util.Map<ZnackModels.GoodsDocument,PermitReadback> permitReadbacks=new java.util.concurrent.ConcurrentHashMap<>();

    public ZnackNationalCatalogService(ZnackApiClient api, ZnackAuthService auth,
                                       ZnackSignatureProvider signer, ZnackModels.Settings settings) {
        this.api = api;
        this.auth = auth;
        this.signer = signer;
        this.settings = settings;
    }

    public Preflight preflight(String tnved) throws Exception {
        String normalized = digits(tnved);
        if (normalized.length() < 4 || normalized.length() > 10) {
            throw new IllegalArgumentException("TN VED must contain 4 to 10 digits.");
        }
        LicenseRuntime.require(Permission.SELLER_API);
        String token = auth.trueApiToken(settings);
        LicenseRuntime.require(Permission.SELLER_API);
        Gs1Status gs1 = parseGs1(api.generatedGtins(settings.resolvedTrueApiBaseUrl(), token));
        if (gs1.quotaKnown() && !gs1.canGenerate() && gs1.existingDrafts() == 0) {
            throw new IllegalStateException("GS1/GTIN quota is unavailable or exhausted (" + gs1.usage()
                    + "/" + gs1.limit() + "). Check the active GS1 RUS membership in National Catalog.");
        }
        List<Category> categories = List.of();
        String categoryTnved = normalized;
        for (String lookupTnved : categoryLookupCodes(normalized)) {
            LicenseRuntime.require(Permission.SELLER_API);
            categories = parseCategories(api.nationalCatalogCategories(
                    settings.resolvedTrueApiBaseUrl(), token, lookupTnved));
            if (!categories.isEmpty()) {
                categoryTnved = lookupTnved;
                break;
            }
        }
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("No active National Catalog category was found for TN VED "
                    + normalized + (normalized.length() == 10 ? " or its four-digit group "
                    + normalized.substring(0, 4) : "") + ".");
        }
        return new Preflight(normalized, categoryTnved, gs1, categories, token);
    }

    /** Correcting an existing GTIN consumes no GS1 quota. */
    public Preflight preflightExisting(String tnved,String token)throws Exception {
        if(tnved==null||!tnved.matches("[0-9]{10}"))throw new IllegalArgumentException("ZNACK_MISSING_TNVED");
        for(String code:categoryLookupCodes(tnved)){
            LicenseRuntime.require(Permission.SELLER_API);
            var categories=parseCategories(api.nationalCatalogCategories(settings.resolvedTrueApiBaseUrl(),token,code));
            if(!categories.isEmpty())return new Preflight(tnved,code,new Gs1Status(0,0,0,false),categories,token);
        }
        throw new IllegalArgumentException("ZNACK_CATEGORY_UNRESOLVED");
    }

    /** Read a rejected draft by its exact identity; active published revisions are protected. */
    public JsonObject rejectedCard(String token,String gtin,Long expectedId)throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);
        var card=readCard(token,gtin,expectedId);
        if(card==null||!"draft".equalsIgnoreCase(string(card,"good_status"))
                ||detailedState(card).stream().noneMatch(Set.of("errors","error","rejected")::contains))
            throw new IllegalStateException("CATALOG_CARD_NOT_REJECTED");
        return card.deepCopy();
    }

    public List<Attribute> requiredAttributes(long categoryId, String token) throws Exception {
        LicenseRuntime.require(Permission.SELLER_API);
        List<Attribute> attributes = parseAttributes(api.nationalCatalogAttributes(
                settings.resolvedTrueApiBaseUrl(), token, categoryId));
        if (attributes.isEmpty()) {
            throw new IllegalStateException("National Catalog returned no mandatory attribute model for category "
                    + categoryId + ". Check that the category is active and try again.");
        }
        return attributes;
    }

    public String generateOne(String token) throws Exception {
        return generateOne(token, Set.of());
    }

    public String generateFreshOne(String token, Set<String> claimedGtins) throws Exception {
        return allocate(token, claimedGtins, false);
    }

    /**
     * Allocates one unclaimed National Catalog draft GTIN.
     *
     * <p>The catalog may already contain unused codes from an earlier interrupted request. Reuse
     * those first. If a quantity request succeeds without returning {@code result.drafts}, read
     * the draft list back instead of issuing the state-changing request again.</p>
     */
    public String generateOne(String token, Set<String> claimedGtins) throws Exception {
        return allocate(token, claimedGtins, true);
    }

    private String allocate(String token, Set<String> claimedGtins, boolean reuseExisting) throws Exception {
        LicenseRuntime.require(Permission.GTIN);
        Set<String> claimed = normalizedGtins(claimedGtins);
        synchronized (GTIN_ALLOCATION_LOCK) {
            LicenseRuntime.require(Permission.GTIN);
            JsonElement beforeResponse = api.generatedGtins(settings.resolvedTrueApiBaseUrl(), token);
            List<String> before = draftGtins(beforeResponse);
            String reusable = firstUnclaimed(before, claimed);
            if (reuseExisting && !reusable.isBlank()) {
                LOGGER.info("Reusing an existing unclaimed National Catalog draft GTIN.");
                return reusable;
            }

            LicenseRuntime.require(Permission.GTIN);
            JsonElement generatedResponse = api.generateGtins(
                    settings.resolvedTrueApiBaseUrl(), token, 1);
            Set<String> previous = new LinkedHashSet<>(before);
            List<String> returned = draftGtins(generatedResponse).stream()
                    .filter(value -> !claimed.contains(value))
                    .filter(value -> reuseExisting || !previous.contains(value)).toList();
            String generated = returned.size() == 1 ? returned.getFirst() : "";
            if (!generated.isBlank()) return generated;

            // A successful allocation response without drafts is ambiguous. The allocation call
            // must never be repeated automatically because it can consume another GS1 number.
            JsonElement readBackResponse = api.generatedGtins(
                    settings.resolvedTrueApiBaseUrl(), token);
            List<String> readBack = draftGtins(readBackResponse);
            List<String> candidates = readBack.stream()
                    .filter(value -> !claimed.contains(value))
                    .filter(value -> !previous.contains(value))
                    .toList();
            String reconciled = candidates.size()==1 ? candidates.getFirst() : "";
            if (!reconciled.isBlank()) {
                LOGGER.warn("National Catalog omitted drafts from the allocation response; "
                        + "recovered the allocated GTIN through exist=1.");
                return reconciled;
            }

            String apiError = firstApiError(generatedResponse);
            String response = ZnackSanitizer.message(
                    generatedResponse == null ? "null" : generatedResponse.toString());
            throw new IllegalStateException(apiError.isBlank()
                    ? "National Catalog accepted the GTIN allocation request but returned no draft GTIN. "
                    + "VN code did not repeat the allocation request to avoid consuming a second number. "
                    + "Response: " + response
                    : apiError + " Response: " + response);
        }
    }

    public String submit(String token, JsonObject payload) throws Exception {
        LicenseRuntime.require(Permission.GTIN);
        validateDocuments(token,payload);
        LicenseRuntime.require(Permission.GTIN);
        JsonObject result = resultObject(api.submitNationalCatalogFeed(
                settings.resolvedTrueApiBaseUrl(), token, payload));
        String feedId = string(result, "feed_id");
        if (feedId.isBlank()) throw new IllegalStateException("National Catalog did not return feed_id.");
        return feedId;
    }

    public ZnackRegistrationDocuments.Selection reviewDocuments(String token,ZnackRegistrationDocuments.Selection selected,String tnved)throws Exception {
        var valid=new ArrayList<ZnackModels.GoodsDocument>();var issues=new ArrayList<String>();
        for(var document:selected.candidates()){
            var cached=permitReadbacks.get(document);
            JsonElement response=cached!=null&&cached.checkedAt().isAfter(java.time.Instant.now().minusSeconds(120))?cached.response():null;
            if(response==null){
                LicenseRuntime.require(Permission.SELLER_API);
                var request=new JsonArray();request.add(RegistrationPermitValidator.request(document));
                response=api.registrationPermitInfo(settings.resolvedTrueApiBaseUrl(),token,request);
                // Pending results must be checked again on the next attempt.
                if(response!=null&&!com.vncode.app.integration.znack.ZnackPermitDocumentParser.registryLookupPending(response))permitReadbacks.put(document,new PermitReadback(response,java.time.Instant.now()));
            }
            String problem=RegistrationPermitValidator.problem(response,document,tnved,java.time.LocalDate.now());
            if(problem.isBlank())valid.add(document);else issues.add(document.number()+": "+problem);
        }
        // Registry readback proves the number, dates, status and TN VED scope. One
        // remaining valid permit no longer needs a per-card seller selection.
        var automatic=valid.contains(selected.automatic())?selected.automatic():valid.size()==1?valid.getFirst():null;
        return new ZnackRegistrationDocuments.Selection(valid,automatic,selected.source(),automatic==null,issues);
    }
    public void validateDocuments(String token,Draft draft)throws Exception {validateDocuments(token,buildPayload("",draft,""));}
    private void validateDocuments(String token,JsonObject payload)throws Exception {
        var documents=new ArrayList<ZnackModels.GoodsDocument>();
        String tnved=string(payload,"tnved"),gender="";
        for(var value:array(payload.get("good_attrs")))if(value.isJsonObject()){
            var a=value.getAsJsonObject();long id=number(a,"attr_id");
            if(id==13933)tnved=string(a,"attr_value");
            if(id==14013)gender=string(a,"attr_value");
            if(id!=DECLARATION_ATTRIBUTE_ID&&id!=CERTIFICATE_ATTRIBUTE_ID)continue;
            var parts=string(a,"attr_value").split(":::",2);
            if(parts.length!=2)throw new RegistrationValidationException("ZNACK_DOCUMENT_DATE_REQUIRED");
            documents.add(new ZnackModels.GoodsDocument(id==DECLARATION_ATTRIBUTE_ID?"CONFORMITY_DECLARATION":"CONFORMITY_CERTIFICATE",parts[0].strip(),parts[1].strip()));
        }
        String tariffProblem=RegistrationTariffRules.genderProblem(tnved,gender);
        if(!tariffProblem.isBlank())throw new RegistrationValidationException(tariffProblem);
        if(documents.isEmpty())return;
        var checked=reviewDocuments(token,new ZnackRegistrationDocuments.Selection(documents,null,ZnackRegistrationDocuments.Source.PRODUCT,true),tnved);
        if(!checked.issues().isEmpty())throw new RegistrationValidationException(String.join("; ",checked.issues()));
    }

    public FeedProgress progress(String token, String feedId, String gtin) throws Exception {
        JsonObject result = resultObject(api.nationalCatalogFeedStatus(
                settings.resolvedTrueApiBaseUrl(), token, feedId));
        String status = string(result, "status");
        List<String> errors = new ArrayList<>();
        Long goodId = null;
        Set<String> exactIdentities = new LinkedHashSet<>();
        for (JsonElement element : array(result.get("item"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemGtin = string(item, "gtin");
            if (!itemGtin.isBlank() && !com.vncode.app.integration.znack.GtinNormalizer.normalize(itemGtin)
                    .equals(com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin))) continue;
            if(!itemGtin.isBlank())exactIdentities.add(com.vncode.app.integration.znack.GtinNormalizer.normalize(itemGtin));
            if (!itemGtin.isBlank()&&item.has("good_id") && !item.get("good_id").isJsonNull()) {
                long identity=item.get("good_id").getAsLong();
                if(goodId!=null&&goodId!=identity)throw new IllegalStateException("CATALOG_IDENTITY_CHANGED");
                goodId=identity;
            }
            String message = first(string(item, "message"), string(item, "status_message"));
            int code = item.has("status_code") && !item.get("status_code").isJsonNull()
                    ? item.get("status_code").getAsInt() : 0;
            if (code != 0) errors.add(attributeError(item, message.isBlank()?"Catalog item status code "+code:message));
        }
        JsonObject details = object(result.get("error_details"));
        collectFeedErrors(details.get("commonError"), errors);
        for (JsonElement element : array(details.get("items"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemGtin=string(item,"gtin");
            if(!itemGtin.isBlank()&&!com.vncode.app.integration.znack.GtinNormalizer.normalize(itemGtin)
                    .equals(com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin)))continue;
            // API 5.68 defines errors as an object; some gateways return an array instead.
            collectFeedErrors(item.get("errors"), errors);
        }
        if(number(details,"totalErrors")>0&&errors.isEmpty())errors.add("CATALOG_VALIDATION_ERRORS_UNAVAILABLE");
        if(errors.isEmpty()&&("Signed".equalsIgnoreCase(status) || "Moderated".equalsIgnoreCase(status)) && exactIdentities.size()!=1)
            throw new IllegalStateException("Catalog feed readback did not confirm the requested GTIN.");
        if(errors.isEmpty()&&"Signed".equalsIgnoreCase(status) && (goodId==null || goodId<=0))
            throw new IllegalStateException("Catalog feed readback did not confirm the signed card identity.");
        boolean publicationVerified=false;
        if(Set.of("received","processing","moderated").contains(status.toLowerCase(Locale.ROOT))&&errors.isEmpty()){
            // The feed can lag behind the card. Inspect the exact product, as well as the feed,
            // before reporting moderation or asking CryptoPro to sign an already published card.
            var card=readCard(token,gtin,goodId);
            if(card!=null){
                goodId=number(card,"good_id");if(goodId==0)goodId=number(card,"goodId");
                var detailsState=detailedState(card);
                if(!isPublished(card)&&!java.util.Collections.disjoint(detailsState,Set.of("errors","error","rejected"))){
                    status="Rejected";errors.add("CATALOG_CARD_REJECTED_DETAILS_UNAVAILABLE");
                }else if(detailsState.contains("moderation"))status="CardModeration";
                else if(!isPublished(card)&&detailsState.contains("draft")&&!detailsState.contains("notsigned"))status="CardDraft";
                else if(!isPublished(card)&&detailsState.contains("notsigned"))status="Moderated";
                else if("Moderated".equalsIgnoreCase(status)&&isPublished(card)
                        &&java.util.Collections.disjoint(detailsState,Set.of("draft","moderation","errors","notsigned"))){status="Signed";publicationVerified=true;}
            }
        }
        if(Set.of("processed","done","completed").contains(status.toLowerCase(Locale.ROOT))&&errors.isEmpty()){
            if(array(result.get("item")).size()>0&&exactIdentities.size()!=1)throw new IllegalStateException("CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
            var card=readCard(token,gtin,goodId);
            if(card==null){status="Rejected";errors.add("CATALOG_FEED_COMPLETED_WITHOUT_CARD");}
            else if(isPublished(card)){
                if(exactIdentities.size()!=1)throw new IllegalStateException("CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
                goodId=number(card,"good_id");if(goodId==0)goodId=number(card,"goodId");status="Signed";publicationVerified=true;
            }
        }
        // A signed feed is an acknowledgement for the import/signing request. It is
        // not proof that the product card is visible and published in the catalog.
        if("Signed".equalsIgnoreCase(status) && errors.isEmpty()&&!publicationVerified
                && !publishedCard(token,gtin,goodId))status="PublicationPending";
        return new FeedProgress(status, goodId, List.copyOf(errors));
    }

    private static String attributeError(JsonObject error,String message){
        String attribute=first(string(error,"attribute_name"),string(error,"attribute_id"),string(error,"attr_id"));
        return ZnackSanitizer.message(attribute.isBlank()?message:attribute+": "+message);
    }
    private static Set<String> detailedState(JsonObject card){
        var result=new LinkedHashSet<String>();var raw=card.get("good_detailed_status");
        if(raw!=null&&raw.isJsonArray())for(var value:raw.getAsJsonArray())if(value.isJsonPrimitive())result.add(value.getAsString().strip().toLowerCase(Locale.ROOT));
        if(raw!=null&&raw.isJsonPrimitive())result.add(raw.getAsString().strip().toLowerCase(Locale.ROOT));
        return result;
    }
    private static void collectFeedErrors(JsonElement value,List<String> errors){
        if(value==null||value.isJsonNull())return;
        if(value.isJsonArray()){for(var error:value.getAsJsonArray())collectFeedErrors(error,errors);return;}
        if(!value.isJsonObject())return;
        var error=value.getAsJsonObject();
        String message=first(string(error,"text"),string(error,"message"),string(error,"status_message"));
        if(!message.isBlank())errors.add(attributeError(error,message));
        else if(error.has("code")&&!string(error,"code").isBlank()&&!string(error,"code").equals("0"))errors.add(attributeError(error,"Catalog error "+string(error,"code")));
    }

    private boolean publishedCard(String token,String gtin,Long expectedId)throws Exception {
        var card=readCard(token,gtin,expectedId);return card!=null&&isPublished(card);
    }
    /** Absence is confirmed only by a successful, identity-checked Catalog response. */
    public boolean cardExists(String token,String gtin,Long expectedId)throws Exception{return readCard(token,gtin,expectedId)!=null;}
    private static boolean isPublished(JsonObject card){
        if(!"published".equalsIgnoreCase(first(string(card,"good_status"),string(card,"goodStatus"),string(card,"cardStatus"))))return false;
        // Published + draft is a published version with a draft revision, not a failed card.
        // An explicitly unsigned response, however, cannot prove that our signing completed.
        var signed=card.get("good_signed");return signed==null||signed.isJsonNull()
                ||signed.isJsonPrimitive()&&signed.getAsJsonPrimitive().isBoolean()&&signed.getAsBoolean();
    }
    private JsonObject readCard(String token,String gtin,Long expectedId)throws Exception {
        JsonElement response=unwrap(api.productCards(settings.resolvedTrueApiBaseUrl(),token,gtin));
        if(response==null||!response.isJsonArray())throw new IllegalStateException("CATALOG_PUBLICATION_RESPONSE_INVALID");
        JsonObject selected=null;
        for(var element:response.getAsJsonArray()){
            if(!element.isJsonObject())throw new IllegalStateException("CATALOG_PUBLICATION_RESPONSE_INVALID");
            var card=element.getAsJsonObject();
            boolean matches=com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,string(card,"gtin"));
            for(var identifier:array(card.get("identified_by")))if(identifier.isJsonObject()){
                var id=identifier.getAsJsonObject();
                if("gtin".equalsIgnoreCase(string(id,"type"))&&com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,string(id,"value")))matches=true;
            }
            if(matches){if(selected!=null)throw new IllegalStateException("CATALOG_PUBLICATION_AMBIGUOUS");selected=card;}
        }
        if(selected==null){if(!response.getAsJsonArray().isEmpty())throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");return null;}
        long actualId=number(selected,"good_id");if(actualId==0)actualId=number(selected,"goodId");
        if(actualId<=0||expectedId!=null&&actualId!=expectedId)throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");
        return selected;
    }

    public record SigningDocument(long goodId, byte[] xml, String sha256) {
        public SigningDocument {
            if (goodId <= 0 || xml == null || xml.length == 0 || xml.length > 4 * 1024 * 1024)
                throw new IllegalArgumentException("Invalid Catalog signing document.");
            xml = xml.clone();
            String actual = digest(xml);
            if (sha256 == null || !actual.equals(sha256)) throw new IllegalArgumentException("Catalog signing document changed.");
        }
        public SigningDocument(long goodId,byte[] xml){this(goodId,xml,digest(xml));}
        @Override public byte[] xml(){return xml.clone();}
        @Override public String toString(){return "SigningDocument[redacted]";}
    }
    @FunctionalInterface public interface BeforeSigningSend { void check() throws Exception; }

    /** Read only. The caller freezes this exact document in its durable review before signing. */
    public SigningDocument reviewSigning(String token,String gtin) throws Exception {
        LicenseRuntime.require(Permission.GTIN);
        JsonObject request = new JsonObject();
        JsonArray gtins = new JsonArray(); gtins.add(gtin); request.add("gtins", gtins);
        request.addProperty("publicationAgreement", true);
        JsonObject document = resultObject(api.nationalCatalogSigningDocument(
                settings.resolvedTrueApiBaseUrl(), token, request));
        JsonArray xmls = array(document.get("xmls"));
        if (xmls.size() != 1 || !xmls.get(0).isJsonObject()) {
            throw new IllegalStateException("The card is not ready for signing.");
        }
        JsonObject item=xmls.get(0).getAsJsonObject();
        return new SigningDocument(item.get("goodId").getAsLong(),string(item,"xml").getBytes(StandardCharsets.UTF_8));
    }

    /** Exactly one reviewed POST. The durable caller must reconcile an uncertain result. */
    public long sendReviewedSigning(String token,SigningDocument document,BeforeSigningSend beforeSend) throws Exception {
        LicenseRuntime.require(Permission.GTIN);
        byte[] xmlBytes=document.xml();
        byte[] signature=signer.sign(xmlBytes,ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
        JsonObject signed = new JsonObject(); signed.addProperty("goodId",document.goodId());
        signed.addProperty("base64Xml",Base64.getEncoder().encodeToString(xmlBytes));
        signed.addProperty("signature",Base64.getEncoder().encodeToString(signature));
        JsonArray batch=new JsonArray();batch.add(signed);
        LicenseRuntime.require(Permission.GTIN);
        if(beforeSend!=null)beforeSend.check();
        JsonObject response=resultObject(api.signNationalCatalogProduct(settings.resolvedTrueApiBaseUrl(),token,batch));
        for(JsonElement id:array(response.get("signed")))if(id.getAsLong()==document.goodId())return document.goodId();
        throw new IllegalStateException("National Catalog did not confirm the signature. Refresh to reconcile before continuing.");
    }
    public long sign(String token,String gtin)throws Exception {
        return sendReviewedSigning(token,reviewSigning(token,gtin),null);
    }
    private static String digest(byte[] value) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    public static JsonObject buildPayload(String gtin, Draft draft, String imageUrl) {
        return RegistrationTemplate.defaults().build(gtin,draft,imageUrl);
    }

    /* Payload construction lives in the versioned default template shared by WB and Ozon. */
    static JsonObject fillTemplate(JsonObject payload,String gtin,Draft draft,String imageUrl) {
        if(draft.isKit())payload.addProperty("is_kit",true);
        if (imageUrl != null && !imageUrl.isBlank()) {
            JsonObject image = new JsonObject();
            image.addProperty("photo_type", "default");
            image.addProperty("photo_url", imageUrl);
            image.addProperty("identifier", gtin);
            image.addProperty("identifier_type", "gtin");
            JsonArray images = new JsonArray();
            images.add(image);
            payload.add("good_images", images);
        }

        JsonArray attributes = new JsonArray();
        draft.attributes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() == null || entry.getValue().isBlank()) return;
            JsonObject attribute = new JsonObject();
            attribute.addProperty("attr_id", entry.getKey());
            attribute.addProperty("attr_value", RegistrationTemplate.clean(entry.getValue()));
            String type=RegistrationTemplate.clean(draft.attributeTypes().getOrDefault(entry.getKey(), ""));
            attribute.addProperty("attr_value_type",Set.of("---","...").contains(type)?"":type);
            attributes.add(attribute);
        });
        payload.add("good_attrs", attributes);
        return payload;
    }

    /** Picks the closest active light-industry catalog leaf without asking on every SKU. */
    public static Category selectLightIndustryCategory(List<Category> categories, String wbSubject) {
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("No National Catalog category is available.");
        }
        if (categories.size() == 1) return categories.get(0);
        Set<String> subjectWords = words(wbSubject);
        return categories.stream()
                .max(Comparator.comparingInt(category -> categoryScore(category.name(), subjectWords)))
                .orElse(categories.get(0));
    }

    private static int categoryScore(String categoryName, Set<String> subjectWords) {
        String normalized = normalize(categoryName);
        int score = 0;
        for (String keyword : List.of("легк", "одежд", "бель", "трикотаж", "текстил", "обув",
                "брюк", "юбк", "плать", "куртк", "носк", "чул", "головн", "перчат")) {
            if (normalized.contains(keyword)) score += 10;
        }
        for (String word : subjectWords) if (word.length() > 3 && normalized.contains(word)) score += 100;
        return score;
    }

    private static Set<String> words(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : normalize(value).split("[^\\p{L}\\p{N}]+")) if (!word.isBlank()) words.add(word);
        return words;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    static Gs1Status parseGs1(JsonElement response) {
        JsonObject result = resultObject(response);
        JsonObject monthly = object(result.get("monthly-limit"));
        long limit = number(monthly, "limit");
        long usage = number(monthly, "usage");
        boolean quotaKnown = monthly.has("limit") && monthly.has("usage");
        return new Gs1Status(limit, usage, draftGtins(response).size(), quotaKnown);
    }

    static List<String> draftGtins(JsonElement response) {
        JsonElement unwrapped = unwrap(response);
        List<String> values = new ArrayList<>();
        if (unwrapped != null && unwrapped.isJsonObject()) {
            collectGtins(unwrapped.getAsJsonObject().get("drafts"), values);
            // Be tolerant of gateway adapters that expose the generated values as gtins.
            if (values.isEmpty()) collectGtins(unwrapped.getAsJsonObject().get("gtins"), values);
        } else if (unwrapped != null && unwrapped.isJsonArray()) {
            collectGtins(unwrapped, values);
        }
        return values.stream().distinct().toList();
    }

    private static void collectGtins(JsonElement source, List<String> target) {
        if (source == null || source.isJsonNull()) return;
        if (source.isJsonArray()) {
            for (JsonElement item : source.getAsJsonArray()) collectGtins(item, target);
            return;
        }
        String value = "";
        if (source.isJsonObject()) {
            JsonElement gtin = source.getAsJsonObject().get("gtin");
            if (gtin != null && (gtin.isJsonArray() || gtin.isJsonObject())) {
                collectGtins(gtin, target);
                return;
            }
            if (gtin != null && gtin.isJsonPrimitive()) value = gtin.getAsString();
        } else if (source.isJsonPrimitive()) value = source.getAsString();
        String normalized = normalizeGtin(value);
        if (!normalized.isBlank()) target.add(normalized);
    }

    private static Set<String> normalizedGtins(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            String normalized = normalizeGtin(value);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
    }

    private static String firstUnclaimed(List<String> candidates, Set<String> claimed) {
        if (candidates == null) return "";
        return candidates.stream().filter(value -> !claimed.contains(value)).findFirst().orElse("");
    }

    private static String normalizeGtin(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("\\d{8,14}") ? normalized : "";
    }

    static List<Category> parseCategories(JsonElement response) {
        List<Category> active = new ArrayList<>();
        List<Category> all = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "cat_id");
            if (id <= 0) continue;
            Category category = new Category(id, string(value, "cat_name"));
            all.add(category);
            if (!value.has("category_active") || value.get("category_active").getAsBoolean()) active.add(category);
        }
        return active.isEmpty() ? all : active;
    }

    static List<String> categoryLookupCodes(String tnved) {
        if (tnved != null && tnved.length() == 10) {
            // National Catalog API v5.62 allows a product with a ten-digit TN VED to use the
            // registered four-digit group. During /v3/feed it stores the group in attribute
            // 3959 and the original ten-digit value in attribute 13933.
            return List.of(tnved, tnved.substring(0, 4));
        }
        return List.of(tnved);
    }

    static List<Attribute> parseAttributes(JsonElement response) {
        List<Attribute> values = new ArrayList<>();
        for (JsonElement element : resultArray(response)) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            long id = number(value, "attr_id");
            if (id <= 0) continue;
            List<String> presets = new ArrayList<>();
            for (JsonElement preset : array(value.get("attr_preset"))) {
                if (preset.isJsonPrimitive()) presets.add(preset.getAsString());
            }
            List<String> valueTypes = new ArrayList<>();
            for (JsonElement type : array(value.get("attr_value_type"))) {
                if (type.isJsonPrimitive()) valueTypes.add(type.getAsString());
            }
            values.add(new Attribute(id, string(value, "attr_name"), string(value, "attr_field_type"),
                    bool(value, "attr_preset_only"), bool(value, "attr_multiplicity"),
                    bool(value, "first_layer"), bool(value, "second_layer"), presets, valueTypes));
        }
        return List.copyOf(values);
    }

    private static JsonObject resultObject(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray resultArray(JsonElement response) {
        JsonElement result = unwrap(response);
        return result != null && result.isJsonArray() ? result.getAsJsonArray() : new JsonArray();
    }

    private static JsonElement unwrap(JsonElement response) {
        if (response == null || response.isJsonNull()) return null;
        if (response.isJsonObject() && response.getAsJsonObject().has("result")) return response.getAsJsonObject().get("result");
        return response;
    }

    private static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private static long number(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return 0L;
        try { return value.get(key).getAsLong(); } catch (RuntimeException ignored) { return 0L; }
    }

    private static boolean bool(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean();
    }

    private static String firstError(JsonObject result) {
        for (JsonElement value : array(result.get("errors"))) {
            if (value.isJsonObject()) {
                String message = first(string(value.getAsJsonObject(), "message"),
                        string(value.getAsJsonObject(), "error"),
                        string(value.getAsJsonObject(), "text"));
                if (!message.isBlank()) return message;
            }
        }
        for (JsonElement value : array(result.get("globalErrors"))) {
            if (value.isJsonObject()) {
                String message = first(string(value.getAsJsonObject(), "message"),
                        string(value.getAsJsonObject(), "error"),
                        string(value.getAsJsonObject(), "text"));
                if (!message.isBlank()) return message;
            }
        }
        return "";
    }

    private static String firstApiError(JsonElement response) {
        JsonObject root = response != null && response.isJsonObject()
                ? response.getAsJsonObject() : new JsonObject();
        JsonObject result = resultObject(response);
        String error = firstError(result);
        if (!error.isBlank()) return error;
        error = firstError(root);
        if (!error.isBlank()) return error;
        return first(string(result, "message"), string(result, "error"),
                string(root, "message"), string(root, "error"));
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public record Preflight(String tnved, String categoryTnved, Gs1Status gs1,
                            List<Category> categories, String token) { }
    public record FeedProgress(String status, Long goodId, List<String> errors) {
        public boolean failed() { return "Rejected".equalsIgnoreCase(status) || !errors.isEmpty(); }
        public boolean readyToSign() { return "Moderated".equalsIgnoreCase(status) && errors.isEmpty(); }
        public boolean signed() { return "Signed".equalsIgnoreCase(status) && errors.isEmpty(); }
        public String errorMessage() { return String.join("; ", errors); }
    }
}
