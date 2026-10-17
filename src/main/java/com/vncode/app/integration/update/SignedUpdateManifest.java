package com.vncode.app.integration.update;

import com.google.gson.*;
import com.vncode.app.features.license.LicenseJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Optional update-key pin is distinct from the desktop license key. Never trust a key from a response. */
final class SignedUpdateManifest {
    static UpdateInfo verify(String envelope,String pinnedPublicKey,String expectedVersion)throws IOException {
        try {
            JsonObject outer=LicenseJson.object(envelope);
            String format=LicenseJson.string(outer,"format",64);
            if(!Set.of("wcode-update-envelope-v1","vncode-update-envelope-v1").contains(format))throw new IOException();
            byte[] bytes=Base64.getDecoder().decode(LicenseJson.string(outer,"payload",100000));
            byte[] signature=Base64.getDecoder().decode(LicenseJson.string(outer,"signature",256));
            if(bytes.length>65536||signature.length!=64)throw new IOException();
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pinnedPublicKey)));
            var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(bytes);
            if(!verifier.verify(signature))throw new IOException();
            JsonObject payload=LicenseJson.object(new String(bytes,StandardCharsets.UTF_8));
            if(LicenseJson.number(payload,"schemaVersion")!=1)throw new IOException();
            String version=LicenseJson.string(payload,"version",32);
            if(!version.equals(expectedVersion)||!version.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))throw new IOException();
            var info=new UpdateInfo();info.setVersion(version);
            String published=LicenseJson.string(payload,"publishedAt",64);java.time.Instant.parse(published);info.setReleaseDate(published);
            JsonElement mandatory=payload.get("mandatory");
            if(mandatory==null||!mandatory.isJsonPrimitive()||!mandatory.getAsJsonPrimitive().isBoolean())throw new IOException();
            info.setMandatory(mandatory.getAsBoolean());
            JsonArray notes=payload.getAsJsonArray("notes");if(notes==null||notes.size()>20)throw new IOException();
            var lines=new ArrayList<String>();
            for(JsonElement note:notes){if(!note.isJsonPrimitive()||!note.getAsJsonPrimitive().isString()||note.getAsString().length()>500)throw new IOException();lines.add("• "+note.getAsString());}
            info.setChangelog(String.join("\n",lines));
            var urls=new LinkedHashMap<String,String>();var hashes=new LinkedHashMap<String,String>();var sizes=new LinkedHashMap<String,Long>();
            JsonArray assets=payload.getAsJsonArray("assets");if(assets==null||assets.size()>20)throw new IOException();
            for(JsonElement value:assets){
                JsonObject asset=value.getAsJsonObject();String kind=LicenseJson.string(asset,"kind",16),platform=LicenseJson.string(asset,"platform",32);
                String url=LicenseJson.string(asset,"url",2048),hash=LicenseJson.string(asset,"sha256",64);long size=LicenseJson.number(asset,"size");
                if(!url.startsWith("https://github.com/ntccong2468-lab/Bancapnhat/releases/download/v"+version+"/")
                        ||!hash.matches("[a-f0-9]{64}")||size<1||size>1024L*1024*1024)throw new IOException();
                String type=switch(platform){case "windows-x64"->kind;case "macos-arm64"->"dmg-arm64";case "macos-x64"->"dmg-x64";default->null;};
                if(type!=null&&Set.of("exe","msi","dmg-arm64","dmg-x64").contains(type)){
                    if(urls.putIfAbsent(type,url)!=null)throw new IOException();hashes.put(type,hash);sizes.put(type,size);
                }
            }
            urls.put("release","https://github.com/ntccong2468-lab/Bancapnhat/releases/tag/v"+version);
            info.setDownloadUrls(urls);info.setDownloadSha256(hashes);info.setDownloadSizes(sizes);
            return info;
        }catch(Exception invalid){throw new IOException("UPDATE_SIGNATURE_INVALID");}
    }
}
