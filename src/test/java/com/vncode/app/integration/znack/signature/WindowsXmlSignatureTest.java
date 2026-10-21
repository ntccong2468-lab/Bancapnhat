package com.vncode.app.integration.znack.signature;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WindowsXmlSignatureTest {
    @Test void xmlPayloadUsesSignedXmlRatherThanCmsAndTemporaryFilesAreDeleted()throws Exception {
        var captured=new java.util.ArrayList<Path>();
        var runner=new CryptoProCommandRunner(){
            @Override public Result run(List<String> command,Duration timeout)throws CryptoProException {
                try{
                    int start=command.indexOf("-File");Path script=Path.of(command.get(start+1));Path payload=Path.of(command.get(start+2));Path output=Path.of(command.getLast());
                    assertEquals("<application/>",Files.readString(payload));
                    String code=Files.readString(script);
                    assertTrue(code.contains("CAdESCOM.SignedXML"));assertFalse(code.contains("SignCades("));
                    assertFalse(String.join(" ",command).contains("<application"));
                    captured.addAll(List.of(script,payload,output));
                    Files.writeString(output,"<application><Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"/></application>");
                    return new Result(0,new byte[0],new byte[0]);
                }catch(java.io.IOException e){throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED,"Fixture failed",e);}
            }
        };
        String result=new WindowsCadesSignatureProvider(runner,"A".repeat(40),Duration.ofSeconds(2)).signXml("<application/>");
        assertTrue(result.contains("Signature"));for(Path file:captured)assertFalse(Files.exists(file));
    }
    @Test void unsignedOutputAndExternalXmlEntitiesAreRejected() {
        var runner=new CryptoProCommandRunner(){@Override public Result run(List<String> command,Duration timeout)throws CryptoProException{
            try{Files.writeString(Path.of(command.getLast()),"<application/>");return new Result(0,new byte[0],new byte[0]);}catch(Exception e){throw new CryptoProException(CryptoProErrorCode.SIGNING_FAILED,"Fixture failed");}
        }};
        var signer=new WindowsCadesSignatureProvider(runner,"A".repeat(40),Duration.ofSeconds(2));
        assertThrows(CryptoProException.class,()->signer.signXml("<application/>"));
        assertThrows(CryptoProException.class,()->signer.signXml("<!DOCTYPE a SYSTEM 'file:///secret'><a/>"));
    }
}
