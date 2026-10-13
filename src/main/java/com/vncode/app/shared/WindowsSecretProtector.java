package com.vncode.app.shared;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Current-user DPAPI. Secret input goes through stdin, never the process command or logs. */
public final class WindowsSecretProtector {
    private static final String MAIL_PURPOSE = "VN code GS1 mail";
    private static final String LICENSE_PURPOSE = "VN code account license";
    private final String purpose;
    private final int maxBytes;
    private final int maxCipherBytes;

    public WindowsSecretProtector() { this(MAIL_PURPOSE, 8192); }
    public WindowsSecretProtector(String purpose, int maxBytes) {
        int allowed = MAIL_PURPOSE.equals(purpose) ? 8192 : LICENSE_PURPOSE.equals(purpose) ? 65536 : 0;
        if (allowed == 0 || maxBytes < 1 || maxBytes > allowed) throw new IllegalArgumentException("Invalid secret protection purpose or size");
        this.purpose = purpose;
        this.maxBytes = maxBytes;
        this.maxCipherBytes = maxBytes + 16384;
    }
    private static final String SCRIPT="""
            $ErrorActionPreference='Stop'
            Add-Type -AssemblyName System.Security
            $request=[Console]::In.ReadToEnd()|ConvertFrom-Json
            $bytes=[Convert]::FromBase64String($request.data)
            $entropy=[Text.Encoding]::UTF8.GetBytes($request.purpose)
            if($request.mode -eq 'protect') {
              $result=[Security.Cryptography.ProtectedData]::Protect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            } else {
              $result=[Security.Cryptography.ProtectedData]::Unprotect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            }
            [Console]::Out.Write([Convert]::ToBase64String($result))
            """;
    public static boolean supported(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");}
    public String protect(String secret)throws IOException{
        if(secret==null||secret.isEmpty()||secret.getBytes(StandardCharsets.UTF_8).length>maxBytes)throw new IOException("Invalid saved secret");
        return "dpapi:"+exchange("protect",Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
    }
    public String reveal(String protectedValue)throws IOException{
        if(protectedValue==null||!protectedValue.startsWith("dpapi:"))throw new IOException("Saved secret is not protected");
        String encoded=protectedValue.substring(6);
        if(encoded.length()>encodedLimit(maxCipherBytes))throw new IOException("Invalid protected secret");
        try {
            byte[] cipher=Base64.getDecoder().decode(encoded);
            if(cipher.length==0||cipher.length>maxCipherBytes)throw new IOException("Invalid protected secret");
            return new String(Base64.getDecoder().decode(exchange("reveal",encoded)),StandardCharsets.UTF_8);
        } catch(IllegalArgumentException invalid){throw new IOException("Invalid protected secret");}
    }
    private static int encodedLimit(int bytes){return ((bytes+2)/3)*4;}
    private String exchange(String mode,String data)throws IOException{
        if(!supported())throw new IOException("Windows DPAPI is required for saved secrets");
        JsonObject request=new JsonObject();request.addProperty("mode",mode);request.addProperty("data",data);request.addProperty("purpose",purpose);
        int responseLimit=encodedLimit("protect".equals(mode)?maxCipherBytes:maxBytes);
        Process process=null;
        try{
            process=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command",SCRIPT).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var stdout=process.getInputStream();
            var output=new FutureTask<byte[]>(()->stdout.readNBytes(responseLimit+1));
            Thread.ofVirtual().name("vncode-secret-protection-output").start(output);
            try(var stream=process.getOutputStream()){stream.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Windows secret protection timed out");}
            if(process.exitValue()!=0)throw new IOException("Windows cannot read or protect this saved secret");
            byte[] response;
            try{response=output.get(5,TimeUnit.SECONDS);}
            catch(ExecutionException|TimeoutException failure){throw new IOException("Windows secret-protection output could not be read");}
            String value=new String(response,StandardCharsets.US_ASCII).strip();
            if(response.length>responseLimit||!value.matches("[A-Za-z0-9+/]+=*"))throw new IOException("Invalid Windows secret-protection response");
            try {
                int decodedLength=Base64.getDecoder().decode(value).length;
                if(decodedLength>("protect".equals(mode)?maxCipherBytes:maxBytes))throw new IOException("Invalid Windows secret-protection response");
            } catch(IllegalArgumentException invalid){throw new IOException("Invalid Windows secret-protection response");}
            return value;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Secret protection interrupted");}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}
