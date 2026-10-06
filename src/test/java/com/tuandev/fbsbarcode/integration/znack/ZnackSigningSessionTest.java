package com.tuandev.fbsbarcode.integration.znack;

import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProSigningResult;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureContext;
import com.tuandev.fbsbarcode.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackSigningSessionTest {
    private static final int SHOP_A = 11;
    private static final int SHOP_B = 22;
    private static final long PIPELINE = 101L;

    @AfterEach
    void resetSession() {
        ZnackSigningSession.resetForTests();
    }

    @Test
    void recoveredPipelineWaitsWithoutOpeningCryptoProUntilItsShopIsExplicitlyActivated() throws Exception {
        AtomicInteger signingCalls = new AtomicInteger();
        ZnackSignatureProvider guarded = ZnackSigningSession.guard(SHOP_A, successfulSigner(signingCalls));

        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));

        assertEquals(0, signingCalls.get());
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE));

        ZnackSigningSession.authorizeShop(SHOP_A);

        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guarded, SHOP_A, PIPELINE).cms());
        assertEquals(1, signingCalls.get());
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE));
    }

    @Test
    void pipelineStartedThisSessionRemainsAuthorizedAfterAnotherShopIsActivated() throws Exception {
        AtomicInteger signingCalls = new AtomicInteger();
        ZnackSignatureProvider guarded = ZnackSigningSession.guard(SHOP_A, successfulSigner(signingCalls));
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);

        ZnackSigningSession.authorizeShop(SHOP_B);

        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guarded, SHOP_A, PIPELINE).cms());
        assertEquals(1, signingCalls.get());
    }

    @Test
    void cancellingCryptoProPausesAutomaticSigningUntilTheUserAuthorizesAgain() throws Exception {
        AtomicInteger signingCalls = new AtomicInteger();
        ZnackSignatureProvider guarded = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            signingCalls.incrementAndGet();
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "User cancelled CryptoPro.");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);

        assertThrows(CryptoProException.class, () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE));

        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE + 1));
        assertEquals(1, signingCalls.get());
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE + 1));
    }

    @Test
    void concurrentPipelinesCannotOpenTwoCryptoProDialogsForTheSameFailure() throws Exception {
        AtomicInteger signingCalls = new AtomicInteger();
        CountDownLatch firstDialogOpened = new CountDownLatch(1);
        CountDownLatch cancelFirstDialog = new CountDownLatch(1);
        ZnackSignatureProvider guarded = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            signingCalls.incrementAndGet();
            firstDialogOpened.countDown();
            try {
                if (!cancelFirstDialog.await(5, TimeUnit.SECONDS)) {
                    throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "Test signing timed out.");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CryptoProException(
                        CryptoProErrorCode.CANCELLED,
                        "Test signing was interrupted.",
                        error);
            }
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "User cancelled CryptoPro.");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> captureFailure(
                    () -> signForPipeline(guarded, SHOP_A, PIPELINE)));
            assertTrue(firstDialogOpened.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> captureFailure(
                    () -> signForPipeline(guarded, SHOP_A, PIPELINE + 1)));

            cancelFirstDialog.countDown();

            assertInstanceOf(CryptoProException.class, first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ZnackSigningSession.SigningDeferredException.class,
                    second.get(5, TimeUnit.SECONDS));
            assertEquals(1, signingCalls.get());
        } finally {
            cancelFirstDialog.countDown();
            executor.shutdownNow();
        }
    }

    private CryptoProSigningResult signForPipeline(ZnackSignatureProvider signer, int shopId, long pipelineId)
            throws Exception {
        try (ZnackSigningSession.PipelineScope ignored = ZnackSigningSession.openPipeline(shopId, pipelineId)) {
            return signer.sign(new byte[]{9}, ZnackSignatureContext.AUTH_CHALLENGE);
        }
    }

    private ZnackSignatureProvider successfulSigner(AtomicInteger calls) {
        return (payload, context) -> {
            calls.incrementAndGet();
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        };
    }

    private Throwable captureFailure(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
