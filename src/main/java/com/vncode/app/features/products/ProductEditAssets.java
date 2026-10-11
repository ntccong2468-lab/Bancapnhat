package com.vncode.app.features.products;

import com.vncode.app.features.listingcopy.*;
import com.vncode.app.integration.listing.ListingIdentity;
import java.sql.*;
import java.util.*;

/** Immutable file payloads live once per job; card journals contain content references. */
final class ProductEditAssets {
    private static final String PREFIX="vncode-docs-sha256:";
    private final Connection connection;
    private final String job;
    private final IdentityHashMap<String,String> references=new IdentityHashMap<>();
    private final Map<String,String> loaded=new HashMap<>();
    ProductEditAssets(Connection connection,String job){this.connection=connection;this.job=job;}
    private String store(String json)throws SQLException{
        if(json==null||json.length()<4096)return json;
        String cached=references.get(json);if(cached!=null)return cached;
        String hash=ListingIdentity.hash(json),reference=PREFIX+hash;
        boolean exists;
        try(var q=connection.prepareStatement("SELECT 1 FROM product_edit_assets WHERE job_id=? AND asset_hash=?")){q.setString(1,job);q.setString(2,hash);try(var r=q.executeQuery()){exists=r.next();}}
        if(!exists)try(var q=connection.prepareStatement("INSERT INTO product_edit_assets(job_id,asset_hash,payload) VALUES(?,?,?)")){q.setString(1,job);q.setString(2,hash);q.setString(3,json);q.executeUpdate();}
        references.put(json,reference);return reference;
    }
    private String load(String value)throws SQLException{
        if(value==null||!value.startsWith(PREFIX))return value;
        String hash=value.substring(PREFIX.length());if(!hash.matches("[0-9a-f]{64}"))throw new IllegalStateException("Invalid product document reference");
        String cached=loaded.get(hash);if(cached!=null)return cached;
        try(var q=connection.prepareStatement("SELECT payload FROM product_edit_assets WHERE job_id=? AND asset_hash=?")){q.setString(1,job);q.setString(2,hash);try(var r=q.executeQuery()){
            if(!r.next())throw new IllegalStateException("Product document asset is missing");
            String json=r.getString(1);if((long)json.length()*2>64L*1024*1024||!hash.equals(ListingIdentity.hash(json)))throw new IllegalStateException("Product document asset is invalid");
            loaded.put(hash,json);return json;
        }}
    }
    ProductBulkEditService.EditItem store(ProductBulkEditService.EditItem item)throws SQLException{return convert(item,true);}
    ProductBulkEditService.EditItem load(ProductBulkEditService.EditItem item)throws SQLException{return convert(item,false);}
    private ProductBulkEditService.EditItem convert(ProductBulkEditService.EditItem item,boolean storing)throws SQLException{
        var edit=item.preview();var p=edit.preview();String documents=storing?store(p.documentsJson()):load(p.documentsJson());
        var preview=withDocuments(p,documents);var r=item.submission();
        if(r!=null)r=new RemoteSubmission(r.shopId(),r.marketplace(),r.owner(),r.requestHash(),r.taskId(),r.articles(),r.requestJson(),r.mediaJson(),storing?store(r.documentsJson()):load(r.documentsJson()),r.phase(),r.identities());
        return new ProductBulkEditService.EditItem(item.key(),item.changes(),item.documents(),new ListingTargetAdapter.ProductEditPreview(edit.key(),edit.sourceRevision(),preview),r,item.state(),item.issues(),item.before());
    }
    static CopyPreview withDocuments(CopyPreview p,String documents){return new CopyPreview(p.shopId(),p.marketplace(),p.owner(),p.sourceKey(),p.sourceRevision(),p.requestHash(),p.requestJson(),p.mediaJson(),documents,p.diff(),p.issues(),p.articles(),p.options(),p.operation());}
    ProductBulkEditService.ProductEditPreview load(ProductBulkEditService.ProductEditPreview p)throws SQLException{
        var items=new ArrayList<ProductBulkEditService.EditItem>();for(var item:p.items())items.add(load(item));
        return new ProductBulkEditService.ProductEditPreview(p.id(),p.shopId(),p.owner(),p.keys(),p.edit(),items);
    }
}
