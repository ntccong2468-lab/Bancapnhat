import com.google.gson.GsonBuilder;
import com.vncode.app.BuildConfig;
import com.vncode.app.Launcher;
import com.vncode.app.config.Database;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.shared.AppDataLock;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs the production entry point using a new disposable profile; no license fixture. */
public class CodeDoctorStartupProbe {
    private static <T>T fx(Callable<T> action)throws Exception {
        var work=new FutureTask<>(action);Platform.runLater(work);return work.get(5,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected output directory");
        var output=Path.of(args[0]);Files.createDirectories(output);
        var profile=Files.createTempDirectory("vncode-code-doctor-runtime-");
        var temp=profile.resolve("tmp");Files.createDirectories(temp);
        System.setProperty("vncode.appdata.dir",profile.toString());
        System.setProperty("java.io.tmpdir",temp.toString());
        Locale.setDefault(Locale.forLanguageTag("vi"));
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var app=new Thread(()->{try{Launcher.main(new String[0]);}catch(Throwable error){failure.set(error);}},"code-doctor-owned-launcher");
        long started=System.nanoTime();app.start();Stage window=null;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(window==null&&System.nanoTime()<deadline){
                if(failure.get()!=null)throw new IllegalStateException("Production launcher failed",failure.get());
                try{window=fx(()->Window.getWindows().stream().filter(w->w instanceof Stage s&&s.isShowing()
                        &&s.getTitle().equals("VN code v"+BuildConfig.getAppVersion())&&s.getScene()!=null
                        &&s.getScene().getRoot().lookup("#dynamicContentContainer")!=null).map(w->(Stage)w).findFirst().orElse(null));}
                catch(IllegalStateException toolkitStarting){}
                if(window==null)Thread.sleep(100);
            }
            if(window==null)throw new IllegalStateException("Workspace did not open before deadline");
            long openMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            var stage=window;
            var dimensions=fx(()->{
                var scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                if(scene.getWidth()<800||scene.getHeight()<500)throw new IllegalStateException("Workspace viewport is too small");
                var image=scene.snapshot(null);int width=(int)image.getWidth(),height=(int)image.getHeight();
                var buffered=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++)buffered.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                ImageIO.write(buffered,"png",output.resolve("startup.png").toFile());
                return List.of(width,height);
            });
            int shops;
            try(var c=Database.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM shops")){r.next();shops=r.getInt(1);}
            if(shops!=0)throw new IllegalStateException("Fresh profile unexpectedly contains shops");
            String license=LicenseRuntime.service().snapshot().status().name();
            if(LicenseRuntime.service().allows(com.vncode.app.features.license.LicenseModels.Permission.SELLER_API))
                throw new IllegalStateException("Fresh profile unexpectedly permits seller API operations");
            fx(()->{stage.close();Platform.exit();return null;});
            app.join(15000);if(app.isAlive())throw new IllegalStateException("Production launcher did not close");
            try(var unlocked=AppDataLock.acquire(profile,"code-doctor-after-close")){}
            var report=new LinkedHashMap<String,Object>();report.put("version",BuildConfig.getAppVersion());
            report.put("entryPoint","com.vncode.app.Launcher");report.put("result","passed");
            report.put("platform",System.getProperty("os.name"));report.put("workspaceOpenedMillis",openMillis);
            report.put("viewportPixels",dimensions);report.put("freshProfile",true);report.put("shops",shops);
            report.put("licenseStatus",license);report.put("sellerApiAllowed",false);report.put("licenseActivationRequested",false);
            report.put("normalShutdown",true);report.put("dataLockReleased",true);
            report.put("scope","Production JavaFX startup and shutdown on Linux/Xvfb, disposable empty profile; no seller mutation or Windows installer test");
            String json=new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n";
            Files.writeString(output.resolve("startup.json"),json);System.out.print(json);
        } finally {
            if(app.isAlive())try{fx(()->{Platform.exit();return null;});}catch(Exception ignored){}
        }
    }
}
