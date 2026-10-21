package com.vncode.app.features.gtinsync;

import com.vncode.app.config.Database;
import com.vncode.app.integration.marketplace.Marketplace;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.vncode.app.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinPreviewServiceTest {
    @TempDir Path temp;
    GtinSyncRepository repo;
    @BeforeEach void init()throws Exception {
        System.setProperty("vncode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Test','WILDBERRIES','test')");}
        repo=new GtinSyncRepository();
        var proof=new com.vncode.app.features.catalogsync.CatalogProductData(java.util.Map.of("@catalog_id",List.of("91"),"@catalog_status",List.of("published")),List.of(),List.of());
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now(),proof)));
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    static class Adapter implements GtinMarketplaceAdapter {
        ProductSnapshot product=GtinSyncRepositoryTest.preview(1,Marketplace.WILDBERRIES).before();
        Capability cap=new Capability(true,true,"");boolean conflict=false;
        int sends;Verification outcome=Verification.APPLIED;boolean loseResponse;
        public Capability capability(ProductKey key){return cap;}
        public ProductSnapshot read(ProductKey key){if(!product.key().equals(key))throw new IllegalArgumentException("wrong_product");return product;}
        public void validateIdentity(ProductSnapshot target,RegisteredGtin source){if(!target.key().equals(product.key()))throw new IllegalArgumentException("product_mismatch");}
        public boolean conflicts(ProductKey key,String gtin){return conflict;}
        public Submission submit(Preview preview)throws IOException{ sends++;if(loseResponse)throw new IOException("lost");return new Submission("task"); }
        public Verification verify(Preview preview,Submission submission){return outcome;}
    }
    @Test void validatesReplacementAndPreservesUnrelatedBarcode()throws Exception {
        var adapter=new Adapter();var service=new GtinPreviewService(repo,k->adapter);
        var p=service.create(adapter.product.key(),Operation.REPLACE,"old","00000000000017");
        assertEquals(List.of("old"),p.before().barcodes());assertEquals("old",p.oldGtin());
        assertThrows(IllegalArgumentException.class,()->service.create(adapter.product.key(),Operation.REPLACE,"missing","00000000000017"));
        adapter.conflict=true;
        assertThrows(IllegalArgumentException.class,()->service.create(adapter.product.key(),Operation.ADD,"","00000000000017"));
    }
    @Test void blocksUnsupportedAndUnregisteredOperations() {
        var adapter=new Adapter();var service=new GtinPreviewService(repo,k->adapter);
        adapter.cap=new Capability(true,false,"unsupported");
        assertThrows(IllegalArgumentException.class,()->service.create(adapter.product.key(),Operation.REPLACE,"old","00000000000017"));
        assertThrows(IllegalArgumentException.class,()->service.create(adapter.product.key(),Operation.ADD,"","00000000000024"));
    }
    @Test void blocksStaleCatalogAndExistingNewCode() {
        var adapter=new Adapter();var service=new GtinPreviewService(repo,k->adapter);
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now().minusSeconds(172800))));
        assertThrows(IllegalArgumentException.class,()->service.create(adapter.product.key(),Operation.ADD,"","00000000000017"));
    }
    @Test void canonicalEquivalentExistingBarcodeCannotBeAddedAgain(){
        var adapter=new Adapter();var key=adapter.product.key();adapter.product=new ProductSnapshot(key,adapter.product.article(),adapter.product.color(),adapter.product.size(),List.of("00000017"),"fixture",Instant.now());
        var failure=assertThrows(IllegalArgumentException.class,()->new GtinPreviewService(repo,k->adapter).create(key,Operation.ADD,"","00000000000017"));assertEquals("already_present",failure.getMessage());
    }
    @Test void legacyPublishedFlagWithoutActualCardIdentityCannotCreateAPreview(){
        var adapter=new Adapter();repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now())));
        var failure=assertThrows(IllegalArgumentException.class,()->new GtinPreviewService(repo,k->adapter).create(adapter.product.key(),Operation.ADD,"","00000000000017"));assertEquals("unverified_catalog_gtin",failure.getMessage());
    }
    @Test void anAdapterWithoutCurrentPhysicalVariantProofFailsClosed(){
        var fixture=new Adapter();GtinMarketplaceAdapter unsupported=new GtinMarketplaceAdapter(){
            public Capability capability(ProductKey key){return fixture.capability(key);}
            public ProductSnapshot read(ProductKey key){return fixture.read(key);}
            public boolean conflicts(ProductKey key,String gtin){return false;}
            public Submission submit(Preview preview){throw new AssertionError("No write without physical variant evidence");}
            public Verification verify(Preview preview,Submission submission){return Verification.UNKNOWN;}
        };
        var failure=assertThrows(IllegalArgumentException.class,()->new GtinPreviewService(repo,k->unsupported).create(fixture.product.key(),Operation.ADD,"","00000000000017"));assertEquals("GTIN_VARIANT_IDENTITY_UNVERIFIED",failure.getMessage());assertEquals(0,fixture.sends);
    }
}
