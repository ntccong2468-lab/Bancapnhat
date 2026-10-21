package com.vncode.app.integration.znack.registration;

import java.util.List;
import java.util.Objects;

/** A read-only immutable set of actual remote documents shown before seller approval. */
public record RegistrationSigningReview(String owner,ZnackRegistrationScope scope,String signer,List<Card> cards) {
    public RegistrationSigningReview {owner=Objects.requireNonNull(owner);scope=Objects.requireNonNull(scope);signer=Objects.requireNonNullElse(signer,"");cards=List.copyOf(cards);}
    public record Card(String productId,String variantId,String revision,String gtin,Long goodId,String catalogState,
                       ZnackNationalCatalogService.SigningDocument document) {
        public Card {Objects.requireNonNull(document);}
    }
}
