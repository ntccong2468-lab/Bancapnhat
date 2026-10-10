import com.vncode.app.features.gs1.Gs1Repository;
import com.vncode.app.integration.gs1.Gs1MailAccount;
import com.vncode.app.shared.WindowsSecretProtector;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;

/** Native DPAPI/storage probe: synthetic data only, no mail or GS1 network calls. */
public final class WindowsGs1Probe {
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!WindowsSecretProtector.supported())throw new IllegalStateException("Require Windows and an isolated probe directory");
        Path root=Path.of(args[1]).toAbsolutePath();Files.createDirectories(root);
        var properties=new Properties();try(var input=WindowsGs1Probe.class.getResourceAsStream("/app.properties")){properties.load(input);}
        if(!args[0].equals(properties.getProperty("app.version")))throw new IllegalStateException("Wrong GS1 probe version");
        var protector=new WindowsSecretProtector();
        // Exercise a payload beyond the typical Windows pipe buffer, with non-ASCII text.
        String secret="VN-code-CI-"+UUID.randomUUID()+"-пароль-"+"fixture".repeat(700);
        String encrypted=protector.protect(secret);
        if(encrypted.contains(secret)||!secret.equals(protector.reveal(encrypted)))throw new IllegalStateException("DPAPI round trip failed");
        Path file=root.resolve("gs1-fixture.db");var repo=new Gs1Repository(file);
        var account=new Gs1MailAccount("smtp.example.invalid",465,"imap.example.invalid",993,"ci-fixture","ci@example.invalid",encrypted);
        repo.saveMailAccount("7707083893",account);
        var reopened=new Gs1Repository(file);
        if(!secret.equals(protector.reveal(reopened.mailAccount("7707083893").orElseThrow().protectedPassword())))throw new IllegalStateException("Protected password did not persist");
        if(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1).contains(secret))throw new IllegalStateException("A plaintext credential was stored");
        var request=repo.createRequest("7707083893","RENEWAL","mail@gs1ru.org","CI fixture","Not sent");
        repo.addMessage(request.inn(),request.id(),"unverified-fixture",false,"mail@gs1ru.org","Synthetic From header, not an authenticated sender",false);
        // Legacy From-based invoice flags must not become trusted after the additive upgrade.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()){s.executeUpdate("UPDATE gs1_messages SET invoice=1");}
        if(new Gs1Repository(file).hasConfirmedInvoice(request.inn(),request.id()))throw new IllegalStateException("Unverified legacy invoice enabled payment proof");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement();var result=s.executeQuery("PRAGMA integrity_check")){if(!result.next()||!"ok".equals(result.getString(1)))throw new IllegalStateException("GS1 fixture integrity failed");}
        JsonObject report=new JsonObject();report.addProperty("appName","VN code");report.addProperty("version",args[0]);report.addProperty("result","passed");
        report.addProperty("dpapiRoundTrip",true);report.addProperty("protectedPasswordPersistence",true);report.addProperty("unverifiedInvoiceBlocked",true);report.addProperty("liveGS1Mutations",false);
        System.out.println(report);
    }
}
