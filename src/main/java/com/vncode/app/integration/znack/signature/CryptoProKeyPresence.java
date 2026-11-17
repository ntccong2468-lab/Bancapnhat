package com.vncode.app.integration.znack.signature;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Non-signing certificate/container reads. Only a positively identified absent removable key defers. */
final class CryptoProKeyPresence {
    enum State { PRESENT, ABSENT, UNKNOWN }
    private record Cached<T>(T value,long at) {
        boolean fresh(long now,long ttl) { return now>=at&&now-at<ttl; }
    }
    private static final Duration TIMEOUT=Duration.ofSeconds(15);
    private static final ConcurrentHashMap<String,Cached<String>> CERTIFICATES=new ConcurrentHashMap<>();
    private static volatile Cached<List<String>> containers;
    static LongSupplier clock=System::currentTimeMillis;
    private CryptoProKeyPresence() { }

    static void requireConnected(String selector,CryptoProCommandRunner runner)throws CryptoProException {
        String fingerprint=selector==null?"":selector.replaceAll("[\\s:]","").toLowerCase(Locale.ROOT);
        if(!fingerprint.matches("[a-f0-9]{40}")) return;
        if(state(fingerprint,runner)==State.ABSENT)
            throw new CryptoProException(CryptoProErrorCode.KEY_NOT_CONNECTED,
                    "The certificate's USB key is not connected; no signing dialog was opened.");
    }

    static State state(String fingerprint,CryptoProCommandRunner runner) {
        String container=certificateContainer(fingerprint,runner,600_000);
        if(container.isEmpty()||fixedStorage(container)) return State.UNKNOWN;
        List<String> connected=connectedContainers(runner);
        if(connected==null) return State.UNKNOWN;
        if(matches(container,connected)) return State.PRESENT;
        // A previously cached certificate may have been rebound to another reader/container.
        container=certificateContainer(fingerprint,runner,300_000);
        if(container.isEmpty()||fixedStorage(container)) return State.UNKNOWN;
        return matches(container,connected)?State.PRESENT:State.ABSENT;
    }

    private static String certificateContainer(String fingerprint,CryptoProCommandRunner runner,long ttl) {
        long now=clock.getAsLong(); Cached<String> old=CERTIFICATES.get(fingerprint);
        if(old!=null&&old.fresh(now,ttl)) return old.value();
        String value="";
        try {
            var result=runner.run(List.of(runner.resolve(null,"certmgr"),"-list","-store","uMy",
                    "-thumbprint",fingerprint),TIMEOUT);
            if(result.exitCode()==0) value=containerOf(result.stdoutText());
        } catch(CryptoProException|RuntimeException unknown) { /* No absence proof. */ }
        CERTIFICATES.put(fingerprint,new Cached<>(value,now)); return value;
    }

    private static synchronized List<String> connectedContainers(CryptoProCommandRunner runner) {
        long now=clock.getAsLong(); var old=containers;
        if(old!=null&&old.fresh(now,30_000)) return old.value();
        List<String> value=null;
        try {
            var result=runner.run(List.of(runner.resolve(null,"csptest"),"-keyset","-enum_cont","-fqcn",
                    "-verifyc","-uniq","-silent"),TIMEOUT);
            if(result.exitCode()==0) value=containersOf(result.stdoutText());
        } catch(CryptoProException|RuntimeException unknown) { /* Unavailable read is not an absent USB key. */ }
        containers=new Cached<>(value,now); return value;
    }

    static String containerOf(String output) {
        if(output==null) return "";
        for(String line:output.split("\\R")) {
            int colon=line.indexOf(':'); if(colon<0) continue;
            String label=line.substring(0,colon).strip().toLowerCase(Locale.ROOT);
            if(label.equals("container")||label.equals("контейнер")) return line.substring(colon+1).strip();
        }
        return "";
    }

    static List<String> containersOf(String output) {
        var found=new ArrayList<String>();
        if(output!=null) for(String line:output.split("\\R")) {
            String value=line.strip(); int namespace=value.indexOf("\\\\.\\");
            if(namespace>=0) found.add(value.substring(namespace).strip());
        }
        return List.copyOf(found);
    }

    static boolean matches(String expected,List<String> connected) {
        String full=normal(expected), shortName=withoutReader(full);
        for(String candidate:connected) {
            String actual=normal(candidate), shortActual=withoutReader(actual);
            if(full.equals(actual)||full.equals(shortActual)||shortName.equals(shortActual)
                    ||actual.endsWith("\\"+full)) return true;
        }
        return false;
    }

    static boolean fixedStorage(String value) {
        String full=normal(value), shortName=withoutReader(full);
        return full.startsWith("\\\\.\\hdimage\\")||full.startsWith("\\\\.\\registry\\")
                ||shortName.startsWith("hdimage\\")||shortName.startsWith("registry\\");
    }
    private static String normal(String value) { return value==null?"":value.strip().replace('/','\\').toLowerCase(Locale.ROOT); }
    private static String withoutReader(String value) {
        if(!value.startsWith("\\\\.\\")) return value;
        int separator=value.indexOf('\\',4); return separator<0?value:value.substring(separator+1);
    }
    static void resetForTests() { CERTIFICATES.clear();containers=null;clock=System::currentTimeMillis; }
}
