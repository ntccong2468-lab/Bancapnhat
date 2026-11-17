package com.vncode.app.integration.znack;

import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.signature.CryptoProSigningResult;
import com.vncode.app.integration.znack.signature.ZnackSignatureContext;
import com.vncode.app.integration.znack.signature.ZnackSignatureProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @Test void gs1AuthenticationWorksWithoutSelectingEveryShopButCannotAuthorizeBusinessSigning()throws Exception{
        var calls=new AtomicInteger();var delegate=successfulSigner(calls);
        var read=ZnackSigningSession.guardReadAuthentication(SHOP_B,"B".repeat(40),delegate);
        read.sign(new byte[]{1},ZnackSignatureContext.CATALOG_AUTH_CHALLENGE);
        read.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE);
        assertEquals(2,calls.get());
        assertThrows(CryptoProException.class,()->read.sign(new byte[]{1},ZnackSignatureContext.SUZ_POST_BODY));
        assertThrows(CryptoProException.class,()->read.sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->ZnackSigningSession.guard(SHOP_B,delegate).sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
        assertEquals(2,calls.get());
    }
    @Test void cancellingAutomaticGs1AuthenticationCannotReopenTheSignerOnEveryPoll()throws Exception{
        var calls=new AtomicInteger();var read=ZnackSigningSession.guardReadAuthentication(SHOP_B,"B".repeat(40),(p,c)->{calls.incrementAndGet();throw new CryptoProException(CryptoProErrorCode.CANCELLED,"Synthetic cancellation");});
        assertThrows(CryptoProException.class,()->read.sign(new byte[]{1},ZnackSignatureContext.CATALOG_AUTH_CHALLENGE));
        for(int retry=0;retry<5;retry++)assertThrows(ZnackSigningSession.SigningDeferredException.class,()->read.sign(new byte[]{1},ZnackSignatureContext.AUTH_CHALLENGE));
        assertEquals(1,calls.get());
    }

    @Test void distinctFingerprintsCanSignConcurrently() throws Exception {
        ZnackSigningSession.authorizeShop(SHOP_A);ZnackSigningSession.authorizeShop(SHOP_B);
        var both=new CountDownLatch(2);var release=new CountDownLatch(1);
        ZnackSignatureProvider delegate=(p,c)->{both.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return new CryptoProSigningResult(new byte[]{1},"");};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->ZnackSigningSession.guard(SHOP_A,"A".repeat(40),delegate).sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
            var b=executor.submit(()->ZnackSigningSession.guard(SHOP_B,"B".repeat(40),delegate).sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
            try{assertTrue(both.await(2,TimeUnit.SECONDS),"Distinct certificates should not share a global lock");}finally{release.countDown();}
            a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        }
    }
    @Test void sameNormalizedFingerprintSerializesCmsAndXmlAcrossShops() throws Exception {
        ZnackSigningSession.authorizeShop(SHOP_A);ZnackSigningSession.authorizeShop(SHOP_B);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var xmlEntered=new CountDownLatch(1);
        var first=ZnackSigningSession.guard(SHOP_A,"a".repeat(40),(p,c)->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new CryptoProSigningResult(new byte[]{1},"");});
        var second=ZnackSigningSession.guardXml(SHOP_B,"AA ".repeat(20),xml->{xmlEntered.countDown();return "signed";});
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->first.sign(new byte[]{1},ZnackSignatureContext.TRUE_API_DOCUMENT));
            assertTrue(entered.await(2,TimeUnit.SECONDS));var b=executor.submit(()->second.signXml("<a/>"));
            try{assertFalse(xmlEntered.await(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}
            a.get(5,TimeUnit.SECONDS);assertEquals("signed",b.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void xmlCannotBypassShopAuthorization() {
        var signer=ZnackSigningSession.guardXml(SHOP_A,"A".repeat(40),xml->{throw new AssertionError("Must not open signer");});
        assertThrows(ZnackSigningSession.SigningDeferredException.class,()->signer.signXml("<a/>"));
    }

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

    @ParameterizedTest
    @EnumSource(value=CryptoProErrorCode.class,names="KEY_NOT_CONNECTED",mode=EnumSource.Mode.EXCLUDE)
    void backgroundDeferralKeepsOperationalCancellationAndRetainsTheActualDiagnosticCause(CryptoProErrorCode code) throws Exception {
        var guarded = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            throw new CryptoProException(code, "PIN=synthetic-private-pin clientToken=synthetic-private-token");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        var originalFailure = assertThrows(CryptoProException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        assertEquals(code, originalFailure.code(), "The actual provider failure must keep its own code");
        assertEquals(ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_" + code.name() + "]",
                ZnackSigningSession.signingWaitMessage(SHOP_A));

        var paused = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));

        assertEquals(ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_" + code.name() + "]", paused.getMessage());
        assertEquals(CryptoProErrorCode.CANCELLED, paused.code(),
                "A deferred operation must keep the existing cancellation control-flow contract");
        assertEquals("CRYPTOPRO_" + code.name(), ZnackErrorMessages.errorCode(paused.getMessage()),
                "The historical cause belongs in diagnostics, not the deferred operation's control code");
        assertFalse(paused.getMessage().contains("synthetic-private"));
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE));
    }

    @Test
    void authorizingOnePipelineClearsOnlyItsShopCauseAndDoesNotWakeOtherWaitingPipelines() throws Exception {
        var attemptsA = new AtomicInteger();
        var guardedA = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            if (attemptsA.incrementAndGet() == 1) throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "Synthetic timeout");
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        });
        var guardedB = ZnackSigningSession.guard(SHOP_B, (payload, context) -> {
            throw new CryptoProException(CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE, "Synthetic unavailable key");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);
        ZnackSigningSession.authorizePipeline(SHOP_B, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedB, SHOP_B, PIPELINE));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedA, SHOP_A, PIPELINE + 1));

        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guardedA, SHOP_A, PIPELINE).cms());
        var otherPipeline = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedA, SHOP_A, PIPELINE + 1));
        assertEquals(ZnackSigningSession.WAITING_MESSAGE, otherPipeline.getMessage());
        var otherShop = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedB, SHOP_B, PIPELINE));
        assertEquals(ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE]", otherShop.getMessage());
        assertEquals(2, attemptsA.get(), "An explicit retry must not open another paused pipeline's signer");
    }

    @Test
    void explicitShopConsentReopensOnlyThatShopAndPreservesAnotherShopsCause() throws Exception {
        var attempts = new AtomicInteger();
        var guardedA = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            if (attempts.incrementAndGet() == 1) throw new CryptoProException(CryptoProErrorCode.CANCELLED, "Synthetic cancellation");
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        });
        var guardedB = ZnackSigningSession.guard(SHOP_B, (payload, context) -> {
            throw new CryptoProException(CryptoProErrorCode.CERTIFICATE_EXPIRED, "Synthetic expired certificate");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_B, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedB, SHOP_B, PIPELINE));
        ZnackSigningSession.authorizeShop(SHOP_A);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guardedA, SHOP_A, PIPELINE).cms());
        var paused = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedB, SHOP_B, PIPELINE));
        assertEquals(ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_CERTIFICATE_EXPIRED]", paused.getMessage());
    }

    @Test
    void localPauseRecognitionRejectsRemoteWrappersAndUnknownOrMalformedCauses() {
        String waiting = ZnackSigningSession.WAITING_MESSAGE;
        assertTrue(ZnackSigningSession.isSigningDeferred(waiting));
        for (var code : CryptoProErrorCode.values()) {
            assertTrue(ZnackSigningSession.isSigningDeferred(waiting + " [CRYPTOPRO_" + code.name() + "]"));
        }
        assertFalse(ZnackSigningSession.isSigningDeferred(null));
        for (String other : java.util.List.of("", " " + waiting, waiting + " ", "HTTP 400: " + waiting,
                "{\"message\":\"" + waiting + "\"}", waiting + " [CRYPTOPRO_UNKNOWN]",
                waiting + " [CRYPTOPRO_timeout]", waiting + " [CRYPTOPRO_]",
                waiting + " [CRYPTOPRO_TIMEOUT", waiting + " [CRYPTOPRO_TIMEOUT] extra",
                waiting + " [CRYPTOPRO_TIMEOUT] [CRYPTOPRO_CANCELLED]")) {
            assertFalse(ZnackSigningSession.isSigningDeferred(other), "Only exact local persisted markers may enable resume");
        }
    }

    @Test
    void readingAPauseExplanationNeverAuthorizesAShopOrOpensTheSigner() {
        var calls = new AtomicInteger();
        assertEquals(ZnackSigningSession.WAITING_MESSAGE, ZnackSigningSession.signingWaitMessage(SHOP_A));
        var guarded = ZnackSigningSession.guard(SHOP_A, successfulSigner(calls));
        var paused = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        assertEquals(CryptoProErrorCode.CANCELLED, paused.code());
        assertEquals(0, calls.get());
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE));
    }

    @Test
    void explicitRetryDoesNotReuseAnEarlierFailureCodeWhenTheNewFailureHasNoKnownCode() throws Exception {
        var attempts = new AtomicInteger();
        var guarded = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            throw new CryptoProException(attempts.incrementAndGet() == 1 ? CryptoProErrorCode.TIMEOUT : null,
                    "PIN=synthetic-private-pin");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        var firstPause = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        assertEquals("CRYPTOPRO_TIMEOUT", ZnackErrorMessages.errorCode(firstPause.getMessage()));

        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        var unknownPause = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE));
        assertEquals(ZnackSigningSession.WAITING_MESSAGE, unknownPause.getMessage());
        assertEquals("SIGNING_CONFIRMATION_REQUIRED", ZnackErrorMessages.errorCode(unknownPause.getMessage()));
        assertEquals(2, attempts.get());
    }

    @Test
    void scopedResumptionLeavesAnUnvisitedSiblingBlockedAfterCancellation() throws Exception {
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();
        var guardedA = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            if (callsA.incrementAndGet() == 1) throw new CryptoProException(CryptoProErrorCode.CANCELLED, "Synthetic cancellation");
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        });
        var guardedB = ZnackSigningSession.guard(SHOP_A, successfulSigner(callsB));
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE + 1),
                "The sibling has not yet reached the signer, so a waiting latch cannot protect it");

        ZnackSigningSession.authorizePipelineResumption(SHOP_A, PIPELINE);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guardedA, SHOP_A, PIPELINE).cms());
        var siblingPause = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedB, SHOP_A, PIPELINE + 1));
        assertEquals(0, callsB.get());
        assertTrue(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE + 1));
        assertEquals(ZnackSigningSession.WAITING_MESSAGE + " [CRYPTOPRO_CANCELLED]", siblingPause.getMessage());
        assertEquals(siblingPause.getMessage(), ZnackSigningSession.signingWaitMessage(SHOP_A),
                "Resuming one lot must preserve the shop's cancellation and diagnostic cause");
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guardedA, SHOP_A, PIPELINE).cms(),
                "A sibling's deferred poll is not a new provider failure and must not revoke this explicit resume");
        assertEquals(3, callsA.get());
    }

    @Test
    void scopedResumptionForOneShopDoesNotReopenAnotherBlockedShop() throws Exception {
        var callsA = new AtomicInteger();
        var callsB = new AtomicInteger();
        var guardedA = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            if (callsA.incrementAndGet() == 1) throw new CryptoProException(CryptoProErrorCode.TIMEOUT, "Synthetic timeout");
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        });
        var guardedB = ZnackSigningSession.guard(SHOP_B, (payload, context) -> {
            callsB.incrementAndGet();
            throw new CryptoProException(CryptoProErrorCode.TOKEN_OR_CERTIFICATE_ABSENT, "Synthetic absent token");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_B, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedB, SHOP_B, PIPELINE));

        ZnackSigningSession.authorizePipelineResumption(SHOP_A, PIPELINE);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guardedA, SHOP_A, PIPELINE).cms());
        var otherShop = assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guardedB, SHOP_B, PIPELINE));
        assertEquals(1, callsB.get());
        assertTrue(otherShop.getMessage().contains("CRYPTOPRO_TOKEN_OR_CERTIFICATE_ABSENT"));
    }

    @Test
    void anotherActualFailureRevokesEveryScopedResumptionForItsShopOnly() throws Exception {
        var callsA = new AtomicInteger();
        var callsSibling = new AtomicInteger();
        var callsOtherShop = new AtomicInteger();
        var guardedA = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            throw new CryptoProException(callsA.incrementAndGet() == 1 ? CryptoProErrorCode.TIMEOUT : CryptoProErrorCode.CANCELLED,
                    "Synthetic provider failure");
        });
        var sibling = ZnackSigningSession.guard(SHOP_A, successfulSigner(callsSibling));
        var otherShop = ZnackSigningSession.guard(SHOP_B, (payload, context) -> {
            if (callsOtherShop.incrementAndGet() == 1) throw new CryptoProException(CryptoProErrorCode.CERTIFICATE_EXPIRED, "Synthetic expired certificate");
            return new CryptoProSigningResult(new byte[]{1, 2, 3}, "ok");
        });
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);
        ZnackSigningSession.authorizePipeline(SHOP_B, PIPELINE);
        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertThrows(CryptoProException.class, () -> signForPipeline(otherShop, SHOP_B, PIPELINE));
        ZnackSigningSession.authorizePipelineResumption(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipelineResumption(SHOP_A, PIPELINE + 1);
        ZnackSigningSession.authorizePipelineResumption(SHOP_B, PIPELINE);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(sibling, SHOP_A, PIPELINE + 1).cms());

        assertThrows(CryptoProException.class, () -> signForPipeline(guardedA, SHOP_A, PIPELINE));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(sibling, SHOP_A, PIPELINE + 1));
        assertEquals(1, callsSibling.get(), "A new provider failure invalidates the sibling's earlier grant");
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(otherShop, SHOP_B, PIPELINE).cms());
        assertEquals(2, callsOtherShop.get(), "A different shop keeps its own explicit resumption");
    }

    @Test
    void scopedResumptionRequiresItsMatchingPipelineContext() throws Exception {
        var calls = new AtomicInteger();
        var guarded = ZnackSigningSession.guard(SHOP_A, successfulSigner(calls));
        ZnackSigningSession.authorizePipelineResumption(SHOP_A, PIPELINE);
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> guarded.sign(new byte[]{1}, ZnackSignatureContext.TRUE_API_DOCUMENT));
        assertThrows(ZnackSigningSession.SigningDeferredException.class,
                () -> signForPipeline(guarded, SHOP_A, PIPELINE + 1));
        assertEquals(0, calls.get());
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(guarded, SHOP_A, PIPELINE).cms());
        assertEquals(1, calls.get());
    }

    @Test
    void legacyPipelineAuthorizationStillUnblocksAPreviouslyAuthorizedUnvisitedSibling() throws Exception {
        var callsSibling = new AtomicInteger();
        var failed = ZnackSigningSession.guard(SHOP_A, (payload, context) -> {
            throw new CryptoProException(CryptoProErrorCode.CANCELLED, "Synthetic cancellation");
        });
        var sibling = ZnackSigningSession.guard(SHOP_A, successfulSigner(callsSibling));
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE + 1);
        assertThrows(CryptoProException.class, () -> signForPipeline(failed, SHOP_A, PIPELINE));
        assertFalse(ZnackSigningSession.isWaitingForSignature(SHOP_A, PIPELINE + 1));

        ZnackSigningSession.authorizePipeline(SHOP_A, PIPELINE);
        assertArrayEquals(new byte[]{1, 2, 3}, signForPipeline(sibling, SHOP_A, PIPELINE + 1).cms());
        assertEquals(1, callsSibling.get(), "Only the new resume API is narrow; the existing API contract stays unchanged");
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
