package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.ProductFieldNames;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.time.Instant;
import java.util.*;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** Produces candidates only; neither an exact barcode nor an article is a write authorisation. */
public final class WbOzonGtinMatcher {
    public enum Evidence { EXACT_BARCODE, EXACT_ARTICLE, MANUAL_PAIR }
    /** Caller has freshly verified the WB journal, shop owner and registration scope. */
    public record NativeAssociation(ProductKey wbKey,String gtin,long goodId,String owner,String scope,String provenance) {
        public NativeAssociation {
            Objects.requireNonNull(wbKey);if(wbKey.marketplace()!=Marketplace.WILDBERRIES||!RegisteredGtinValidator.isValid(gtin)||goodId<=0
                    ||owner==null||owner.isBlank()||scope==null||scope.isBlank()||!"WB_PUBLISHED_JOURNAL".equals(provenance))
                throw new IllegalArgumentException("WB_ASSOCIATION_INVALID");
        }
    }
    public record Candidate(ProductRow wb, ProductRow ozon, RegisteredGtin catalog, Evidence evidence, boolean autoEligible,NativeAssociation nativeAssociation) {
        public Candidate(ProductRow wb,ProductRow ozon,RegisteredGtin catalog,Evidence evidence,boolean autoEligible){this(wb,ozon,catalog,evidence,autoEligible,null);}
        public Candidate { Objects.requireNonNull(wb); Objects.requireNonNull(ozon); Objects.requireNonNull(catalog); Objects.requireNonNull(evidence); }
        /** Conflicting descriptions require explicit seller review; they never prove an identity. */
        public List<String> reviewWarnings(){return descriptiveWarnings(wb,ozon,catalog);}
    }
    public record Result(ProductRow ozon, List<Candidate> candidates, String reason) {
        public Result { Objects.requireNonNull(ozon); candidates=List.copyOf(candidates); reason=Objects.requireNonNull(reason); }
    }
    private record Source(ProductRow wb, RegisteredGtin card, NativeAssociation association) {}

    /**
     * Candidate card observations must come from the destination Ozon shop's Catalog scope.
     * The caller must independently establish that the WB and Ozon businesses are the same,
     * and re-read ownership, source and product identity before any mutation.
     */
    public List<Result> match(List<ProductRow> wb, List<ProductRow> ozon, List<RegisteredGtin> catalog,
                              String expectedCatalogSource, Instant now) {
        return match(wb,ozon,catalog,expectedCatalogSource,now,Map.of());
    }
    public List<Result> match(List<ProductRow> wb, List<ProductRow> ozon, List<RegisteredGtin> catalog,
                              String expectedCatalogSource, Instant now,Map<ProductKey,NativeAssociation> associations) {
        Objects.requireNonNull(now); requireRows(wb,Marketplace.WILDBERRIES); requireRows(ozon,Marketplace.OZON);
        Objects.requireNonNull(associations);
        var cards=cards(catalog,expectedCatalogSource,now);
        var byArticle=new HashMap<String,List<Source>>(); var byCode=new HashMap<String,List<Source>>();
        for(var row:wb){
            for(var source:sourceCards(row,cards,associations.get(row.key()))){
                String article=article(row.vendorArticle());
                if(!article.isBlank())byArticle.computeIfAbsent(article,k->new ArrayList<>()).add(source);
                byCode.computeIfAbsent(code(source.card().gtin()),k->new ArrayList<>()).add(source);
            }
        }
        var provisional=new ArrayList<Result>();
        for(var dest:ozon){
            var found=new LinkedHashMap<String,Candidate>();
            var issues=new LinkedHashSet<String>();
            boolean destinationHasCatalogCode=dest.barcodes().stream().map(WbOzonGtinMatcher::code).anyMatch(cards::containsKey);
            for(String barcode:dest.barcodes())for(var source:byCode.getOrDefault(code(barcode),List.of())){
                String issue=mismatch(source.wb(),dest,source.card());if(!issue.isBlank())issues.add(issue);
                add(found,candidate(source.wb(),dest,source.card(),Evidence.EXACT_BARCODE,source.association()));
            }
            if(found.isEmpty()&&!destinationHasCatalogCode)for(var source:byArticle.getOrDefault(article(dest.vendorArticle()),List.of())){
                String issue=mismatch(source.wb(),dest,source.card());if(!issue.isBlank())issues.add(issue);
                add(found,candidate(source.wb(),dest,source.card(),Evidence.EXACT_ARTICLE,source.association()));
            }
            var values=List.copyOf(found.values());
            if(values.size()>1)values=values.stream().map(c->new Candidate(c.wb(),c.ozon(),c.catalog(),c.evidence(),false,c.nativeAssociation())).toList();
            String reason=values.isEmpty()?(!issues.isEmpty()?issueReason(issues):destinationHasCatalogCode?"EXISTING_GTIN_CONFLICT":"NO_SAFE_MATCH")
                    :values.size()>1?"AMBIGUOUS":values.getFirst().autoEligible()?"":"SELLER_REVIEW_REQUIRED";
            provisional.add(new Result(dest,values,reason));
        }
        return distinctDestinations(provisional);
    }
    private static List<Result> distinctDestinations(List<Result> provisional){
        // A GTIN shared by multiple destination variants is not safe to auto-link.
        var destinations=new HashMap<String,Set<ProductKey>>();
        for(var result:provisional)for(var candidate:result.candidates())
            destinations.computeIfAbsent(code(candidate.catalog().gtin()),k->new HashSet<>()).add(candidate.ozon().key());
        return provisional.stream().map(result->{
            if(result.candidates().size()!=1)return result;
            var c=result.candidates().getFirst();
            if(destinations.get(code(c.catalog().gtin())).size()>1)
                return new Result(result.ozon(),List.of(new Candidate(c.wb(),c.ozon(),c.catalog(),c.evidence(),false,c.nativeAssociation())),"GTIN_MULTIPLE_OZON_VARIANTS");
            return result;
        }).toList();
    }

    /** Seller-picked pair may have different article values; it is never automatic. */
    public Result evaluatePair(ProductRow wb, ProductRow ozon, List<RegisteredGtin> catalog,
                               String expectedCatalogSource, Instant now){
        return evaluatePair(wb,ozon,catalog,expectedCatalogSource,now,null);
    }
    public Result evaluatePair(ProductRow wb, ProductRow ozon, List<RegisteredGtin> catalog,
                               String expectedCatalogSource, Instant now,NativeAssociation association){
        requireRows(List.of(wb),Marketplace.WILDBERRIES);requireRows(List.of(ozon),Marketplace.OZON);
        var index=cards(catalog,expectedCatalogSource,Objects.requireNonNull(now));
        var sources=sourceCards(wb,index,association);
        Set<String> existing=new HashSet<>();for(String barcode:ozon.barcodes()){String value=code(barcode);if(index.containsKey(value))existing.add(value);}
        var matches=sources.stream()
                .filter(source->existing.isEmpty()||existing.contains(code(source.card().gtin())))
                .map(source->candidate(wb,ozon,source.card(),Evidence.MANUAL_PAIR,source.association())).filter(Objects::nonNull).toList();
        var issues=new LinkedHashSet<String>();for(var source:sources){if(!existing.isEmpty()&&!existing.contains(code(source.card().gtin()))){issues.add("EXISTING_GTIN_CONFLICT");continue;}String issue=mismatch(wb,ozon,source.card());if(!issue.isBlank())issues.add(issue);}
        return new Result(ozon,matches,matches.isEmpty()?(issues.isEmpty()?"NO_SAFE_MATCH":issueReason(issues)):matches.size()>1?"AMBIGUOUS":"SELLER_REVIEW_REQUIRED");
    }

    /**
     * Review one selected WB model against many Ozon variants. Catalog and variant
     * indexes are built once; all results remain seller-reviewed, never automatic.
     */
    public List<Result> evaluateManualBatch(List<ProductRow> wbVariants,List<ProductRow> ozon,List<RegisteredGtin> catalog,
                                            String expectedCatalogSource,Instant now,Map<ProductKey,NativeAssociation> associations){
        requireRows(wbVariants,Marketplace.WILDBERRIES);requireRows(ozon,Marketplace.OZON);Objects.requireNonNull(associations);
        if(wbVariants.stream().map(v->v.key().cardId()).distinct().limit(2).count()>1)throw new IllegalArgumentException("ONE_WB_MODEL_REQUIRED");
        var cards=cards(catalog,expectedCatalogSource,Objects.requireNonNull(now));
        record Variant(String color,String size) {}
        var index=new HashMap<Variant,List<Source>>();
        for(var wb:wbVariants)for(var source:sourceCards(wb,cards,associations.get(wb.key()))){
            var key=new Variant(colorKey(source.card().color()),norm(source.card().size()));
            if(!key.color().isBlank()&&!key.size().isBlank())index.computeIfAbsent(key,k->new ArrayList<>()).add(source);
        }
        var results=new ArrayList<Result>();
        for(var dest:ozon){
            var sizes=new LinkedHashSet<String>();sizes.add(norm(GtinVariantIdentity.primarySize(dest)));sizes.add(norm(dest.russianSize()));sizes.remove("");
            var found=new LinkedHashMap<String,Candidate>();var examined=new LinkedHashSet<Source>();var issues=new LinkedHashSet<String>();
            boolean tooMany=false;for(String size:sizes){
                var sources=index.getOrDefault(new Variant(colorKey(dest.color()),size),List.of());
                if(sources.size()>100){tooMany=true;break;}
                examined.addAll(sources);
            }
            if(tooMany||examined.size()>100){results.add(new Result(dest,List.of(),"AMBIGUOUS_TOO_MANY"));continue;}
            Set<String> existing=new HashSet<>();for(String barcode:dest.barcodes()){String c=code(barcode);if(cards.containsKey(c))existing.add(c);}
            for(var source:examined){
                if(!existing.isEmpty()&&!existing.contains(code(source.card().gtin()))){issues.add("EXISTING_GTIN_CONFLICT");continue;}
                String issue=mismatch(source.wb(),dest,source.card());if(!issue.isBlank())issues.add(issue);
                add(found,candidate(source.wb(),dest,source.card(),Evidence.MANUAL_PAIR,source.association()));
            }
            var values=List.copyOf(found.values());String reason=values.isEmpty()?(issues.isEmpty()?"NO_SAFE_MATCH":issueReason(issues))
                    :values.size()>1?"AMBIGUOUS":"SELLER_REVIEW_REQUIRED";
            results.add(new Result(dest,values,reason));
        }
        return distinctDestinations(results);
    }

    private static Candidate candidate(ProductRow wb,ProductRow ozon,RegisteredGtin card,Evidence evidence,NativeAssociation association){
        if(!mismatch(wb,ozon,card).isBlank())return null;
        String brand=card.details().single("brand"),gender=card.details().single("gender"),material=card.details().single("material");
        String w=tnved(wb.tnved()),o=tnved(ozon.tnved()),c=tnved(card.details().single("tnved"));
        boolean enough=descriptiveWarnings(wb,ozon,card).isEmpty()&&wb.attributesLoaded()&&ozon.attributesLoaded()&&w.matches("[0-9]{10}")&&w.equals(o)&&w.equals(c)
                &&knownSame(wb.brand(),ozon.brand())&&knownSame(wb.material(),ozon.material())&&knownSame(wb.gender(),ozon.gender())
                &&knownSame(wb.brand(),brand)&&knownSame(wb.material(),material)&&knownSame(wb.gender(),gender);
        return new Candidate(wb,ozon,card,evidence,evidence!=Evidence.MANUAL_PAIR&&enough,association);
    }
    private static String mismatch(ProductRow wb,ProductRow ozon,RegisteredGtin card){
        if(norm(wb.color()).isBlank()||norm(ozon.color()).isBlank()||norm(card.color()).isBlank())return "COLOR_MISSING";
        if(!OzonSkuGtinMatcher.sameColor(wb.color(),ozon.color())||!OzonSkuGtinMatcher.sameColor(ozon.color(),card.color()))return "COLOR_MISMATCH";
        if(norm(GtinVariantIdentity.primarySize(wb)).isBlank()||norm(GtinVariantIdentity.primarySize(ozon)).isBlank()||norm(card.size()).isBlank())return "SIZE_MISSING";
        if(!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(wb),wb.russianSize())
                ||!GtinVariantIdentity.sizeMatches(card,GtinVariantIdentity.primarySize(ozon),ozon.russianSize()))return "SIZE_MISMATCH";
        String w=tnved(wb.tnved()),o=tnved(ozon.tnved()),c=tnved(card.details().single("tnved"));
        if(!w.isBlank()&&!o.isBlank()&&!w.equals(o)||!w.isBlank()&&!c.isBlank()&&!w.equals(c)||!o.isBlank()&&!c.isBlank()&&!o.equals(c))return "TNVED_MISMATCH";
        return "";
    }
    private static List<String> descriptiveWarnings(ProductRow wb,ProductRow ozon,RegisteredGtin card){
        var warnings=new ArrayList<String>();
        if(conflicts(wb.brand(),ozon.brand())||conflicts(wb.brand(),card.details().single("brand"))||conflicts(ozon.brand(),card.details().single("brand")))warnings.add("BRAND_MISMATCH");
        if(conflicts(wb.gender(),ozon.gender())||conflicts(wb.gender(),card.details().single("gender"))||conflicts(ozon.gender(),card.details().single("gender")))warnings.add("GENDER_MISMATCH");
        if(conflicts(wb.material(),ozon.material())||conflicts(wb.material(),card.details().single("material"))||conflicts(ozon.material(),card.details().single("material")))warnings.add("MATERIAL_MISMATCH");
        return List.copyOf(warnings);
    }
    private static String colorKey(String value){
        return switch(norm(value)){
            case "NÂU","NAU","BROWN","КОРИЧНЕВЫЙ" -> "BROWN";
            case "ĐEN","DEN","BLACK","ЧЕРНЫЙ","ЧЁРНЫЙ" -> "BLACK";
            case "TRẮNG","TRANG","WHITE","БЕЛЫЙ" -> "WHITE";
            case "BE","BEIGE","БЕЖЕВЫЙ" -> "BEIGE";
            default -> norm(value);
        };
    }
    private static String issueReason(Set<String> issues){return issues.size()==1?issues.iterator().next():"MULTIPLE_IDENTITY_MISMATCHES";}
    private static Map<String,List<RegisteredGtin>> cards(List<RegisteredGtin> catalog,String source,Instant now){
        Objects.requireNonNull(catalog);if(source==null||source.isBlank())throw new IllegalArgumentException("CATALOG_SCOPE_REQUIRED");
        var out=new HashMap<String,List<RegisteredGtin>>();
        for(var row:catalog)if(row!=null&&source.equals(row.source())&&RegisteredGtinProof.currentObservation(row,now)
                &&!GtinNormalizer.isTechnicalRange(row.gtin()))out.computeIfAbsent(code(row.gtin()),k->new ArrayList<>()).add(row);
        return out;
    }
    private static List<Source> sourceCards(ProductRow wb,Map<String,List<RegisteredGtin>> cards,NativeAssociation association){
        var refs=new LinkedHashSet<String>();if(wb.verifiedGtin()!=null&&!wb.verifiedGtin().isBlank())refs.add(code(wb.verifiedGtin()));
        for(var barcode:wb.barcodes())refs.add(code(barcode));
        if(association!=null){if(!association.wbKey().equals(wb.key()))throw new IllegalArgumentException("WB_ASSOCIATION_INVALID");refs.add(code(association.gtin()));}
        var results=new LinkedHashSet<Source>();for(var ref:refs)for(var card:cards.getOrDefault(ref,List.of())){
            NativeAssociation proved=association!=null&&RegisteredGtinProof.sameCode(association.gtin(),card.gtin())
                    &&Long.toString(association.goodId()).equals(card.details().single("@catalog_id"))?association:null;
            // A journal-only GTIN is usable only when its good_id also matches the Catalog card.
            boolean barcode=wb.barcodes().stream().anyMatch(v->RegisteredGtinProof.sameCode(v,card.gtin()))
                    ||RegisteredGtinProof.sameCode(wb.verifiedGtin(),card.gtin());
            if(barcode||proved!=null)results.add(new Source(wb,card,proved));
        }return List.copyOf(results);
    }
    private static void add(Map<String,Candidate> result,Candidate candidate){if(candidate!=null)result.putIfAbsent(candidate.wb().key()+"\n"+code(candidate.catalog().gtin())+"\n"+candidate.catalog().details().single("@catalog_id"),candidate);}
    private static String code(String value){return RegisteredGtinProof.canonicalCode(value);}
    private static String article(String value){return GtinMatchingService.normalizeArticle(value);}
    private static String tnved(String value){return ProductFieldNames.tnvedCode(value);}
    private static boolean conflicts(String a,String b){return !norm(a).isBlank()&&!norm(b).isBlank()&&!norm(a).equals(norm(b));}
    private static boolean knownSame(String a,String b){return !norm(a).isBlank()&&norm(a).equals(norm(b));}
    private static String norm(String value){return GtinMatchingService.normalize(value);}
    private static void requireRows(List<ProductRow> rows,Marketplace marketplace){
        Objects.requireNonNull(rows);Set<ProductKey> keys=new HashSet<>();Integer shop=null;
        for(var row:rows){if(row==null||row.key().marketplace()!=marketplace||!keys.add(row.key()))throw new IllegalArgumentException("PRODUCT_SCOPE_INVALID");
            if(shop==null)shop=row.key().shopId();else if(shop!=row.key().shopId())throw new IllegalArgumentException("PRODUCT_SCOPE_INVALID");}
    }
}
