package com.tuandev.fbsbarcode.features.gtinsync;

import com.tuandev.fbsbarcode.config.Database;
import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.*;
import static org.junit.jupiter.api.Assertions.*;

class GtinPreviewServiceTest {
    @TempDir Path temp;
    GtinSyncRepository repo;
    @BeforeEach void init()throws Exception {
        System.setProperty("wcode.appdata.dir",temp.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Test','WILDBERRIES','test')");}
        repo=new GtinSyncRepository();
        repo.saveCatalog(1,Marketplace.WILDBERRIES,List.of(new RegisteredGtin("00000000000017","PANTS","Đen","XL",true,true,"NK",Instant.now())));
    }
    @AfterEach void clear(){System.clearProperty("wcode.appdata.dir");}
    static class Adapter implements GtinMarketplaceAdapter {
        ProductSnapshot product=GtinSyncRepositoryTest.preview(1,Marketplace.WILDBERRIES).before();
        Capability cap=new Capability(true,true,"");boolean conflict=false;
        int sends;Verification outcome=Verification.APPLIED;boolean loseResponse;
        public Capability capability(ProductKey key){return cap;}
        public ProductSnapshot read(ProductKey key){if(!product.key().equals(key))throw new IllegalArgumentException("wrong_product");return product;}
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
}
