import com.google.gson.*;
import com.vncode.app.config.Database;
import com.vncode.app.features.products.*;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import java.nio.file.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only remote acceptance; temporary isolated profile is deleted in finally. No license fixture. */
public class LiveWbProductSyncRead {
    public static void main(String[] args)throws Exception {
        Path privateRoot=Path.of(args[0]);Path receipt=Path.of(args[1]);
        var ids=new LinkedHashSet<String>();
        try(var pages=Files.list(privateRoot)){
            for(var path:pages.filter(p->p.getFileName().toString().matches("wb-page-[0-9]+\\.json")).sorted().toList())
                for(var raw:JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("cards"))ids.add(raw.getAsJsonObject().get("nmID").getAsString());
        }
        if(ids.isEmpty()||ids.size()>10_000)throw new IllegalArgumentException("Invalid bounded read selection");
        Path profile=Files.createTempDirectory(privateRoot,"live-profile-");
        var result=new LinkedHashMap<String,Object>();long started=System.nanoTime();
        try {
            System.setProperty("vncode.appdata.dir",profile.toString());Database.initDatabase();
            String apiKey=Objects.requireNonNull(System.getenv("VNCODE_WB_TEST_API_KEY"));
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'Read acceptance','WILDBERRIES',?)")){
                p.setString(1,apiKey);p.executeUpdate();
            }
            try(var c=Database.getConnection();var p=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,title,synced_at) VALUES(1,?,'Observed product','now')")){
                for(String id:ids){p.setLong(1,Long.parseLong(id));p.addBatch();}p.executeBatch();
            }
            var shop=new Shop(1,"Read acceptance",Marketplace.WILDBERRIES,null,apiKey);
            var client=new ListingHttpClient(shop,configuredHttp(),okhttp3.HttpUrl.parse("https://content-api.wildberries.ru/"));
            var calls=new AtomicInteger();var observed=new HashSet<String>();
            ListingTransport transport=(path,body,mutation)->{
                if(mutation||!path.equals("content/v2/get/cards/list"))throw new SecurityException("Only the read-only WB product list is permitted");
                var response=client.call(path,body,false);calls.incrementAndGet();
                for(var raw:response.getAsJsonObject().getAsJsonArray("cards"))observed.add(raw.getAsJsonObject().get("nmID").getAsString());
                return response;
            };
            var keys=ids.stream().map(id->new ProductKey(1,Marketplace.WILDBERRIES,id,"")).toList();
            var repository=new ProductRepository();var reader=new WbProductSnapshotReader(s->transport);
            int complete=0;var failures=new TreeMap<String,Integer>();var documents=new TreeMap<String,Integer>();
            try(var batch=reader.openBatch(shop,keys)){
                for(var key:keys)try{
                    var snapshot=batch.read(key);repository.saveVerifiedSnapshot(shop,snapshot);complete++;
                    for(var document:snapshot.documents())documents.merge(document.state().name(),1,Integer::sum);
                }catch(WbProductSnapshotReader.CardReadException e){failures.merge(e.code(),1,Integer::sum);}
                catch(java.io.IOException e){failures.merge(e.getClass().getSimpleName(),1,Integer::sum);}
            }
            var page=repository.loadProductPage(new ProductQuery(1,Marketplace.WILDBERRIES,"",List.of(),null,false,null,1000));
            var projected=new TreeMap<String,Integer>();for(var row:page.rows())projected.merge(row.documentState().name(),1,Integer::sum);
            result.put("checkedAtUtc",Instant.now().toString());result.put("sourceCommit",args[2]);
            result.put("mode","Live WB read-only transport, production batch/parser/repository in temporary isolated profile; no license fixture or seller mutation");
            result.put("requestedCards",keys.size());result.put("observedCardsInFreshPages",observed.size());result.put("readOnlyRequests",calls.get());
            result.put("snapshotsSaved",complete);result.put("failures",failures);result.put("documentStates",documents);result.put("projectedProductStates",projected);
            result.put("localProductCount",page.total());result.put("seconds",(System.nanoTime()-started)/1e9);
            result.put("scope","Selection observed by earlier fresh list with configured WB key; not proof of exact screenshot store/cache, Ozon or Windows UI");
        } finally {
            System.clearProperty("vncode.appdata.dir");
            try(var files=Files.walk(profile)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
            result.put("temporaryProfileDeleted",!Files.exists(profile));
        }
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Files.writeString(receipt,json+"\n");System.out.println(json);
    }
    private static okhttp3.OkHttpClient configuredHttp()throws Exception {
        var builder=new okhttp3.OkHttpClient.Builder();
        String proxy=System.getenv("HTTPS_PROXY");if(proxy!=null&&!proxy.isBlank()){
            var uri=URI.create(proxy);builder.proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(uri.getHost(),uri.getPort())));
        }
        String ca=System.getenv("SSL_CERT_FILE");if(ca!=null&&!ca.isBlank()){
            var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
            try(var input=Files.newInputStream(Path.of(ca))){int i=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))store.setCertificateEntry("managed-ca-"+i++,certificate);}
            var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
            var manager=(X509TrustManager)factory.getTrustManagers()[0];var context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{manager},null);
            builder.sslSocketFactory(context.getSocketFactory(),manager);
        }
        return builder.build();
    }
}
