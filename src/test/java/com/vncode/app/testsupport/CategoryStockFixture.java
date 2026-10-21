package com.vncode.app.testsupport;

import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.util.*;

/** Synthetic paid-mark shaped data, never a seller's real codes. */
public final class CategoryStockFixture {
    public static final String GTIN="04645588781154";
    public static String add(Shop shop,String nativeProduct,Path root,int count)throws Exception{
        var groups=new KizStockCategoryRepository();var category=groups.create(shop,"Synthetic category");
        groups.assignProducts(shop,category.id(),List.of(new ProductKey(shop.getId(),shop.getMarketplace(),nativeProduct,"")));
        var lines=new ArrayList<String>();for(int n=1;n<=count;n++)lines.add("01"+GTIN+"21"+String.format(Locale.ROOT,"%013d",n)+"\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=");
        var file=root.resolve("synthetic-"+UUID.randomUUID()+".txt");Files.write(file,lines);var warehouse=new KizWarehouseRepository();
        var batch=warehouse.commit(shop,KizImportReader.read(file));groups.classifyBatch(shop,category.id(),batch.batch());
        return category.id();
    }
}
