package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.ListingIdentity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationFieldProvenanceCountryTest extends RegistrationDynamicPreflightTest {
    @Test void countryConversionKeepsDictionaryEvidenceForExactlyThisVariantAndSchema()throws Exception{
        api.announcedCountry=true;var original=snapshot("1");var attributes=new ArrayList<>(original.attributes());
        attributes.add(new ProductAttribute("country","Страна производства",List.of("Китай"),""));
        var source=new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),attributes,original.variants(),original.dimensions(),original.documents(),original.checkedAt(),original.metadata());
        var review=service.read(shop,List.of(source),Map.of(source.key(),Map.of(999L,new RegistrationReviewedValues.Value("A",""))));
        var plan=review.plans().getFirst();assertEquals("CN",plan.draft().attributes().get(2630L));
        var evidence=RegistrationFieldProvenance.read(shop,plan.snapshot(),plan.draft(),plan.schema());
        var country=evidence.stream().filter(e->e.field().equals("product.origin")).findFirst().orElseThrow();
        assertEquals(RegistrationFieldProvenance.Source.MARKETPLACE_MAPPED_UNVERIFIED,country.source());
        assertEquals(ListingIdentity.hash("Китай"),country.sourceValueHash());assertEquals(ListingIdentity.hash("CN"),country.valueHash());
        assertTrue(country.dictionaryHash().matches("[a-f0-9]{64}"));assertNotNull(country.dictionaryObservedAt());assertEquals(0,api.writes);
    }
    @Test void preflightReportsSizeConflictBeforeAnyCatalogSubmission()throws Exception{
        var original=snapshot("1");var metadata=new HashMap<>(original.metadata());metadata.put("source.size.conflict","true");
        var source=new ProductSnapshot(original.key(),original.revision(),original.title(),original.description(),original.brand(),original.vendorArticle(),original.categoryId(),original.media(),original.attributes(),original.variants(),original.dimensions(),original.documents(),original.checkedAt(),metadata);
        var review=service.read(shop,List.of(source),Map.of());assertTrue(review.plans().isEmpty());assertEquals(1,review.blocked().size());assertEquals("OZON_SIZE_CONFLICT",review.blocked().getFirst().detail());assertEquals(0,api.writes);
    }
}
