package com.vncode.app.integration.znack.signature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Wb1326KeyPresenceTest {
    private static final String CERT="A".repeat(40);
    @AfterEach void cleanup() { CryptoProKeyPresence.resetForTests(); }

    @Test void knownMissingUsbContainerDefersWithoutAnySigningCommand() {
        var commands=new AtomicInteger();
        var runner=runner("Container : \\\\.\\Aktiv Reader\\fixture-container\n","\\\\.\\Other Reader\\different-container\n",commands);
        var failure=assertThrows(CryptoProException.class,()->CryptoProKeyPresence.requireConnected(CERT,runner));
        assertEquals(CryptoProErrorCode.KEY_NOT_CONNECTED,failure.code());
        assertEquals(2,commands.get());
    }
    @Test void matchingContainerCaseAndReaderCanBeRecognized() throws Exception {
        var commands=new AtomicInteger();
        var runner=runner("Контейнер : \\\\.\\Reader 1\\FIXTURE\n","\\\\.\\Reader 2\\fixture\n",commands);
        CryptoProKeyPresence.requireConnected(CERT,runner);
        CryptoProKeyPresence.requireConnected(CERT,runner);
        assertEquals(2,commands.get(),"Public reads are cached; no sign/PIN command is used");
    }
    @Test void unavailableCertificateReadIsUnknownRatherThanEvidenceOfAbsence() throws Exception {
        var commands=new AtomicInteger();
        CryptoProKeyPresence.requireConnected(CERT,runner("No matching public certificate\n","",commands));
        assertEquals(1,commands.get());
    }
    @Test void registryAndDiskKeysAreNotDeclaredMissingRemovableTokens() throws Exception {
        var commands=new AtomicInteger();
        CryptoProKeyPresence.requireConnected(CERT,runner("Container : \\\\.\\REGISTRY\\fixture\n","",commands));
        assertEquals(1,commands.get());assertTrue(CryptoProKeyPresence.fixedStorage("HDIMAGE\\fixture"));
    }
    @Test void publicProbeNeverUsesTheSigningSubcommandAndHasABoundedTimeout() throws Exception {
        var runner=new CryptoProCommandRunner() {
            @Override public String resolve(String override,String command) { return command; }
            @Override public Result run(List<String> command,Duration timeout) {
                assertFalse(command.contains("-sign"));assertTrue(timeout.compareTo(Duration.ofSeconds(15))<=0);
                return new Result(0,"Container : HDIMAGE\\fixture\n".getBytes(StandardCharsets.UTF_8),new byte[0]);
            }
        };
        CryptoProKeyPresence.requireConnected(CERT,runner);
    }
    private CryptoProCommandRunner runner(String certificate,String containers,AtomicInteger calls) {
        return new CryptoProCommandRunner() {
            @Override public String resolve(String override,String command) { return command; }
            @Override public Result run(List<String> command,Duration timeout) {
                calls.incrementAndGet();assertFalse(command.contains("-sign"));
                return new Result(0,(command.getFirst().equals("certmgr")?certificate:containers).getBytes(StandardCharsets.UTF_8),new byte[0]);
            }
        };
    }
}
