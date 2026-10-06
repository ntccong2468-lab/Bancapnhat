package com.tuandev.fbsbarcode.integration.ozon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tuandev.fbsbarcode.integration.marketplace.MarketplaceGuard;
import com.tuandev.fbsbarcode.models.Shop;
import com.tuandev.fbsbarcode.shared.AtomicFilePublisher;
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
        OzonLabelRepository.LabelJob job = labels.findOrCreate(shop.getId(), postingNumber);
        String taskId = job.taskId();
        if (taskId == null || taskId.isBlank()) {
            if ("RECONCILE_REQUIRED".equals(job.status())) {
                throw new IOException(
                        "Ozon did not return a label task ID. WCode will not create another label job automatically.");
            }
            JsonArray postings = new JsonArray();
            postings.add(postingNumber);
            JsonObject create = new JsonObject();
            create.add("posting_number", postings);
            try {
                taskId = bigLabelTaskId(api.createLabelJob(create));
                if (taskId.isBlank()) {
                    labels.update(shop.getId(), postingNumber, null,
                            "RECONCILE_REQUIRED", null, "invalid_response");
                    throw new IOException(
                            "Ozon did not return a big-label task ID. WCode will not create another label job automatically.");
                }
                job = labels.update(shop.getId(), postingNumber, taskId, "POLLING", null, null);
            } catch (OzonApiException exception) {
                labels.update(shop.getId(), postingNumber, null,
                        exception.ambiguousMutation() ? "RECONCILE_REQUIRED" : "FAILED", null, exception.kind());
                throw exception;
            }
        }

        JsonObject get = new JsonObject();
        get.addProperty("task_id", taskId);
        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            LabelPoll poll = labelPoll(api.getLabelJob(get));
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

    private static boolean isPdf(byte[] value) {
        return value != null && value.length >= 5
                && value[0] == '%' && value[1] == 'P' && value[2] == 'D' && value[3] == 'F' && value[4] == '-';
    }

    private static String bigLabelTaskId(JsonObject response) {
        JsonObject result = OzonJson.object(response, "result");
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

    private static LabelPoll labelPoll(JsonObject response) {
        JsonObject result = OzonJson.object(response, "result");
        String status = OzonJson.text(result, "status").toLowerCase(java.util.Locale.ROOT);
        String error = OzonJson.text(result, "error");
        String fileUrl = OzonJson.text(result, "file_url");
        boolean failed = !error.isBlank() || List.of("failed", "error", "rejected").contains(status);
        return new LabelPoll(fileUrl, failed);
    }

    private static String safeTaskId(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}") ? normalized : "";
    }

    private record LabelPoll(String fileUrl, boolean failed) {
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
