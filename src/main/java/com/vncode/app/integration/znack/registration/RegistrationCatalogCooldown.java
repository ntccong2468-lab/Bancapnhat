package com.vncode.app.integration.znack.registration;

import com.vncode.app.features.sync.SyncFailure;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppPaths;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Hands an asynchronous Catalog limit back to its exact owner/profile monitor. */
final class RegistrationCatalogCooldown {
    private static final RegistrationCatalogCooldown CURRENT=new RegistrationCatalogCooldown(Clock.systemUTC());
    private static final int MAX_OWNERS=512;
    private record Owner(Path profile,String identity){}
    private final Clock clock;
    private final LinkedHashMap<Owner,Instant> until=new LinkedHashMap<>();
    RegistrationCatalogCooldown(Clock clock){this.clock=java.util.Objects.requireNonNull(clock);}

    static void record(Shop shop,Throwable failure){
        CURRENT.recordFailure(AppPaths.appDataDir(),ListingIdentity.fingerprint(shop),failure);
    }
    static SyncFailure warning(Shop shop){return CURRENT.pending(AppPaths.appDataDir(),ListingIdentity.fingerprint(shop));}

    void recordFailure(Path profile,String identity,Throwable failure){
        if(CatalogFeedback.rateLimited(failure))defer(profile,identity,SyncFailure.of(failure,0).delay());
    }

    synchronized void defer(Path profile,String identity,Duration delay){
        if(delay==null||delay.isNegative()||delay.isZero())return;
        Instant now=clock.instant();until.entrySet().removeIf(entry->!entry.getValue().isAfter(now));
        var owner=new Owner(profile.toAbsolutePath().normalize(),identity);Instant deadline;
        try{deadline=now.plus(delay);}catch(java.time.DateTimeException|ArithmeticException overflow){deadline=Instant.MAX;}
        var previous=until.get(owner);if(previous!=null&&previous.isAfter(deadline))return;
        until.put(owner,deadline);
        while(until.size()>MAX_OWNERS)until.remove(until.keySet().iterator().next());
    }
    synchronized SyncFailure pending(Path profile,String identity){
        var owner=new Owner(profile.toAbsolutePath().normalize(),identity);var deadline=until.get(owner);if(deadline==null)return null;
        Duration delay=Duration.between(clock.instant(),deadline);
        if(delay.isNegative()||delay.isZero()){until.remove(owner);return null;}
        return new SyncFailure("ZNACK_RATE_LIMIT",delay,false);
    }
}
