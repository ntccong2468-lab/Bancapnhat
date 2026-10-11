package com.vncode.app.ui.ozon;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.ozon.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OzonManualSyncUiTest {
    @TempDir Path root;
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    static <T>T fx(Callable<T> f)throws Exception{var task=new FutureTask<>(f);Platform.runLater(task);return task.get(5,TimeUnit.SECONDS);}
    @Test void actualManualSyncPopulatesEmptyCatalogAndRechecksDisappearedPosting()throws Exception{
        System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(1,'Ozon','OZON','11','fixture')");}
        var repository=new OzonPostingRepository();repository.upsertDetail(1,new OzonPostingDto("OLD","","","awaiting_packaging","","","2020-01-01T00:00:00Z","","","",new OzonRequirements(List.of(),List.of(),List.of()),List.of(),false,List.of()));
        var starts=new CopyOnWriteArrayList<String>();var title=new AtomicReference<>("Initial");var busy=new AtomicBoolean();
        try(var server=new MockWebServer()){
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                String path=request.getPath(),data=switch(path){
                    case "/v1/roles" -> "{\"roles\":[]}";
                    case "/v2/warehouse/list" -> "{\"warehouses\":[]}";
                    case "/v4/posting/fbs/unfulfilled/list","/v4/posting/fbs/list" -> "{\"postings\":[],\"has_next\":false,\"cursor\":\"\"}";
                    case "/v3/posting/fbs/get" -> "{\"result\":{\"posting_number\":\"OLD\",\"status\":\"cancelled\",\"products\":[]}}";
                    case "/v3/product/list" -> {starts.add(com.google.gson.JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("last_id").getAsString());yield "{\"result\":{\"items\":[{\"product_id\":321,\"offer_id\":\"O-1\"}],\"last_id\":\"done\"}}";}
                    case "/v3/product/info/list" -> "{\"items\":[{\"id\":321,\"offer_id\":\"O-1\",\"sku\":11,\"name\":\""+title.get()+"\"}]}";
                    case "/v4/product/info/attributes" -> "{\"result\":[{\"id\":321,\"offer_id\":\"O-1\",\"description_category_id\":9,\"type_id\":8,\"attributes\":[]}]}";
                    default -> "{\"result\":[]}";
                };return new MockResponse().setHeader("Content-Type","application/json").setBody(data);
            }});server.start();
            var api=new OzonApiClient(1,new OzonCredentials("11","fixture"),server.url("/"),new OkHttpClient.Builder().callTimeout(2,TimeUnit.SECONDS).build(),new OzonApiRateLimiter(Duration.ZERO),new OzonRetryPolicy(1,Duration.ofMillis(1),Duration.ofMillis(1)));
            var workflow=new OzonSyncWorkflow(s->api);
            var loader=fx(()->{var l=FxmlViewLoader.loader(OzonDashboardController.class,"ozon-dashboard-view.fxml");l.setControllerFactory(type->new OzonDashboardController(workflow));new Scene(l.load(),1024,600);return l;});OzonDashboardController controller=loader.getController();
            try{
                fx(()->{controller.setOnBusy((id,value)->busy.set(value));controller.setShop(new Shop(1,"Ozon",Marketplace.OZON,"11","fixture"),false);return null;});assertEquals(0,server.getRequestCount(),"Switching shop must stay local");
                for(String expected:List.of("Initial","Updated")){
                    title.set(expected);fx(()->{controller.sync();return null;});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(busy.get()&&System.nanoTime()<end)Thread.sleep(10);assertFalse(busy.get());
                    var page=new ProductRepository().loadPage(new ProductQuery(1,Marketplace.OZON,"",List.of(),null,false,null,100));assertEquals(1,page.total());assertEquals(expected,page.rows().getFirst().title());
                    assertEquals("cancelled",repository.find(1,"OLD").status());
                }
                assertEquals(List.of("",""),starts,"An explicit refresh must revisit products from the first page");
            }finally{fx(()->{controller.dispose();return null;});}
        }finally{while(AppTaskExecutor.hasRunningTasks()||ShopReadCoordinator.shared().activeCount()>0)Thread.sleep(10);System.clearProperty("vncode.appdata.dir");}
    }
}
