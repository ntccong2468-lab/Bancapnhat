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
        GtinWriteTestFixture.saveSettings(shop,GtinWriteTestFixture.CERTIFICATE,com.vncode.app.integration.znack.ZnackModels.PRODUCTION_TRUE_API);
        var groups=new KizStockCategoryRepository();var category=groups.create(shop,"Synthetic category");
        groups.assignProducts(shop,category.id(),List.of(new ProductKey(shop.getId(),shop.getMarketplace(),nativeProduct,"")));
        var lines=new ArrayList<String>();for(int n=1;n<=count;n++)lines.add("01"+GTIN+"21"+String.format(Locale.ROOT,"%013d",n)+"\u001d91ABCD\u001d92RklYVFVSRS1OT1QtQS1SRUFMLUNSWVBUTy1TSUdOQVRVUkU=");
        var file=root.resolve("synthetic-"+UUID.randomUUID()+".txt");Files.write(file,lines);var warehouse=new KizWarehouseRepository();var scope=RegisteredGtinProof.current(shop);
        var batch=warehouse.commit(shop,scope,KizImportReader.read(file));groups.classifyBatch(shop,category.id(),batch.batch());
        long after=0;while(true){var pending=warehouse.pending(shop,scope.source(),1000,after);if(pending.isEmpty())break;
            var verdicts=new HashMap<String,KizWarehouseRepository.Verdict>();pending.forEach(p->verdicts.put(p.cis(),new KizWarehouseRepository.Verdict(true,"KIZ_READY")));warehouse.verified(shop,scope.source(),pending,verdicts);after=pending.getLast().id();}
        return category.id();
    }
}
