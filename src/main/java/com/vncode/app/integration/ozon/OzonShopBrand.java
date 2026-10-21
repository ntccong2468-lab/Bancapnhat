package com.vncode.app.integration.ozon;

import com.vncode.app.config.Database;
import com.vncode.app.config.SqliteWriteTransaction;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.util.Objects;

/** Catalog fallback only. It never overwrites a marketplace brand or writes a product. */
public final class OzonShopBrand {
    private static String key(Shop shop){ListingIdentity.require(shop);if(shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");return "ozon.catalog.brand."+shop.getId()+"."+ListingIdentity.hash(shop.getClientId());}
    public String load(Shop shop){
        String key=key(shop);
        try(var c=Database.getConnection();var p=c.prepareStatement("SELECT value FROM app_config WHERE key=?")){
            p.setString(1,key);try(var r=p.executeQuery()){return r.next()?Objects.toString(r.getString(1),""):"";}
        }catch(java.sql.SQLException error){throw new IllegalStateException("OZON_BRAND_STORAGE",error);}
    }
    public void save(Shop shop,String value){
        String key=key(shop),brand=Objects.toString(value,"").strip();
        if(brand.length()>200||brand.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("OZON_BRAND_INVALID");
        try{SqliteWriteTransaction.run(c->{ListingIdentity.require(shop);try(var p=c.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){p.setString(1,key);p.setString(2,brand);p.executeUpdate();}return null;});}
        catch(java.sql.SQLException error){throw new IllegalStateException("OZON_BRAND_STORAGE",error);}
    }
    public ProductSnapshot apply(Shop shop,ProductSnapshot product){
        ListingIdentity.require(shop,product.key());
        if(product.brand()!=null&&!product.brand().isBlank())return product;
        String brand=load(shop);if(brand.isBlank())return product;
        return new ProductSnapshot(product.key(),product.revision(),product.title(),product.description(),brand,product.vendorArticle(),product.categoryId(),product.media(),product.attributes(),product.variants(),product.dimensions(),product.documents(),product.checkedAt(),product.metadata());
    }
}
