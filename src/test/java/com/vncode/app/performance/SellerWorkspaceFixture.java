package com.vncode.app.performance;

import com.vncode.app.config.Database;
import java.nio.file.*;
import java.sql.*;

/** Generated, credential-free data. Refuses to overwrite any existing database. */
public final class SellerWorkspaceFixture {
 private SellerWorkspaceFixture(){}
 public static void main(String[] args)throws Exception {if(args.length!=2)throw new IllegalArgumentException("isolated-directory ozon-shops");create(Path.of(args[0]),Integer.parseInt(args[1]));}
 public static void create(Path appdata)throws Exception {create(appdata,4);}
 public static void create(Path appdata,int ozonShops)throws Exception {
  if(ozonShops!=4&&ozonShops!=10)throw new IllegalArgumentException("Use 6WB/4Ozon or 10Ozon");
  Files.createDirectories(appdata);if(Files.exists(appdata.resolve("database.db")))throw new IllegalArgumentException("Fixture requires a new isolated directory");
  String prior=System.getProperty("vncode.appdata.dir");System.setProperty("vncode.appdata.dir",appdata.toAbsolutePath().toString());
  try{
   Database.initDatabase();try(var c=Database.getConnection()){
    c.setAutoCommit(false);
    try(var shop=c.prepareStatement("INSERT INTO shops(id,name,marketplace,client_id,api_key) VALUES(?,?,?,?,?)");
        var wb=c.prepareStatement("INSERT INTO wb_product_cards(shop_id,nm_id,vendor_code,title,subject_id,subject_name,synced_at) VALUES(?,?,?,?,1,'Fixture',?)");
        var size=c.prepareStatement("INSERT INTO wb_product_sizes(shop_id,chrt_id,nm_id,tech_size,wb_size) VALUES(?,?,?,'M','48')");
        var wbOrder=c.prepareStatement("INSERT INTO wb_orders(shop_id,order_id,nm_id,chrt_id,article,supplier_status,wb_status,created_at,synced_at) VALUES(?,?,?,?,?,'new','waiting',?,?)");
        var oz=c.prepareStatement("INSERT INTO ozon_products(shop_id,product_id,offer_id,sku,name,size,category,synced_at) VALUES(?,?,?,?,?,'M','Fixture',?)");
        var post=c.prepareStatement("INSERT INTO ozon_postings(shop_id,posting_number,status,synced_at,in_process_at) VALUES(?,?,?,?,?)");
        var item=c.prepareStatement("INSERT INTO ozon_posting_items(shop_id,posting_number,item_index,product_id,sku,offer_id,name,quantity,currency_code,price) VALUES(?,?,0,?,?,?,?,1,'RUB','1000')")){
     String time="2026-10-11T00:00:00Z";
     for(int owner=1;owner<=10;owner++){
      boolean ozon=owner>10-ozonShops;shop.setInt(1,owner);shop.setString(2,"Synthetic shop "+owner);shop.setString(3,ozon?"OZON":"WILDBERRIES");shop.setString(4,ozon?Integer.toString(owner):null);shop.setString(5,ozon?"synthetic-fixture-key":"fixture.e30.fixture");shop.executeUpdate();
      for(int i=0;i<10000;i++){
       long id=owner*1000000L+i;String article="fixture-"+owner+"-"+i;String name=String.format(java.util.Locale.ROOT,"Synthetic %02d %05d",owner,i);
       if(ozon){oz.setInt(1,owner);oz.setString(2,Long.toString(id));oz.setString(3,article);oz.setString(4,Long.toString(owner*10000000L+i));oz.setString(5,name);oz.setString(6,time);oz.addBatch();}
       else{wb.setInt(1,owner);wb.setLong(2,id);wb.setString(3,article);wb.setString(4,name);wb.setString(5,time);wb.addBatch();size.setInt(1,owner);size.setLong(2,id);size.setLong(3,id);size.addBatch();}
       if(i<1000){
        if(ozon){String number="fixture-"+owner+"-"+i;post.setInt(1,owner);post.setString(2,number);post.setString(3,new String[]{"awaiting_packaging","awaiting_deliver","delivering"}[i%3]);post.setString(4,time);post.setString(5,time);post.addBatch();item.setInt(1,owner);item.setString(2,number);item.setString(3,Long.toString(id));item.setString(4,Long.toString(owner*10000000L+i));item.setString(5,article);item.setString(6,name);item.addBatch();}
        else{wbOrder.setInt(1,owner);wbOrder.setLong(2,id);wbOrder.setLong(3,id);wbOrder.setLong(4,id);wbOrder.setString(5,article);wbOrder.setString(6,time);wbOrder.setString(7,time);wbOrder.addBatch();}
       }
      }
      if(ozon){oz.executeBatch();post.executeBatch();item.executeBatch();}else{wb.executeBatch();size.executeBatch();wbOrder.executeBatch();}
     }
    }
    c.commit();c.setAutoCommit(true);try(var s=c.createStatement()){s.execute("ANALYZE");s.execute("PRAGMA wal_checkpoint(TRUNCATE)");}
   }
   Files.writeString(appdata.resolve("fixture.json"),"{\"dataset\":\""+(ozonShops==10?"10ozon":"6wb4ozon")+"\",\"shops\":10,\"products\":100000,\"orders\":10000,\"synthetic\":true}");
  }finally{if(prior==null)System.clearProperty("vncode.appdata.dir");else System.setProperty("vncode.appdata.dir",prior);}
 }
}
