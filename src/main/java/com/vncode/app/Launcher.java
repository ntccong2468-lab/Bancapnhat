package com.vncode.app;

import com.vncode.app.shared.AppPaths;
import com.vncode.app.shared.LocalDataMigrationGate;
import javafx.application.Application;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class Launcher {
    private static volatile LocalDataMigrationGate.Session dataSession;
    private static final Object SESSION_LOCK=new Object();
    private static boolean exiting;
    public static void main(String[] args) {
        configureStartupEnvironment();
        synchronized(SESSION_LOCK){exiting=false;}
        try {
            Application.launch(MainApplication.class, args);
        } catch (Exception exception) {
            writeStartupLog("main", exception);
            System.err.println("VN code could not acquire its app-data directory.");
        }finally{
            synchronized(SESSION_LOCK){exiting=true;var session=dataSession;dataSession=null;if(session!=null)try{session.close();}catch(java.io.IOException failed){writeStartupLog("close",failed);}}
        }
    }
    /** The visible startup scene precedes recovery; no application writer opens first. */
    static void prepareDataSession(java.util.function.Consumer<String> phase)throws Exception {
        var session=LocalDataMigrationGate.prepare(AppPaths.appDataDir(),BuildConfig.getAppVersion(),"javafx",phase);
        synchronized(SESSION_LOCK){
            if(exiting||Thread.currentThread().isInterrupted()){session.close();throw new java.io.InterruptedIOException("STARTUP_CANCELLED");}
            dataSession=session;
        }
    }
    public static void prepareUpdateSnapshot()throws java.io.IOException {
        prepareUpdateSnapshot(null);
    }
    public static void prepareUpdateSnapshot(String targetVersion)throws java.io.IOException {
        prepareUpdateSnapshot(targetVersion,progress->{});
    }
    public static void prepareUpdateSnapshot(String targetVersion,java.util.function.Consumer<com.vncode.app.shared.LocalDataSnapshotService.Progress> progress)throws java.io.IOException {
        var session=dataSession;
        if(session==null)throw new java.io.IOException("UPDATE_BACKUP_UNAVAILABLE");
        try{session.createSignedUpdateSnapshot(targetVersion,progress);}
        catch(Exception failed){throw new java.io.IOException("UPDATE_BACKUP_FAILED", failed);}
    }

    private static void configureStartupEnvironment() {
        try {
            Path tempDir = AppPaths.nativeTempDir();
            Path javafxCacheDir = AppPaths.javaFxCacheDir();
            Path safeUserHomeDir = AppPaths.safeUserHomeDir();
            Files.createDirectories(tempDir);
            Files.createDirectories(javafxCacheDir);
            Files.createDirectories(safeUserHomeDir);

            String currentUserHome = System.getProperty("user.home", "");
            if (!isAsciiOnly(currentUserHome)) {
                System.setProperty("user.home", safeUserHomeDir.toString());
            }
            System.setProperty("org.sqlite.tmpdir", tempDir.toString());
            System.setProperty("java.io.tmpdir", tempDir.toString());
            System.setProperty("javafx.cachedir", javafxCacheDir.toString());

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> writeStartupLog(thread.getName(), throwable));
        } catch (Exception ignored) {
        }
    }

    private static boolean isAsciiOnly(String value) {
        return value != null && value.chars().allMatch(ch -> ch >= 32 && ch <= 126);
    }

    static void writeStartupLog(String threadName, Throwable throwable) {
        try {
            Path logsDir = AppPaths.logsDir();
            Files.createDirectories(logsDir);
            Path logFile = logsDir.resolve("startup.log");

            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));

            String content = """
                    [%s] Thread: %s
                    user.home=%s
                    LOCALAPPDATA=%s
                    APPDATA=%s
                    java.io.tmpdir=%s
                    org.sqlite.tmpdir=%s
                    javafx.cachedir=%s
                    %s

                    """.formatted(
                    LocalDateTime.now(),
                    threadName,
                    System.getProperty("user.home"),
                    System.getenv("LOCALAPPDATA"),
                    System.getenv("APPDATA"),
                    System.getProperty("java.io.tmpdir"),
                    System.getProperty("org.sqlite.tmpdir"),
                    System.getProperty("javafx.cachedir"),
                    stack
            );

            Files.writeString(logFile, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
