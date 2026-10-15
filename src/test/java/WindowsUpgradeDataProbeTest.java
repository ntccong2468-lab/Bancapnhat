import com.vncode.app.config.Database;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WindowsUpgradeDataProbeTest {
    @TempDir static Path classes;
    @TempDir Path data;
    static URLClassLoader loader;
    static Class<?> probe;

    @BeforeAll static void compileActualNativeProbe() throws Exception {
        int result=ToolProvider.getSystemJavaCompiler().run(null,null,null,
                "-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),
                Path.of("tools/WindowsUpgradeDataProbe.java").toAbsolutePath().toString());
        assertEquals(0,result,"The actual native upgrade probe must compile");
        loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},WindowsUpgradeDataProbeTest.class.getClassLoader());
        probe=loader.loadClass("WindowsUpgradeDataProbe");
    }
    @AfterAll static void closeLoader() throws Exception {if(loader!=null)loader.close();}
    @AfterEach void clear() {System.clearProperty("vncode.appdata.dir");}

    private Object invoke(String method,Class<?>[] types,Object... args) throws Exception {
        try{return probe.getMethod(method,types).invoke(null,args);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception cause)throw cause;throw failure;}
    }
    private void seedLegacyShape() throws Exception {
        invoke("main",new Class<?>[]{String[].class},(Object)new String[]{"seed",data.toString()});
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("DROP INDEX idx_kiz_codes_priority_circulation");
            s.execute("ALTER TABLE kiz_codes DROP COLUMN source_priority");
        }
    }
    private String fingerprint() throws Exception {return (String)invoke("fingerprint",new Class<?>[]{Path.class},data);}

    @Test void additivePriorityKeepsEveryLegacyValueAndStillDetectsStockAndTemplateChanges() throws Exception {
        seedLegacyShape();String before=fingerprint();
        Database.initDatabase();
        assertEquals(before,fingerprint(),"Adding a defaulted column must preserve legacy data fingerprints");
        invoke("verifyLegacyKizPriority",new Class<?>[]{Path.class},data);
        try(var c=Database.getConnection();var s=c.createStatement()) {
            s.execute("UPDATE kiz_codes SET raw_code='changed-legacy-code' WHERE id=100");
            assertNotEquals(before,fingerprint(),"Changes to legacy paid marking data must still fail preservation");
            s.execute("UPDATE kiz_codes SET raw_code='fixture-code-available' WHERE id=100");
            assertEquals(before,fingerprint());
            s.execute("UPDATE print_templates SET name='changed-template' WHERE name='upgrade-personal-fixture'");
            assertNotEquals(before,fingerprint(),"Changes to saved templates must still fail preservation");
        }
    }
    @Test void rejectsMissingOrIncorrectLegacyStockPriority() throws Exception {
        seedLegacyShape();
        assertThrows(java.sql.SQLException.class,()->invoke("verifyLegacyKizPriority",new Class<?>[]{Path.class},data));
        Database.initDatabase();invoke("verifyLegacyKizPriority",new Class<?>[]{Path.class},data);
        try(var c=Database.getConnection();var s=c.createStatement()) {s.execute("UPDATE kiz_codes SET source_priority=0 WHERE id=100");}
        assertThrows(IllegalStateException.class,()->invoke("verifyLegacyKizPriority",new Class<?>[]{Path.class},data));
    }
}
