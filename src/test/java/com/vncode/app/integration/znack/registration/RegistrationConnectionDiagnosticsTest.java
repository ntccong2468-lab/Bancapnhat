package com.vncode.app.integration.znack.registration;

import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.vncode.app.integration.znack.registration.RegistrationConnectionDiagnostics.*;

class RegistrationConnectionDiagnosticsTest {
    @Test void reportsEveryStepOnlyAfterItsActualCheck()throws Exception{
        var fake=new Fake();var events=new ArrayList<Event>();check(fake,events::add);
        var last=last(events);for(var step:Step.values())assertEquals(State.VERIFIED,last.get(step).state(),step.name());
        assertEquals(List.of("marketplace","certificate","authentication","enterprise","catalog"),fake.calls);
    }
    @Test void signerFailureDoesNotPretendAccountOrCatalogWereVerified()throws Exception{
        var fake=new Fake();fake.signingFailure=new CryptoProException(CryptoProErrorCode.PRIVATE_KEY_UNAVAILABLE,"secret-token");
        var events=new ArrayList<Event>();check(fake,events::add);var last=last(events);
        assertEquals(State.FAILED,last.get(Step.SIGNATURE).state());assertEquals("CRYPTOPRO_PRIVATE_KEY_UNAVAILABLE",last.get(Step.SIGNATURE).code());
        assertEquals(State.UNVERIFIED,last.get(Step.ENTERPRISE).state());assertEquals(State.UNVERIFIED,last.get(Step.CATALOG).state());
        assertFalse(events.toString().contains("secret-token"));assertFalse(fake.calls.contains("catalog"));
    }
    @Test void networkFailureBeforeChallengeLeavesSigningUnverified()throws Exception{
        var fake=new Fake();fake.signingFailure=new SocketTimeoutException("secret-token");var events=new ArrayList<Event>();check(fake,events::add);var last=last(events);
        assertEquals(State.UNVERIFIED,last.get(Step.SIGNATURE).state());assertEquals(State.FAILED,last.get(Step.AUTHENTICATION).state());assertEquals("NETWORK_TIMEOUT",last.get(Step.AUTHENTICATION).code());
        assertFalse(events.toString().contains("secret-token"));
    }
    @Test void absentSignedChallengeAndUnknownEnterpriseNeverGiveFalseSuccess()throws Exception{
        var fake=new Fake();fake.dontSign=true;var events=new ArrayList<Event>();check(fake,events::add);var last=last(events);
        assertEquals(State.UNVERIFIED,last.get(Step.AUTHENTICATION).state());assertEquals(State.UNVERIFIED,last.get(Step.CATALOG).state());assertFalse(fake.calls.contains("enterprise"));
        fake=new Fake();fake.enterpriseFailure=new IllegalStateException("CATALOG_OWNER_UNVERIFIED");events.clear();check(fake,events::add);last=last(events);
        assertEquals(State.FAILED,last.get(Step.ENTERPRISE).state());assertEquals(State.UNVERIFIED,last.get(Step.CATALOG).state());assertFalse(fake.calls.contains("catalog"));
    }
    @Test void localCmsOutputAloneDoesNotProveRemoteSignatureAcceptance()throws Exception{
        var fake=new Fake();fake.afterSigningFailure=new IOException("unauthorized");var events=new ArrayList<Event>();check(fake,events::add);var last=last(events);
        assertEquals(State.UNVERIFIED,last.get(Step.SIGNATURE).state());assertEquals(State.FAILED,last.get(Step.AUTHENTICATION).state());assertEquals(State.UNVERIFIED,last.get(Step.CATALOG).state());
    }
    @Test void marketplaceFailureDoesNotHideIndependentCatalogResults()throws Exception{
        var fake=new Fake();fake.marketplaceFailure=new IOException("WB_TOKEN_SECRET");var events=new ArrayList<Event>();check(fake,events::add);var last=last(events);
        assertEquals(State.FAILED,last.get(Step.MARKETPLACE).state());assertEquals(State.VERIFIED,last.get(Step.CATALOG).state());assertFalse(events.toString().contains("WB_TOKEN_SECRET"));
    }
    private static Map<Step,Event> last(List<Event> events){var result=new EnumMap<Step,Event>(Step.class);events.forEach(e->result.put(e.step(),e));return result;}
    private static final class Fake implements Checker{
        final List<String> calls=new ArrayList<>();Exception marketplaceFailure,signingFailure,afterSigningFailure,enterpriseFailure;boolean dontSign;
        @Override public void marketplace()throws Exception{calls.add("marketplace");if(marketplaceFailure!=null)throw marketplaceFailure;}
        @Override public void certificate(){calls.add("certificate");}
        @Override public void authenticate(Runnable signed)throws Exception{calls.add("authentication");if(signingFailure!=null)throw signingFailure;if(!dontSign)signed.run();if(afterSigningFailure!=null)throw afterSigningFailure;}
        @Override public void enterprise()throws Exception{calls.add("enterprise");if(enterpriseFailure!=null)throw enterpriseFailure;}
        @Override public void catalog(){calls.add("catalog");}
    }
}
