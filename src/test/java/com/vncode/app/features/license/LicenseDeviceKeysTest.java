package com.vncode.app.features.license;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LicenseDeviceKeysTest {
    @Test void restoresPairAndRejectsMismatchedKeysAndNoncanonicalEncoding() throws Exception {
        var first=LicenseDeviceKeys.create(); var second=LicenseDeviceKeys.create();
        var restored=LicenseDeviceKeys.restore(first.publicKey(),first.privateKey());
        assertEquals(first.publicKey(),restored.publicKey()); assertEquals(first.hash(),restored.hash());
        var verifier=Signature.getInstance("Ed25519"); verifier.initVerify(LicenseDeviceKeys.publicKey(restored.publicKey())); verifier.update("device-challenge".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(LicenseDeviceKeys.decode(restored.sign("device-challenge"),64)));
        assertThrows(IOException.class,()->LicenseDeviceKeys.restore(first.publicKey(),second.privateKey()));
        assertThrows(IOException.class,()->LicenseDeviceKeys.publicKey(first.publicKey()+"="));
        assertThrows(IOException.class,()->LicenseDeviceKeys.decode("AB",32));
        assertThrows(IOException.class,()->first.sign("x".repeat(4097)));
    }
    @Test void nodeAndJavaAgreeOnRawPublicKeyHashAndEd25519Signatures() throws Exception {
        var keys=LicenseDeviceKeys.create(); var input=new JsonObject();
        input.addProperty("publicKey",keys.publicKey()); input.addProperty("privateKey",keys.privateKey());
        input.addProperty("hash",keys.hash()); input.addProperty("message","VNCode challenge\nUTF-8: Việt Nam");
        input.addProperty("signature",keys.sign(input.get("message").getAsString()));
        String script="""
            const crypto=require('node:crypto'); let input='';
            process.stdin.setEncoding('utf8'); process.stdin.on('data',s=>input+=s);
            process.stdin.on('end',()=>{
              const p=JSON.parse(input), raw=Buffer.from(p.publicKey,'base64url');
              const publicKey=crypto.createPublicKey({key:Buffer.concat([Buffer.from('302a300506032b6570032100','hex'),raw]),format:'der',type:'spki'});
              const privateKey=crypto.createPrivateKey({key:Buffer.from(p.privateKey,'base64url'),format:'der',type:'pkcs8'});
              const message=Buffer.from(p.message,'utf8');
              const hash=crypto.createHash('sha256').update(p.publicKey,'utf8').digest('hex');
              const matches=raw.length===32 && hash===p.hash && crypto.verify(null,message,publicKey,Buffer.from(p.signature,'base64url'))
                && crypto.sign(null,message,privateKey).toString('base64url')===p.signature;
              process.exit(matches?0:1);
            });
            """;
        Process node;
        try { node=new ProcessBuilder("node","-e",script).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch(IOException unavailable) { assumeTrue(false,"Node.js is required for the optional cross-runtime contract check"); return; }
        try {
            try(var stream=node.getOutputStream()){stream.write(input.toString().getBytes(StandardCharsets.UTF_8));}
            assertTrue(node.waitFor(10,TimeUnit.SECONDS)); assertEquals(0,node.exitValue(),"Node and Java device-key contract must agree");
        } finally {if(node.isAlive())node.destroyForcibly();}
    }
}
