package com.vncode.app.features.gtinsync;

import com.vncode.app.features.catalogsync.CatalogProductData;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import static org.junit.jupiter.api.Assertions.*;

class OzonExactVariantIdentityTest {
    private RegisteredGtin card(String gtin,String article,String size) {
        return new RegisteredGtin(gtin,article,"different color",size,true,true,"fixture",Instant.now(),
                new CatalogProductData(Map.of("@size_type",List.of(size.matches("[0-9]+")?"РОССИЯ":"МЕЖДУНАРОДНЫЙ")),List.of(),List.of()));
    }
    private ProductRow row(String article,String size,String russian) {
        return new ProductRow(new ProductKey(71,Marketplace.OZON,"901",article),"Other product title",article,"99001", 
                "Other category","Other brand","Other color",size,List.of(),"","","Other material","other tariff",
                DocumentState.MISSING,"Other gender",true,russian,size);
    }
    private ProductSnapshot snapshot(String article,String size,String russian) {
        var key=new ProductKey(71,Marketplace.OZON,"901",article);
        return new ProductSnapshot(key,"live:fixture","Other title","","Other brand",article,"Other category",
                List.of(),List.of(),List.of(new ProductVariant(article,article,"other",size,"",false,russian)),null,List.of(),Instant.now());
    }

    @Test void exactArticleAndCoPresentRussianSizeIgnoreUnrelatedProductDetails() {
        var c=card("00000000000017","Q47 NAU","52");
        assertEquals(List.of(c),new OzonExactVariantIdentity(List.of(c)).candidates(row("q47nau-2XL","XXL","52")));
        assertEquals(List.of(c),new OzonExactVariantIdentity(List.of(c)).candidates(snapshot("Q47NAU-XXL","2XL","52")));
        assertTrue(OzonExactVariantIdentity.sameIdentity(snapshot("Q47NAU-XXL","2XL","52"),c));
    }
    @ParameterizedTest @ValueSource(strings={"B3Q47NAU","D Q47NAU","Q47NAU DASH","Q47NAU-DASH","Q47NAUDASHOA","Q470NAU"})
    void prefixesAndSuffixWordsNeverBecomeTheSameFullModel(String article) {
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","2XL","52"),card("00000000000017",article,"52")));
    }
    @Test void knownSizeSuffixIsOnlyRemovedOnceAndMustBeProperlyDelimited() {
        var c=card("00000000000017","Q47NAU","2XL");
        assertTrue(OzonExactVariantIdentity.sameIdentity(row("Q47 NAU / XXL","2XL",""),c));
        for(String article:List.of("Q47NAU2XL","Q47NAU-XXL-52","Q47NAU(XXL]","Q47NAU[XXL)","Q47NAU-12XL"))
            assertFalse(OzonExactVariantIdentity.sameIdentity(row(article,"2XL","52"),c),article);
    }
    @Test void suffixMustAgreeWithAnObservedSizeRatherThanInventingIt() {
        var c=card("00000000000017","Q47NAU","L");
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","L","48"),c));
        assertTrue(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-L","L","48"),c));
    }
    @Test void russianNumericSizeHasNoGlobalManufacturerConversion() {
        var c=card("00000000000017","Q47NAU","52");
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","2XL",""),c));
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","2XL","54"),c));
        assertTrue(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","2XL","52"),c));
    }
    @Test void aliasesComeFromEachRowAndCannotLeakBetweenVariantsOrModels() {
        var c=card("00000000000017","Q47NAU","52");var matcher=new OzonExactVariantIdentity(List.of(c));
        assertEquals(List.of(c),matcher.candidates(row("Q47NAU-XXL","2XL","52")));
        assertTrue(matcher.candidates(row("Q47NAU-XXL","2XL","")).isEmpty());
        assertTrue(matcher.candidates(row("OTHER-XXL","2XL","52")).isEmpty());
    }
    @Test void russianAndManufacturerRolesCannotBeSwappedOrCollapsedIntoAnUnlabelledSet() {
        var russian=card("00000000000017","Q47NAU","52");
        var correct=row("Q47NAU","50","52");var swapped=row("Q47NAU","52","50");
        assertTrue(OzonExactVariantIdentity.sameIdentity(correct,russian));
        assertFalse(OzonExactVariantIdentity.sameIdentity(swapped,russian));
        assertNotEquals(OzonExactVariantIdentity.identity(correct),OzonExactVariantIdentity.identity(swapped));
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU","52",""),russian));
    }
    @Test void publicationTradeUnitAndDocumentsRemainCallerEligibilityRatherThanIdentity() {
        var c=new RegisteredGtin("00000000000017","Q47NAU","", "52",false,false,"fixture",Instant.now());
        assertEquals(List.of(c),new OzonExactVariantIdentity(List.of(c)).candidates(row("Q47NAU-2XL","2XL","52")));
    }
    @Test void multipleGtinsRemainCandidatesAndNoSmallestCodeIsChosen() {
        var a=card("00000000000024","Q47NAU","52");var b=card("00000000000017","q47 nau","52");
        assertEquals(List.of(a,b),new OzonExactVariantIdentity(List.of(a,b)).candidates(row("Q47NAU-2XL","2XL","52")));
    }
    @Test void caseAndCompatibilityWidthNormalizeWithoutDroppingOtherCharacters() {
        assertEquals("q47nau",OzonExactVariantIdentity.modelKey(" Ｑ４７\u00a0ＮＡＵ "));
        assertEquals("2xl",OzonExactVariantIdentity.canonicalSize(" ＸＸＬ "));
        assertNotEquals(OzonExactVariantIdentity.modelKey("Q47NAU DASH"),OzonExactVariantIdentity.modelKey("Q47NAU"));
    }
    @Test void dedicatedArticleCannotBeOverriddenByAnArticleMarkerInTitle() {
        var p=row("B3Q47NAU-2XL","2XL","52");
        var misleading=new ProductRow(p.key(),"арт. Q47NAU",p.vendorArticle(),p.marketArticle(),p.category(),p.brand(),p.color(),p.size(),p.barcodes(),p.verifiedGtin(),p.thumbnailUrl(),p.material(),p.tnved(),p.documentState(),p.gender(),true,p.russianSize(),p.manufacturerSize());
        assertFalse(OzonExactVariantIdentity.sameIdentity(misleading,card("00000000000017","Q47NAU","52")));
    }
    @Test void fallbackRequiresExplicitMarkerAndPreservesTheWholeModelSpan() {
        var c=card("00000000000017","Q47NAU","52");var empty=row("","2XL","52");
        assertFalse(OzonExactVariantIdentity.sameIdentity(withTitle(empty,"Suit Q47NAU"),c));
        assertFalse(OzonExactVariantIdentity.sameIdentity(withTitle(empty,"articleQ47NAU"),c));
        assertFalse(OzonExactVariantIdentity.sameIdentity(withTitle(empty,"артQ47NAU"),c));
        assertFalse(OzonExactVariantIdentity.sameIdentity(withTitle(empty,"арт. Q47NAU DASH; размер:52"),c));
        assertTrue(OzonExactVariantIdentity.sameIdentity(withTitle(empty,"арт. «Q47 NAU»; размер:52"),c));
    }
    @Test void catalogDedicatedArticleCannotBeOverriddenByItsTitle() {
        var c=new RegisteredGtin("00000000000017","B3Q47NAU","","52",true,true,"fixture",Instant.now(),
                new CatalogProductData(Map.of("title",List.of("арт. Q47NAU")),List.of(),List.of()));
        assertFalse(OzonExactVariantIdentity.sameIdentity(row("Q47NAU-2XL","2XL","52"),c));
    }
    @Test void snapshotNeverUsesRussianSizeFromAnotherVariant() {
        var s=snapshot("Q47NAU-2XL","2XL","");
        var other=new ProductVariant("OTHER-L","OTHER-L","", "L","",false,"52");
        var mixed=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),s.attributes(),List.of(s.variants().getFirst(),other),s.dimensions(),s.documents(),s.checkedAt());
        assertFalse(OzonExactVariantIdentity.sameIdentity(mixed,card("00000000000017","Q47NAU","52")));
    }
    @Test void explicitArticleAttributeHasTheSamePriorityAsTheCachedSellerArticle() {
        var s=snapshot("ARBITRARY-OFFER-2XL","2XL","52");
        var fresh=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),
                List.of(new ProductAttribute("9","Артикул продавца",List.of("Q47NAU"),"")),s.variants(),s.dimensions(),s.documents(),s.checkedAt());
        var cached=row("Q47NAU","2XL","52");
        assertEquals(OzonExactVariantIdentity.identity(cached),OzonExactVariantIdentity.identity(fresh));
        assertTrue(OzonExactVariantIdentity.sameIdentity(fresh,card("00000000000017","Q47NAU","52")));
    }
    @Test void explicitRussianAttributeIsRecoveredBeforeInterpretingAnOlderNumericDisplaySize() {
        var s=snapshot("Q47NAU-XXL","52","");
        var typed=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),
                List.of(new ProductAttribute("1","Размер производителя",List.of("2XL"),""),
                        new ProductAttribute("2","Российский размер",List.of("52"),"")),s.variants(),s.dimensions(),s.documents(),s.checkedAt());
        assertTrue(OzonExactVariantIdentity.sameIdentity(typed,card("00000000000017","Q47NAU","52")));
        assertTrue(OzonExactVariantIdentity.sameIdentity(typed,card("00000000000017","Q47NAU","XXL")));
    }
    @Test void explicitManufacturerAttributesOverrideGenericDisplaySizeAndConflictsAreUnknown() {
        var s=snapshot("Q47NAU-XXL","52","52");
        var a=new ProductAttribute("1","Размер производителя",List.of("2XL"),"");
        var typed=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),List.of(a),s.variants(),s.dimensions(),s.documents(),s.checkedAt());
        assertTrue(OzonExactVariantIdentity.sameIdentity(typed,card("00000000000017","Q47NAU","XXL")));
        var conflict=new ProductSnapshot(s.key(),s.revision(),s.title(),s.description(),s.brand(),s.vendorArticle(),s.categoryId(),s.media(),List.of(a,new ProductAttribute("2","Manufacturer size",List.of("L"),"")),s.variants(),s.dimensions(),s.documents(),s.checkedAt());
        assertTrue(OzonExactVariantIdentity.identity(conflict).isEmpty());
        var sourceConflict=snapshot("Q47NAU","L","52");
        var wrong=new ProductSnapshot(sourceConflict.key(),sourceConflict.revision(),sourceConflict.title(),sourceConflict.description(),sourceConflict.brand(),sourceConflict.vendorArticle(),sourceConflict.categoryId(),sourceConflict.media(),List.of(a),sourceConflict.variants(),sourceConflict.dimensions(),sourceConflict.documents(),sourceConflict.checkedAt());
        assertTrue(OzonExactVariantIdentity.identity(wrong).isEmpty());
    }
    @Test void wbRowsAndSnapshotsAreOutsideThisNewOzonMatcher() {
        var p=row("Q47NAU-2XL","2XL","52");
        var wb=new ProductRow(new ProductKey(71,Marketplace.WILDBERRIES,"901","11"),p.title(),p.vendorArticle(),p.marketArticle(),p.category(),p.brand(),p.color(),p.size(),p.barcodes(),p.verifiedGtin(),p.thumbnailUrl(),p.material(),p.tnved(),p.documentState(),p.gender(),true,p.russianSize(),p.manufacturerSize());
        assertTrue(new OzonExactVariantIdentity(List.of(card("00000000000017","Q47NAU","52"))).candidates(wb).isEmpty());
    }
    private ProductRow withTitle(ProductRow p,String title) {
        return new ProductRow(p.key(),title,p.vendorArticle(),p.marketArticle(),p.category(),p.brand(),p.color(),p.size(),p.barcodes(),p.verifiedGtin(),p.thumbnailUrl(),p.material(),p.tnved(),p.documentState(),p.gender(),true,p.russianSize(),p.manufacturerSize());
    }
}
