package com.vncode.app.features.kiz;

import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;

/** Marketplace-red codes are a permanent exclusion, including duplicate file occurrences. */
public final class KizRejectedStock {
    private KizRejectedStock(){}
    public static void initialize(Connection c)throws SQLException{
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS kiz_rejected_stock(shop_id INTEGER NOT NULL REFERENCES shops(id) ON DELETE CASCADE,identity TEXT NOT NULL,code_hash TEXT NOT NULL,owner TEXT NOT NULL,marketplace TEXT NOT NULL,reason TEXT NOT NULL,checked_at TEXT NOT NULL,PRIMARY KEY(shop_id,identity))");
        }
    }
    public static String identity(String code){
        String value=Objects.toString(KizService.scannerSafeCode(code),"").strip();
        try{return KizMark.parse(value).cis();}catch(IllegalArgumentException unavailable){int end=value.indexOf(29);return end<0?value:value.substring(0,end);}
    }
    public static boolean contains(Connection c,int shop,String code)throws SQLException{
        try(var p=c.prepareStatement("SELECT 1 FROM kiz_rejected_stock WHERE shop_id=? AND identity=?")){
            p.setInt(1,shop);p.setString(2,identity(code));try(var r=p.executeQuery()){return r.next();}
        }
    }
    /** Caller owns a short write transaction; no remote call and no return to AVAILABLE. */
    public static void discard(Connection c,Shop shop,Kn3Mark mark)throws SQLException{
        if(mark.state()!=Kn3Mark.State.REJECTED||mark.code().isBlank())return;
        String identity=identity(mark.code()),now=Instant.now().toString();
        try(var p=c.prepareStatement("INSERT INTO kiz_rejected_stock VALUES(?,?,?,?,?,?,?) ON CONFLICT(shop_id,identity) DO UPDATE SET reason=excluded.reason,checked_at=excluded.checked_at")){
            p.setInt(1,shop.getId());p.setString(2,identity);p.setString(3,ListingIdentity.hash(identity));p.setString(4,ListingIdentity.fingerprint(shop));p.setString(5,shop.getMarketplace().name());p.setString(6,mark.reason());p.setString(7,now);p.executeUpdate();
        }
        try(var p=c.prepareStatement("UPDATE kiz_codes SET status='CONSUMED',legal_status='REJECTED',consumed_at=?,updated_at=?,reservation_token=NULL,reserved_at=NULL,reservation_recoverable=NULL WHERE shop_id=? AND (raw_code=? OR display_code=? OR substr(raw_code,1,instr(raw_code,char(29))-1)=?)")){
            p.setString(1,now);p.setString(2,now);p.setInt(3,shop.getId());p.setString(4,identity);p.setString(5,identity);p.setString(6,identity);p.executeUpdate();
        }
    }
    public static String eligible(String alias){
        if(!alias.matches("[a-zA-Z_][a-zA-Z0-9_]*"))throw new IllegalArgumentException("KIZ_STOCK_ALIAS");
        return "NOT EXISTS(SELECT 1 FROM kiz_rejected_stock rejected WHERE rejected.shop_id="+alias+".shop_id AND (rejected.identity="+alias+".display_code OR rejected.identity=CASE WHEN instr("+alias+".raw_code,char(29))>0 THEN substr("+alias+".raw_code,1,instr("+alias+".raw_code,char(29))-1) ELSE "+alias+".raw_code END))";
    }
}
