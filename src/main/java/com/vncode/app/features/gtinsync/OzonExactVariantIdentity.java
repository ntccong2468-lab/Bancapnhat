package com.vncode.app.features.gtinsync;

import com.vncode.app.features.products.ProductAttribute;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.features.products.ProductVariant;
import com.vncode.app.integration.marketplace.Marketplace;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;

/** Ozon model/size identity only. Owner, publication and write/payment eligibility belong to callers. */
public final class OzonExactVariantIdentity {
    /** Sorted, immutable facts suitable for binding an identity-specific proof. No size chart is inferred. */
    public record Identity(String model,List<String> sizes,String sizeSystem,String primarySize,String russianSize) {
        public Identity {model=Objects.requireNonNull(model);sizes=List.copyOf(new TreeSet<>(sizes));}
    }
    private record Candidate(RegisteredGtin card,Identity identity) {}
    private record Facts(List<String> articles,List<String> fallback,Set<String> sizes,String primary,String russian,String sizeSystem) {}
    private static final Pattern ARTICLE_MARKER=Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:арт(?:икул)?|article)(?:\\.\\s*[:#№=]?\\s*|\\s*[:#№=]\\s*|\\s+)([^;\\r\\n,|]+)");
    private static final Pattern NEXT_FIELD=Pattern.compile("(?iu)\\s+(?:цвет|color|размер|size)\\s*[:=]\\s*");
    private static final Pattern SIZE_MARKER=Pattern.compile("(?iu)^(?:size|размер)\\s*[:=]?\\s*");
    private final Map<String,List<Candidate>> models=new HashMap<>();

    public OzonExactVariantIdentity(List<RegisteredGtin> catalog) {
        for(var card:catalog)identity(card).ifPresent(id->models.computeIfAbsent(id.model(),key->new ArrayList<>()).add(new Candidate(card,id)));
    }
    /** Keeps multiple GTINs/observations as candidates; never picks the smallest or first GTIN. */
    public List<RegisteredGtin> candidates(ProductRow product) {
        return candidates(identity(product));
    }
    /** Native proof lookup reuses the same immutable per-read Catalog index. */
    public List<RegisteredGtin> candidates(ProductSnapshot product) {
        return candidates(identity(product));
    }
    private List<RegisteredGtin> candidates(Optional<Identity> actual) {
        if(actual.isEmpty())return List.of();
        return models.getOrDefault(actual.get().model(),List.of()).stream()
                .filter(candidate->overlap(actual.get(),candidate.identity()))
                .map(Candidate::card).distinct().toList();
    }
    public static boolean sameIdentity(ProductRow product,RegisteredGtin card) {
        return same(identity(product),identity(card));
    }
    public static boolean sameIdentity(ProductSnapshot product,RegisteredGtin card) {
        return same(identity(product),identity(card));
    }
    private static boolean same(Optional<Identity> left,Optional<Identity> right) {
        return left.isPresent()&&right.isPresent()&&overlap(left.get(),right.get());
    }
    private static boolean overlap(Identity left,Identity right) {
        if(!left.model().equals(right.model()))return false;
        return switch(right.sizeSystem()) {
            case "CATALOG_RUSSIAN" -> !left.russianSize().isBlank()&&left.russianSize().equals(right.russianSize());
            case "CATALOG_MANUFACTURER" -> !left.primarySize().isBlank()&&left.primarySize().equals(right.primarySize());
            default -> !Collections.disjoint(left.sizes(),right.sizes());
        };
    }

    public static Optional<Identity> identity(ProductRow product) {
        if(product==null||product.key().marketplace()!=Marketplace.OZON)return Optional.empty();
        var sizes=sizeAliases(product);var articles=new ArrayList<String>();
        if(!blank(product.vendorArticle()))articles.add(product.vendorArticle());
        // A numeric Ozon SKU/product id is not a seller model. The offer id is used
        // only when no dedicated seller article is available.
        else if(!blank(product.key().variantId())&&!product.key().variantId().matches("[0-9]+"))articles.add(product.key().variantId());
        String primary=blank(product.manufacturerSize())?product.size():product.manufacturerSize();
        return resolve(new Facts(articles,List.of(text(product.title())),sizes,canonicalSize(primary),canonicalSize(product.russianSize()),"OZON"));
    }
    public static Optional<Identity> identity(ProductSnapshot product) {
        var facts=snapshotFacts(product);return facts.flatMap(OzonExactVariantIdentity::resolve);
    }
    public static Optional<Identity> identity(RegisteredGtin card) {
        if(card==null)return Optional.empty();
        var articles=new ArrayList<String>();if(!blank(card.article()))articles.add(card.article());
        articles.addAll(card.details().values("article"));
        for(var attribute:card.details().attributes())if(attribute.id().equals("13914")||articleName(attribute.name()))articles.addAll(attribute.values());
        var fallback=new ArrayList<String>();fallback.addAll(card.details().values("title"));fallback.addAll(card.details().values("description"));
        String size=blank(card.size())?card.details().single("size"):card.size();
        String kind=GtinVariantIdentity.russianSize(card)?"CATALOG_RUSSIAN":
                card.details().values("@size_type").stream().map(OzonExactVariantIdentity::fold)
                        .anyMatch(v->v.contains("международ")||Set.of("international","manufacturer","manufacturer size").contains(v))?"CATALOG_MANUFACTURER":"CATALOG_UNSPECIFIED";
        return resolve(new Facts(articles,fallback,sizes(size),kind.equals("CATALOG_RUSSIAN")?"":canonicalSize(size),
                kind.equals("CATALOG_RUSSIAN")?canonicalSize(size):"",kind));
    }
    private static Optional<Identity> resolve(Facts facts) {
        if(facts.sizes().isEmpty())return Optional.empty();
        var articles=facts.articles().stream().filter(value->!blank(value)).toList();
        if(articles.isEmpty())articles=facts.fallback().stream().flatMap(value->markedArticles(value).stream()).toList();
        var keys=new LinkedHashSet<String>();for(String article:articles){String key=articleKey(article,facts.sizes());if(!key.isBlank())keys.add(key);}
        return keys.size()==1?Optional.of(new Identity(keys.iterator().next(),new ArrayList<>(facts.sizes()),facts.sizeSystem(),facts.primary(),facts.russian())):Optional.empty();
    }

    /** Every alias comes from this very row; no static/model-wide manufacturer-to-Russian lookup. */
    public static Set<String> sizeAliases(ProductRow product) {
        if(product==null||product.key().marketplace()!=Marketplace.OZON)return Set.of();
        return sizes(blank(product.manufacturerSize())?product.size():product.manufacturerSize(),product.russianSize());
    }
    public static Set<String> sizeAliases(ProductSnapshot product) {
        return snapshotFacts(product).map(Facts::sizes).orElse(Set.of());
    }
    private static Set<String> sizes(String... values) {
        var result=new TreeSet<String>();for(String value:values){String size=canonicalSize(value);if(!size.isBlank())result.add(size);}
        return Collections.unmodifiableSet(result);
    }
    private static Optional<Facts> snapshotFacts(ProductSnapshot product) {
        if(product==null||product.key().marketplace()!=Marketplace.OZON)return Optional.empty();
        List<ProductVariant> selected;
        if(blank(product.key().variantId()))selected=product.variants().size()==1?product.variants():List.of();
        else selected=product.variants().stream().filter(v->product.key().variantId().equals(v.id())||product.key().variantId().equals(v.sellerArticle())).toList();
        if(selected.size()!=1)return Optional.empty();
        var variant=selected.getFirst();String primary=text(variant.size()),russian=text(variant.russianSize());
        // Product-level attributes are scoped to a native Ozon product. Do not
        // borrow them across multiple variants in a reconstructed snapshot.
        if(product.variants().size()==1){
            var manufacturer=attributeSizes(product.attributes(),false);var ru=attributeSizes(product.attributes(),true);
            if(manufacturer.size()>1||ru.size()>1)return Optional.empty();
            if(!ru.isEmpty()){
                String observed=ru.iterator().next();
                if(!blank(russian)&&!canonicalSize(russian).equals(observed))return Optional.empty();
                russian=observed;
            }
            if(!manufacturer.isEmpty()){
                String observed=manufacturer.iterator().next();
                if(!blank(primary)&&!canonicalSize(primary).equals(observed)&&!canonicalSize(primary).equals(canonicalSize(russian)))return Optional.empty();
                primary=observed;
            }
        }
        var articles=new ArrayList<String>();
        for(var attribute:product.attributes())if(articleName(attribute.name()))articles.addAll(attribute.values());
        if(articles.stream().noneMatch(value->!blank(value))&&!blank(product.vendorArticle()))articles.add(product.vendorArticle());
        if(articles.stream().noneMatch(value->!blank(value))&&!blank(variant.sellerArticle()))articles.add(variant.sellerArticle());
        if(articles.isEmpty()&&!blank(product.key().variantId())&&!product.key().variantId().matches("[0-9]+"))articles.add(product.key().variantId());
        var fallback=new ArrayList<String>();fallback.add(text(product.title()));fallback.add(text(product.description()));
        for(var attribute:product.attributes())if(noteName(attribute.name())||attribute.id().equals("4191"))fallback.addAll(attribute.values());
        return Optional.of(new Facts(articles,fallback,sizes(primary,russian),canonicalSize(primary),canonicalSize(russian),"OZON"));
    }
    private static Set<String> attributeSizes(List<ProductAttribute> attributes,boolean russian) {
        var found=new LinkedHashSet<String>();for(var attribute:attributes){
            String name=fold(attribute.name());
            boolean matches=russian?name.equals("российский размер")||name.equals("russian size"):
                    name.equals("размер производителя")||name.equals("manufacturer size")||name.equals("technical size")||name.equals("tech size");
            if(matches)for(String value:attribute.values()){String size=canonicalSize(value);if(!size.isBlank())found.add(size);}
        }return found;
    }
    private static boolean articleName(String value) {
        return Set.of("артикул","артикул товара","артикул производителя","артикул продавца","код продавца",
                "модель / артикул производителя","article","seller article","seller code","manufacturer article").contains(fold(value));
    }
    private static boolean noteName(String value) {
        String name=fold(value);return name.contains("примеч")||name.contains("комментар")||name.contains("описан")
                ||name.contains("note")||name.contains("remark")||name.contains("comment")||name.contains("description");
    }
    private static List<String> markedArticles(String value) {
        var result=new ArrayList<String>();var matcher=ARTICLE_MARKER.matcher(Normalizer.normalize(text(value),Normalizer.Form.NFKC));
        while(matcher.find()){
            String span=NEXT_FIELD.split(matcher.group(1),2)[0].strip();
            for(var pair:List.of(new String[]{"«","»"},new String[]{"\"","\""},new String[]{"'","'"}))
                if(span.length()>1&&span.startsWith(pair[0])&&span.endsWith(pair[1])){span=span.substring(1,span.length()-1).strip();break;}
            if(!span.isBlank())result.add(span);
        }return result;
    }
    private static String articleKey(String article,Set<String> knownSizes) {
        String value=fold(article);
        if(value.isBlank())return "";
        // Brackets must be paired; an invalid suffix remains part of the model.
        for(var pair:List.of(new char[]{'(',')'},new char[]{'[',']'}))if(value.charAt(value.length()-1)==pair[1]){
            int opening=value.lastIndexOf(pair[0]);
            if(opening>0&&knownSize(value.substring(opening+1,value.length()-1),knownSizes))return modelKey(value.substring(0,opening));
        }
        // Strip at most one typed, delimited, currently observed size. Never
        // remove model words, arbitrary prefixes, or concatenate distinct models.
        for(int i=1;i<value.length();i++)if(isSeparator(value.charAt(i))){
            String suffix=value.substring(i).replaceFirst("^[\\s_/-]+","");
            suffix=SIZE_MARKER.matcher(suffix).replaceFirst("");
            if(knownSize(suffix,knownSizes))return modelKey(value.substring(0,i));
        }
        return modelKey(value);
    }
    private static boolean isSeparator(char value){return Character.isWhitespace(value)||value=='_'||value=='/'||value=='-';}
    private static boolean knownSize(String suffix,Set<String> knownSizes) {
        String normalized=canonicalSize(suffix);
        return !normalized.isBlank()&&knownSizes.contains(normalized)
                &&fold(suffix).replaceAll("\\s+","").matches("[0-9]{1,3}(?:[-/][0-9]{1,3})?|[2-9]?x{0,8}[sml]|onesize|единый");
    }
    /** Full-model key. Punctuation and every non-whitespace prefix/suffix remain significant. */
    public static String modelKey(String value){return fold(value).replaceAll("\\s+","");}
    /** Typography aliases only: XXL and 2XL share a label; no numeric garment-size conversion. */
    public static String canonicalSize(String value) {
        String normalized=fold(value).replaceAll("\\s+","");
        if(normalized.matches("x{2,8}[ls]"))return (normalized.length()-1)+normalized.substring(normalized.length()-1).replace("l","xl").replace("s","xs");
        return normalized;
    }
    private static String fold(String value){return Normalizer.normalize(text(value),Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String text(String value){return Objects.toString(value,"");}
}
