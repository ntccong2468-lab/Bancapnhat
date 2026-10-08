import com.vncode.app.config.Database;
import com.vncode.app.features.print.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.security.MessageDigest;
import java.util.HexFormat;
public final class WindowsUpgradeDataProbe {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[1]).toAbsolutePath();System.setProperty("vncode.appdata.dir",root.toString());
  if("seed".equals(args[0])) {
   Database.initDatabase();
   var service=new PrintTemplateService();var template=service.createTemplate("upgrade-personal-fixture");
   template.getElements().stream().filter(e->e.getFieldKey()==PrintFieldKey.ARTICLE).findFirst().orElseThrow().setPrefix("PERSONAL");service.saveTemplate(template);
   try(var c=Database.getConnection();var s=c.createStatement()) {
    s.execute("INSERT INTO shops(id,name,marketplace,api_key) VALUES(1,'upgrade-fixture','WILDBERRIES','fixture')");
    s.execute("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,synced_at) VALUES(1,101,'fixture','Fixture','fixture')");
    s.execute("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size) VALUES(1,11,101,'XL')");
    s.execute("INSERT INTO znack_card_registrations(shop_id,chrt_id,nm_id,gtin,feed_id,good_id,wb_updated,status,created_at,updated_at) VALUES(1,11,101,'04631993764363','fixture-feed',91,1,'ERROR','fixture','fixture')");
    s.execute("INSERT INTO znack_products(shop_id,gtin,product_name,synced_at) VALUES(1,'04631993764363','fixture','fixture')");
    s.execute("INSERT INTO kiz_orders(id,shop_id,gtin,quantity,remote_status,local_status,created_at,updated_at) VALUES(100,1,'04631993764363',2,'completed','completed','fixture','fixture')");
    s.execute("INSERT INTO kiz_codes(id,shop_id,order_id,raw_code,display_code,gtin,status,created_at,updated_at) VALUES(100,1,100,'fixture-code-available','fixture','04631993764363','available','fixture','fixture'),(101,1,100,'fixture-code-printed','fixture','04631993764363','printed','fixture','fixture')");
    s.execute("INSERT INTO print_jobs(id,shop_id,shop_name,printed_at,item_count,template_layout_json,status) VALUES(100,1,'fixture','fixture',1,'{}','printed')");
    s.execute("INSERT INTO print_job_items(print_job_id,sort_index,order_id,barcode,kiz) VALUES(100,0,1,'fixture-barcode','fixture-printed-kiz')");
   }
   return;
  }
  MessageDigest hash=MessageDigest.getInstance("SHA-256");
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("database.db"));var s=c.createStatement()){
   try(var r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equals(r.getString(1)))throw new IllegalStateException("Integrity failure");}
   try(var r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new IllegalStateException("Foreign key failure");}
   for(String table:new String[]{"shops","print_templates","fbo_print_templates","znack_card_registrations","print_jobs","print_job_items","kiz_orders","kiz_codes"}){
    hash.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try(var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY rowid")){var meta=r.getMetaData();while(r.next()){for(int col=1;col<=meta.getColumnCount();col++){
      byte[] value=r.getString(col)==null?new byte[0]:r.getString(col).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      hash.update(java.nio.ByteBuffer.allocate(4).putInt(r.getString(col)==null?-1:value.length).array());hash.update(value);
    }}}
   }
  }
  System.out.println(HexFormat.of().formatHex(hash.digest()));
 }
}
