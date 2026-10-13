package com.vncode.app.features.print;

import com.vncode.app.features.kiz.KizService;
import com.vncode.app.features.license.LicenseGate.LicenseDeniedException;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/** Two global writers, one request stream per shop; rotate shops after each order. */
public final class KizAttachmentCoordinator {
    private static final Logger LOGGER=LoggerFactory.getLogger(KizAttachmentCoordinator.class);
    private static final int MAX_WORKERS=2;
    private static final Executor DEFAULT_EXECUTOR=Executors.newFixedThreadPool(MAX_WORKERS,r->{Thread t=new Thread(r,"vncode-kiz-attachment");t.setDaemon(true);return t;});
    private static final KizAttachmentCoordinator INSTANCE=new KizAttachmentCoordinator(DEFAULT_EXECUTOR,new AttachmentClient(){
        public KizService.RemoveMetaResult remove(String token,long id)throws IOException{return KizService.removeSgtinFromOrder(token,id);}
        public KizService.AttachCodeResult attach(String token,long id,String code)throws IOException{return KizService.addDataMatrixCodeToOrder(token,id,code);}
        public Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids)throws IOException{return KizService.getSgtinMetadata(token,ids);}
        public void requireCurrentShop(int shopId,String token)throws IOException{KizService.requireCurrentShop(shopId,token);}
        public KizService.RemoveMetaResult remove(int shopId,String token,long id)throws IOException{return KizService.removeSgtinFromOrder(shopId,token,id);}
        public KizService.AttachCodeResult attach(int shopId,String token,long id,String code)throws IOException{return KizService.addDataMatrixCodeToOrder(shopId,token,id,code);}
        public KizService.RemoveMetaResult remove(int shopId,String token,long id,BooleanSupplier cancelled)throws IOException{return KizService.removeSgtinFromOrder(shopId,token,id,cancelled);}
        public KizService.AttachCodeResult attach(int shopId,String token,long id,String code,BooleanSupplier cancelled)throws IOException{return KizService.addDataMatrixCodeToOrder(shopId,token,id,code,cancelled);}
    },new KizAttachmentCheckpointRepository(),System::nanoTime);
    private final Object lock=new Object();
    private final Map<JobKey,Job> activeJobs=new ConcurrentHashMap<>();
    private final Map<OrderKey,Job> orderOwners=new HashMap<>();
    private final Map<OrderKey,String> failedAttachments=new ConcurrentHashMap<>();
    private final LinkedHashMap<Integer,ArrayDeque<Job>> ready=new LinkedHashMap<>();
    private final Set<Integer> runningShops=new HashSet<>();
    private final Set<OrderKey> reconciling=new HashSet<>();
    private final CopyOnWriteArrayList<Consumer<KizAttachmentProgress>> listeners=new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final AttachmentClient client;
    private final AttachmentJournal journal;
    private final LongSupplier nanos;
    private int workers;

    KizAttachmentCoordinator(Executor executor,AttachmentClient client){this(executor,client,new MemoryJournal(),System::nanoTime);}
    KizAttachmentCoordinator(Executor executor,AttachmentClient client,AttachmentJournal journal,LongSupplier nanos){this.executor=Objects.requireNonNull(executor);this.client=Objects.requireNonNull(client);this.journal=Objects.requireNonNull(journal);this.nanos=Objects.requireNonNull(nanos);}
    public static KizAttachmentCoordinator getInstance(){return INSTANCE;}
    public String getAttachmentError(int shopId,long orderId){return failedAttachments.get(new OrderKey(shopId,orderId));}
    public void clearError(int shopId,long orderId){failedAttachments.remove(new OrderKey(shopId,orderId));}
    public void clearErrors(int shopId){failedAttachments.keySet().removeIf(key->key.shopId()==shopId);}
    /** Legacy callers must not observe/clear a different shop when IDs overlap. */
    @Deprecated public String getAttachmentError(long orderId){var matches=failedAttachments.entrySet().stream().filter(e->e.getKey().orderId()==orderId).toList();return matches.size()==1?matches.getFirst().getValue():null;}
    @Deprecated public void clearError(long orderId){var matches=failedAttachments.keySet().stream().filter(k->k.orderId()==orderId).toList();if(matches.size()==1)failedAttachments.remove(matches.getFirst());}
    @Deprecated public void clearErrors(){var shops=failedAttachments.keySet().stream().map(OrderKey::shopId).distinct().toList();if(shops.size()==1)clearErrors(shops.getFirst());}
    public void addListener(Consumer<KizAttachmentProgress> listener){if(listener!=null)listeners.addIfAbsent(listener);}
    public void removeListener(Consumer<KizAttachmentProgress> listener){listeners.remove(listener);}
    public boolean hasActiveJobs(){return !activeJobs.isEmpty();}
    public boolean hasActiveJobForShop(int shopId){return activeJobs.keySet().stream().anyMatch(key->key.shopId()==shopId);}
    public boolean hasActiveJobForSupply(int shopId,String supplyId){return findActiveJobForSupply(shopId,supplyId).isPresent();}
    public Optional<KizAttachmentProgress> findActiveJobForSupply(int shopId,String supplyId){if(supplyId==null||supplyId.isBlank())return Optional.empty();Job job=activeJobs.get(new JobKey(shopId,supplyId));return job==null?Optional.empty():Optional.of(job.progress);}

    public void enqueue(Shop shop,String supplyId,String supplyName,List<OrderExportWorkflow.KizAttachmentAssignment> assignments){
        if(shop==null||assignments==null||assignments.isEmpty())return;
        MarketplaceGuard.requireWildberries(shop);LicenseRuntime.require(Permission.KIZ);
        if(shop.getId()<=0||assignments.size()>100000)throw new IllegalArgumentException("KIZ_JOB_INVALID");
        List<OrderExportWorkflow.KizAttachmentAssignment> copy=assignments.stream().map(a->{
            if(a==null)throw new IllegalArgumentException("KIZ_ASSIGNMENT_DUPLICATE_OR_INVALID");
            var source=a.sourceKiz();var frozen=source==null?null:new com.vncode.app.models.Kiz(source.getId(),source.getCode(),source.getReservationToken());
            return new OrderExportWorkflow.KizAttachmentAssignment(a.orderId(),a.kizCode(),frozen,a.replaceExisting());
        }).toList();
        Set<Long> ids=new HashSet<>();Set<String> codes=new HashSet<>();
        for(var a:copy)if(a.orderId()==null||a.orderId()<=0||a.kizCode()==null||a.kizCode().isBlank()||!ids.add(a.orderId())||!codes.add(hash(a.kizCode()))||a.sourceKiz()!=null&&!Objects.equals(KizService.scannerSafeCode(a.kizCode()),KizService.scannerSafeCode(a.sourceKiz().getCode())))throw new IllegalArgumentException("KIZ_ASSIGNMENT_DUPLICATE_OR_INVALID");
        Job job=new Job(shop,new JobKey(shop.getId(),supplyId==null?"":supplyId),supplyName==null?"":supplyName,copy);
        synchronized(lock){
            if(activeJobs.size()>=128)throw new IllegalStateException("KIZ_QUEUE_FULL");
            if(activeJobs.putIfAbsent(job.key,job)!=null)throw new IllegalStateException("KIZ_JOB_ALREADY_ACTIVE");
            for(long id:ids)if(orderOwners.containsKey(new OrderKey(shop.getId(),id))||reconciling.contains(new OrderKey(shop.getId(),id))){activeJobs.remove(job.key,job);throw new IllegalStateException("KIZ_ORDER_ALREADY_ACTIVE");}
            for(long id:ids)orderOwners.put(new OrderKey(shop.getId(),id),job);
            ready.computeIfAbsent(shop.getId(),ignored->new ArrayDeque<>()).addLast(job);
        }
        publish(job,true,true);dispatch();
    }
    /** Cancellation stops unsent orders; an already submitted request is checkpointed to its result. */
    public boolean cancel(int shopId,String supplyId){Job job=activeJobs.get(new JobKey(shopId,supplyId==null?"":supplyId));if(job==null)return false;job.cancelled=true;return true;}
    public void cancelShop(int shopId){activeJobs.values().stream().filter(job->job.key.shopId()==shopId).forEach(job->job.cancelled=true);}

    private void dispatch(){
        List<Job> starts=new ArrayList<>();
        synchronized(lock){while(workers<MAX_WORKERS){Job job=take();if(job==null)break;workers++;starts.add(job);}}
        for(Job job:starts){try{executor.execute(()->run(job));}catch(RuntimeException rejected){job.failures.add("KIZ_QUEUE_UNAVAILABLE");finish(job);synchronized(lock){runningShops.remove(job.key.shopId());workers--;}dispatch();}}
    }
    private Job take(){
        var iterator=ready.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();if(runningShops.contains(entry.getKey()))continue;Job job=entry.getValue().removeFirst();if(entry.getValue().isEmpty())iterator.remove();runningShops.add(job.key.shopId());return job;}
        return null;
    }
    private void run(Job initial){
        Job job=initial;
        while(job!=null){
            try{step(job);}catch(LicenseDeniedException denied){fail(job,"LICENSE_REQUIRED",true);}
            catch(IOException|RuntimeException failure){String code=failure.getMessage();if(code==null||!Set.of("SHOP_CREDENTIALS_CHANGED","SHOP_CREDENTIALS_UNAVAILABLE","KIZ_REQUEST_CANCELLED","KIZ_CHECKPOINT_UNAVAILABLE").contains(code))code="KIZ_RECONCILIATION_REQUIRED";fail(job,code,true);LOGGER.warn("KIZ job stopped. shopId={}, errorType={}",job.key.shopId(),failure.getClass().getSimpleName());}
            boolean done=job.cancelled||job.stopped||job.index>=job.assignments.size();
            if(done)finish(job);else publish(job,true,false);
            synchronized(lock){
                runningShops.remove(job.key.shopId());
                if(!done){var queue=ready.remove(job.key.shopId());if(queue==null)queue=new ArrayDeque<>();queue.addLast(job);ready.put(job.key.shopId(),queue);}
                job=take();if(job==null)workers--;
            }
        }
        dispatch();
    }
    private void step(Job job)throws IOException{
        if(job.cancelled){job.failures.add("KIZ_JOB_CANCELLED");return;}
        var a=job.assignments.get(job.index);OrderKey key=new OrderKey(job.key.shopId(),a.orderId());job.currentOrder=a.orderId();
        LicenseRuntime.require(Permission.KIZ);
        client.requireCurrentShop(job.key.shopId(),job.token);
        String fingerprint=hash(a.kizCode());Checkpoint old=journal.read(key);
        if(old!=null&&old.ambiguous()){fail(job,"KIZ_RECONCILIATION_REQUIRED",false);job.index++;return;}
        if(old!=null&&old.codeHash().equals(fingerprint)&&old.phase().equals("CONFIRMED")){success(job,a);job.index++;return;}
        if(old!=null&&old.phase().equals("OTHER_CODE")&&!a.replaceExisting()){fail(job,"KIZ_OTHER_CODE_PRESENT",false);job.index++;return;}
        boolean alreadyRemoved=old!=null&&old.codeHash().equals(fingerprint)&&old.phase().equals("READ_EMPTY");
        if(a.replaceExisting()&&!alreadyRemoved){
            if(job.cancelled)return;
            LicenseRuntime.require(Permission.KIZ);journal.write(key,job.key.supplyId(),fingerprint,"REMOVE_PENDING");
            client.requireCurrentShop(job.key.shopId(),job.token);
            var result=client.remove(job.key.shopId(),job.token,a.orderId(),()->job.cancelled);
            if(!result.success()){recordFailure(job,key,fingerprint,result.statusCode(),result.responseBody());job.index++;return;}
            journal.write(key,job.key.supplyId(),fingerprint,"READ_EMPTY");
        }
        if(job.cancelled)return;
        LicenseRuntime.require(Permission.KIZ);journal.write(key,job.key.supplyId(),fingerprint,"ATTACH_PENDING");
        client.requireCurrentShop(job.key.shopId(),job.token);
        var result=client.attach(job.key.shopId(),job.token,a.orderId(),a.kizCode(),()->job.cancelled);
        if(result.success()){journal.write(key,job.key.supplyId(),fingerprint,"CONFIRMED");success(job,a);}
        else recordFailure(job,key,fingerprint,result.statusCode(),result.responseBody());
        job.index++;
    }
    private void recordFailure(Job job,OrderKey key,String fingerprint,int status,String body)throws IOException{
        boolean ambiguous=status<=0||status>=500;
        journal.write(key,job.key.supplyId(),fingerprint,ambiguous?"RECONCILE":"REJECTED");
        if(status==409&&body!=null&&(body.contains("FailedToUpdateMeta")||body.contains("Processing status"))){job.skipped++;return;}
        fail(job,ambiguous?"KIZ_RECONCILIATION_REQUIRED":"KIZ_HTTP_"+status,false);
        if(status==429)job.stopped=true;
    }
    private void success(Job job,OrderExportWorkflow.KizAttachmentAssignment a){job.completed++;if(a.sourceKiz()!=null&&a.sourceKiz().getCode()!=null)job.successful.add(a.sourceKiz().getCode());failedAttachments.remove(new OrderKey(job.key.shopId(),a.orderId()));}
    private void fail(Job job,String code,boolean stop){job.failures.add(code);if(job.currentOrder!=null)failedAttachments.put(new OrderKey(job.key.shopId(),job.currentOrder),code);job.stopped|=stop;}
    private void finish(Job job){
        synchronized(lock){activeJobs.remove(job.key,job);for(var a:job.assignments)orderOwners.remove(new OrderKey(job.key.shopId(),a.orderId()),job);}
        publish(job,false,true);
    }
    private void publish(Job job,boolean active,boolean force){
        long now=nanos.getAsLong();int quantum=Math.max(1,(job.assignments.size()+24)/25);
        if(!force&&(job.index-job.lastPublished<quantum||now-job.lastPublishedAt<250_000_000L))return;
        job.lastPublished=job.index;job.lastPublishedAt=now;
        String message=job.cancelled?"KIZ_JOB_CANCELLED":job.stopped&&!job.failures.isEmpty()?job.failures.getLast():"Отправка KIZ в WB "+job.completed+"/"+job.assignments.size()+(job.skipped>0?" (пропущено "+job.skipped+")":"");
        job.progress=new KizAttachmentProgress(job.key.shopId(),job.shopName,job.key.supplyId(),job.supplyName,job.completed,job.assignments.size(),active,message,job.failures,job.successful);
        for(var listener:listeners)try{listener.accept(job.progress);}catch(RuntimeException failure){LOGGER.warn("KIZ progress listener failed. errorType={}",failure.getClass().getSimpleName());}
    }
    /** Read-only reconciliation. It never resubmits a mark or removes WB metadata. */
    public Map<Long,Reconciliation> reconcilePrintedJob(Shop shop,long printJobId)throws IOException {
        MarketplaceGuard.requireWildberries(shop);LicenseRuntime.require(Permission.KIZ);
        client.requireCurrentShop(shop.getId(),shop.getApiKey());
        boolean owned=new com.vncode.app.features.print.history.PrintHistoryRepository().findJobsByShop(shop.getId()).stream()
                .anyMatch(job->job.id()==printJobId&&job.canReprint());
        if(!owned)throw new IOException("KIZ_PRINT_HISTORY_OWNER_MISMATCH");
        return com.vncode.app.features.shop.ShopOperationCoordinator.withActiveShop(shop.getId(),()->{
            client.requireCurrentShop(shop.getId(),shop.getApiKey());
            var desired=new LinkedHashMap<Long,String>();
            try(var c=com.vncode.app.config.Database.getConnection();var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wb_kiz_attachment_checkpoints'")){if(!r.next())return Map.of();}
                try(var p=c.prepareStatement("""
                    SELECT i.order_id,i.kiz FROM print_job_items i
                    JOIN print_jobs j ON j.id=i.print_job_id
                    JOIN wb_kiz_attachment_checkpoints checkpoint ON checkpoint.shop_id=j.shop_id AND checkpoint.order_id=i.order_id
                    WHERE j.id=? AND j.shop_id=? AND j.marketplace='WILDBERRIES' AND lower(j.status)='success'
                      AND checkpoint.phase IN ('REMOVE_PENDING','ATTACH_PENDING','RECONCILE')
                      AND i.kiz IS NOT NULL AND trim(i.kiz)<>'' ORDER BY i.sort_index LIMIT 10001
                    """)) {
                    p.setLong(1,printJobId);p.setInt(2,shop.getId());
                    try(var r=p.executeQuery()){while(r.next())desired.put(r.getLong(1),r.getString(2));}
                }
            }catch(java.sql.SQLException unavailable){throw new IOException("KIZ_CHECKPOINT_UNAVAILABLE");}
            if(desired.size()>10000)throw new IOException("KIZ_JOB_INVALID");
            var result=new LinkedHashMap<Long,Reconciliation>();
            for(var entry:desired.entrySet()) {
                if(Thread.currentThread().isInterrupted())throw new IOException("KIZ_JOB_CANCELLED");
                LicenseRuntime.require(Permission.KIZ);
                try{result.put(entry.getKey(),reconcile(shop,entry.getKey(),entry.getValue()));}
                catch(IOException unavailable){result.put(entry.getKey(),Reconciliation.UNAVAILABLE);}
            }
            return Map.copyOf(result);
        });
    }

    public Reconciliation reconcile(Shop shop,long orderId,String desiredCode)throws IOException{
        MarketplaceGuard.requireWildberries(shop);LicenseRuntime.require(Permission.KIZ);
        client.requireCurrentShop(shop.getId(),shop.getApiKey());
        OrderKey key=new OrderKey(shop.getId(),orderId);
        synchronized(lock){if(orderOwners.containsKey(key)||!reconciling.add(key))throw new IOException("KIZ_ORDER_ALREADY_ACTIVE");}
        try {
        Checkpoint old=journal.read(key);if(old==null||!old.codeHash().equals(hash(desiredCode)))throw new IOException("KIZ_CHECKPOINT_MISMATCH");
        var metadata=client.metadata(shop.getApiKey(),List.of(orderId)).get(orderId);
        if(metadata==null||!metadata.available())return Reconciliation.UNAVAILABLE;
        String phase;Reconciliation result;
        if(KizService.scannerSafeCode(desiredCode).equals(KizService.scannerSafeCode(metadata.appliedValue()))){phase="CONFIRMED";result=Reconciliation.CONFIRMED;}
        else if(!metadata.hasAppliedValue()){phase="READ_EMPTY";result=Reconciliation.EMPTY;}
        else{phase="OTHER_CODE";result=Reconciliation.OTHER_CODE;}
        journal.write(key,"",old.codeHash(),phase);if(result==Reconciliation.CONFIRMED)failedAttachments.remove(key);return result;
        } finally {synchronized(lock){reconciling.remove(key);}}
    }
    static String hash(String code){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(KizService.scannerSafeCode(Objects.requireNonNull(code)).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("KIZ_HASH_UNAVAILABLE");}}
    record JobKey(int shopId,String supplyId){}
    record OrderKey(int shopId,long orderId){}
    record Checkpoint(String codeHash,String phase){boolean ambiguous(){return Set.of("REMOVE_PENDING","ATTACH_PENDING","RECONCILE").contains(phase);}}
    interface AttachmentJournal {Checkpoint read(OrderKey key)throws IOException;void write(OrderKey key,String supply,String codeHash,String phase)throws IOException;}
    static final class MemoryJournal implements AttachmentJournal{
        private final Map<OrderKey,Checkpoint> entries=new ConcurrentHashMap<>();
        public Checkpoint read(OrderKey key){return entries.get(key);}
        public void write(OrderKey key,String supply,String codeHash,String phase){entries.put(key,new Checkpoint(codeHash,phase));}
    }
    interface AttachmentClient {
        KizService.RemoveMetaResult remove(String token,long orderId)throws IOException;
        KizService.AttachCodeResult attach(String token,long orderId,String code)throws IOException;
        default void requireCurrentShop(int shopId,String token)throws IOException{}
        default KizService.RemoveMetaResult remove(int shopId,String token,long orderId)throws IOException{return remove(token,orderId);}
        default KizService.AttachCodeResult attach(int shopId,String token,long orderId,String code)throws IOException{return attach(token,orderId,code);}
        default KizService.RemoveMetaResult remove(int shopId,String token,long orderId,BooleanSupplier cancelled)throws IOException{return remove(shopId,token,orderId);}
        default KizService.AttachCodeResult attach(int shopId,String token,long orderId,String code,BooleanSupplier cancelled)throws IOException{return attach(shopId,token,orderId,code);}
        default Map<Long,KizService.SgtinMetadata> metadata(String token,List<Long> ids)throws IOException{throw new IOException("KIZ_RECONCILIATION_NOT_SUPPORTED");}
    }
    public enum Reconciliation{CONFIRMED,EMPTY,OTHER_CODE,UNAVAILABLE}
    private static final class Job{
        final JobKey key;final String shopName,supplyName,token;final List<OrderExportWorkflow.KizAttachmentAssignment> assignments;
        final List<String> failures=new ArrayList<>(),successful=new ArrayList<>();
        volatile KizAttachmentProgress progress;volatile boolean cancelled;boolean stopped;int index,completed,skipped,lastPublished;long lastPublishedAt;Long currentOrder;
        Job(Shop shop,JobKey key,String supplyName,List<OrderExportWorkflow.KizAttachmentAssignment> assignments){this.key=key;this.shopName=shop.getName();this.supplyName=supplyName;this.token=shop.getApiKey();this.assignments=assignments;this.progress=new KizAttachmentProgress(key.shopId(),shopName,key.supplyId(),supplyName,0,assignments.size(),true,"Отправка KIZ в WB 0/"+assignments.size(),List.of(),List.of());}
    }
    public record KizAttachmentProgress(int shopId,String shopName,String supplyId,String supplyName,int completed,int total,boolean active,String message,List<String> failures,List<String> successfulKizCodes){
        public KizAttachmentProgress{failures=List.copyOf(failures);successfulKizCodes=List.copyOf(successfulKizCodes);}
        @Override public String toString(){return "KizAttachmentProgress[shopId="+shopId+", completed="+completed+", total="+total+", active="+active+"]";}
    }
}
