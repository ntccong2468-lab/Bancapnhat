package com.vncode.app.integration.znack;
import com.vncode.app.models.Shop;
import java.io.IOException;
/** Synthetic verified same-enterprise keys; no API or operating-system secret store. */
public final class CatalogRotationKeys {
 private static CatalogCredentialRepository store(){return new CatalogCredentialRepository(new CatalogCredentialRepository.Protection(){
  public String protect(String value){return "synthetic:"+java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  public String reveal(String value){return new String(java.util.Base64.getDecoder().decode(value.substring(10)),java.nio.charset.StandardCharsets.UTF_8);}
 });}
 public static void save(Shop shop,String key)throws Exception{
  new CatalogConnectionService(store(),(s,settings,owner,input,guard)->{
   guard.run();if(!owner.inn().equals("7700000000")||!java.util.Set.of("synthetic-A","synthetic-B").contains(input))throw new IOException("CATALOG_KEY_OWNER_MISMATCH");
  }).checkAndSave(shop,key,()->true);
 }
 public static void remove(Shop shop){store().remove(shop);}
}
