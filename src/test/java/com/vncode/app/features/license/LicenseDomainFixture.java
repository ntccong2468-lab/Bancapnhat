package com.vncode.app.features.license;

import com.google.gson.*;
import com.vncode.app.integration.license.LicenseApi;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.vncode.app.features.license.LicenseModels.*;

/** Test-only signed authority for existing domain tests, never bundled in the production JAR. */
public final class LicenseDomainFixture {
    public static final String CODE="VNC-0000-0000-0000-0000-0000-0000-0000-0000";
    public static final String TOKEN="a".repeat(43);
    public static Envelope sign(KeyPair server,LicenseDeviceKeys device,String account,long issued,int shops)throws Exception {
        JsonObject o=new JsonObject();o.addProperty("formatVersion",1);o.addProperty("product","vncode");o.addProperty("accountId",account);o.addProperty("deviceId","fixture-device");o.addProperty("devicePublicKeyHash",device.hash());o.addProperty("licenseRevision",1);o.addProperty("issuedAt",issued);o.addProperty("notBefore",issued);o.addProperty("expiresAt",issued+72*3600);o.addProperty("subscriptionExpiresAt",issued+7*86400);o.addProperty("maxDevices",1);o.addProperty("maxShops",shops);o.addProperty("offlineHours",72);
        var permissions=new JsonArray();for(var p:Permission.values())permissions.add(p.name().toLowerCase(Locale.ROOT));o.add("permissions",permissions);
        return signJson(server,o.toString());
    }
    public static Envelope signJson(KeyPair server,String json)throws Exception {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);var signature=Signature.getInstance("Ed25519");signature.initSign(server.getPrivate());signature.update(bytes);
        return new Envelope("test-fixture-v1",LicenseDeviceKeys.encode(bytes),LicenseDeviceKeys.encode(signature.sign()));
    }
    public static LicenseStore store(Path path) {return new LicenseStore(path,new LicenseStore.SecretCodec(){public String protect(String v){return "TEST:"+Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8));}public String reveal(String v)throws IOException{if(!v.startsWith("TEST:"))throw new IOException("LICENSE_STORE_INVALID");try{return new String(Base64.getDecoder().decode(v.substring(5)),StandardCharsets.UTF_8);}catch(IllegalArgumentException bad){throw new IOException("LICENSE_STORE_INVALID");}}});}
    public static LicenseService active(Path dir)throws Exception {
        var device=LicenseDeviceKeys.create();var server=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();long now=Instant.now().getEpochSecond();
        var envelope=sign(server,device,"fixture-account",now,100);var store=store(dir);
        store.save(new State(device.publicKey(),device.privateKey(),TOKEN,null,new Account("fixture-account","fixture@gmail.com","approved",now+7*86400),envelope,now,1,""));
        var service=new LicenseService(new UnusedApi(),new LicenseVerifier(Map.of("test-fixture-v1",server.getPublic())),store,Clock.systemUTC(),System::nanoTime);
        service.initialize().get(10,TimeUnit.SECONDS);if(!service.allows(Permission.SELLER_API))throw new AssertionError("Signed test fixture unavailable");return service;
    }
    public static LicenseService denied(Path dir)throws Exception {
        var service=new LicenseService(new UnusedApi(),new LicenseVerifier(Map.of()),store(dir),Clock.systemUTC(),System::nanoTime);service.initialize().get(10,TimeUnit.SECONDS);return service;
    }
    public static class UnusedApi implements LicenseApi {
        private IOException unused(){return new IOException("TEST_NETWORK_FORBIDDEN");}
        public Registration start(String email)throws IOException{throw unused();}
        public Registration enroll(String code)throws IOException{throw unused();}
        public RegistrationStatus verify(String token,String otp)throws IOException{throw unused();}
        public RegistrationStatus status(String token)throws IOException{throw unused();}
        public Challenge challenge(String token,String key,String action)throws IOException{throw unused();}
        public Grant activate(String token,Challenge c,String key,String proof,String code)throws IOException{throw unused();}
        public Grant renew(String token,Challenge c,String key,String proof)throws IOException{throw unused();}
    }
}
