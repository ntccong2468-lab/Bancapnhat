package com.vncode.app.features.print;

import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.features.kiz.*;
import com.vncode.app.features.print.history.PrintHistoryService;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackGtinInventoryService;
import com.vncode.app.models.*;
import com.vncode.app.models.Order;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real local stock, PDF and history; marketplace metadata is an explicit simulator. */
class KizCategoryDeletionExportTest {
    @TempDir Path directory;
    Shop shop;
    final ShipmentKizRepository shipments=new ShipmentKizRepository();
    final ZnackGtinInventoryService inventory=new ZnackGtinInventoryService();
    static final String CODE="0104645588781154210000000000001";
    @BeforeEach void init()throws Exception{
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);
        try(var c=Database.getConnection();var s=c.createStatement()){
            s.execute("DELETE FROM znack_settings");
            s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,subject_id,subject_name,title,synced_at) VALUES(1,101,10,'Unused','Fixture','fixture'),(1,102,20,'Trousers','Fixture','fixture')");
        }
    }
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    private OrderExportWorkflow workflow(Map<Long,KizService.SgtinMetadata> remote){
        return new OrderExportWorkflow(new OrderExportWorkflow.KizPlanningData(){
            public Map<Long,KizService.SgtinMetadata> metadata(Shop s,List<Long> ids){return remote;}
            public Map<Long,String> mappings(int s,List<Long> ids){throw new AssertionError("Seller stock does not require purchase mappings");}
            public Set<Long> required(int s,List<Long> ids){return Set.of(102L);}
            public Map<Long,String> stockCategories(int s,List<Long> ids){
                var active=shipments.assignmentCategories(shop);var bindings=new KizStockCategoryRepository().bindings(shop,ids.stream().map(String::valueOf).toList());
                var result=new HashMap<Long,String>();bindings.forEach((id,group)->{if(active.contains(group))result.put(Long.parseLong(id),group);});return result;
            }
            public Map<Long,List<Kiz>> reserveCategoryStock(int s,Map<Long,Integer> quantities){
                var selected=new KizCategoryAllocator().reservePartial(shop,quantities.entrySet().stream().map(e->new KizCategoryAllocator.Demand(e.getKey().toString(),e.getKey().toString(),e.getValue())).toList());
                var result=new HashMap<Long,List<Kiz>>();selected.forEach((id,codes)->result.put(Long.parseLong(id),codes));return result;
            }
            public Map<String,List<Kiz>> reserve(int s,Map<String,Integer> quantities){throw new AssertionError("No purchase permitted by seller-only source");}
            public void release(int s,List<Kiz> codes){inventory.release(s,codes);}
        });
    }
    private List<Order> orders(int count){
        var result=new ArrayList<Order>();for(int i=1;i<=count;i++){
            var order=new Order();order.setId((long)i);order.setNmId(102L);order.setName("Synthetic trousers");order.setSubjectName("Trousers");
            order.setArticle("FIXTURE");order.setBarcode("2040000000001");order.setStickerCode("WB-FIXTURE-"+i);order.setRequiresKiz(true);result.add(order);
        }return result;
    }
    private void importAndDeleteUnusedCategory()throws Exception{
        var categories=shipments.refreshCategories(shop);String unused=categories.stream().filter(c->c.name().endsWith("WB 10")).findFirst().orElseThrow().id();
        String active=categories.stream().filter(c->c.name().endsWith("WB 20")).findFirst().orElseThrow().id();
        var file=directory.resolve("seller.txt");Files.write(file,List.of(CODE,CODE));
        new KizWarehouseService().importFile(shop,KizImportReader.read(file),true,active);
        new KizStockCategoryRepository().delete(shop,unused);assertEquals(1,shipments.refreshCategories(shop).size());
    }
    private OrderExportWorkflow.ExportResult export(Map<Long,KizService.SgtinMetadata> remote,int count)throws Exception{
        return workflow(remote).export(new OrderExportWorkflow.ExportRequest(shop,"SIM-SHIPMENT","Simulation",orders(count),
                PrintJobOptions.defaults(),directory.resolve("labels.pdf").toFile(),directory.resolve("details.pdf").toFile(),KizSourceSelection.SELLER_ONLY));
    }
    @Test void deletingAnotherCategoryDoesNotBlockDuplicateSellerOccurrencesOrPdfReprint()throws Exception{
        importAndDeleteUnusedCategory();var result=export(Map.of(),2);
        assertEquals(2,result.consumedKizs().size());assertEquals(2,result.consumedKizs().stream().map(Kiz::getId).distinct().count());
        assertEquals(2,result.kizAttachments().size());assertEquals(2,result.kizAttachments().stream().map(a->a.sourceKiz().getId()).distinct().count());
        assertEquals(0,shipments.stocks(shop).getFirst().ready());
        var history=new PrintHistoryService();var job=history.getJobs(1).stream().filter(j->j.id()==result.printJobId()).findFirst().orElseThrow();
        var reprinted=history.reprint(job,directory.resolve("reprinted.pdf").toFile(),directory.resolve("reprinted-details.pdf").toFile());
        assertEquals(List.of(CODE,CODE),reprinted.exportedOrders().stream().map(Order::getKiz).toList());assertTrue(reprinted.consumedKizs().isEmpty());
        for(String name:List.of("labels.pdf","details.pdf","reprinted.pdf","reprinted-details.pdf"))try(var pdf=org.apache.pdfbox.Loader.loadPDF(directory.resolve(name).toFile())){assertTrue(pdf.getNumberOfPages()>0);}
    }
    @Test void anExistingRemoteCodeDoesNotStealTheSourceOccurrenceFromAnotherOrder()throws Exception{
        importAndDeleteUnusedCategory();var result=export(Map.of(1L,new KizService.SgtinMetadata(true,CODE)),3);
        assertEquals(List.of(2L,3L),result.kizAttachments().stream().map(OrderExportWorkflow.KizAttachmentAssignment::orderId).toList());
        assertEquals(2,result.kizAttachments().stream().map(a->a.sourceKiz().getId()).distinct().count());
    }
}
