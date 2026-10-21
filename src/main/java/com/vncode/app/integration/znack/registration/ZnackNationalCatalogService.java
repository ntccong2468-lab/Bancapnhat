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
import java.util.Collections;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** National Catalog card workflow primitives. All methods are blocking and must run off the FX thread. */
public final class ZnackNationalCatalogService {
    private static final String REVIEWED_DRAFT="_vncode_reviewed_draft";
    private static final Logger LOGGER = LoggerFactory.getLogger(ZnackNationalCatalogService.class);
    private static final Object GTIN_ALLOCATION_LOCK = new Object();
    public static final long DECLARATION_ATTRIBUTE_ID = 23_557L;
    public static final long CERTIFICATE_ATTRIBUTE_ID = 23_561L;
    private record SchemaReadback(List<Attribute> attributes,java.time.Instant checkedAt){}
    private final java.util.Map<Long,SchemaReadback> schemas=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long,SchemaReadback> completeSchemas=new java.util.concurrent.ConcurrentHashMap<>();
    public record CountryDictionaryEvidence(String source,String environment,String scopeFingerprint,
            String operationId,int apiVersion,String etag,String contentHash,java.time.Instant loadedAt,
            int entries,String reusePolicy){}
    private record CountryReadback(java.util.Map<String,String> aliases,List<String> codes,CountryDictionaryEvidence evidence){}
    private volatile CountryReadback countries;
    private final ThreadLocal<CountryReadScope> countryReadScopes=new ThreadLocal<>();
    private static final class CountryReadState {
        final String operationId=java.util.UUID.randomUUID().toString();
        final Set<String> models=new HashSet<>();
        final java.util.Map<Long,List<Attribute>> mandatory=new java.util.HashMap<>(),complete=new java.util.HashMap<>(),registration=new java.util.HashMap<>();
        CountryReadback snapshot;Exception failure;String credentialScope;
    }
    /** One verified immutable dictionary for a read/registration operation; never an HTTP cache. */
    public final class CountryReadScope implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final CountryReadScope parent;
        private final CountryReadState state;
        private boolean closed;
        private CountryReadScope(CountryReadScope parent){this.parent=parent;this.state=parent==null?new CountryReadState():parent.state;}
        @Override public void close(){
            if(closed)return;
            if(owner!=Thread.currentThread()||countryReadScopes.get()!=this)throw new IllegalStateException("Country read scopes must close in their owning operation");
            closed=true;if(parent==null)countryReadScopes.remove();else countryReadScopes.set(parent);
        }
    }
    public CountryReadScope registrationCountryReadScope(){
        assertCountryAccess();var scope=new CountryReadScope(countryReadScopes.get());countryReadScopes.set(scope);return scope;
    }
    /** Historical diagnostics only; possession of evidence does not authorize reuse or a write. */
    public java.util.Optional<CountryDictionaryEvidence> countryDictionaryEvidence(){
        if(!dataProfile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))return java.util.Optional.empty();
        var snapshot=countries;return snapshot==null?java.util.Optional.empty():java.util.Optional.of(snapshot.evidence());
    }

    private final ZnackApiClient api;
    private final ZnackAuthService auth;
    private final ZnackSignatureProvider signer;
    private final ZnackModels.Settings settings;
    private final java.time.Clock permitClock;
    private final java.nio.file.Path dataProfile=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
    private record PermitReadback(JsonElement response,java.time.Instant checkedAt){}
    private final java.util.Map<ZnackModels.GoodsDocument,PermitReadback> permitReadbacks=new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PERMIT_READBACKS=256;
    private static final long TECHNICAL_REGULATION_ATTRIBUTE_ID=13_836L;
    private static final java.util.regex.Pattern TECHNICAL_REGULATION=java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}0-9])ТР\\s*(ТС|ЕАЭС)\\s*([0-9]{3})\\s*/\\s*([0-9]{4})(?![0-9])");

    public ZnackNationalCatalogService(ZnackApiClient api, ZnackAuthService auth,
                                       ZnackSignatureProvider signer, ZnackModels.Settings settings) {
        this(api,auth,signer,settings,java.time.Clock.systemUTC());
    }
    ZnackNationalCatalogService(ZnackApiClient api,ZnackAuthService auth,ZnackSignatureProvider signer,
                               ZnackModels.Settings settings,java.time.Clock permitClock){
        this.api = api;
        this.auth = auth;
        this.signer = signer;
        this.settings = settings;
        this.permitClock=java.util.Objects.requireNonNull(permitClock);
    }

    /** Owner-verified Catalog keys authenticate Catalog requests directly, without True API CMS. */
    public String catalogToken() throws Exception {
        return api.hasCatalogKey() ? "" : auth.trueApiToken(settings);
    }

    public Preflight preflight(String tnved) throws Exception {
        String normalized = digits(tnved);
        if (normalized.length() < 4 || normalized.length() > 10) {
            throw new IllegalArgumentException("TN VED must contain 4 to 10 digits.");
        }
        LicenseRuntime.require(Permission.SELLER_API);
        String token = catalogToken();
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
        if(tnved==null||!tnved.matches("(?:[0-9]{4}|[0-9]{10})"))throw new IllegalArgumentException("ZNACK_MISSING_TNVED");
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
        var cached=schemas.get(categoryId);if(cached!=null&&cached.checkedAt().isAfter(java.time.Instant.now().minusSeconds(120)))return cached.attributes();
        List<Attribute> attributes = parseAttributes(api.nationalCatalogAttributes(
                settings.resolvedTrueApiBaseUrl(), token, categoryId));
        if (attributes.isEmpty()) {
            throw new IllegalStateException("National Catalog returned no mandatory attribute model for category "
                    + categoryId + ". Check that the category is active and try again.");
        }
        if(attributes.stream().anyMatch(a->!a.dependencies().isEmpty())){
            LicenseRuntime.require(Permission.SELLER_API);
            var all=parseAttributes(api.nationalCatalogAllAttributes(settings.resolvedTrueApiBaseUrl(),token,categoryId));
            if(all.isEmpty())throw new IllegalStateException("CATALOG_CONDITIONAL_SCHEMA_UNAVAILABLE");
            cacheCompleteSchema(categoryId,all);
            attributes=RegistrationAttributeRules.resolve(attributes,all);
        }
        schemas.put(categoryId,new SchemaReadback(attributes,java.time.Instant.now()));return attributes;
    }

    /** Registration planning includes only the country optional field actually announced by this category. */
    public List<Attribute> registrationAttributes(long categoryId,String token)throws Exception {
        assertCountryAccess();
        if(countryReadScopes.get()==null)try(var scope=registrationCountryReadScope()){return registrationAttributes(categoryId,token);}
        var state=countryState(token);var existing=state.registration.get(categoryId);if(existing!=null)return existing;
        var required=registrationMandatoryAttributes(categoryId,token);
        var full=registrationCompleteAttributes(categoryId,token);
        var model=new java.util.LinkedHashMap<Long,Attribute>();required.forEach(attribute->model.put(attribute.id(),attribute));
        if(!model.containsKey(2630L)){
            var announced=full.stream().filter(attribute->attribute.id()==2630L).toList();
            if(announced.size()==1)model.put(2630L,announced.getFirst());
        }
        var country=model.get(2630L);
        if(country!=null){
            var dictionary=countryDictionary(token);
            if(!country.presetOnly())throw invalidCountry("Country attribute is not an official dictionary field");
            if(!country.presets().isEmpty()&&!dictionary.codes().containsAll(country.presets()))throw invalidCountry("Country schema contains a value outside the verified dictionary");
            var codes=country.presets().isEmpty()?dictionary.codes():country.presets();
            model.put(2630L,new Attribute(country.id(),country.name(),country.fieldType(),country.presetOnly(),country.multiple(),country.firstLayer(),country.secondLayer(),codes,country.valueTypes(),country.dependencies()));
        }
        var result=List.copyOf(model.values());if(country!=null)state.models.add(RegistrationReviewedValues.schemaRevision(result));state.registration.put(categoryId,result);return result;
    }
    private List<Attribute> registrationMandatoryAttributes(long category,String token)throws Exception {
        var state=countryState(token);var cached=state.mandatory.get(category);if(cached!=null)return cached;
        try{
            var required=parseAttributes(api.nationalCatalogAttributes(settings.resolvedTrueApiBaseUrl(),token,category));
            if(required.isEmpty())throw new IllegalStateException("CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE",new IllegalArgumentException("Current mandatory category model is empty"));
            if(required.stream().anyMatch(attribute->!attribute.dependencies().isEmpty()))required=RegistrationAttributeRules.resolve(required,registrationCompleteAttributes(category,token));
            state.mandatory.put(category,required);schemas.put(category,new SchemaReadback(required,java.time.Instant.now()));return required;
        }catch(Exception failure){state.failure=failure;throw failure;}
    }
    private List<Attribute> registrationCompleteAttributes(long category,String token)throws Exception {
        var state=countryState(token);var cached=state.complete.get(category);if(cached!=null)return cached;
        try{
            var attributes=parseAttributes(api.nationalCatalogAllAttributes(settings.resolvedTrueApiBaseUrl(),token,category));
            if(attributes.isEmpty())throw new IllegalStateException("CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE",new IllegalArgumentException("Current complete category model is empty"));
            state.complete.put(category,attributes);cacheCompleteSchema(category,attributes);return attributes;
        }catch(Exception failure){state.failure=failure;throw failure;}
    }
    /** Resolve an explicit country name/code through the same current official dictionary, without HTTP. */
    public String registrationCountryCode(String source,List<Attribute> schema){
        if(source==null||source.isBlank()||schema==null||!dataProfile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))return "";
        assertCountryAccess();var read=countryReadScopes.get();
        if(read==null||read.state.snapshot==null||read.state.failure!=null||!read.state.models.contains(RegistrationReviewedValues.schemaRevision(schema)))return "";
        var fields=schema.stream().filter(attribute->attribute.id()==2630L).toList();if(fields.size()!=1)return "";
        var field=fields.getFirst();String key=countryKey(source),code=read.state.snapshot.aliases().getOrDefault(key,"");
        return field.presets().contains(code)&&RegistrationAttributeRules.validFieldValue(field,code)?code:"";
    }
    private CountryReadback countryDictionary(String token)throws Exception {
        var state=countryState(token);if(state.snapshot!=null)return state.snapshot;
        try{
            var response=api.nationalCatalogCountries(settings.resolvedTrueApiBaseUrl(),token);
            assertCountryAccess();var snapshot=parseCountryDictionary(response,state.operationId,state.credentialScope);
            countries=snapshot;state.snapshot=snapshot;return snapshot;
        }catch(Exception failure){state.failure=failure;throw failure;}
    }
    private CountryReadState countryState(String token)throws Exception {
        assertCountryAccess();var scope=countryReadScopes.get();
        if(scope==null)throw new IllegalStateException("Country dictionary requires an owning read operation");
        var state=scope.state;String credential=countryScope(token);
        if(state.credentialScope!=null&&!state.credentialScope.equals(credential))state.failure=new ZnackRegistrationScope.ScopeChangedException();
        state.credentialScope=credential;if(state.failure!=null)throw state.failure;return state;
    }
    private CountryReadback parseCountryDictionary(JsonElement response,String operation,String credential){
        if(response==null||!response.isJsonObject())throw invalidCountry("Expected a JSON object with apiversion and result");
        var root=response.getAsJsonObject();
        if(!root.keySet().equals(Set.of("apiversion","result"))){
            String observed=com.vncode.app.integration.znack.ZnackErrorMessages.errorCode(response.toString());
            throw invalidCountry("Unexpected country response fields; business errors or incomplete envelopes cannot be used"+(observed.isBlank()?"":"; observed business errorCode="+observed));
        }
        var version=root.get("apiversion");
        if(!version.isJsonPrimitive()||!version.getAsJsonPrimitive().isNumber()||!version.getAsString().equals("3"))throw invalidCountry("Country response API version is not 3");
        var rawResult=root.get("result");if(!rawResult.isJsonObject())throw invalidCountry("Country result must contain the documented _etag and _list object");
        var result=rawResult.getAsJsonObject();
        if(!result.keySet().equals(Set.of("_etag","_list")))throw invalidCountry("Country result is incomplete or has unsupported pagination metadata");
        String etag=countryString(result.get("_etag"),"Country dictionary _etag");
        var rawEntries=result.get("_list");if(!rawEntries.isJsonArray())throw invalidCountry("Country dictionary _list must be an array");
        var entries=rawEntries.getAsJsonArray();if(entries.size()>1024)throw invalidCountry("Country dictionary exceeds the read bound");
        if(entries.isEmpty())throw new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",new IllegalArgumentException("The complete country dictionary contains no rows"));
        var aliases=new java.util.LinkedHashMap<String,String>();var ambiguous=new HashSet<String>();var codes=new LinkedHashSet<String>();
        var canonical=new java.util.TreeMap<String,String>();
        for(var value:entries){
            if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("country_iso","country_name")))throw invalidCountry("Every country row must contain only country_iso and country_name");
            var entry=value.getAsJsonObject();String code=countryString(entry.get("country_iso"),"Country ISO"),name=countryString(entry.get("country_name"),"Country name");
            if(!code.matches("[A-Z]{2}")||!codes.add(code))throw invalidCountry("Country ISO must be a unique uppercase two-letter dictionary value");
            canonical.put(code,name);
            for(String alias:List.of(code,name)){String key=countryKey(alias);var previous=aliases.putIfAbsent(key,code);if(previous!=null&&!previous.equals(code))ambiguous.add(key);}
        }
        ambiguous.forEach(aliases::remove);
        var content=new JsonArray();canonical.forEach((code,name)->{var row=new JsonObject();row.addProperty("country_iso",code);row.addProperty("country_name",name);content.add(row);});
        var evidence=new CountryDictionaryEvidence(api.nationalCatalogCountrySource(settings.resolvedTrueApiBaseUrl()),countryEnvironment(),credential,operation,3,etag,
                com.vncode.app.integration.listing.ListingIdentity.hash(content.toString()),permitClock.instant(),codes.size(),"current-operation-only; HTTP no-store");
        return new CountryReadback(java.util.Map.copyOf(aliases),List.copyOf(codes),evidence);
    }
    private void assertCountryAccess(){
        if(!dataProfile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))throw new ZnackRegistrationScope.ScopeChangedException();
        LicenseRuntime.require(Permission.SELLER_API);api.assertCatalogReadScope();
    }
    private String countryScope(String token){return com.vncode.app.integration.listing.ListingIdentity.hash(api.catalogReadScopeId()+"\n"+countryEnvironment()+"\n"+dataProfile+"\n"+settings.participantInn()+"\n"+settings.ownerInn()+"\n"+(api.hasCatalogKey()?"Catalog key":java.util.Objects.toString(token,"")));}
    private String countryEnvironment(){
        var uri=java.net.URI.create(settings.resolvedTrueApiBaseUrl());
        return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+java.util.Objects.toString(uri.getPath(),"");
    }
    private static String countryString(JsonElement value,String label){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank())throw invalidCountry(label+" must be a nonblank string");
        return value.getAsString();
    }
    private static IllegalStateException invalidCountry(String reason){return new IllegalStateException("CATALOG_COUNTRY_DICTIONARY_INVALID",new IllegalArgumentException(reason));}
    private static String countryKey(String value){return java.text.Normalizer.normalize(value.strip(),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");}
    private void cacheCompleteSchema(long categoryId,List<Attribute> attributes){
        synchronized(completeSchemas){
            var now=permitClock.instant();completeSchemas.entrySet().removeIf(entry->!entry.getValue().checkedAt().isAfter(now.minusSeconds(120)));
            if(!completeSchemas.containsKey(categoryId))while(completeSchemas.size()>=128){var oldest=completeSchemas.entrySet().stream().min(java.util.Map.Entry.comparingByValue(Comparator.comparing(SchemaReadback::checkedAt))).orElseThrow();completeSchemas.remove(oldest.getKey());}
            completeSchemas.put(categoryId,new SchemaReadback(List.copyOf(attributes),now));
        }
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
        return submit(token,payload,()->{});
    }
    public String submit(String token,JsonObject payload,Runnable beforeSend)throws Exception {
        if(hasReviewedDraft(payload))throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");
        return submit(token,payload,beforeSend,null);
    }
    public static boolean hasReviewedDraft(JsonObject payload){return payload!=null&&payload.has(REVIEWED_DRAFT);}
    /** Local checkpoint metadata only; it must never be sent to National Catalog. */
    public static JsonObject reviewedDraftPayload(com.vncode.app.models.Shop shop,JsonObject original){
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        var payload=original.deepCopy();payload.remove(REVIEWED_DRAFT);
        long id=exactPositiveId(payload.get("good_id"),"good_id");String gtin=string(payload,"gtin");
        if(!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin)||!payload.has("moderation")||!(number(payload,"moderation")==1||payload.get("moderation").isJsonPrimitive()&&payload.getAsJsonPrimitive("moderation").isBoolean()&&payload.get("moderation").getAsBoolean()))throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");
        var settings=new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var marker=new JsonObject();marker.addProperty("version",1);marker.addProperty("gtin",gtin);marker.addProperty("good_id",id);
        marker.addProperty("owner",com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop));
        marker.addProperty("scope",draftScope(settings));marker.addProperty("payload_hash",draftHash(payload));
        payload.add(REVIEWED_DRAFT,marker);return payload;
    }
    public String submitReviewedDraft(com.vncode.app.models.Shop shop,String token,JsonObject reviewed,Runnable beforeSend)throws Exception{
        requireDraftReview(shop,reviewed);
        var payload=reviewed.deepCopy();payload.remove(REVIEWED_DRAFT);
        return submit(token,payload,beforeSend,()->{
            requireDraftReview(shop,reviewed);
            var card=observedCardStatus(token,string(payload,"gtin"),exactPositiveId(payload.get("good_id"),"good_id"));
            if(card==null||!card.draftForReview())throw new RegistrationValidationException("SOURCE_CARD_ACTION_CHANGED");
            requireDraftReview(shop,reviewed);
        });
    }
    private void requireDraftReview(com.vncode.app.models.Shop shop,JsonObject reviewed){
        com.vncode.app.integration.listing.ListingIdentity.require(shop);
        try{
            if(!hasReviewedDraft(reviewed)||!reviewed.get(REVIEWED_DRAFT).isJsonObject())throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");
            var marker=reviewed.getAsJsonObject(REVIEWED_DRAFT);var payload=reviewed.deepCopy();payload.remove(REVIEWED_DRAFT);
            var current=new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
            if(!string(marker,"owner").equals(com.vncode.app.integration.listing.ListingIdentity.fingerprint(shop))||!string(marker,"scope").equals(draftScope(current))||!draftScope(current).equals(draftScope(settings)))throw new ZnackRegistrationScope.ScopeChangedException();
            if(number(marker,"version")!=1||!string(marker,"gtin").equals(string(payload,"gtin"))||exactPositiveId(marker.get("good_id"),"good_id")!=exactPositiveId(payload.get("good_id"),"good_id")||!string(marker,"payload_hash").equals(draftHash(payload)))throw new RegistrationValidationException("ZNACK_REVIEWED_VALUES_CHANGED");
        }catch(ZnackRegistrationScope.ScopeChangedException|RegistrationValidationException invalid){throw invalid;}
        catch(RuntimeException malformed){throw new RegistrationValidationException("REGISTRATION_REVIEW_REQUIRED");}
    }
    private static String draftHash(JsonObject payload){return com.vncode.app.integration.listing.ListingIdentity.hash(com.vncode.app.integration.listing.ListingIdentity.canonical(payload));}
    private static String draftScope(ZnackModels.Settings settings){var scope=ZnackRegistrationScope.fromSettings(settings);return com.vncode.app.integration.listing.ListingIdentity.hash(scope.inn()+"\n"+scope.environment()+"\n"+scope.fingerprint());}
    @FunctionalInterface private interface DraftDispatchCheck{void check()throws Exception;}
    private String submit(String token,JsonObject payload,Runnable beforeSend,DraftDispatchCheck draftCheck)throws Exception {
        LicenseRuntime.require(Permission.GTIN);
        payload=normalizedFeed(payload);
        validateExistingCard(token,payload);
        validateDocuments(token,payload);
        try(var read=registrationCountryReadScope()){
            validateCurrentRegistrationModel(token,payload);
            validateRegistrationCountry(token,payload);
        }
        LicenseRuntime.require(Permission.GTIN);
        if(draftCheck!=null)draftCheck.check();
        beforeSend.run();
        LicenseRuntime.require(Permission.GTIN);
        JsonObject result = resultObject(api.submitNationalCatalogFeed(
                settings.resolvedTrueApiBaseUrl(), token, payload));
        String feedId = string(result, "feed_id");
        if (feedId.isBlank()) throw new IllegalStateException("National Catalog did not return feed_id.");
        return feedId;
    }

    /** A read-only preallocation check. It never supplies missing facts or rewrites the reviewed payload. */
    public void validateCurrentRegistrationDraft(String token,Draft draft)throws Exception {
        java.util.Objects.requireNonNull(draft,"draft");
        var payload=normalizedFeed(buildPayload("",draft,""));
        try(var read=registrationCountryReadScope()){
            validateCurrentRegistrationModel(token,payload);
            validateRegistrationCountry(token,payload);
        }
    }

    /** Re-read the current mandatory model immediately before the durable dispatch marker. */
    private void validateCurrentRegistrationModel(String token,JsonObject payload)throws Exception {
        var supplied=new java.util.LinkedHashMap<Long,List<JsonObject>>();
        var values=new java.util.LinkedHashMap<Long,String>();
        if(!payload.has("good_attrs")||!payload.get("good_attrs").isJsonArray())throw invalidRegistrationField("good_attrs");
        for(var raw:payload.getAsJsonArray("good_attrs")){
            if(!raw.isJsonObject())throw invalidRegistrationField("good_attrs");
            var attribute=raw.getAsJsonObject();long id=exactPositiveId(attribute.get("attr_id"),"attr_id");
            var rawValue=attribute.get("attr_value");
            if(rawValue==null||!rawValue.isJsonPrimitive()||!rawValue.getAsJsonPrimitive().isString())throw invalidRegistrationField("attr_value ["+id+"]");
            supplied.computeIfAbsent(id,ignored->new ArrayList<>()).add(attribute);
            values.put(id,rawValue.getAsString());
        }
        values.putIfAbsent(2478L,string(payload,"good_name"));values.putIfAbsent(2504L,string(payload,"brand"));
        if(!payload.has("categories")||!payload.get("categories").isJsonArray()||payload.getAsJsonArray("categories").isEmpty())throw invalidRegistrationField("categories");
        var categories=new LinkedHashSet<Long>();
        for(var category:payload.getAsJsonArray("categories"))categories.add(exactPositiveId(category,"category"));
        for(long category:categories){
            var state=countryState(token);
            // A previous review in the same long-running batch cannot authorize a later POST.
            state.mandatory.remove(category);state.complete.remove(category);state.registration.remove(category);
            var required=RegistrationAttributeRules.required(registrationMandatoryAttributes(category,token),values);
            var current=new java.util.LinkedHashMap<Long,Attribute>();
            registrationCompleteAttributes(category,token).forEach(attribute->current.put(attribute.id(),attribute));
            required.forEach(attribute->current.put(attribute.id(),attribute));
            for(var attribute:required){
                long id=attribute.id();
                if(id==3959L||id==13933L&&string(payload,"tnved").length()==10||id==2630L)continue;
                if(id==DECLARATION_ATTRIBUTE_ID||id==CERTIFICATE_ATTRIBUTE_ID){
                    if(!supplied.containsKey(DECLARATION_ATTRIBUTE_ID)&&!supplied.containsKey(CERTIFICATE_ATTRIBUTE_ID))throw missingRegistrationField(attribute);
                    continue;
                }
                var entries=supplied.getOrDefault(id,List.of());String text=values.getOrDefault(id,"");
                if(text.isBlank())throw missingRegistrationField(attribute);
                validateSubmittedAttribute(attribute,entries,text);
            }
            for(var suppliedField:supplied.entrySet()){
                long id=suppliedField.getKey();
                if(id==3959L||id==13933L||id==2630L||id==DECLARATION_ATTRIBUTE_ID||id==CERTIFICATE_ATTRIBUTE_ID)continue;
                var attribute=current.get(id);
                // Title/brand are also documented base feed fields, independent of category attrs.
                if(attribute==null&&(id==2478L||id==2504L))continue;
                if(attribute==null)throw invalidRegistrationField("Unannounced current attribute ["+id+"]");
                validateSubmittedAttribute(attribute,suppliedField.getValue(),values.getOrDefault(id,""));
            }
        }
    }
    private static void validateSubmittedAttribute(Attribute attribute,List<JsonObject> entries,String fallback){
        String field=attribute.name()+" ["+attribute.id()+"]";
        if(!attribute.multiple()&&entries.size()>1)throw invalidRegistrationField(field);
        var texts=entries.isEmpty()?List.of(fallback):entries.stream().map(entry->string(entry,"attr_value")).toList();
        for(String value:texts)if(value.isBlank()||value.length()>5000||attribute.presetOnly()&&!attribute.presets().contains(value)||!RegistrationAttributeRules.validFieldValue(attribute,value))throw invalidRegistrationField(field);
        for(var entry:entries){
            var rawType=entry.get("attr_value_type");
            if(rawType==null||!rawType.isJsonPrimitive()||!rawType.getAsJsonPrimitive().isString())throw invalidRegistrationField(field);
            if(!attribute.valueTypes().isEmpty()&&!attribute.valueTypes().contains(rawType.getAsString()))throw invalidRegistrationField(field);
        }
    }
    private static RegistrationValidationException missingRegistrationField(Attribute attribute){return new RegistrationValidationException("ZNACK_REQUIRED_ATTRIBUTES_MISSING: "+attribute.name()+" ["+attribute.id()+"]");}
    private static RegistrationValidationException invalidRegistrationField(String field){return new RegistrationValidationException("ZNACK_INVALID_ATTRIBUTE_VALUE: "+field);}
    private static long exactPositiveId(JsonElement value,String field){
        if(value==null||!value.isJsonPrimitive()||value.getAsJsonPrimitive().isBoolean())throw invalidRegistrationField(field);
        try{String text=value.getAsString();if(!text.matches("[1-9][0-9]{0,18}"))throw invalidRegistrationField(field);return Long.parseLong(text);}
        catch(NumberFormatException invalid){throw invalidRegistrationField(field);}
    }

    /** Verify the same country-bearing draft before allocating any GS1 number. */
    public void validateRegistrationCountry(String token,Draft draft)throws Exception {
        java.util.Objects.requireNonNull(draft,"draft");
        validateRegistrationCountry(token,buildPayload("",draft,""));
    }

    /** The final safe read precedes the durable send marker and the non-replayed feed POST. */
    private void validateRegistrationCountry(String token,JsonObject payload)throws Exception {
        var countryValues=new ArrayList<JsonObject>();var values=new java.util.LinkedHashMap<Long,String>();
        for(var raw:array(payload.get("good_attrs")))if(raw.isJsonObject()){
            var attribute=raw.getAsJsonObject();long id=number(attribute,"attr_id");
            if(id==2630L)countryValues.add(attribute);
            if(attribute.has("attr_value")&&attribute.get("attr_value").isJsonPrimitive())values.put(id,attribute.get("attr_value").getAsString());
        }
        var categories=new LinkedHashSet<Long>();
        for(var raw:array(payload.get("categories"))){
            if(!raw.isJsonPrimitive()||!raw.getAsString().matches("[1-9][0-9]{0,17}"))throw invalidCountry("A country-bearing feed requires an exact category");
            categories.add(raw.getAsLong());
        }
        if(categories.isEmpty()){
            if(!countryValues.isEmpty())throw invalidCountry("Country values cannot be verified without the feed category");
            return;
        }
        try(var read=registrationCountryReadScope()){
            for(long category:categories){
                var mandatory=RegistrationAttributeRules.required(registrationMandatoryAttributes(category,token),values);
                boolean required=mandatory.stream().anyMatch(attribute->attribute.id()==2630L);
                if(countryValues.isEmpty()&&!required)continue;
                var schema=registrationAttributes(category,token);
                var fields=schema.stream().filter(attribute->attribute.id()==2630L).toList();
                if(fields.size()!=1)throw invalidCountry("The feed category does not announce a unique manufacturing-country field");
                if(countryValues.isEmpty())throw new RegistrationValidationException("ZNACK_REQUIRED_ATTRIBUTES_MISSING: Страна производства [2630]");
                var country=fields.getFirst();
                if(!country.multiple()&&countryValues.size()>1)throw invalidCountry("The country field does not allow multiple values");
                for(var attribute:countryValues){
                    String code=countryString(attribute.get("attr_value"),"Submitted country ISO");
                    if(!code.equals(registrationCountryCode(code,schema)))throw invalidCountry("Submitted country is not allowed by the current verified dictionary");
                    String type=attribute.has("attr_value_type")?countryStringOrEmpty(attribute.get("attr_value_type")):"";
                    if(country.valueTypes().isEmpty()?!type.isEmpty():!country.valueTypes().contains(type))throw invalidCountry("Submitted country uses an unsupported value type");
                }
            }
        }
    }
    private static String countryStringOrEmpty(JsonElement value){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw invalidCountry("Country value type must be a string");
        return value.getAsString();
    }

    /** Updating a card never changes the identity or tariff of the registered trade item. */
    private void validateExistingCard(String token,JsonObject payload)throws Exception {
        if(!payload.has("good_id"))return;
        String gtin=string(payload,"gtin");
        if(!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin))throw new RegistrationValidationException("CATALOG_REPAIR_INVALID");
        var card=readCard(token,gtin,payload.get("good_id").getAsLong());
        if(card==null)throw new RegistrationValidationException("CATALOG_REPAIR_CARD_NOT_FOUND");
        String registered=CatalogProductDataParser.parse(card).single("tnved"),requested=string(payload,"tnved");
        for(var value:array(payload.get("good_attrs")))if(value.isJsonObject()&&number(value.getAsJsonObject(),"attr_id")==13933)
            requested=string(value.getAsJsonObject(),"attr_value");
        if(!registered.matches("[0-9]{4}|[0-9]{10}")||!requested.matches("[0-9]{4}|[0-9]{10}")
                ||!registered.substring(0,4).equals(requested.substring(0,4))
                ||registered.length()==10&&requested.length()==10&&!registered.equals(requested))
            throw new RegistrationValidationException("CATALOG_REGISTERED_TNVED_MISMATCH");
        for(String field:List.of("owner_inn","participant_inn")){
            String owner=string(card,field);
            if(!owner.isBlank()&&!owner.equals(settings.participantInn())&&!owner.equals(settings.ownerInn()))
                throw new RegistrationValidationException("CATALOG_OWNER_MISMATCH");
        }
    }

    /** v5.68 §3.2.2, including saved requests prepared by an older application. */
    static JsonObject normalizedFeed(JsonObject original){
        var payload=original.deepCopy();String tnved=string(payload,"tnved");
        if(!tnved.isBlank()&&!tnved.matches("(?:[0-9]{4}|[0-9]{10})"))throw new RegistrationValidationException("ZNACK_TNVED_FORMAT");
        if(payload.has("good_id")&&!string(payload,"good_id").matches("[1-9][0-9]{0,18}"))throw new RegistrationValidationException("CATALOG_REPAIR_INVALID");
        var attributes=new JsonArray();
        for(var value:array(payload.get("good_attrs"))){
            if(!value.isJsonObject()){attributes.add(value);continue;}
            var attribute=value.getAsJsonObject();long id=number(attribute,"attr_id");
            if(id==3959||id==13933&&tnved.length()==10)continue;
            if(id==13933&&tnved.length()==4){
                String full=string(attribute,"attr_value");
                if(!full.matches("[0-9]{10}")||!full.startsWith(tnved))throw new RegistrationValidationException("ZNACK_TNVED_GROUP_MISMATCH");
            }
            attributes.add(attribute);
        }
        if(payload.has("good_attrs"))payload.add("good_attrs",attributes);
        if(payload.toString().getBytes(StandardCharsets.UTF_8).length>25*1024*1024)throw new RegistrationValidationException("CATALOG_FEED_TOO_LARGE");
        return payload;
    }

    public ZnackRegistrationDocuments.Selection reviewDocuments(String token,ZnackRegistrationDocuments.Selection selected,String tnved)throws Exception {
        var valid=new ArrayList<ZnackModels.GoodsDocument>();var issues=new ArrayList<String>();var reviewRequired=new HashSet<ZnackModels.GoodsDocument>();
        for(var document:selected.candidates()){
            var cached=permitReadbacks.get(document);
            JsonElement response=cached!=null&&cached.checkedAt().isAfter(permitClock.instant().minusSeconds(120))?cached.response():null;
            if(response==null){
                LicenseRuntime.require(Permission.SELLER_API);
                var request=new JsonArray();request.add(RegistrationPermitValidator.request(document));
                response=api.registrationPermitInfo(settings.resolvedTrueApiBaseUrl(),token,request);
                // Pending results must be checked again on the next attempt.
                if(response!=null&&!com.vncode.app.integration.znack.ZnackPermitDocumentParser.registryLookupPending(response))cachePermitReadback(document,response);
            }
            String problem=RegistrationPermitValidator.problem(response,document,tnved,java.time.LocalDate.now(permitClock));
            if(problem.isBlank()||RegistrationPermitValidator.advisory(problem))valid.add(document);
            if(!problem.isBlank())issues.add(document.number()+": "+problem);
            if(RegistrationPermitValidator.advisory(problem))reviewRequired.add(document);
        }
        // Registry readback proves the number, dates, status and TN VED scope. One
        // remaining valid permit no longer needs a per-card seller selection.
        var automatic=valid.contains(selected.automatic())?selected.automatic():valid.size()==1?valid.getFirst():null;
        if(reviewRequired.contains(automatic))automatic=null;
        return new ZnackRegistrationDocuments.Selection(valid,automatic,selected.source(),automatic==null,issues);
    }

    /** Facts from the selected active registry permit, expressed as exact current-schema literals. */
    public java.util.Map<Long,String> verifiedRegistrationAttributes(ZnackModels.GoodsDocument document,List<Attribute> schema){
        if(document==null||!document.complete()||!Set.of("CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE").contains(document.type())||schema==null||!dataProfile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))return java.util.Map.of();
        var cached=permitReadbacks.get(document);
        if(cached==null||!cached.checkedAt().isAfter(permitClock.instant().minusSeconds(120)))return java.util.Map.of();
        String problem=RegistrationPermitValidator.problem(cached.response(),document,"",java.time.LocalDate.now(permitClock));
        if(!problem.isBlank()&&!RegistrationPermitValidator.advisory(problem))return java.util.Map.of();
        var registry=exactPermit(cached.response(),document);if(registry==null)return java.util.Map.of();
        var regulations=regulationCodes(permitText(registry,"product_tech_regulations"));if(regulations.isEmpty())return java.util.Map.of();
        var fields=schema.stream().filter(attribute->attribute.id()==TECHNICAL_REGULATION_ATTRIBUTE_ID).toList();if(fields.size()!=1)return java.util.Map.of();
        var field=fields.getFirst();var matches=new LinkedHashSet<String>();
        for(String literal:field.presets()){
            var codes=regulationCodes(literal);
            if(codes.size()==1&&regulations.contains(codes.iterator().next())&&RegistrationAttributeRules.validFieldValue(field,literal))matches.add(literal);
        }
        return matches.size()==1?java.util.Map.of(TECHNICAL_REGULATION_ATTRIBUTE_ID,matches.iterator().next()):java.util.Map.of();
    }
    private void cachePermitReadback(ZnackModels.GoodsDocument document,JsonElement response){
        synchronized(permitReadbacks){
            var now=permitClock.instant();permitReadbacks.entrySet().removeIf(entry->!entry.getValue().checkedAt().isAfter(now.minusSeconds(120)));
            if(!permitReadbacks.containsKey(document))while(permitReadbacks.size()>=MAX_PERMIT_READBACKS){
                var oldest=permitReadbacks.entrySet().stream().min(java.util.Map.Entry.comparingByValue(Comparator.comparing(PermitReadback::checkedAt))).orElseThrow();permitReadbacks.remove(oldest.getKey());
            }
            permitReadbacks.put(document,new PermitReadback(response.deepCopy(),now));
        }
    }
    private static JsonObject exactPermit(JsonElement response,ZnackModels.GoodsDocument document){
        var result=response!=null&&response.isJsonObject()?response.getAsJsonObject():new JsonObject();
        if(result.has("result")&&result.get("result").isJsonObject())result=result.getAsJsonObject("result");
        JsonObject found=null;
        for(var value:array(result.get("documents")))if(value.isJsonObject()){
            var candidate=value.getAsJsonObject();
            if(permitText(candidate,"number").equals(document.number())&&permitText(candidate,"from_date").equals(document.date())&&permitText(candidate,"attr_id").equals(Long.toString(RegistrationPermitValidator.attribute(document)))){
                if(found!=null)return null;found=candidate;
            }
        }
        return found;
    }
    private static String permitText(JsonObject value,String key){
        var field=value.get(key);return field!=null&&field.isJsonPrimitive()?field.getAsString().strip():"";
    }
    private static Set<String> regulationCodes(String value){
        var codes=new LinkedHashSet<String>();var matches=TECHNICAL_REGULATION.matcher(value);
        while(matches.find())codes.add(matches.group(1).toUpperCase(Locale.ROOT)+":"+matches.group(2)+"/"+matches.group(3));
        return Set.copyOf(codes);
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
        var blocking=checked.issues().stream().filter(issue->!RegistrationPermitValidator.advisory(issue)).toList();
        if(!blocking.isEmpty())throw new RegistrationValidationException(String.join("; ",blocking));
    }

    public FeedProgress progress(String token, String feedId, String gtin) throws Exception {
        return readFeedProgress(token, feedId, gtin, true);
    }

    /** A receipt proves import acceptance, never that a product card was created. */
    public FeedProgress submissionStatus(String token, String feedId, String gtin) throws Exception {
        FeedProgress feed = readFeedProgress(token, feedId, gtin, false);
        if (feed.failed()) return feed;
        JsonObject card = readCard(token, gtin, feed.goodId());
        if (card != null) {
            // An old published version may coexist with the revision submitted by this
            // still-active feed. Its publication does not prove the revision is finished.
            var revision=detailedState(card);
            if(Set.of("received","processing","moderated").contains(feed.status().toLowerCase(Locale.ROOT))
                    &&isPublished(card)&&!Collections.disjoint(revision,Set.of("draft","moderation","errors","error","rejected","notsigned"))){
                long good=number(card,"good_id");if(good==0)good=number(card,"goodId");
                if(!Collections.disjoint(revision,Set.of("errors","error","rejected")))
                    return new FeedProgress("Rejected",good,List.of("CATALOG_CARD_REJECTED_DETAILS_UNAVAILABLE"));
                if(revision.contains("moderation"))return new FeedProgress("CardModeration",good,List.of());
                if(revision.contains("draft"))return new FeedProgress("CardDraft",good,List.of());
                return new FeedProgress("Moderated",good,List.of());
            }
            return observedCard(card);
        }
        if (Set.of("processed", "done", "completed").contains(feed.status().toLowerCase(Locale.ROOT))) {
            return new FeedProgress("Rejected", null, List.of("CATALOG_FEED_COMPLETED_WITHOUT_CARD"));
        }
        return new FeedProgress("CardNotFound", null, List.of());
    }

    private FeedProgress readFeedProgress(String token, String feedId, String gtin, boolean verifyCard) throws Exception {
        JsonObject result;
        try{result=feedResult(api.nationalCatalogFeedStatus(settings.resolvedTrueApiBaseUrl(),token,feedId));}
        catch(Exception missingFeed){
            if(!CatalogFeedback.notFound(missingFeed))throw missingFeed;
            // Old imports can expire from feed history while their exact card still exists.
            // A 404 is neither a rejection nor permission to resend the original request.
            var card=readCard(token,gtin,null);
            return card==null?new FeedProgress("CardNotFound",null,List.of()):observedCard(card);
        }
        String status = string(result, "status");
        List<String> errors = new ArrayList<>();
        Long goodId = null;
        Set<String> exactIdentities = new LinkedHashSet<>();
        var entryIdentities=new java.util.HashMap<String,Set<String>>();
        for (JsonElement element : array(result.get("item"))) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemGtin = string(item, "gtin");
            String entry=string(item,"id");
            if(!entry.isBlank()&&!itemGtin.isBlank())entryIdentities.computeIfAbsent(entry,key->new LinkedHashSet<>())
                    .add(com.vncode.app.integration.znack.GtinNormalizer.normalize(itemGtin));
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
        collectStandardFeedErrors(result.get("result"),entryIdentities,gtin,errors);
        if(!verifyCard&&errors.isEmpty()&&!array(result.get("item")).isEmpty()&&exactIdentities.size()!=1)
            throw new IllegalStateException("CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
        if(verifyCard&&errors.isEmpty()&&("Signed".equalsIgnoreCase(status) || "Moderated".equalsIgnoreCase(status)) && exactIdentities.size()!=1)
            throw new IllegalStateException("Catalog feed readback did not confirm the requested GTIN.");
        if(verifyCard&&errors.isEmpty()&&"Signed".equalsIgnoreCase(status) && (goodId==null || goodId<=0))
            throw new IllegalStateException("Catalog feed readback did not confirm the signed card identity.");
        boolean publicationVerified=false;
        if(verifyCard&&Set.of("received","processing","moderated").contains(status.toLowerCase(Locale.ROOT))&&errors.isEmpty()){
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
                else if(isPublished(card)
                        &&java.util.Collections.disjoint(detailsState,Set.of("draft","moderation","errors","notsigned"))){status="Signed";publicationVerified=true;}
            }
        }
        if(verifyCard&&Set.of("processed","done","completed").contains(status.toLowerCase(Locale.ROOT))&&errors.isEmpty()){
            if(array(result.get("item")).size()>0&&exactIdentities.size()!=1)throw new IllegalStateException("CATALOG_FEED_IDENTITY_NOT_CONFIRMED");
            var card=readCard(token,gtin,goodId);
            if(card==null){status="Rejected";errors.add("CATALOG_FEED_COMPLETED_WITHOUT_CARD");}
            else if(isPublished(card)){
                goodId=number(card,"good_id");if(goodId==0)goodId=number(card,"goodId");status="Signed";publicationVerified=true;
            }else{var observed=observedCard(card);status=observed.status();goodId=observed.goodId();errors.addAll(observed.errors());}
        }
        // A signed feed is an acknowledgement for the import/signing request. It is
        // not proof that the product card is visible and published in the catalog.
        if(verifyCard&&"Signed".equalsIgnoreCase(status) && errors.isEmpty()&&!publicationVerified
                && !publishedCard(token,gtin,goodId))status="PublicationPending";
        return new FeedProgress(status, goodId, List.copyOf(new LinkedHashSet<>(errors)));
    }

    private static JsonObject feedResult(JsonElement response){
        var raw=unwrap(response);
        if(raw==null||!raw.isJsonObject())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
        var result=raw.getAsJsonObject();var status=result.get("status");
        if(status==null||!status.isJsonPrimitive()||!status.getAsJsonPrimitive().isString()||status.getAsString().isBlank())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
        if(result.has("item")&&!result.get("item").isJsonArray()||result.has("error_details")&&!result.get("error_details").isJsonObject())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
        if(result.has("result")&&!result.get("result").isJsonObject())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
        for(var item:array(result.get("item")))if(!item.isJsonObject())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
        return result;
    }

    private static FeedProgress observedCard(JsonObject card){
        long good=number(card,"good_id");if(good==0)good=number(card,"goodId");
        var state=detailedState(card);
        if(isPublished(card))return new FeedProgress("Signed",good,List.of());
        if(!java.util.Collections.disjoint(state,Set.of("errors","error","rejected")))
            return new FeedProgress("Rejected",good,List.of("CATALOG_CARD_REJECTED_DETAILS_UNAVAILABLE"));
        return new FeedProgress(state.contains("moderation")?"CardModeration":state.contains("notsigned")?"Moderated":"CardDraft",good,List.of());
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
    /** API 5.68 also documents the standard (non-verbose) entry-indexed error block.
     * An index alone is not a GTIN: retain its feed scope unless item.id proves the link. */
    private static void collectStandardFeedErrors(JsonElement raw,java.util.Map<String,Set<String>> identities,String gtin,List<String> errors){
        if(raw==null||raw.isJsonNull())return;
        var standard=raw.getAsJsonObject();String requested=com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin);
        boolean applicable=false,hasEntries=false;int initial=errors.size();
        for(var entry:standard.entrySet()){
            if(entry.getKey().equals("totalErrors"))continue;
            hasEntries=true;var codes=identities.getOrDefault(entry.getKey(),Set.of());
            if(!codes.isEmpty()&&!codes.contains(requested))continue;
            applicable=true;boolean linked=codes.size()==1&&codes.contains(requested);
            var value=entry.getValue();if(value==null||!value.isJsonArray())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
            for(var message:value.getAsJsonArray()){
                if(!message.isJsonPrimitive()||!message.getAsJsonPrimitive().isString())throw new IllegalStateException("CATALOG_FEED_RESPONSE_INVALID");
                String text=ZnackSanitizer.message(message.getAsString());if(text.isBlank())continue;
                errors.add(linked?text:"CATALOG_FEED_ERROR: "+text);
            }
        }
        if(number(standard,"totalErrors")>0&&(!hasEntries||applicable)&&errors.size()==initial&&errors.isEmpty())
            errors.add("CATALOG_VALIDATION_ERRORS_UNAVAILABLE");
    }

    private boolean publishedCard(String token,String gtin,Long expectedId)throws Exception {
        var card=readCard(token,gtin,expectedId);return card!=null&&isPublished(card);
    }
    /** Absence is confirmed only by a successful, identity-checked Catalog response. */
    public boolean cardExists(String token,String gtin,Long expectedId)throws Exception{return readCard(token,gtin,expectedId)!=null;}
    public record ObservedCard(long goodId,String gtin,String rawStatus,FeedProgress progress,
            com.vncode.app.features.catalogsync.CatalogProductData details,java.time.Instant checkedAt,boolean tradeUnit){
        public ObservedCard(long goodId,String gtin,String rawStatus,FeedProgress progress,com.vncode.app.features.catalogsync.CatalogProductData details,java.time.Instant checkedAt){this(goodId,gtin,rawStatus,progress,details,checkedAt,false);}
        /** Only a real, ordinary Draft may enter the seller-reviewed update flow. */
        public boolean draftForReview(){return goodId>0&&tradeUnit&&"draft".equalsIgnoreCase(rawStatus)
                &&progress!=null&&!progress.failed()&&"CardDraft".equalsIgnoreCase(progress.status())
                &&details!=null&&details.values("@catalog_signed").stream().allMatch("false"::equalsIgnoreCase)
                &&details.values("@catalog_detailed_status").stream().allMatch(value->"draft".equalsIgnoreCase(value.strip()));}
    }
    /** Fresh exact-identity evidence. Authentication, parse and incomplete reads are never absence. */
    public ObservedCard observedCardStatus(String token,String gtin,Long expectedId)throws Exception {
        assertRegistrationReadScope();
        if(!com.vncode.app.features.gtinsync.RegisteredGtinValidator.isValid(gtin))throw new RegistrationValidationException("CATALOG_REPAIR_INVALID");
        var card=readCard(token,gtin,expectedId);if(card==null)return null;
        validateObservedOwner(card);
        String raw=first(string(card,"good_status"),string(card,"goodStatus"),string(card,"cardStatus"));
        if(!Set.of("draft","published").contains(raw.toLowerCase(Locale.ROOT)))throw new IllegalStateException("CATALOG_PUBLICATION_RESPONSE_INVALID");
        var status=observedCard(card);
        return new ObservedCard(status.goodId(),com.vncode.app.integration.znack.GtinNormalizer.normalize(gtin),raw,status,CatalogProductDataParser.parse(card),permitClock.instant(),observedTradeUnit(card,gtin));
    }
    private static boolean observedTradeUnit(JsonObject card,String gtin){boolean found=false;var raw=card.get("identified_by");if(raw==null||!raw.isJsonArray())return false;for(var value:raw.getAsJsonArray()){if(!value.isJsonObject())return false;var identity=value.getAsJsonObject();if("gtin".equalsIgnoreCase(string(identity,"type"))&&com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,first(string(identity,"value"),string(identity,"gtin")))){if(!"trade-unit".equalsIgnoreCase(string(identity,"level")))return false;found=true;}}return found;}
    /** Reassert the original client's credential/profile/license guard when a caller reuses read evidence. */
    public void assertRegistrationReadScope(){assertCountryAccess();}
    private void validateObservedOwner(JsonObject card){
        for(String field:List.of("owner_inn","participant_inn")){
            String owner=string(card,field);
            if(!owner.isBlank()&&!owner.equals(settings.participantInn())&&!owner.equals(settings.ownerInn()))throw new RegistrationValidationException("CATALOG_OWNER_MISMATCH");
        }
    }

    /** Automatic consent covers the saved business values, not any later XML for the same GTIN. */
    public void validateApprovedSigningCard(String token,String gtin,Long expectedId,JsonObject approvedPayload)throws Exception {
        assertRegistrationReadScope();
        if(approvedPayload==null||!com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(gtin,string(approvedPayload,"gtin"))||expectedId==null||expectedId<=0)throw signingReviewRequired();
        var approved=normalizedFeed(approvedPayload);var card=readCard(token,gtin,expectedId);
        if(card==null)throw signingReviewRequired();validateObservedOwner(card);
        if(!observedCard(card).readyToSign())throw signingReviewRequired();
        var data=CatalogProductDataParser.parse(card);
        for(var field:java.util.Map.of("good_name","title","brand","brand").entrySet()){
            String requested=string(approved,field.getKey());
            if(requested.isBlank()||!RegistrationTemplate.clean(requested).equals(RegistrationTemplate.clean(data.single(field.getValue()))))throw signingReviewRequired();
        }
        String tariff=string(approved,"tnved");
        for(var raw:array(approved.get("good_attrs")))if(raw.isJsonObject()&&number(raw.getAsJsonObject(),"attr_id")==13933L)tariff=string(raw.getAsJsonObject(),"attr_value");
        if(!tariff.matches("[0-9]{10}")||!tariff.equals(data.single("tnved")))throw signingReviewRequired();
        // Unknown/absent category evidence requires a manual review of the current XML.
        if(!approved.has("categories")||!approved.get("categories").isJsonArray()||!card.has("categories")||!card.get("categories").isJsonArray()||!approved.get("categories").equals(card.get("categories")))throw signingReviewRequired();
        if(approved.has("is_kit")?!approved.get("is_kit").equals(card.get("is_kit")):card.has("is_kit")&&(!card.get("is_kit").isJsonPrimitive()||!card.get("is_kit").getAsJsonPrimitive().isBoolean()||card.get("is_kit").getAsBoolean()))throw signingReviewRequired();
        var desired=businessAttributes(approved);var observed=businessAttributes(card);
        if(!desired.equals(observed))throw signingReviewRequired();
    }
    private static java.util.Map<Long,List<String>> businessAttributes(JsonObject payload){
        var result=new java.util.TreeMap<Long,List<String>>();
        for(var attribute:com.vncode.app.integration.znack.ZnackProductCardAttributes.from(payload)){
            String idText=com.vncode.app.integration.znack.ZnackProductCardAttributes.id(attribute);
            if(!idText.matches("[1-9][0-9]{0,18}"))throw signingReviewRequired();
            long id;try{id=Long.parseLong(idText);}catch(NumberFormatException invalid){throw signingReviewRequired();}
            if(id==3959L||id==13933L||id==2478L||id==2504L)continue;
            String value=com.vncode.app.integration.znack.ZnackProductCardAttributes.text(attribute,"attr_value","attrValue","value");
            if(value.isBlank())throw signingReviewRequired();
            if(id==13914L)value=CatalogArticleValue.stored(value);
            String type=com.vncode.app.integration.znack.ZnackProductCardAttributes.text(attribute,"attr_value_type","attrValueType");
            result.computeIfAbsent(id,ignored->new ArrayList<>()).add(RegistrationTemplate.clean(value)+"\u0000"+RegistrationTemplate.clean(type));
        }
        result.values().forEach(Collections::sort);return result;
    }
    private static RegistrationValidationException signingReviewRequired(){return new RegistrationValidationException("CATALOG_SIGNING_REVIEW_REQUIRED");}
    private static boolean isPublished(JsonObject card){
        if(!"published".equalsIgnoreCase(first(string(card,"good_status"),string(card,"goodStatus"),string(card,"cardStatus"))))return false;
        // Published + draft is a published version with a draft revision, not a failed card.
        // An explicitly unsigned response, however, cannot prove that our signing completed.
        var signed=card.get("good_signed");return signed==null||signed.isJsonNull()
                ||signed.isJsonPrimitive()&&signed.getAsJsonPrimitive().isBoolean()&&signed.getAsBoolean();
    }
    private JsonObject readCard(String token,String gtin,Long expectedId)throws Exception {
        // Only a successful empty response proves absence. An HTTP 404 remains retryable.
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
        long actualId=catalogCardIdentity(selected.get(selected.has("good_id")?"good_id":"goodId"));
        if(selected.has("good_id")&&selected.has("goodId")&&catalogCardIdentity(selected.get("goodId"))!=actualId)throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");
        if(actualId<=0||expectedId!=null&&actualId!=expectedId)throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");
        return selected;
    }
    private static long catalogCardIdentity(JsonElement value){
        if(value==null||!value.isJsonPrimitive()||value.getAsJsonPrimitive().isBoolean())throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");
        try{String text=value.getAsString();if(!text.matches("[1-9][0-9]{0,18}"))throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED");return Long.parseLong(text);}
        catch(NumberFormatException invalid){throw new IllegalStateException("CATALOG_PUBLICATION_ID_CHANGED",invalid);}
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
        return sendReviewedSigningBatch(token,List.of(document),beforeSend).getFirst();
    }

    /** At most ten immutable, separately signed XML documents in one non-replayed POST. */
    public List<Long> sendReviewedSigningBatch(String token,List<SigningDocument> documents,BeforeSigningSend beforeSend) throws Exception {
        var frozen=List.copyOf(documents);
        if(frozen.isEmpty()||frozen.size()>10||frozen.stream().map(SigningDocument::goodId).distinct().count()!=frozen.size())
            throw new IllegalArgumentException("CATALOG_SIGNING_BATCH_INVALID");
        LicenseRuntime.require(Permission.GTIN);
        JsonArray batch=new JsonArray();
        for(var document:frozen){
            byte[] xmlBytes=document.xml();
            byte[] signature=signer.sign(xmlBytes,ZnackSignatureContext.TRUE_API_DOCUMENT).cms();
            JsonObject signed = new JsonObject(); signed.addProperty("goodId",document.goodId());
            signed.addProperty("base64Xml",Base64.getEncoder().encodeToString(xmlBytes));
            signed.addProperty("signature",Base64.getEncoder().encodeToString(signature));batch.add(signed);
        }
        LicenseRuntime.require(Permission.GTIN);
        if(beforeSend!=null)beforeSend.check();
        JsonObject response=resultObject(api.signNationalCatalogProduct(settings.resolvedTrueApiBaseUrl(),token,batch));
        var acknowledged=new LinkedHashSet<Long>();
        if(!response.has("signed")||!response.get("signed").isJsonArray())throw new IllegalStateException("CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID");
        for(JsonElement id:response.getAsJsonArray("signed")){
            if(!id.isJsonPrimitive()||!id.getAsJsonPrimitive().isNumber())throw new IllegalStateException("CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID");
            long value;try{value=id.getAsBigDecimal().longValueExact();}catch(ArithmeticException|NumberFormatException invalid){throw new IllegalStateException("CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID",invalid);}
            if(value<=0||!acknowledged.add(value))throw new IllegalStateException("CATALOG_SIGNING_ACKNOWLEDGEMENT_INVALID");
        }
        if(!acknowledged.equals(frozen.stream().map(SigningDocument::goodId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))))
            throw new IllegalStateException(frozen.size()==1?"National Catalog did not confirm the signature. Refresh to reconcile before continuing.":"National Catalog did not confirm every signature. Refresh to reconcile before continuing.");
        return frozen.stream().map(SigningDocument::goodId).toList();
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
            // §3.2.2: the feed carries the registered tariff node; do not duplicate its generated attributes.
            if(entry.getKey()==3959L||entry.getKey()==13933L&&draft.feedTnved().length()==10)return;
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
        return parseAttributes(response,0);
    }
    private static List<Attribute> parseAttributes(JsonElement response,int depth) {
        if(depth>8)throw new IllegalStateException("CATALOG_CONDITIONAL_SCHEMA_INVALID");
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
            var dependencies=new java.util.LinkedHashMap<String,List<Attribute>>();
            for(var dependency:array(value.get("dependent_attributes")))if(dependency.isJsonObject()){
                var condition=dependency.getAsJsonObject();var children=condition.has("attrs")?condition.get("attrs"):condition.get("atters");
                var wrapper=new JsonObject();wrapper.add("result",array(children));dependencies.put(string(condition,"value"),parseAttributes(wrapper,depth+1));
            }
            values.add(new Attribute(id, string(value, "attr_name"), string(value, "attr_field_type"),
                    bool(value, "attr_preset_only"), bool(value, "attr_multiplicity"),
                    bool(value, "first_layer"), bool(value, "second_layer"), presets, valueTypes,dependencies));
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
        public boolean failed() { return Set.of("rejected", "failed", "error").contains(status.toLowerCase(Locale.ROOT)) || !errors.isEmpty(); }
        public boolean cardVerified() {
            return !failed() && goodId != null && goodId > 0
                    && Set.of("carddraft", "cardmoderation", "moderated", "signed").contains(status.toLowerCase(Locale.ROOT));
        }
        public boolean readyToSign() { return "Moderated".equalsIgnoreCase(status) && errors.isEmpty(); }
        public boolean signed() { return "Signed".equalsIgnoreCase(status) && errors.isEmpty(); }
        public String errorMessage() { return String.join("; ", errors); }
    }
}
