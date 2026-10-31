package com.vncode.app.integration.znack.registration;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.RegisteredGtinProof;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fresh, exact old-card evidence for one seller-reviewed replacement allocation. */
final class CatalogReplacementEvidence {
    private record OldCard(String gtin,long goodId,List<String> feeds,boolean rejected){}
    private final Shop shop;private final ProductKey key;private final ZnackRegistrationScope scope;
    private final RegistrationIntent intent;private final OldCard old;private final long observedAt;
    private CatalogReplacementEvidence(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,OldCard old,long observedAt){
        this.shop=shop;this.key=key;this.scope=scope;this.intent=intent;this.old=old;this.observedAt=observedAt;
    }
    static CatalogReplacementEvidence verify(Shop shop,ProductKey key,ZnackRegistrationScope scope,RegistrationIntent intent,ZnackCardRegistrationWorkflow.CatalogSession session)throws Exception{
        if(session==null||scope==null||intent==null||!scope.equals(session.scope()))throw denied();
        scope.assertCurrent(shop);ListingIdentity.require(shop,key);
        OldCard old;try(var c=Database.getConnection()){old=read(c,shop,key,intent);}
        long observed=session.catalog().verifiedTerminalFeeds(session.token(),old.feeds(),old.gtin(),old.goodId(),scope.inn(),()->{scope.assertCurrent(shop);ListingIdentity.require(shop,key);});
        // An ERROR row may represent a timeout or a nonterminal feed. A new
        // GTIN is permissible only after Catalog rejects this exact card.
        if(old.rejected())for(String feed:old.feeds())
            if(!session.catalog().verifiedRejectedFeed(session.token(),feed,old.gtin(),old.goodId(),scope.inn(),()->{scope.assertCurrent(shop);ListingIdentity.require(shop,key);}))throw denied();
        if(observed==0)throw denied();scope.assertCurrent(shop);
        return new CatalogReplacementEvidence(shop,key,scope,intent,old,observed);
    }
    void assertCurrent(Connection c,Shop current,ProductKey currentKey,RegistrationIntent currentIntent)throws SQLException{
        if(!key.equals(currentKey)||!intent.equals(currentIntent)||!ListingIdentity.fingerprint(shop).equals(ListingIdentity.fingerprint(current))
                ||!scope.equals(ZnackRegistrationScope.fromSettings(new com.vncode.app.integration.znack.ZnackRepository(new com.vncode.app.integration.znack.ZnackModels.ShopContext(current.getId(),current.getName())).getSettings()))
                ||System.nanoTime()-observedAt<0||System.nanoTime()-observedAt>TimeUnit.MINUTES.toNanos(1)||!old.equals(read(c,current,currentKey,currentIntent)))throw denied();
    }
    private static OldCard read(Connection c,Shop shop,ProductKey key,RegistrationIntent intent)throws SQLException{
        RegistrationSourceConsentGuard.requirePosition(c,intent,key);RegistrationSourceConsent.ensureReplacementColumns(c);
        String oldHash;long id;
        try(var p=c.prepareStatement("SELECT owner,scope,replaced_gtin_hash,replaced_good_id,phase FROM znack_source_consents WHERE run_id=? AND position=? AND shop_id=? AND marketplace=? AND product_id=? AND variant_id=? AND action='REPLACE'")){
            p.setString(1,intent.runId());p.setInt(2,intent.position());p.setInt(3,key.shopId());p.setString(4,key.marketplace().name());p.setString(5,key.cardId());p.setString(6,key.variantId());
            try(var r=p.executeQuery()){
                if(!r.next()||!ListingIdentity.fingerprint(c,shop.getId(),shop.getMarketplace()).equals(r.getString(1))||!RegisteredGtinProof.source(shop).equals(r.getString(2))||!"ACTIVE".equals(r.getString(5)))throw denied();
                oldHash=r.getString(3);id=r.getLong(4);if(oldHash==null||oldHash.isBlank()||r.wasNull()||id<=0||r.next())throw denied();
            }
        }
        // Only local archived snapshots of the exact seller-selected card qualify;
        // Catalog verification below independently proves both feed and ownership.
        String sql=key.marketplace()==Marketplace.WILDBERRIES
                ?"SELECT active_gtin,active_good_id,active_feed_id,'PUBLISHED' FROM znack_card_registration_replacements WHERE shop_id=? AND chrt_id=? UNION ALL SELECT gtin,good_id,feed_id,status FROM znack_card_registration_history WHERE shop_id=? AND chrt_id=? AND nm_id=? UNION ALL SELECT gtin,good_id,feed_id,status FROM znack_card_registrations WHERE shop_id=? AND chrt_id=? AND nm_id=?"
                :"SELECT gtin,good_id,feed_id,status FROM ozon_card_registration_history WHERE shop_id=? AND product_id=? AND offer_id=? UNION ALL SELECT gtin,good_id,feed_id,status FROM ozon_card_registrations WHERE shop_id=? AND product_id=? AND offer_id=?";
        String gtin="";var feeds=new TreeSet<String>();boolean rejected=false;
        try(var p=c.prepareStatement(sql)){
            p.setInt(1,key.shopId());if(key.marketplace()==Marketplace.WILDBERRIES){p.setString(2,key.variantId());p.setInt(3,key.shopId());p.setString(4,key.variantId());p.setString(5,key.cardId());p.setInt(6,key.shopId());p.setString(7,key.variantId());p.setString(8,key.cardId());}
            else {p.setString(2,key.cardId());p.setString(3,key.variantId());p.setInt(4,key.shopId());p.setString(5,key.cardId());p.setString(6,key.variantId());}
            try(var r=p.executeQuery()){while(r.next()){
                String candidate=Objects.toString(r.getString(1),"");long candidateId=r.getLong(2);boolean idNull=r.wasNull();String feed=Objects.toString(r.getString(3),"");
                if(idNull||candidateId!=id||!ListingIdentity.hash(candidate).equals(oldHash))continue;
                if(!gtin.isBlank()&&!gtin.equals(candidate))throw denied();gtin=candidate;if(!feed.isBlank())feeds.add(feed);
                rejected|="ERROR".equals(r.getString(4))||(key.marketplace()==Marketplace.OZON&&Objects.toString(r.getString(4),"").isBlank());
            }}
        }
        if(gtin.isBlank()||feeds.isEmpty())throw denied();return new OldCard(gtin,id,List.copyOf(feeds),rejected);
    }
    private static RegistrationValidationException denied(){return new RegistrationValidationException("CATALOG_REPLACEMENT_READBACK_REQUIRED");}
}
