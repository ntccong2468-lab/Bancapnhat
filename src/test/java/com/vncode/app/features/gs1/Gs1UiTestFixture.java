package com.vncode.app.features.gs1;

import com.vncode.app.integration.gs1.Gs1Membership;
import com.vncode.app.integration.znack.*;
import com.vncode.app.models.Shop;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Synthetic source observations, never real signing, mailbox or marketplace credentials. */
public final class Gs1UiTestFixture {
    public static void sync(Shop shop,boolean good)throws Exception {
        var member=new Gs1Membership("7700000000","Synthetic enterprise",true,false,LocalDate.now().plusYears(1),List.of(new Gs1Membership.Prefix("4601234",735,List.of("4601234000000"))),Instant.now(),Gs1Membership.CATALOG_SOURCE);
        var balance=new BalanceSnapshot(new BalanceScope("7700000000","production","2","1"),new BigDecimal("4737.09"),"RUB",Instant.now(),URI.create("https://markirovka.crpt.ru/bff-elk/v1/organisation/balance"));
        new Gs1SyncService(new Gs1AutomaticSyncService((s,settings,scope)->new Gs1AutomaticSyncService.Result(good?member:null,good?"source@example.invalid":"",good?List.of(balance):List.of(),good?"":"NETWORK_TIMEOUT",good?"":"NETWORK_DNS",true,false))).sync(shop);
    }
}
