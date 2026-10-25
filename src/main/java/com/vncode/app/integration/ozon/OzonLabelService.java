package com.vncode.app.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.marketplace.MarketplaceGuard;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AtomicFilePublisher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Downloads the official Ozon PDF unchanged and publishes it atomically. */
public final class OzonLabelService {
    private static final int MAX_POLLS = 12;
    private final OzonLabelRepository labels;
    private final BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients;

    public OzonLabelService() {
        this(new OzonLabelRepository(), OzonApiClient::new);
    }

    OzonLabelService(
            OzonLabelRepository labels,
            BiFunction<Integer, OzonCredentials, OzonApiClient> apiClients) {
        this.labels = Objects.requireNonNull(labels, "labels");
        this.apiClients = Objects.requireNonNull(apiClients, "apiClients");
    }

    public File downloadOfficialPdf(Shop shop, String postingNumber, File target) throws IOException {
        MarketplaceGuard.requireOzon(shop);
        if (target == null || !target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Ozon shipping label target must be a PDF file.");
        }
        OzonApiClient api = apiClients.apply(
                shop.getId(), new OzonCredentials(shop.getClientId(), shop.getApiKey()));
        OzonLabelRepository.LabelJob job = labels.find(shop.getId(), postingNumber);
        if (job == null) {
            LicenseRuntime.require(Permission.PRINT_NEW);
            job = labels.findOrCreate(shop.getId(), postingNumber);
        }
        String taskId = job.taskId();
        if(taskId!=null&&taskId.startsWith("v3:")) {
            try(var batch=new OzonBatchLabelDownloader(this,shop,List.of(postingNumber))) {
                return batch.download(shop,postingNumber,target);
            }
        }
        if (taskId == null || taskId.isBlank()) {
            if ("RECONCILE_REQUIRED".equals(job.status())) {
                throw new IOException(
                        "Ozon did not return a label task ID. VN code will not create another label job automatically.");
            }
            JsonArray postings = new JsonArray();
            postings.add(postingNumber);
            JsonObject create = new JsonObject();
            create.add("posting_number", postings);
            LicenseRuntime.require(Permission.PRINT_NEW);
            try {
                taskId = bigLabelTaskId(api.createLabelJob(create));
                if (taskId.isBlank()) {
                    labels.update(shop.getId(), postingNumber, null,
                            "RECONCILE_REQUIRED", null, "invalid_response");
                    throw new IOException(
                            "Ozon did not return a big-label task ID. VN code will not create another label job automatically.");
                }
                job = labels.update(shop.getId(), postingNumber, taskId, "POLLING", null, null);
            } catch (OzonApiException exception) {
                labels.update(shop.getId(), postingNumber, null,
                        exception.ambiguousMutation() ? "RECONCILE_REQUIRED" : "FAILED", null, exception.kind());
                throw exception;
            }
        }

        JsonObject get = new JsonObject();
        if(taskId.startsWith("v3:"))get.addProperty("task_id",new java.math.BigInteger(taskId.substring(3)));else get.addProperty("task_id",taskId);
        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            LabelPoll poll = labelPoll(taskId.startsWith("v3:")?api.getBatchLabelJob(get):api.getLabelJob(get));
            if (poll.failed()) {
                labels.update(shop.getId(), postingNumber, taskId, "FAILED", null, "label_failed");
                throw new IOException("Ozon could not generate the official shipping label.");
            }
            if (!poll.fileUrl().isBlank()) {
                byte[] bytes = api.downloadOfficialDocument(poll.fileUrl());
                if (!isPdf(bytes)) {
                    labels.update(shop.getId(), postingNumber, taskId, "FAILED", null, "invalid_pdf");
                    throw new IOException("Ozon returned an invalid shipping label file.");
                }
                File staging = AtomicFilePublisher.stagingFile(target, ".pdf");
                try {
                    Files.write(staging.toPath(), bytes);
                    AtomicFilePublisher.publish(staging, target);
                } catch (IOException exception) {
                    AtomicFilePublisher.deleteQuietly(staging);
                    labels.update(shop.getId(), postingNumber, taskId, "FAILED", null, "publish_failed");
                    throw exception;
                }
                // The local destination may contain a person's account or directory name. The
                // durable job needs only the remote task/status, so never persist that path.
                labels.update(shop.getId(), postingNumber, taskId, "READY", null, null);
                return target;
            }
            if (attempt + 1 < MAX_POLLS) sleep(Duration.ofMillis(500));
        }
        labels.update(shop.getId(), postingNumber, taskId, "POLLING", null, "label_pending");
        throw new IOException("Ozon label is still being generated. Retry will continue the same job.");
    }

    /** One durable task for up to 1,000 postings. Existing uncertain tasks are never recreated. */
    File downloadOfficialBatchPdf(Shop shop,List<String> numbers,File target)throws IOException {
        MarketplaceGuard.requireOzon(shop);com.vncode.app.integration.listing.ListingIdentity.require(shop);
        if(numbers.isEmpty()||numbers.size()>1000||numbers.stream().distinct().count()!=numbers.size())throw new IllegalArgumentException("OZON_LABEL_BATCH_LIMIT");
        var api=apiClients.apply(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()));
        String task=null;
        for(String number:numbers) {
            var job=labels.find(shop.getId(),number);
            if(job==null){LicenseRuntime.require(Permission.PRINT_NEW);job=labels.findOrCreate(shop.getId(),number);}
            if("ISOLATING".equals(job.status())||"ISOLATE_REQUIRED".equals(job.status()))throw new IOException("OZON_LABEL_TASK_UNKNOWN");
            if(job.taskId()!=null&&!job.taskId().isBlank()) {
                if(!job.taskId().startsWith("v3:")||task!=null&&!task.equals(job.taskId()))throw new IOException("OZON_LABEL_BATCH_TASK_CHANGED");
                task=job.taskId();
            } else if("RECONCILE_REQUIRED".equals(job.status()))throw new IOException("OZON_LABEL_TASK_UNKNOWN");
        }
        if(task==null) {
            LicenseRuntime.require(Permission.PRINT_NEW);
            // Save the uncertainty boundary BEFORE task creation, also across an abrupt app exit.
            labels.claimBatch(shop.getId(),numbers);
            var body=new JsonObject();var ids=new JsonArray();numbers.forEach(ids::add);body.add("posting_numbers",ids);
            try {
                String remote=bigLabelTaskId(api.createBatchLabelJob(body));
                if(!remote.matches("[1-9][0-9]{0,18}"))throw new IOException("OZON_LABEL_TASK_UNKNOWN");
                task="v3:"+remote;
                labels.updateBatch(shop.getId(),numbers,task,"POLLING",null);
            } catch(OzonApiException failed) {
                if(!failed.ambiguousMutation())labels.updateBatch(shop.getId(),numbers,null,"FAILED",failed.safeErrorCode());
                throw failed;
            }
        } else {
            // A partially saved membership cannot be silently attached to another task.
            for(String number:numbers)if(!task.equals(labels.find(shop.getId(),number).taskId()))throw new IOException("OZON_LABEL_BATCH_TASK_CHANGED");
        }
        var request=new JsonObject();request.addProperty("task_id",new java.math.BigInteger(task.substring(3)));
        for(int attempt=0;attempt<60;attempt++) {
            var poll=labelPoll(api.getBatchLabelJob(request));
            if(poll.failed())throw new IOException("OZON_LABEL_BATCH_FAILED");
            if(!poll.fileUrl().isBlank()) {
                byte[] pdf=api.downloadOfficialDocument(poll.fileUrl());
                if(!isPdf(pdf))throw new IOException("OZON_LABEL_BATCH_INVALID_PDF");
                Files.write(target.toPath(),pdf);
                labels.updateBatch(shop.getId(),numbers,task,"READY",null);
                return target;
            }
            if(attempt+1<60)sleep(Duration.ofSeconds(1));
        }
        throw new IOException("OZON_LABEL_BATCH_PENDING");
    }

    /** A PDF without page identities is fetched again through a task for exactly one posting.
     * This never associates shared pages by their order or by a product barcode. */
    File downloadIsolatedPdf(Shop shop,String number,File target)throws IOException {
        MarketplaceGuard.requireOzon(shop);com.vncode.app.integration.listing.ListingIdentity.require(shop);
        var job=labels.find(shop.getId(),number);if(job==null)throw new IOException("OZON_LABEL_BATCH_TASK_CHANGED");
        if(job.taskId()!=null&&!job.taskId().startsWith("v3:"))return downloadOfficialPdf(shop,number,target);
        String original=job.taskId();
        LicenseRuntime.require(Permission.PRINT_NEW);
        if(original==null||!labels.claimIsolation(shop.getId(),number,original))throw new IOException("OZON_LABEL_TASK_UNKNOWN");
        var api=apiClients.apply(shop.getId(),new OzonCredentials(shop.getClientId(),shop.getApiKey()));
        var request=new JsonObject();var postings=new JsonArray();postings.add(number);request.add("posting_number",postings);
        try{
            String receipt=bigLabelTaskId(api.createLabelJob(request));
            if(receipt.isBlank()||receipt.startsWith("v3:"))throw new IOException("OZON_LABEL_TASK_UNKNOWN");
            if(!labels.isolationReceipt(shop.getId(),number,original,receipt))throw new IOException("OZON_LABEL_BATCH_TASK_CHANGED");
        }catch(OzonApiException failed){
            if(!failed.ambiguousMutation())labels.isolationRejected(shop.getId(),number,original);
            throw failed;
        }
        return downloadOfficialPdf(shop,number,target);
    }

    static boolean isPdf(byte[] value) {
        return value != null && value.length >= 5
                && value[0] == '%' && value[1] == 'P' && value[2] == 'D' && value[3] == 'F' && value[4] == '-';
    }

    static String bigLabelTaskId(JsonObject response) {
        JsonObject result = response.has("result")?OzonJson.object(response,"result"):response;
        // Current v2 responses return one selected label task directly. Older responses returned
        // a task list containing both regular (big) and small label variants.
        String direct = safeTaskId(OzonJson.text(result, "task_id"));
        if (!direct.isBlank()) return direct;
        JsonArray tasks = OzonJson.array(result, "tasks");
        String soleTask = "";
        for (JsonElement element : tasks) {
            if (element.isJsonObject()) {
                JsonObject task = element.getAsJsonObject();
                String taskId = safeTaskId(OzonJson.text(task, "task_id"));
                if (tasks.size() == 1) soleTask = taskId;
                if ("big_label".equalsIgnoreCase(OzonJson.text(task, "task_type"))) {
                    return taskId;
                }
            }
        }
        return soleTask;
    }

    static LabelPoll labelPoll(JsonObject response) {
        JsonObject result = response.has("result")?OzonJson.object(response,"result"):response;
        var state=result.get("status");
        String status = (state!=null&&state.isJsonObject()?OzonJson.text(state.getAsJsonObject(),"code"):OzonJson.text(result,"status")).toLowerCase(java.util.Locale.ROOT);
        var failure=result.get("error");
        String error = failure!=null&&failure.isJsonObject()?OzonJson.text(failure.getAsJsonObject(),"code")+OzonJson.text(failure.getAsJsonObject(),"message"):OzonJson.text(result,"error");
        String fileUrl = OzonJson.text(result, "file_url");
        boolean failed = !error.isBlank() || List.of("failed", "error", "rejected").contains(status);
        return new LabelPoll(fileUrl, failed);
    }

    private static String safeTaskId(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}") ? normalized : "";
    }

    record LabelPoll(String fileUrl, boolean failed) {
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Ozon label polling was interrupted.", exception);
        }
    }
}
