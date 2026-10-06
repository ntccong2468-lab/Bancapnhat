package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local consent and pause state for background Znack signing.
 *
 * <p>Nothing in this class is persisted. A recovered pipeline can open CryptoPro after its shop is
 * authorized by the startup active-shop selection or by a later explicit shop selection, while a
 * pipeline created in this process keeps its authorization if the visible shop changes.</p>
 */
public final class ZnackSigningSession {
    static final String WAITING_MESSAGE = "Waiting for the user to select this shop before signing.";

    private static final Object SIGNING_LOCK = new Object();
    private static final Set<Integer> AUTHORIZED_SHOPS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> BLOCKED_SHOPS = ConcurrentHashMap.newKeySet();
    private static final Set<PipelineKey> AUTHORIZED_PIPELINES = ConcurrentHashMap.newKeySet();
    private static final Set<PipelineKey> WAITING_PIPELINES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<PipelineKey> CURRENT_PIPELINE = new ThreadLocal<>();

    private ZnackSigningSession() {
    }

    public static ZnackSignatureProvider guard(int shopId, ZnackSignatureProvider delegate) {
        if (delegate == null) throw new IllegalArgumentException("Signature provider is required.");
        return (payload, context) -> {
            // CryptoPro can show a native modal window. Serializing this boundary prevents two
            // background pipelines from opening competing certificate/token dialogs.
            synchronized (SIGNING_LOCK) {
                PipelineKey pipeline = CURRENT_PIPELINE.get();
                boolean matchingPipeline = pipeline != null && pipeline.shopId() == shopId;
                if (BLOCKED_SHOPS.contains(shopId)
                        || (matchingPipeline && WAITING_PIPELINES.contains(pipeline))
                        || (!AUTHORIZED_SHOPS.contains(shopId)
                        && (!matchingPipeline || !AUTHORIZED_PIPELINES.contains(pipeline)))) {
                    if (matchingPipeline) WAITING_PIPELINES.add(pipeline);
                    throw new SigningDeferredException(WAITING_MESSAGE);
                }
                try {
                    return delegate.sign(payload, context);
                } catch (CryptoProException error) {
                    BLOCKED_SHOPS.add(shopId);
                    if (matchingPipeline) WAITING_PIPELINES.add(pipeline);
                    throw error;
                }
            }
        };
    }

    public static void authorizePipeline(int shopId, long pipelineId) {
        PipelineKey key = new PipelineKey(shopId, pipelineId);
        BLOCKED_SHOPS.remove(shopId);
        AUTHORIZED_PIPELINES.add(key);
        WAITING_PIPELINES.remove(key);
    }

    public static void authorizeShop(int shopId) {
        BLOCKED_SHOPS.remove(shopId);
        AUTHORIZED_SHOPS.add(shopId);
        WAITING_PIPELINES.removeIf(key -> key.shopId() == shopId);
    }

    public static boolean isWaitingForSignature(int shopId, long pipelineId) {
        return WAITING_PIPELINES.contains(new PipelineKey(shopId, pipelineId));
    }

    static PipelineScope openPipeline(int shopId, long pipelineId) {
        PipelineKey previous = CURRENT_PIPELINE.get();
        CURRENT_PIPELINE.set(new PipelineKey(shopId, pipelineId));
        return new PipelineScope(previous);
    }

    static void resetForTests() {
        AUTHORIZED_SHOPS.clear();
        BLOCKED_SHOPS.clear();
        AUTHORIZED_PIPELINES.clear();
        WAITING_PIPELINES.clear();
        CURRENT_PIPELINE.remove();
    }

    private record PipelineKey(int shopId, long pipelineId) {
    }

    static final class PipelineScope implements AutoCloseable {
        private final PipelineKey previous;
        private boolean closed;

        private PipelineScope(PipelineKey previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT_PIPELINE.remove();
            else CURRENT_PIPELINE.set(previous);
        }
    }

    public static final class SigningDeferredException extends CryptoProException {
        private SigningDeferredException(String message) {
            super(CryptoProErrorCode.CANCELLED, message);
        }
    }
}
