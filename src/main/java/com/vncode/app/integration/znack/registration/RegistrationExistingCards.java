package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.models.Shop;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import static com.vncode.app.integration.znack.registration.ZnackCardRegistrationWorkflow.CatalogSession;

/** One bounded, current read operation; neither a durable negative cache nor permission to write. */
public final class RegistrationExistingCards {
    private RegistrationExistingCards(){}
    /** A loader must either return the complete scoped snapshot or throw; partial lists are not absence. */
    @FunctionalInterface public interface SnapshotLoader {List<RegisteredGtin> read(Shop shop,CatalogSession session)throws Exception;}
    @FunctionalInterface public interface Observer {ZnackNationalCatalogService.ObservedCard read(CatalogSession session,String gtin,Long expectedId)throws Exception;}
    @FunctionalInterface public interface ScopeReader {String read(Shop shop,CatalogSession session)throws Exception;}
    public record Variant(ProductKey key,String article,String color,String size,String russianSize,String tnved,
                          List<String> barcodes,String title,String category,String brand){
        public Variant {Objects.requireNonNull(key);article=text(article);color=text(color);size=text(size);russianSize=text(russianSize);tnved=text(tnved);barcodes=List.copyOf(barcodes);title=text(title);category=text(category);brand=text(brand);}
        /** WB Sku has no shop field; callers must supply the stable shop identity explicitly. */
        public static Variant wb(Shop shop,Sku row,Draft draft){
            return new Variant(new ProductKey(shop.getId(),Marketplace.WILDBERRIES,Long.toString(row.nmId()),Long.toString(row.chrtId())),row.vendorCode(),row.color(),row.size(),row.wbSize(),draft.tnved(),row.barcodes(),row.title(),row.subjectName(),row.brand());
        }
        public static Variant ozon(ProductSnapshot snapshot,Draft draft){
            var variants=snapshot.variants().stream().filter(v->snapshot.key().variantId().equals(v.id())).toList();
            if(variants.size()!=1)throw invalid("CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED");var variant=variants.getFirst();
            return new Variant(snapshot.key(),variant.sellerArticle(),variant.color(),variant.size(),variant.russianSize(),draft.tnved(),variant.barcodes(),snapshot.title(),snapshot.metadata().getOrDefault("source.category.name",snapshot.categoryId()),snapshot.brand());
        }
    }
    public static LookupContext currentOperation(){
        return new LookupContext((shop,session)->{
            String source=RegisteredGtinProof.source(shop);var rows=NationalCatalogGtinSource.forRegistrationLookup(shop).read(shop.getId());
            if(rows.stream().anyMatch(row->!source.equals(row.source())))throw new ZnackRegistrationScope.ScopeChangedException();return rows;
        },(session,gtin,id)->session.catalog().observedCardStatus(session.token(),gtin,id),RegistrationExistingCards::currentScope);
    }
    private static String currentScope(Shop shop,CatalogSession session){
        ListingIdentity.require(shop);session.catalog().assertRegistrationReadScope();
        if(session.scope()==null)throw new ZnackRegistrationScope.ScopeChangedException();session.scope().assertCurrent(shop);
        return ListingIdentity.hash(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()+"\n"+ListingIdentity.fingerprint(shop)+"\n"+RegisteredGtinProof.source(shop));
    }
    public static final class LookupContext implements AutoCloseable {
        private final SnapshotLoader loader;private final Observer observer;private final ScopeReader scopeReader;
        private final java.nio.file.Path profile=com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize();
        private String scope;private Shop originalShop;private CatalogSession originalSession;private GtinCatalogIndex index;
        private final Map<String,List<RegisteredGtin>> allCodes=new HashMap<>();private boolean loaded,closed;private Exception failure;
        public LookupContext(SnapshotLoader loader,Observer observer,ScopeReader scopeReader){this.loader=Objects.requireNonNull(loader);this.observer=Objects.requireNonNull(observer);this.scopeReader=Objects.requireNonNull(scopeReader);}
        /** Empty means one complete current read found no supported exact match. Every unknown result throws. */
        public synchronized Optional<ZnackNationalCatalogService.ObservedCard> lookup(Shop shop,Variant variant,CatalogSession session)throws Exception{
            if(closed)throw invalid("CATALOG_EXISTING_CARD_LOOKUP_CLOSED");if(failure!=null)throw failure;
            boolean snapshotReady=false;
            try{
                checkScope(shop,variant,session);
                if(!loaded){
                    var rows=List.copyOf(loader.read(shop,session));checkScope(shop,variant,session);
                    for(var row:rows){if(!RegisteredGtinValidator.isValid(row.gtin()))throw invalid("CATALOG_EXISTING_CARD_LOOKUP_INVALID");allCodes.computeIfAbsent(normal(row.gtin()),key->new ArrayList<>()).add(row);}
                    index=new GtinCatalogIndex(rows,true);loaded=true;
                }
                snapshotReady=true;
                if(allCodes.isEmpty())return Optional.empty();
                if(variant.article().isBlank()||variant.color().isBlank()||variant.size().isBlank())throw invalid("CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED");
                var candidates=new LinkedHashSet<RegisteredGtin>();
                for(String barcode:variant.barcodes())if(RegisteredGtinValidator.isValid(barcode)){
                    var exact=allCodes.getOrDefault(normal(barcode),List.of());
                    for(var row:exact)if(!compatible(variant,row))throw invalid("CATALOG_EXISTING_CARD_VARIANT_CONFLICT");candidates.addAll(exact);
                }
                candidates.addAll(matches(variant,index));
                var identities=new LinkedHashMap<String,RegisteredGtin>();
                for(var row:candidates){var prior=identities.putIfAbsent(normal(row.gtin()),row);if(prior!=null&&!sameObservation(prior,row))throw invalid("CATALOG_EXISTING_CARD_AMBIGUOUS");}
                if(identities.isEmpty())return Optional.empty();if(identities.size()!=1)throw invalid("CATALOG_EXISTING_CARD_AMBIGUOUS");
                var candidate=identities.values().iterator().next();if(!compatible(variant,candidate))throw invalid("CATALOG_EXISTING_CARD_VARIANT_CONFLICT");
                Long expected=expectedId(candidate);checkScope(shop,variant,session);var observed=observer.read(session,candidate.gtin(),expected);checkScope(shop,variant,session);
                if(observed==null)throw invalid("CATALOG_EXISTING_CARD_READBACK_REQUIRED");
                if(observed.goodId()<=0||expected!=null&&expected!=observed.goodId()||!RegisteredGtinProof.sameCode(candidate.gtin(),observed.gtin())||observed.progress()==null||observed.progress().goodId()==null||observed.progress().goodId()!=observed.goodId()||observed.rawStatus()==null||observed.rawStatus().isBlank()||observed.checkedAt()==null||observed.checkedAt().isAfter(Instant.now().plusSeconds(300)))throw invalid("CATALOG_EXISTING_CARD_LOOKUP_INVALID");
                var fresh=new RegisteredGtin(observed.gtin(),observed.details().single("article"),observed.details().single("color"),observed.details().single("size"),candidate.tradeUnit(),observed.progress().signed(),candidate.source(),observed.checkedAt(),observed.details());
                if(!compatible(variant,fresh))throw invalid("CATALOG_EXISTING_CARD_VARIANT_CONFLICT");
                requireTnved(variant.tnved(),fresh.details().values("tnved"));return Optional.of(observed);
            }catch(Exception unknown){
                // A deterministic problem with one variant must not discard valid evidence
                // for the other approved rows. Unknown source/scope/transport failures still stop reuse.
                if(!snapshotReady||!rowSpecific(unknown)){failure=unknown;index=null;allCodes.clear();}
                throw unknown;
            }
        }
        private void checkScope(Shop shop,Variant variant,CatalogSession session)throws Exception{
            if(!profile.equals(com.vncode.app.shared.AppPaths.appDataDir().toAbsolutePath().normalize()))throw new ZnackRegistrationScope.ScopeChangedException();ListingIdentity.require(shop,variant.key());Objects.requireNonNull(session);
            String current=scopeReader.read(shop,session);if(current==null||current.isBlank())throw new ZnackRegistrationScope.ScopeChangedException();
            if(scope==null){scope=current;originalShop=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());originalSession=session;}
            if(!scope.equals(current)||!scope.equals(scopeReader.read(originalShop,originalSession)))throw new ZnackRegistrationScope.ScopeChangedException();
        }
        @Override public synchronized void close(){closed=true;allCodes.clear();index=null;originalSession=null;originalShop=null;}
    }
    private static List<RegisteredGtin> matches(Variant variant,GtinCatalogIndex index){
        var exact=index.attributes(variant.article(),variant.color(),variant.size(),variant.russianSize());
        if(variant.key().marketplace()!=Marketplace.OZON||!exact.isEmpty())return exact;
        var row=new ProductRow(variant.key(),variant.title(),variant.article(),"",variant.category(),variant.brand(),variant.color(),variant.size(),variant.barcodes(),"","","",variant.tnved(),DocumentState.UNVERIFIED,"",true,variant.russianSize(),variant.size());return index.ozon(row);
    }
    private static boolean compatible(Variant variant,RegisteredGtin row){
        return row.tradeUnit()&&!GtinNormalizer.isTechnicalRange(row.gtin())&&matches(variant,new GtinCatalogIndex(List.of(row),true)).size()==1;
    }
    private static boolean sameObservation(RegisteredGtin a,RegisteredGtin b){return a.article().equals(b.article())&&a.color().equals(b.color())&&a.size().equals(b.size())&&a.tradeUnit()==b.tradeUnit()&&a.details().equals(b.details());}
    private static boolean rowSpecific(Exception failure){return failure instanceof RegistrationValidationException&&Set.of(
            "CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED","CATALOG_EXISTING_CARD_VARIANT_CONFLICT","CATALOG_EXISTING_CARD_AMBIGUOUS",
            "CATALOG_EXISTING_CARD_READBACK_REQUIRED","CATALOG_REGISTERED_TNVED_MISMATCH","CATALOG_OWNER_MISMATCH").contains(failure.getMessage());}
    private static Long expectedId(RegisteredGtin row){String value=row.details().single("@catalog_id");if(value.isBlank())return null;try{long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException invalid){throw invalid("CATALOG_EXISTING_CARD_LOOKUP_INVALID");}}
    private static void requireTnved(String requested,List<String> registered){
        if(!requested.matches("[0-9]{4}|[0-9]{10}")||registered.size()!=1||!registered.getFirst().matches("[0-9]{4}|[0-9]{10}"))throw invalid("CATALOG_EXISTING_CARD_VARIANT_UNVERIFIED");
        String actual=registered.getFirst();if(requested.length()==10?!requested.equals(actual):!requested.equals(actual.substring(0,4)))throw invalid("CATALOG_REGISTERED_TNVED_MISMATCH");
    }
    private static String normal(String gtin){return RegisteredGtinProof.canonicalCode(gtin);}
    private static String text(String value){return Objects.toString(value,"").strip();}
    private static RegistrationValidationException invalid(String code){return new RegistrationValidationException(code);}
}
