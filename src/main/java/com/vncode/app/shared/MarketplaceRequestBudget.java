package com.vncode.app.shared;

import java.io.InterruptedIOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Acquire for one seller HTTP attempt/body, never during backoff or signing. */
public final class MarketplaceRequestBudget {
    private static final Semaphore PERMITS=new Semaphore(2,true);
    private static final Semaphore BACKGROUND=new Semaphore(1,true);
    private static final ThreadLocal<Boolean> SLOW=ThreadLocal.withInitial(()->false);
    private MarketplaceRequestBudget(){}
    /** Background catalog/registry workers leave one HTTP slot available to continuous FBS. */
    public static Lane lane(boolean background){return new Lane(background);}
    public static final class Lane implements AutoCloseable {
        private final boolean prior=SLOW.get();
        private Lane(boolean background){SLOW.set(background);}
        @Override public void close(){if(prior)SLOW.set(true);else SLOW.remove();}
    }
    public static Slot acquire()throws InterruptedIOException {
        boolean slow=SLOW.get(),reserved=false;
        try{if(slow){BACKGROUND.acquire();reserved=true;}PERMITS.acquire();return new Slot(slow);}
        catch(InterruptedException interrupted){if(reserved)BACKGROUND.release();Thread.currentThread().interrupt();throw new InterruptedIOException("Marketplace request cancelled before sending");}
    }
    public static int activeCount(){return 2-PERMITS.availablePermits();}
    public static final class Slot implements AutoCloseable {
        private final AtomicBoolean released=new AtomicBoolean();
        private final boolean slow;
        private Slot(boolean slow){this.slow=slow;}
        public void close(){if(released.compareAndSet(false,true)){PERMITS.release();if(slow)BACKGROUND.release();}}
    }
}
