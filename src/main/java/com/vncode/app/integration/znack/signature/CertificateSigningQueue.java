package com.vncode.app.integration.znack.signature;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.*;

/** Resolved fingerprints share a queue across every provider; unresolved selectors use a global gate. */
public final class CertificateSigningQueue {
    private static final ReentrantReadWriteLock SELECTOR_GATE = new ReentrantReadWriteLock(true);
    private static final ConcurrentHashMap<String,ReentrantLock> CERTIFICATES = new ConcurrentHashMap<>();
    private CertificateSigningQueue() {}
    @FunctionalInterface public interface Work<T> { T run() throws CryptoProException; }
    public static <T> T run(String selector,Work<T> work)throws CryptoProException {
        String fingerprint=selector==null?"":selector.replaceAll("\\s","").toUpperCase(Locale.ROOT);
        boolean resolved=fingerprint.matches("[A-F0-9]{40}");
        Lock gate=resolved?SELECTOR_GATE.readLock():SELECTOR_GATE.writeLock();
        Lock certificate=resolved?CERTIFICATES.computeIfAbsent(fingerprint,k->new ReentrantLock(true)):null;
        boolean gated=false,locked=false;
        try{
            gate.lockInterruptibly();gated=true;
            if(certificate!=null){certificate.lockInterruptibly();locked=true;}
            return work.run();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Certificate signing queue was interrupted");}
        finally{if(locked)certificate.unlock();if(gated)gate.unlock();}
    }
}
