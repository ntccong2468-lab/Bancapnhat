package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.registration.ZnackRegistrationScope;
import com.vncode.app.models.Shop;
import java.sql.SQLException;
import java.util.*;

/** A native WB registration is a hint bound to the same WB variant and original Catalog scope. */
public final class WbOzonSourceEvidence {
    private WbOzonSourceEvidence(){}
    public static Map<ProductKey,WbOzonGtinMatcher.NativeAssociation> associations(Shop wb) {
        if(wb==null||wb.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("WB_OZON_SHOP_MISMATCH");
        ListingIdentity.require(wb);
        var proof=RegisteredGtinProof.current(wb);
        var scope=new ZnackRegistrationScope(proof.inn(),proof.environment(),proof.fingerprint());
        String owner=ListingIdentity.fingerprint(wb);
        var result=new HashMap<ProductKey,WbOzonGtinMatcher.NativeAssociation>();
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT r.nm_id,r.chrt_id,r.gtin,r.good_id,s.participant_inn,s.environment,s.fingerprint,s.owner_hash
            FROM znack_card_registrations r JOIN znack_card_registration_scopes s
              ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id
            WHERE r.shop_id=? AND r.status='PUBLISHED' AND r.good_id>0 AND length(trim(COALESCE(r.gtin,'')))>0
            """)) {
            p.setInt(1,wb.getId());try(var rows=p.executeQuery()){while(rows.next()){
                if(!scope.inn().equals(rows.getString(5))||!scope.environment().equals(rows.getString(6))
                        ||!scope.fingerprint().equals(rows.getString(7))||!owner.equals(rows.getString(8)))continue;
                String gtin=rows.getString(3);if(!RegisteredGtinValidator.isValid(gtin))continue;
                var key=new ProductKey(wb.getId(),Marketplace.WILDBERRIES,Long.toString(rows.getLong(1)),Long.toString(rows.getLong(2)));
                result.put(key,new WbOzonGtinMatcher.NativeAssociation(key,gtin,rows.getLong(4),owner,proof.source(),"WB_PUBLISHED_JOURNAL"));
            }}
            ListingIdentity.require(wb);
            if(!proof.equals(RegisteredGtinProof.current(wb)))throw new SecurityException("WB_OZON_SOURCE_CHANGED");
            return Map.copyOf(result);
        } catch(SQLException error){throw new IllegalStateException("WB_REGISTRATION_READ_FAILED",error);}
    }
    public static boolean matches(Shop wb,ProductKey key,String gtin,long goodId) {
        if(wb==null||key==null||wb.getMarketplace()!=Marketplace.WILDBERRIES||key.marketplace()!=Marketplace.WILDBERRIES||key.shopId()!=wb.getId()
                ||!key.cardId().matches("[1-9][0-9]{0,18}")||!key.variantId().matches("[1-9][0-9]{0,18}")||goodId<=0)return false;
        ListingIdentity.require(wb);var proof=RegisteredGtinProof.current(wb);String owner=ListingIdentity.fingerprint(wb);
        try(var c=Database.getConnection();var p=c.prepareStatement("""
            SELECT r.gtin,r.good_id,s.participant_inn,s.environment,s.fingerprint,s.owner_hash
            FROM znack_card_registrations r JOIN znack_card_registration_scopes s
              ON s.shop_id=r.shop_id AND s.chrt_id=r.chrt_id
            WHERE r.shop_id=? AND r.nm_id=? AND r.chrt_id=? AND r.status='PUBLISHED'
            """)){
            p.setInt(1,wb.getId());p.setString(2,key.cardId());p.setString(3,key.variantId());
            try(var row=p.executeQuery()){
                if(!row.next())return false;
                boolean same=row.getLong(2)==goodId&&RegisteredGtinProof.sameCode(row.getString(1),gtin)
                        &&proof.inn().equals(row.getString(3))&&proof.environment().equals(row.getString(4))
                        &&proof.fingerprint().equals(row.getString(5))&&owner.equals(row.getString(6));
                ListingIdentity.require(wb);if(!proof.equals(RegisteredGtinProof.current(wb)))throw new SecurityException("WB_OZON_SOURCE_CHANGED");
                return same;
            }
        }catch(SQLException error){throw new IllegalStateException("WB_REGISTRATION_READ_FAILED",error);}
    }
}
