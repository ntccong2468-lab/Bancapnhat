package com.vncode.app.integration.gs1;

import java.time.*;
import java.util.*;

/** A sourced GS1 assertion, not inferred from the National Catalog GTIN quota. */
public record Gs1Membership(String inn, String legalName, Boolean member, boolean excluded,
                            LocalDate expiry, List<Prefix> prefixes, Instant checkedAt, String source) {
    public static final String CATALOG_SOURCE = "https://xn--j1ab.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai/rest/gs1/gcp-gln";
    public enum Status { ACTIVE, EXPIRING, EXPIRED, EXCLUDED, NON_MEMBER, UNKNOWN }
    public Gs1Membership {
        requireInn(inn);
        legalName = Objects.requireNonNullElse(legalName, "");
        prefixes = List.copyOf(prefixes);
        Objects.requireNonNull(checkedAt);
        Objects.requireNonNull(source);
    }
    public record Prefix(String gcp, Integer gtinsLeft, List<String> glns) {
        public Prefix {
            if (gcp == null || !gcp.matches("[0-9]{4,12}")) throw new IllegalArgumentException("Invalid GCP");
            if (gtinsLeft != null && gtinsLeft < 0) throw new IllegalArgumentException("Invalid allowance");
            glns = List.copyOf(glns);
        }
    }
    public Status status(LocalDate date) {
        if (excluded) return Status.EXCLUDED;
        if (member == null) return Status.UNKNOWN;
        if (!member) return Status.NON_MEMBER;
        if (expiry == null) return Status.UNKNOWN;
        if (expiry.isBefore(date)) return Status.EXPIRED;
        return expiry.isAfter(date.plusDays(60)) ? Status.ACTIVE : Status.EXPIRING;
    }
    public List<Prefix> visiblePrefixes(LocalDate date) {
        Status status = status(date);
        return status == Status.ACTIVE || status == Status.EXPIRING ? prefixes : List.of();
    }
    public boolean stale(Instant now) { return checkedAt.plus(Duration.ofHours(24)).isBefore(now); }
    public static String requireInn(String inn) {
        if (inn == null || !inn.matches("[0-9]{10}|[0-9]{12}")) throw new IllegalArgumentException("Invalid enterprise INN");
        return inn;
    }
    @Override public String toString() { return "Gs1Membership[status assertion, checkedAt=" + checkedAt + "]"; }
}
