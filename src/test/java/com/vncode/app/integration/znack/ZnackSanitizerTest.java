package com.vncode.app.integration.znack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZnackSanitizerTest {
    @Test void copiedCausesKeepUrlPathButRemoveUserInfoAndEntireQuery(){
        String safe=ZnackSanitizer.diagnostic("ConnectException: https://user:private-password@host.invalid/v3/dictionary/isocountry?opaque=private-query&apikey=private-api\nHTTP status: 403");
        assertTrue(safe.contains("/v3/dictionary/isocountry"));assertTrue(safe.contains("HTTP status: 403"));
        for(String secret:new String[]{"private-password","private-query","private-api"})assertFalse(safe.contains(secret));
    }
    @Test void copiedHeadersDoNotExposeAuthorizationOrCookies(){
        String safe=ZnackSanitizer.diagnostic("Authorization: Basic private-basic\nCookie: session=private-cookie; other=private-other\nSet-Cookie: credential=private-returned\nOperation: COUNTRY_DICTIONARY");
        for(String secret:new String[]{"private-basic","private-cookie","private-other","private-returned"})assertFalse(safe.contains(secret));
        assertTrue(safe.contains("Operation: COUNTRY_DICTIONARY"));assertTrue(safe.contains("[REDACTED]"));
    }
    @Test void nonUrlIdentifiersAndSafeBusinessCodesRemainAvailable(){
        String safe=ZnackSanitizer.diagnostic("HTTP status: 429; code=1090; operation=COUNTRY_DICTIONARY; retry_after_seconds=301; feed_id=fixture-feed");
        assertTrue(safe.contains("code=1090"));assertTrue(safe.contains("retry_after_seconds=301"));assertTrue(safe.contains("feed_id=fixture-feed"));
    }
}
