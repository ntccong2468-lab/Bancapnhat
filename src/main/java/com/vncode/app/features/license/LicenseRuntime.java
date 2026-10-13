package com.vncode.app.features.license;

import com.vncode.app.integration.license.WebLicenseApi;
import com.vncode.app.shared.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static com.vncode.app.features.license.LicenseModels.*;

/** Production starts this singleton before FXML. No runtime setting can disable licensing. */
public final class LicenseRuntime {
    private static volatile LicenseService current;
    private static volatile int observedShopCount=-1;
    private LicenseRuntime(){}
    public static synchronized void bootstrap(){if(current==null){current=create();current.initialize();}}
    public static synchronized LicenseService service(){bootstrap();return current;}
    public static synchronized void install(LicenseService service){current=Objects.requireNonNull(service);observedShopCount=0;}
    public static void require(Permission permission){LicenseService s=current;if(s==null)throw new LicenseGate.LicenseDeniedException();new LicenseGate(s).require(permission);if(permission!=Permission.SHOP_MANAGE&&(observedShopCount<0||observedShopCount>s.maxShops()))throw new LicenseGate.LicenseDeniedException("LICENSE_SHOP_LIMIT");}
    public static void observeShopCount(int count){if(count<0)throw new IllegalArgumentException("Invalid shop count");observedShopCount=count;}
    public static void requireShopCapacity(int currentCount){LicenseService s=current;if(s==null)throw new LicenseGate.LicenseDeniedException();new LicenseGate(s).requireShopCapacity(currentCount);}
    public static boolean hasPendingTasks(){LicenseService s=current;return s!=null&&s.hasPendingTasks();}
    public static synchronized void close(){if(current!=null){current.close();current=null;observedShopCount=-1;}}
    private static LicenseService create(){
        Map<String,java.security.PublicKey> keys=new HashMap<>();
        try(var stream=LicenseRuntime.class.getResourceAsStream("/com/vncode/app/license/trusted-keys.json")){
            if(stream!=null){var object=LicenseJson.object(new String(stream.readNBytes(32769),StandardCharsets.UTF_8));for(var e:object.entrySet())keys.put(e.getKey(),LicenseDeviceKeys.publicKey(e.getValue().getAsString()));}
        }catch(IOException|RuntimeException invalid){keys.clear();}
        var protector=new WindowsSecretProtector("VN code account license",65536);
        var store=new LicenseStore(AppPaths.appDataDir().resolve("license"),new LicenseStore.SecretCodec(){public String protect(String v)throws IOException{return protector.protect(v);}public String reveal(String v)throws IOException{return protector.reveal(v);}});
        return new LicenseService(new WebLicenseApi(),new LicenseVerifier(keys),store,Clock.systemUTC(),System::nanoTime);
    }
}
