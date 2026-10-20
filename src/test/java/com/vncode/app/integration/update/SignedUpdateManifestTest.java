package com.vncode.app.integration.update;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SignedUpdateManifestTest {
    private final KeyPair keys;
    SignedUpdateManifestTest()throws Exception{keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}
    private String envelope(String body)throws Exception {
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);var sig=Signature.getInstance("Ed25519");sig.initSign(keys.getPrivate());sig.update(bytes);
        var o=new JsonObject();o.addProperty("format","wcode-update-envelope-v1");o.addProperty("payload",Base64.getEncoder().encodeToString(bytes));o.addProperty("signature",Base64.getEncoder().encodeToString(sig.sign()));return o.toString();
    }
    private String payload(String version,String url){
        var o=new JsonObject();o.addProperty("schemaVersion",1);o.addProperty("version",version);o.addProperty("publishedAt","2026-10-14T00:00:00Z");o.addProperty("mandatory",false);
        var notes=new JsonArray();notes.add("License and updates");o.add("notes",notes);
        var asset=new JsonObject();asset.addProperty("platform","windows-x64");asset.addProperty("kind","exe");asset.addProperty("url",url);asset.addProperty("sha256","a".repeat(64));asset.addProperty("size",12345);
        var assets=new JsonArray();assets.add(asset);o.add("assets",assets);return o.toString();
    }
    private String publicKey(){return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());}
    @Test void verifiedManifestSuppliesExactVersionNotesAndInstallerDigest()throws Exception {
        String body=payload("1.4.4","https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.4/VN-code.exe");
        var info=SignedUpdateManifest.verify(envelope(body),publicKey(),"1.4.4");
        assertEquals("1.4.4",info.getVersion());assertTrue(info.getDisplayChangelog().contains("License and updates"));
        String os=System.getProperty("os.name");try{System.setProperty("os.name","Windows 11");
        assertEquals("a".repeat(64),info.getBestDownloadSha256());assertEquals(12345L,info.getBestDownloadSize());
        }finally{System.setProperty("os.name",os);}
        var modified=JsonParser.parseString(envelope(body)).getAsJsonObject();modified.addProperty("payload",Base64.getEncoder().encodeToString(body.replace("1.4.4","9.9.9").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("UPDATE_SIGNATURE_INVALID",assertThrows(java.io.IOException.class,()->SignedUpdateManifest.verify(modified.toString(),publicKey(),"1.4.4")).getMessage());
    }
    @Test void wrongPinWrongVersionAndForeignBinaryAreRejected()throws Exception {
        String signed=envelope(payload("1.4.4","https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v1.4.4/VN-code.exe"));
        var other=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();String otherPin=Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
        assertThrows(java.io.IOException.class,()->SignedUpdateManifest.verify(signed,otherPin,"1.4.4"));
        assertThrows(java.io.IOException.class,()->SignedUpdateManifest.verify(signed,publicKey(),"1.4.5"));
        String foreign=envelope(payload("1.4.4","https://github.com/rupphi/relatest-wcode/releases/download/v1.4.4/WCode.exe"));
        assertThrows(java.io.IOException.class,()->SignedUpdateManifest.verify(foreign,publicKey(),"1.4.4"));
    }
}
