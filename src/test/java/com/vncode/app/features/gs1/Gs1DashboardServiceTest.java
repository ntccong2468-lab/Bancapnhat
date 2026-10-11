package com.vncode.app.features.gs1;
import com.vncode.app.integration.gs1.Gs1Membership;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class Gs1DashboardServiceTest {
    @TempDir Path directory;private final LocalDate today=LocalDate.of(2026,10,11);
    private Gs1Membership member(List<Gs1Membership.Prefix> prefixes){return new Gs1Membership("7707083893","Fixture",true,false,today.plusYears(1),prefixes,Instant.parse("2026-10-11T00:00:00Z"),Gs1Membership.CATALOG_SOURCE);}
    @Test void expiredPrefixesAreExcluded(){var service=new Gs1DashboardService(new Gs1Repository(directory.resolve("gs1.db")));var result=service.availability(member(List.of(new Gs1Membership.Prefix("4601234",100,List.of()),new Gs1Membership.Prefix("4609999",900,List.of(),today.minusDays(1),false))),today);assertEquals(100,result.available().orElseThrow());assertEquals(1,result.activePrefixes());assertTrue(result.complete());}
    @Test void missingLimitMakesTotalPartial(){var service=new Gs1DashboardService(new Gs1Repository(directory.resolve("gs1.db")));var result=service.availability(member(List.of(new Gs1Membership.Prefix("4601234",100,List.of()),new Gs1Membership.Prefix("4609999",null,List.of()))),today);assertEquals(100,result.available().orElseThrow());assertFalse(result.complete());assertEquals(1,result.unknownPrefixes());assertTrue(service.availability(member(List.of()),today).available().isEmpty());}
    @Test void environmentAndInnCacheIsolation(){var repo=new Gs1Repository(directory.resolve("gs1.db"));repo.saveMembership(member(List.of()),"production");var service=new Gs1DashboardService(repo);assertTrue(service.cached("7707083893","production").isPresent());assertTrue(service.cached("7707083893","sandbox").isEmpty());assertTrue(service.cached("7811089030","production").isEmpty());}
    @Test void legacyUnscopedMembershipIsNotTrustedAsScoped(){var repo=new Gs1Repository(directory.resolve("gs1.db"));repo.saveMembership(member(List.of()));assertTrue(new Gs1DashboardService(repo).cached("7707083893","production").isEmpty());}
    @Test void duplicatePrefixesDoNotInflateAllowance(){var service=new Gs1DashboardService(new Gs1Repository(directory.resolve("gs1.db")));var result=service.availability(member(List.of(new Gs1Membership.Prefix("4601234",100,List.of()),new Gs1Membership.Prefix("4601234",100,List.of()))),today);assertEquals(100,result.available().orElseThrow());}
}
