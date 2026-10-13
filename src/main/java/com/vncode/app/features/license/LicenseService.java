package com.vncode.app.features.license;

import com.vncode.app.integration.license.LicenseApi;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static com.vncode.app.features.license.LicenseModels.*;

/** One session/worker for all shops. Network, key generation and DPAPI never run on FX. */
public final class LicenseService implements AutoCloseable {
    private final LicenseApi api;
    private final LicenseVerifier verifier;
    private final LicenseStore store;
    private final Clock wall;
    private final java.util.function.LongSupplier nanos;
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService timer;
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners=new CopyOnWriteArrayList<>();
    private final AtomicLong generation=new AtomicLong();
    private volatile State state;
    private volatile LicenseDeviceKeys keys;
    private volatile LicenseClock clock;
    private volatile Lease lease;
    private volatile long onlineDeadline;
    private volatile long leaseGeneration=-1;
    private CompletableFuture<Snapshot> initialization;
    private volatile boolean closed;
    private volatile boolean startupCheckPending;
    private final Object submissionLock=new Object();
    private volatile Snapshot snapshot=new Snapshot(Status.UNREGISTERED,"",0,0,0,0,"",false);
    private volatile CompletableFuture<Snapshot> refreshing;
    private final LicenseRefreshCadence refreshCadence=new LicenseRefreshCadence(()->ThreadLocalRandom.current().nextInt());

    public LicenseService(LicenseApi api,LicenseVerifier verifier,LicenseStore store,Clock wall,java.util.function.LongSupplier nanos) {
        this.api=api;this.verifier=verifier;this.store=store;this.wall=wall;this.nanos=nanos;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"vncode-license");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"vncode-license-timer");t.setDaemon(true);return t;});
    }
    public synchronized CompletableFuture<Snapshot> initialize() {
        if(initialization!=null)return initialization;
        initialization=submit(()->{
            State saved=store.load().orElse(null);
            if(saved==null){keys=LicenseDeviceKeys.create();clock=new LicenseClock(wall,nanos,0);state=new State(keys.publicKey(),keys.privateKey(),null,null,null,null,clock.now(),0,"");store.save(state);publish(Status.UNREGISTERED,"");}
            else {
                keys=LicenseDeviceKeys.restore(saved.publicKey(),saved.privateKey());clock=new LicenseClock(wall,nanos,saved.maxObservedEpoch());state=saved;startupCheckPending=!blank(saved.accessToken())&&blank(saved.denialCode());persist();
                if(!blank(saved.denialCode()))publish(Status.DENIED,saved.denialCode());
                else if(saved.account()!=null && saved.envelope()!=null) {
                    try { lease=verifier.verify(saved.envelope(),saved.account().id(),keys.hash(),saved.minRevision(),clock.now(),false);if(clock.rollbackDetected())throw new IOException("LICENSE_CLOCK_ROLLBACK");leaseGeneration=generation.get();publish(Status.OFFLINE,""); }
                    catch(IOException failure){publish(failure.getMessage().equals("LICENSE_EXPIRED")?Status.EXPIRED:Status.UNVERIFIED,safeCode(failure));}
                } else if(!blank(saved.registrationToken())&&saved.account()!=null)publish(registrationStatus(saved.account().status()),"");
                else if(!blank(saved.registrationToken()))publish(Status.VERIFYING_EMAIL,"");
                else publish(Status.UNREGISTERED,"");
            }
            return snapshot;
        }).thenCompose(s->{
            if(!closed&&state!=null&&!blank(state.accessToken())&&blank(state.denialCode())&&verifier.configured()){startupCheckPending=true;publish(Status.CHECKING,"");return refresh();}
            return CompletableFuture.completedFuture(s);
        }).thenApply(s->{if(!closed){try{timer.scheduleWithFixedDelay(this::tick,ThreadLocalRandom.current().nextLong(10,61),60,TimeUnit.SECONDS);}catch(RejectedExecutionException ignored){}}return s;});
        return initialization;
    }
    public Snapshot snapshot(){return snapshot;}
    public AutoCloseable subscribe(Consumer<Snapshot> listener){listeners.add(listener);listener.accept(snapshot);return ()->listeners.remove(listener);}
    public int listenerCount(){return listeners.size();}
    public boolean hasPendingTasks(){return worker.getActiveCount()>0||!worker.getQueue().isEmpty();}
    public CompletableFuture<Snapshot> startRegistration(String gmail) {
        String email=gmail==null?"":gmail.strip().toLowerCase(Locale.ROOT);
        if(!email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\\.com")||email.length()>254)return failed("GMAIL_REQUIRED");
        return transition(email,()->{
            requireKeys();state=new State(keys.publicKey(),keys.privateKey(),null,null,new Account("",email,"verifying",0),null,clock.now(),0,"");persist();var result=api.start(email);ensureCurrent();
            state=new State(keys.publicKey(),keys.privateKey(),result.token(),null,new Account("",email,"verifying",0),null,clock.now(),0,"");
            persist();publish(Status.VERIFYING_EMAIL,"");return snapshot;
        });
    }
    public CompletableFuture<Snapshot> verifyEmail(String otp) {
        if(otp==null||!otp.matches("[0-9]{6}"))return failed("INVALID_CODE");
        return submit(()->{
            requireRegistration();var result=api.verify(state.registrationToken(),otp);
            updateRegistration(result);persist();return snapshot;
        });
    }
    public CompletableFuture<Snapshot> checkStatus() {
        State saved=state;if(saved!=null&&!blank(saved.accessToken()))return refresh();
        return submit(()->{requireRegistration();updateRegistration(api.status(state.registrationToken()));persist();return snapshot;});
    }
    public CompletableFuture<Snapshot> activate(String code) {
        if(code==null||!code.strip().matches("(?i)VNC(?:-[A-F0-9]{4}){8}"))return failed("INVALID_LICENSE");
        String normalized=code.strip().toUpperCase(Locale.ROOT);
        return submit(()->{
            requireRegistration();if(!verifier.configured())throw new IOException("LICENSE_TRUST_NOT_CONFIGURED");
            var challenge=api.challenge(state.registrationToken(),keys.publicKey(),"activate");validateChallenge(challenge,"activate");
            accept(api.activate(state.registrationToken(),challenge,keys.publicKey(),keys.sign(challenge.message()),normalized),true);
            return snapshot;
        });
    }
    public synchronized CompletableFuture<Snapshot> refresh() {
        if(refreshing!=null&&!refreshing.isDone())return refreshing;
        refreshing=submit(()->{
            boolean successful=false;
            try{
                requireKeys();if(state==null||blank(state.accessToken()))throw new IOException("SESSION_EXPIRED");
                if(!verifier.configured())throw new IOException("LICENSE_TRUST_NOT_CONFIGURED");
                var challenge=api.challenge(state.accessToken(),keys.publicKey(),"renew");validateChallenge(challenge,"renew");
                accept(api.renew(state.accessToken(),challenge,keys.publicKey(),keys.sign(challenge.message())),false);successful=true;return snapshot;
            }finally{
                Long expected=operationGeneration.get();if(expected!=null&&generation.get()==expected&&!closed)refreshCadence.completed(nanos.getAsLong(),successful);
            }
        });return refreshing;
    }
    public CompletableFuture<Snapshot> logout() {
        return transition(null,()->{requireKeys();state=new State(keys.publicKey(),keys.privateKey(),null,null,null,null,clock.now(),0,"");persist();publish(Status.UNREGISTERED,"");return snapshot;});
    }
    public boolean allows(Permission permission) {
        Lease current=lease;State saved=state;LicenseClock time=clock;
        if(closed||startupCheckPending||leaseGeneration!=generation.get()||current==null||saved==null||!blank(saved.denialCode())||time==null||time.rollbackDetected()||!current.permissions().contains(permission))return false;
        long now=time.now();return now<current.subscriptionExpiresAt() && (now<onlineDeadline||current.offlineHours()>0&&now<current.expiresAt());
    }
    public int maxShops(){return lease==null?0:lease.maxShops();}
    private void validateChallenge(LicenseApi.Challenge challenge,String action)throws IOException {
        long now=clock.now();if(challenge.id()==null||challenge.id().isBlank()||challenge.message()==null||challenge.message().length()>4096||challenge.expiresAt()<=now||challenge.expiresAt()>now+300)throw LicenseJson.invalid();
        String[] parts=challenge.message().split("\n",-1);if(parts.length!=4||!parts[0].equals("vncode-device-v1")||!parts[1].equals(action)||!parts[2].equals(challenge.id())||!parts[3].matches("[A-Za-z0-9_-]{16,256}"))throw LicenseJson.invalid();
    }
    private void accept(LicenseApi.Grant grant,boolean activating)throws IOException {
        ensureCurrent();
        if(state.account()==null||!grant.account().email().equals(state.account().email())||!grant.account().status().equals("approved"))throw LicenseJson.invalid();
        if(!activating&&(!grant.account().id().equals(state.account().id())))throw LicenseJson.invalid();
        long min=activating&& !grant.account().id().equals(state.account().id())?0:state.minRevision();
        Lease verified=verifier.verify(grant.envelope(),grant.account().id(),keys.hash(),min,clock.now(),true);
        if(grant.account().expiresAt()!=verified.subscriptionExpiresAt()||grant.onlineValidUntil()<=clock.now()||grant.onlineValidUntil()>Math.min(verified.subscriptionExpiresAt(),verified.issuedAt()+300))throw LicenseJson.invalid();
        if(clock.rollbackDetected())throw new IOException("LICENSE_CLOCK_ROLLBACK");
        String token=activating?grant.accessToken():state.accessToken();if(blank(token))throw LicenseJson.invalid();
        state=new State(keys.publicKey(),keys.privateKey(),state.registrationToken(),token,grant.account(),grant.envelope(),clock.now(),verified.revision(),"");
        persist();startupCheckPending=false;lease=verified;leaseGeneration=operationGeneration.get()==null?generation.get():operationGeneration.get();onlineDeadline=grant.onlineValidUntil();publish(Status.ACTIVE,"");
        refreshCadence.completed(nanos.getAsLong(),true);
    }
    private void updateRegistration(LicenseApi.RegistrationStatus result)throws IOException {
        ensureCurrent();
        if(state.account()==null||!result.email().equals(state.account().email()))throw LicenseJson.invalid();
        state=new State(keys.publicKey(),keys.privateKey(),state.registrationToken(),null,new Account("",result.email(),result.status(),0),null,clock.now(),0,"");lease=null;onlineDeadline=0;
        publish(registrationStatus(result.status()),"");
    }
    private Status registrationStatus(String status)throws IOException {
        return switch(status){case "verifying"->Status.VERIFYING_EMAIL;case "pending"->Status.PENDING;case "approved"->Status.APPROVED;case "suspended"->Status.DENIED;default->throw LicenseJson.invalid();};
    }
    private void requireKeys()throws IOException {if(keys==null||state==null||clock==null)throw new IOException("LICENSE_STORE_INVALID");}
    private void requireRegistration()throws IOException {requireKeys();if(blank(state.registrationToken()))throw new IOException("REGISTRATION_EXPIRED");}
    private void ensureCurrent()throws IOException { Long expected=operationGeneration.get();if(closed||expected!=null&&generation.get()!=expected)throw new IOException("LICENSE_OPERATION_CANCELLED"); }
    private void persist()throws IOException {
        ensureCurrent();
        if(clock==null||state==null)return;
        State s=state;state=new State(s.publicKey(),s.privateKey(),s.registrationToken(),s.accessToken(),s.account(),s.envelope(),clock.maxObservedEpoch(),s.minRevision(),s.denialCode());store.save(state);
    }
    private final ThreadLocal<Long> operationGeneration=new ThreadLocal<>();
    private CompletableFuture<Snapshot> transition(String email,Callable<Snapshot> task) {
        synchronized(submissionLock) {
            generation.incrementAndGet();lease=null;onlineDeadline=0;startupCheckPending=false;
            refreshCadence.reset();
            if(keys!=null&&clock!=null)state=new State(keys.publicKey(),keys.privateKey(),null,null,email==null?null:new Account("",email,"verifying",0),null,clock.now(),0,"");
            refreshing=null;publish(email==null?Status.UNREGISTERED:Status.VERIFYING_EMAIL,"");
            Runnable queued;while((queued=worker.getQueue().poll())!=null)if(queued instanceof Request request)request.future.complete(snapshot);
            return submit(task);
        }
    }
    private CompletableFuture<Snapshot> submit(Callable<Snapshot> task) {
        synchronized(submissionLock) {
            var request=new Request(generation.get(),task);
            if(closed){request.future.complete(snapshot);return request.future;}
            try {worker.execute(request);}
            catch(RejectedExecutionException full){request.future.completeExceptionally(new IOException("LICENSE_BUSY"));}
            return request.future;
        }
    }
    private final class Request implements Runnable {
        private final long expected;
        private final Callable<Snapshot> task;
        private final CompletableFuture<Snapshot> future=new CompletableFuture<>();
        private Request(long expected,Callable<Snapshot> task){this.expected=expected;this.task=task;}
        public void run() {
            if(closed||generation.get()!=expected){future.complete(snapshot);return;}
            operationGeneration.set(expected);setBusy(true);
            Lease previousLease=lease;long previousDeadline=onlineDeadline;
            try {task.call();}
            catch(Exception failure){if(generation.get()==expected&&!closed)handleFailure(failure,previousLease,previousDeadline);}
            finally {operationGeneration.remove();if(generation.get()==expected&&!closed)setBusy(false);future.complete(snapshot);}
        }
    }
    private void handleFailure(Exception failure,Lease previousLease,long previousDeadline) {
        String code=safeCode(failure);startupCheckPending=false;
        boolean transientError=failure instanceof LicenseApi.Failure f?f.transientFailure():failure instanceof java.net.SocketTimeoutException||failure instanceof java.net.SocketException||failure instanceof java.net.UnknownHostException;
        if(transientError&&previousLease!=null&&state!=null&&blank(state.denialCode())) {
            lease=previousLease;onlineDeadline=previousDeadline;
            if(allows(Permission.SELLER_API)){try{persist();publish(Status.OFFLINE,code);return;}catch(IOException unavailable){code="LICENSE_STORE_INVALID";}}
        }
        boolean denied=failure instanceof LicenseApi.Failure f&&(f.status()==401||f.status()==403);
        if(denied || code.equals("LICENSE_PROTOCOL_INVALID") || code.equals("LICENSE_CLOCK_ROLLBACK") || code.equals("SIGNING_NOT_CONFIGURED") || code.equals("LICENSE_TRUST_NOT_CONFIGURED")) {
            lease=null;onlineDeadline=0;
            if(state!=null){State s=state;state=new State(s.publicKey(),s.privateKey(),s.registrationToken(),s.accessToken(),s.account(),s.envelope(),clock==null?s.maxObservedEpoch():clock.now(),s.minRevision(),code);try{persist();}catch(IOException ignored){try{store.blockCachedRights();}catch(IOException blocked){/* Disk failure remains a visible readiness blocker. */}code="LICENSE_STORE_INVALID";}}
        }
        // OTP/input errors keep the current registration workflow available for retry.
        if(snapshot.status()==Status.VERIFYING_EMAIL||snapshot.status()==Status.PENDING||snapshot.status()==Status.APPROVED)publish(snapshot.status(),code);
        else {lease=null;onlineDeadline=0;publish(code.equals("LICENSE_EXPIRED")?Status.EXPIRED:denied?Status.DENIED:Status.UNVERIFIED,code);}
    }
    private void publish(Status status,String code) {
        Long expected=operationGeneration.get();if(expected!=null&&generation.get()!=expected)return;
        State s=state;Lease l=lease;
        snapshot=new Snapshot(status,s!=null&&s.account()!=null?s.account().email():"",s!=null&&s.account()!=null?s.account().expiresAt():0,l==null?0:l.expiresAt(),l==null?0:l.maxDevices(),l==null?0:l.maxShops(),code,snapshot.busy());
        notifyListeners();
    }
    private void setBusy(boolean busy){Snapshot s=snapshot;snapshot=new Snapshot(s.status(),s.email(),s.expiresAt(),s.offlineExpiresAt(),s.maxDevices(),s.maxShops(),s.errorCode(),busy);notifyListeners();}
    private void notifyListeners(){for(var listener:listeners){try{listener.accept(snapshot);}catch(RuntimeException ignored){}}}
    private CompletableFuture<Snapshot> failed(String code){publish(snapshot.status(),code);return CompletableFuture.completedFuture(snapshot);}
    private void tick(){
        if(closed)return;
        submit(()->{persist();return snapshot;}).thenAccept(saved->{
            if(!closed&&state!=null&&!blank(state.accessToken())&&!saved.errorCode().equals("LICENSE_STORE_INVALID")&&refreshCadence.due(nanos.getAsLong()))refresh();
        });
    }
    private static String safeCode(Exception error){String code=error.getMessage();return code!=null&&code.matches("[A-Z][A-Z0-9_]{1,80}")?code:"LICENSE_UNAVAILABLE";}
    private static boolean blank(String s){return s==null||s.isBlank();}
    @Override public void close(){closed=true;generation.incrementAndGet();lease=null;onlineDeadline=0;listeners.clear();timer.shutdownNow();for(Runnable queued:worker.shutdownNow())if(queued instanceof Request request)request.future.complete(snapshot);}
}
