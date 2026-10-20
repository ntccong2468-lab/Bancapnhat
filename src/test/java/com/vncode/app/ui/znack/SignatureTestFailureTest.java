package com.vncode.app.ui.znack;

import com.vncode.app.integration.znack.ZnackApiClient.ZnackApiException;
import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class SignatureTestFailureTest {
    @Test void remoteTimeoutDoesNotBecomeCryptoProSigningFailure() {
        var error=SignatureTestFailure.classify(new SocketTimeoutException("token=sensitive-timeout"),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("NETWORK_TIMEOUT",error.code());
        assertEquals("znack.signature.error.remote_unavailable",error.messageKey());
        assertEquals(Duration.ofSeconds(5),error.retryAfter());
        assertNull(error.cryptoError());
        assertFalse(error.diagnostic().contains("sensitive-timeout"));
        assertFalse(error.diagnostic().contains("CRYPTOPRO"));
    }

    @Test void authenticationRejectionHasItsOwnCodeAndDoesNotExposeRemoteDetails() {
        var failure=new ZnackApiException("access denied",403,"secret=private-payload","POST","https://private.invalid/auth?credential=private-url");
        var error=SignatureTestFailure.classify(failure,SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("ZNACK_AUTH_REQUIRED",error.code());
        assertEquals("znack.signature.error.remote_rejected",error.messageKey());
        assertEquals(Integer.valueOf(403),error.httpStatus());
        assertNull(error.retryAfter());assertNull(error.cryptoError());
        assertEquals("phase=CRPT_AUTHENTICATION; code=ZNACK_AUTH_REQUIRED; http_status=403",error.diagnostic());
        assertFalse(error.diagnostic().contains("private"));
    }

    @Test void rateLimitKeepsTheActualServerDelayWithoutCappingIt() {
        var error=SignatureTestFailure.classify(new ZnackApiException("limit",429,"","GET","",Duration.ofSeconds(301)),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("ZNACK_RATE_LIMIT",error.code());
        assertEquals("znack.signature.error.remote_rate_limit",error.messageKey());
        assertEquals(Duration.ofSeconds(301),error.retryAfter());
        assertTrue(error.diagnostic().contains("retry_after_seconds=301"));
    }

    @Test void rateLimitWithMissingOrInvalidDelayUsesABoundedDefault() {
        for(Duration retry:new Duration[]{null,Duration.ZERO,Duration.ofSeconds(-1)}) {
            var error=SignatureTestFailure.classify(new ZnackApiException("limit",429,"","GET","",retry),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
            assertEquals(Duration.ofSeconds(60),error.retryAfter());
        }
    }

    @Test void wrappedCryptoProChallengeFailureRetainsItsActualCode() {
        var crypto=new CryptoProException(CryptoProErrorCode.CRYPTCP_LICENSE_INVALID,"local cryptcp license error");
        var error=SignatureTestFailure.classify(new CompletionException(new IOException("challenge failed",crypto)),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals(SignatureTestFailure.Phase.LOCAL_SIGNATURE,error.phase());
        assertEquals("CRYPTOPRO_CRYPTCP_LICENSE_INVALID",error.code());
        assertSame(crypto,error.cryptoError());assertNull(error.retryAfter());
    }

    @Test void remoteServerFailureRemainsRetryableRemoteFailure() {
        var error=SignatureTestFailure.classify(new ZnackApiException("down",503,"","POST","",Duration.ofSeconds(12)),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("ZNACK_HTTP_503",error.code());
        assertEquals("znack.signature.error.remote_unavailable",error.messageKey());
        assertEquals(Duration.ofSeconds(12),error.retryAfter());
        assertNull(error.cryptoError());
    }

    @Test void wrappedDnsFailureKeepsSafeNetworkReason() {
        var error=SignatureTestFailure.classify(new CompletionException(new java.net.UnknownHostException("private-host.invalid")),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("NETWORK_DNS",error.code());
        assertNull(error.cryptoError());
        assertFalse(error.diagnostic().contains("private-host"));
    }

    @Test void plainRemoteIoFailureDoesNotExposeItsPotentialCredentialMessage() {
        var error=SignatureTestFailure.classify(new IOException("Authorization Bearer do-not-print"),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("NETWORK_OR_RESPONSE",error.code());
        assertEquals("znack.signature.error.remote_unavailable",error.messageKey());
        assertFalse(error.diagnostic().contains("do-not-print"));
    }

    @Test void saveFailureAfterAuthenticationDoesNotInvalidateLocalSigningDiagnostics() {
        var error=SignatureTestFailure.classify(new IOException("private local path"),SignatureTestFailure.Phase.SAVE_VERIFICATION);
        assertEquals("SIGNATURE_VERIFICATION_SAVE_FAILED",error.code());
        assertEquals("znack.signature.error.verification_save",error.messageKey());
        assertNull(error.cryptoError());assertNull(error.retryAfter());
        assertFalse(error.diagnostic().contains("private"));
    }

    @Test void unknownLocalFailureIsNotInventedAsCryptoProFailure() {
        var error=SignatureTestFailure.classify(new IllegalStateException("private-details"),SignatureTestFailure.Phase.LOCAL_SIGNATURE);
        assertEquals("SIGNATURE_VERIFICATION_FAILED",error.code());
        assertEquals("znack.signature.error.verification_failed",error.messageKey());
        assertNull(error.cryptoError());assertFalse(error.diagnostic().contains("private-details"));
    }

    @Test void cancellationDoesNotClaimACompletedVerification() {
        var error=SignatureTestFailure.classify(new java.io.InterruptedIOException("cancelled"),SignatureTestFailure.Phase.CRPT_AUTHENTICATION);
        assertEquals("SIGNATURE_VERIFICATION_CANCELLED",error.code());
        assertEquals("znack.signature.error.verification_cancelled",error.messageKey());
        assertNull(error.cryptoError());assertNull(error.retryAfter());
    }
}
