package com.vncode.app.features.kiz;

import com.vncode.app.models.Shop;
import java.sql.*;
import java.util.*;

/** Identity-aware import review. Never exposes full marks or another account's details. */
final class KizImportInventory {
    enum Disposition { NEW, UNASSIGNED, SAME_CATEGORY, OTHER_CATEGORY, RESERVED, CONSUMED, ARCHIVED, OTHER_STOCK, CONFLICT }
    record Existing(long id,int shopId,KizMark mark,String status,String owner,int batchShop,
                    String category,String categoryOwner,int categoryShop) {}
    private final Map<String,Existing> marks=new HashMap<>();
    private final Set<String> ambiguous=new HashSet<>();

    KizImportInventory(Connection connection)throws SQLException {
        try(var query=connection.createStatement();var rows=query.executeQuery("""
            SELECT k.id,k.shop_id,k.raw_code,k.status,b.owner,b.shop_id,c.category_id,category.owner,category.shop_id
            FROM kiz_codes k LEFT JOIN kiz_import_entries i ON i.code_id=k.id
            LEFT JOIN kiz_import_batches b ON b.id=i.batch_id
            LEFT JOIN kiz_stock_classifications c ON c.code_id=k.id
            LEFT JOIN kiz_stock_categories category ON category.id=c.category_id
            """)) {
            while(rows.next()) {
                try {
                    var mark=KizMark.parse(rows.getString(3));
                    var existing=new Existing(rows.getLong(1),rows.getInt(2),mark,rows.getString(4),rows.getString(5),rows.getInt(6),rows.getString(7),rows.getString(8),rows.getInt(9));
                    if(marks.putIfAbsent(mark.cis(),existing)!=null)ambiguous.add(mark.cis());
                }catch(IllegalArgumentException legacy){/* Malformed legacy stock cannot be imported as a valid mark. */}
            }
        }
    }
    Existing existing(KizMark mark){return marks.get(mark.cis());}
    Disposition disposition(Shop shop,String owner,String category,KizMark mark) {
        var old=existing(mark);if(old==null)return Disposition.NEW;
        if(ambiguous.contains(mark.cis())||old.shopId()!=shop.getId()||old.batchShop()!=shop.getId()||!owner.equals(old.owner()))return Disposition.OTHER_STOCK;
        if(old.mark().fullDataMatrix()&&mark.fullDataMatrix()&&!old.mark().raw().equals(mark.raw()))return Disposition.CONFLICT;
        if(!"AVAILABLE".equals(old.status()))return switch(Objects.toString(old.status(),"")) {
            case "RESERVED" -> Disposition.RESERVED;
            case "CONSUMED" -> Disposition.CONSUMED;
            case "ARCHIVED" -> Disposition.ARCHIVED;
            default -> Disposition.OTHER_STOCK;
        };
        if(category==null)return Disposition.SAME_CATEGORY;
        if(old.category()==null)return Disposition.UNASSIGNED;
        if(!owner.equals(old.categoryOwner())||old.categoryShop()!=shop.getId())return Disposition.OTHER_STOCK;
        return category.equals(old.category())?Disposition.SAME_CATEGORY:Disposition.OTHER_CATEGORY;
    }
    KizWarehouseRepository.ImportReview review(Shop shop,String owner,String category,KizImportReader.Preview preview) {
        var counts=new EnumMap<Disposition,Integer>(Disposition.class);
        for(var mark:preview.marks())counts.merge(disposition(shop,owner,category,mark),1,Integer::sum);
        return new KizWarehouseRepository.ImportReview(counts.getOrDefault(Disposition.NEW,0),counts.getOrDefault(Disposition.UNASSIGNED,0),
            counts.getOrDefault(Disposition.SAME_CATEGORY,0),counts.getOrDefault(Disposition.OTHER_CATEGORY,0),counts.getOrDefault(Disposition.RESERVED,0),
            counts.getOrDefault(Disposition.CONSUMED,0),counts.getOrDefault(Disposition.ARCHIVED,0),counts.getOrDefault(Disposition.OTHER_STOCK,0),
            counts.getOrDefault(Disposition.CONFLICT,0),preview.duplicates());
    }
}
