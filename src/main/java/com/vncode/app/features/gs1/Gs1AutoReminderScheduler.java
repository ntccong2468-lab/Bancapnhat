package com.vncode.app.features.gs1;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One bounded mail worker while VNCode is open. Closing never races a newly started delivery. */
public final class Gs1AutoReminderScheduler implements AutoCloseable {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"vncode-gs1-reminder");thread.setDaemon(true);return thread;});
    private final Object lifecycle=new Object();
    private ScheduledFuture<?> scheduled;
    private boolean running,closed;
    private Gs1AutoReminderService service;
    private final Runnable dueCheck;
    private final long initialDelayMs,intervalMs;
    public Gs1AutoReminderScheduler(){
        initialDelayMs=30_000;intervalMs=600_000;
        dueCheck=()->{if(service==null)service=new Gs1AutoReminderService(new Gs1Repository());service.runDue();};
    }
    Gs1AutoReminderScheduler(Runnable dueCheck,long initialDelayMs,long intervalMs){
        if(initialDelayMs<0||intervalMs<1)throw new IllegalArgumentException("Invalid reminder interval");
        this.dueCheck=java.util.Objects.requireNonNull(dueCheck);this.initialDelayMs=initialDelayMs;this.intervalMs=intervalMs;
    }
    public void start(){synchronized(lifecycle){if(closed||scheduled!=null)return;scheduled=worker.scheduleWithFixedDelay(this::tick,initialDelayMs,intervalMs,TimeUnit.MILLISECONDS);}}
    private void tick(){
        synchronized(lifecycle){if(closed)return;running=true;}
        try{dueCheck.run();}
        catch(RuntimeException unavailable){/* Durable consent/checkpoints remain for the next check; no response or PII is logged. */}
        finally{synchronized(lifecycle){running=false;}}
    }
    /** A close request stops future checks only when no mail operation is in progress. */
    public boolean tryStop(){synchronized(lifecycle){if(running)return false;close();return true;}}
    @Override public void close(){synchronized(lifecycle){closed=true;if(scheduled!=null)scheduled.cancel(false);worker.shutdown();}}
}
