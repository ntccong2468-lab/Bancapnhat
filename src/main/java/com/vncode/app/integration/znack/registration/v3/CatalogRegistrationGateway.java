package com.vncode.app.integration.znack.registration.v3;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.znack.*;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.Attribute;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;

/** Real adapters from VNCode 1.5.7. No mock hosts, synthetic acknowledgements, or runtime write bypass. */
public final class CatalogRegistrationGateway implements RegistrationGateway {
    private static final Gson JSON=new GsonBuilder().registerTypeAdapter(Instant.class,new InstantAdapter()).create();
    private static final class InstantAdapter implements JsonSerializer<Instant>,JsonDeserializer<Instant> {
        public JsonElement serialize(Instant value,java.lang.reflect.Type type,JsonSerializationContext context){return new JsonPrimitive(value.toString());}
        public Instant deserialize(JsonElement value,java.lang.reflect.Type type,JsonDeserializationContext context){return Instant.parse(value.getAsString());}
    }
    private final Shop shop;
    public CatalogRegistrationGateway(Shop selected) {
        if(selected==null||selected.getMarketplace()!=Marketplace.WILDBERRIES)throw new IllegalArgumentException("V3_WB_SHOP_REQUIRED");
        shop=new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
    }
    public Owner owner() {
        ListingIdentity.require(shop);
        var settings=new ZnackRepository(new ZnackModels.ShopContext(shop.getId(),shop.getName())).getSettings();
        var url=okhttp3.HttpUrl.get(settings.resolvedTrueApiBaseUrl());
        boolean production=Set.of(okhttp3.HttpUrl.get(ZnackModels.PRODUCTION_TRUE_API).host(),okhttp3.HttpUrl.get(ZnackModels.PRODUCTION_NATIONAL_CATALOG).host()).contains(url.host());
        boolean sandbox=Set.of("markirovka.sandbox.crptech.ru",okhttp3.HttpUrl.get(ZnackModels.SANDBOX_NATIONAL_CATALOG).host()).contains(url.host());
        if(!url.isHttps()||url.port()!=443||!url.username().isEmpty()||!url.password().isEmpty()||!(production||sandbox))throw new SecurityException("V3_CATALOG_ENVIRONMENT_INVALID");
        String scope;
        try {var verified=ZnackRegistrationScope.fromSettings(settings);scope=hash(verified.inn()+"|"+verified.environment()+"|"+verified.fingerprint());}
        catch(ZnackRegistrationScope.ScopeChangedException unconfigured){scope=hash("UNCONFIGURED|"+settings.resolvedTrueApiBaseUrl());}
        return new Owner(shop.getId(),ListingIdentity.fingerprint(shop),production?"production":"sandbox",scope);
    }
    private void guard(RegistrationJob job){if(!owner().equals(job.owner()))throw new SecurityException("V3_OWNER_CHANGED");}
    private ZnackCardRegistrationWorkflow.CatalogSession session(RegistrationJob job)throws Exception {
        guard(job);var result=ZnackCardRegistrationWorkflow.productionCatalog(shop);guard(job);return result;
    }
    public List<Product> products()throws Exception {
        var expected=owner();var api=new WbApiClient();
        return WbRegistrationImport.read((updated,nm)->{
            if(!expected.equals(owner()))throw new SecurityException("V3_OWNER_CHANGED");
            var page=api.getProductCards(shop.getApiKey(),"ru",updated,nm,100);
            if(!expected.equals(owner()))throw new SecurityException("V3_OWNER_CHANGED");return page;
        });
    }
    public List<Attribute> attributes(RegistrationJob job)throws Exception {
        var s=session(job);if(job.data().draft()==null)throw new IllegalStateException("V3_DRAFT_REQUIRED");
        var category=s.catalog().preflightExisting(job.data().draft().tnved(),s.token());
        if(category.categories().stream().noneMatch(c->c.id()==job.data().draft().categoryId()))throw new IllegalStateException("V3_CATEGORY_INVALID");
        return s.catalog().registrationAttributes(job.data().draft().categoryId(),s.token());
    }
    public List<ZnackCardRegistrationModels.Category> categories(RegistrationJob job,String tnved)throws Exception {
        var s=session(job);var result=s.catalog().preflightExisting(tnved,s.token()).categories();guard(job);return result;
    }
    public void validate(RegistrationJob job)throws Exception {
        var issues=RegistrationValidation.issues(job.data().gtin(),job.data().draft(),attributes(job));
        if(!issues.isEmpty())throw new IllegalStateException(String.join("\n",issues));
        var s=session(job);s.catalog().validateCurrentRegistrationDraft(s.token(),job.data().draft());
        s.catalog().validateDocuments(s.token(),job.data().draft());guard(job);
    }
    private RegistrationExistingCards.Variant variant(RegistrationJob job) {
        var p=job.product();var codes=new ArrayList<>(p.barcodes());if(!blank(job.data().gtin()))codes.add(job.data().gtin());
        return new RegistrationExistingCards.Variant(new com.vncode.app.features.products.ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(p.nmId()),Long.toString(p.chrtId())),
                p.vendorCode(),p.color(),p.size(),p.russianSize(),job.data().draft()==null?"":job.data().draft().tnved(),codes,p.title(),p.subjectName(),p.brand());
    }
    public String allocate(RegistrationJob job)throws Exception {
        RegistrationContractPolicy.require(Action.ALLOCATE_GTIN,job.owner().environment());
        var s=session(job);
        // A complete lookup of existing cards must succeed before allocating anything.
        try(var lookup=RegistrationExistingCards.currentOperation()) {
            var existing=lookup.lookup(shop,variant(job),s);if(existing.isPresent())return existing.orElseThrow().gtin();
        }
        var preflight=s.catalog().preflight(job.data().draft().tnved());
        if(!preflight.gs1().quotaKnown()||!preflight.gs1().canGenerate())throw new IllegalStateException("V3_GTIN_QUOTA_UNVERIFIED");
        s.catalog().validateCurrentRegistrationDraft(s.token(),job.data().draft());guard(job);
        var claimed=new HashSet<>(new ZnackCardRegistrationRepository().claimedGtins());claimed.addAll(new RegistrationStore().claimedGtins(job.owner()));
        return s.catalog().generateOne(s.token(),claimed);
    }
    public String submit(RegistrationJob job)throws Exception {
        RegistrationContractPolicy.require(Action.SUBMIT,job.owner().environment());
        var s=session(job);var body=JsonParser.parseString(job.data().payload()).getAsJsonObject();
        if(job.data().goodId()!=null) {
            try {
                s.catalog().rejectedCard(s.token(),job.data().gtin(),job.data().goodId());
                return s.catalog().submit(s.token(),body,()->{
                    guard(job);
                    try{s.catalog().rejectedCard(s.token(),job.data().gtin(),job.data().goodId());}
                    catch(Exception changed){throw new IllegalStateException("V3_REJECTED_CARD_CHANGED",changed);}
                });
            }catch(IllegalStateException notRejected){
                if(!"CATALOG_CARD_NOT_REJECTED".equals(notRejected.getMessage()))throw notRejected;
            }
            var reviewed=ZnackNationalCatalogService.reviewedDraftPayload(shop,body);
            return s.catalog().submitReviewedDraft(shop,s.token(),reviewed,()->guard(job));
        }
        return s.catalog().submit(s.token(),body,()->guard(job));
    }
    public Observation observe(RegistrationJob job)throws Exception {
        var s=session(job);var card=s.catalog().observedCardStatus(s.token(),job.data().gtin(),job.data().goodId());
        if(card==null)throw new IllegalStateException("V3_CARD_NOT_FOUND_OR_PENDING");
        if(job.data().goodId()==null&&blank(job.data().feedId())) {
            try(var lookup=RegistrationExistingCards.currentOperation()) {
                var known=lookup.lookup(shop,variant(job),s);
                if(known.isEmpty()||!RegisteredGtinProof.sameCode(job.data().gtin(),known.orElseThrow().gtin()))throw new IllegalStateException("V3_VARIANT_IDENTITY_UNVERIFIED");
            }
        }
        var p=blank(job.data().feedId())?card.progress():s.catalog().submissionStatus(s.token(),job.data().feedId(),job.data().gtin());
        if(p.goodId()!=null&&p.goodId()!=card.goodId())throw new IllegalStateException("V3_CARD_IDENTITY_CHANGED");
        RemoteState state=p.failed()?RemoteState.REJECTED:p.signed()?RemoteState.PUBLISHED:p.readyToSign()?RemoteState.APPROVED:
                Set.of("received","processing","cardmoderation").contains(p.status().toLowerCase(Locale.ROOT))?RemoteState.MODERATION:RemoteState.DRAFT;
        String matched="";
        if(!blank(job.data().payload())) {
            var body=JsonParser.parseString(job.data().payload()).getAsJsonObject();
            if(state==RemoteState.PUBLISHED) {
                if(s.catalog().verifyReviewedPublication(s.token(),job.data().gtin(),card.goodId(),body))matched=hash(job.data().payload());
                else state=RemoteState.DRAFT;
            } else if(state==RemoteState.APPROVED) {
                s.catalog().validateApprovedSigningCard(s.token(),job.data().gtin(),card.goodId(),body);matched=hash(job.data().payload());
            } else if(state==RemoteState.REJECTED&&s.catalog().matchesReviewedCard(s.token(),job.data().gtin(),card.goodId(),body)) {
                matched=hash(job.data().payload());
            }
        }
        guard(job);
        return new Observation(card.gtin(),card.goodId(),job.data().feedId(),state,job.owner().environment(),card.checkedAt().toString(),
                hash(card.gtin()+"|"+card.goodId()+"|"+card.rawStatus()+"|"+p.status()+"|"+card.checkedAt()),matched,p.errorMessage());
    }
    public Document document(RegistrationJob job)throws Exception {
        var s=session(job);var observed=observe(job);
        if(observed.state()!=RemoteState.APPROVED)throw new IllegalStateException("V3_SIGNING_NOT_APPROVED");
        var doc=s.catalog().reviewSigning(s.token(),job.data().gtin());guard(job);
        return new Document(doc.goodId(),new String(doc.xml(),java.nio.charset.StandardCharsets.UTF_8));
    }
    public void sign(RegistrationJob job)throws Exception {
        RegistrationContractPolicy.require(Action.SIGN,job.owner().environment());
        var s=session(job);var live=s.catalog().reviewSigning(s.token(),job.data().gtin());
        if(!Objects.equals(job.data().goodId(),live.goodId())||!live.sha256().equals(job.data().signingHash()))throw new IllegalStateException("V3_SIGNING_DOCUMENT_CHANGED");
        s.catalog().validateApprovedSigningCard(s.token(),job.data().gtin(),job.data().goodId(),JsonParser.parseString(job.data().payload()).getAsJsonObject());
        s.catalog().sendReviewedSigning(s.token(),live,()->guard(job));
    }
    public String previewWb(RegistrationJob job)throws Exception {
        guard(job);var repository=new GtinSyncRepository();
        var observed=NationalCatalogGtinSource.forSelection(shop,Set.of(job.data().gtin()),true).read(shop.getId());
        guard(job);repository.mergeCatalog(shop.getId(),Marketplace.WILDBERRIES,observed);
        var key=new ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(job.product().nmId()),Long.toString(job.product().chrtId()));
        var preview=new GtinPreviewService(repository,k->new WbGtinAdapter(shop)).create(key,Operation.ADD,"",job.data().gtin());
        return JSON.toJson(preview);
    }
    private Preview wbPreview(RegistrationJob job){return JSON.fromJson(job.data().wbPreview(),Preview.class);}
    public boolean syncWb(RegistrationJob job)throws Exception {
        RegistrationContractPolicy.require(Action.SYNC_WB,job.owner().environment());guard(job);
        var preview=wbPreview(job);var adapter=new WbGtinAdapter(shop);var result=adapter.submit(preview);guard(job);
        return adapter.verify(preview,result)==Verification.APPLIED;
    }
    public boolean verifyWb(RegistrationJob job)throws Exception {
        guard(job);return new WbGtinAdapter(shop).verify(wbPreview(job),new Submission(""))==Verification.APPLIED;
    }
}
