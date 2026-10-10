package com.vncode.app.shared;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Current-user DPAPI. Secret input goes through stdin, never the process command or logs. */
public final class WindowsSecretProtector {
    private static final String SCRIPT="""
            $ErrorActionPreference='Stop'
            Add-Type -AssemblyName System.Security
            $request=[Console]::In.ReadToEnd()|ConvertFrom-Json
            $bytes=[Convert]::FromBase64String($request.data)
            $entropy=[Text.Encoding]::UTF8.GetBytes('VN code GS1 mail')
            if($request.mode -eq 'protect') {
              $result=[Security.Cryptography.ProtectedData]::Protect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            } else {
              $result=[Security.Cryptography.ProtectedData]::Unprotect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            }
            [Console]::Out.Write([Convert]::ToBase64String($result))
            """;
    public static boolean supported(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");}
    public String protect(String secret)throws IOException{
        if(secret==null||secret.isEmpty()||secret.length()>8192)throw new IOException("Invalid mail password");
        return "dpapi:"+exchange("protect",Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
    }
    public String reveal(String protectedValue)throws IOException{
        if(protectedValue==null||!protectedValue.startsWith("dpapi:"))throw new IOException("Mail password is not protected");
        try{return new String(Base64.getDecoder().decode(exchange("reveal",protectedValue.substring(6))),StandardCharsets.UTF_8);}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid protected mail password");}
    }
    private String exchange(String mode,String data)throws IOException{
        if(!supported())throw new IOException("Windows DPAPI is required for saved mail credentials");
        JsonObject request=new JsonObject();request.addProperty("mode",mode);request.addProperty("data",data);
        Process process=null;
        try{
            process=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command",SCRIPT).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try(var stream=process.getOutputStream()){stream.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Windows secret protection timed out");}
            if(process.exitValue()!=0)throw new IOException("Windows cannot read or protect this mail password");
            byte[] response=process.getInputStream().readNBytes(32769);
            String value=new String(response,StandardCharsets.US_ASCII).strip();
            if(response.length>32768||!value.matches("[A-Za-z0-9+/]+=*"))throw new IOException("Invalid Windows secret-protection response");
            return value;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Secret protection interrupted");}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}
