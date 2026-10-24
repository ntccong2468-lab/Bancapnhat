package com.vncode.app.integration.ozon;

import com.vncode.app.config.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.*;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Successful final PDF publication, independent of whether an order needs KIZ. */
public final class OzonPrintReceiptRepository {
    public static void migrate(Connection c)throws SQLException{
        try(var statement=c.createStatement()){
            statement.execute("""
                INSERT OR IGNORE INTO ozon_fbs_print_receipts(shop_id,posting_number,owner,printed_at)
                SELECT r.shop_id,i.product_id,r.owner,i.updated_at FROM sync_runs r
                JOIN sync_run_items i ON i.run_id=r.id
                JOIN ozon_postings p ON p.shop_id=r.shop_id AND p.posting_number=i.product_id
                WHERE r.marketplace='OZON' AND r.kind='OZON_FBS_PRINT' AND i.state='SUCCEEDED'
                  AND i.step='FBS_PDF_CREATED' AND length(r.owner)=64
                  AND json_valid(i.evidence_json) AND json_extract(i.evidence_json,'$.pdfBatchComplete')=1
                """);
        }
    }
    public void record(Shop shop,List<String> postings){
        MarketplaceGuard.requireOzon(shop);var numbers=postings.stream().map(p->OzonApiClient.requireExternalId(p,"posting number")).distinct().toList();
        if(numbers.isEmpty()||numbers.size()>10000)throw new IllegalArgumentException("Invalid printed Ozon postings");
        try{SqliteWriteTransaction.run(c->{
            String owner=ListingIdentity.fingerprint(shop);
            if(!owner.equals(ListingIdentity.fingerprint(c,shop.getId(),Marketplace.OZON)))throw new SecurityException("SHOP_CREDENTIALS_CHANGED");
            try(var insert=c.prepareStatement("""
                INSERT INTO ozon_fbs_print_receipts(shop_id,posting_number,owner,printed_at)
                SELECT shop_id,posting_number,?,? FROM ozon_postings WHERE shop_id=? AND posting_number=?
                ON CONFLICT(shop_id,posting_number,owner) DO UPDATE SET printed_at=excluded.printed_at
                """)){
                String now=Instant.now().toString();
                for(String number:numbers){insert.setString(1,owner);insert.setString(2,now);insert.setInt(3,shop.getId());insert.setString(4,number);if(insert.executeUpdate()!=1)throw new IllegalStateException("OZON_POSTING_NOT_FOUND");}
            }
            return null;
        });}catch(SQLException e){throw new IllegalStateException("FBS_PRINT_RECEIPT_STORAGE",e);}
    }
}
