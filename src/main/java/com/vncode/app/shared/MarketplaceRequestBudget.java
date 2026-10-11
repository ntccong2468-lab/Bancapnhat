package com.vncode.app.shared;

import java.io.InterruptedIOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Acquire for one seller HTTP attempt/body, never during backoff or signing. */
public final class MarketplaceRequestBudget {
    private static final Semaphore PERMITS=new Semaphore(2,true);
    private MarketplaceRequestBudget(){}
    public static Slot acquire()throws InterruptedIOException {
        try{PERMITS.acquire();return new Slot();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Marketplace request cancelled before sending");}
    }
    public static int activeCount(){return 2-PERMITS.availablePermits();}
    public static final class Slot implements AutoCloseable {
        private final AtomicBoolean released=new AtomicBoolean();
        private Slot(){}
        public void close(){if(released.compareAndSet(false,true))PERMITS.release();}
    }
}
