package com.vncode.app.integration.ozon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.vncode.app.config.Database;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional tracking observations; no schema migration or network call is needed for export. */
final class OzonPostingTrackingStore {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final String PREFIX = "ozon_posting_tracking.v1:";
    private static final String LABEL_PREFIX = "ozon_posting_scanit.v1:";
    record Tracking(String number, boolean verified) {}

    private OzonPostingTrackingStore() {}

    /** Called in the posting's own write transaction; unrelated owners are never persisted. */
    static void recordAll(Connection connection, int shopId, List<OzonPostingDto> postings) throws SQLException {
        if (postings.isEmpty()) return;
        String owner = ListingIdentity.fingerprint(connection, shopId, Marketplace.OZON);
        List<OzonPostingDto> scoped = postings.stream().filter(p -> p.trackingOwner() == null || owner.equals(p.trackingOwner())).toList();
        if (scoped.isEmpty()) return;
        try(var write=connection.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            for(var posting:scoped)if(owner.equals(posting.trackingOwner())&&posting.scanit()!=null){
                write.setString(1,LABEL_PREFIX+shopId+":"+owner+":"+ListingIdentity.hash(posting.postingNumber()));
                write.setString(2,posting.scanit());write.addBatch();
            }
            write.executeBatch();
        }
        Set<String> invalid = new HashSet<>();
        Map<String, Tracking> previous = read(connection, shopId, owner, scoped, invalid);
        try (var write = connection.prepareStatement("INSERT INTO app_config(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            for (OzonPostingDto posting : scoped) {
                if (invalid.contains(posting.postingNumber())) continue; // Preserve unreadable state for recovery.
                Tracking old = previous.get(posting.postingNumber());
                String observed = posting.trackingNumber();
                Tracking next = owner.equals(posting.trackingOwner()) && observed != null && !observed.isBlank() ? new Tracking(observed, true)
                        : old == null ? null : new Tracking(old.number(), false);
                if (next == null || next.equals(old)) continue;
                write.setString(1, key(shopId, owner, posting.postingNumber()));
                write.setString(2, JSON.toJson(next));
                write.addBatch();
                previous.put(posting.postingNumber(), next);
            }
            write.executeBatch();
        }
    }

    /** One bounded lookup per 500 postings, and only for the current frozen shop owner. */
    static Map<String, Tracking> forExport(Shop shop, List<OzonPostingDto> postings) throws SQLException {
        if (shop == null || shop.getId() <= 0 || shop.getMarketplace() != Marketplace.OZON || postings.isEmpty()) return Map.of();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String owner = ListingIdentity.fingerprint(connection, shop.getId(), Marketplace.OZON);
                Map<String, Tracking> result = owner.equals(ListingIdentity.fingerprint(shop))
                        ? Map.copyOf(read(connection, shop.getId(), owner, postings, new HashSet<>())) : Map.of();
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** Persisted native label numbers have a separate key from third-party tracking numbers. */
    static Map<String,OzonPostingDto> withLabelNumbers(Shop shop,Map<String,OzonPostingDto> postings) throws SQLException {
        var result=new LinkedHashMap<>(postings);
        if(shop==null||postings.isEmpty())return result;
        try(var connection=Database.getConnection()){
            String owner=ListingIdentity.fingerprint(connection,shop.getId(),Marketplace.OZON);
            if(!owner.equals(ListingIdentity.fingerprint(shop)))return result;
            var keys=new LinkedHashMap<String,String>();
            postings.keySet().forEach(number->keys.put(LABEL_PREFIX+shop.getId()+":"+owner+":"+ListingIdentity.hash(number),number));
            var all=new ArrayList<>(keys.keySet());
            for(int start=0;start<all.size();start+=500){
                var part=all.subList(start,Math.min(start+500,all.size()));
                try(var query=connection.prepareStatement("SELECT key,value FROM app_config WHERE key IN ("+String.join(",",java.util.Collections.nCopies(part.size(),"?"))+")")){
                    for(int i=0;i<part.size();i++)query.setString(i+1,part.get(i));
                    try(var rows=query.executeQuery()){while(rows.next()){
                        String number=keys.get(rows.getString(1)),value=rows.getString(2);
                        if(value!=null&&value.matches("[A-Za-z0-9-]{6,128}"))result.put(number,postings.get(number).withScanit(value));
                    }}
                }
            }
        }
        return result;
    }

    private static Map<String, Tracking> read(Connection connection, int shopId, String owner,
            List<OzonPostingDto> postings, Set<String> invalid) throws SQLException {
        Map<String, String> postingByKey = new LinkedHashMap<>();
        for (OzonPostingDto posting : postings) postingByKey.put(key(shopId, owner, posting.postingNumber()), posting.postingNumber());
        List<String> keys = new ArrayList<>(postingByKey.keySet());
        Map<String, Tracking> result = new LinkedHashMap<>();
        for (int start = 0; start < keys.size(); start += 500) {
            List<String> chunk = keys.subList(start, Math.min(start + 500, keys.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            try (var query = connection.prepareStatement("SELECT key,value FROM app_config WHERE key IN (" + placeholders + ")")) {
                for (int i = 0; i < chunk.size(); i++) query.setString(i + 1, chunk.get(i));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        try {
                            String number = postingByKey.get(rows.getString(1));
                            JsonElement element = JSON.fromJson(rows.getString(2), JsonElement.class);
                            if (element == null || !element.isJsonObject()) { invalid.add(number); continue; }
                            var object = element.getAsJsonObject();
                            JsonElement text = object.get("number"), verified = object.get("verified");
                            if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()
                                    || verified == null || !verified.isJsonPrimitive() || !verified.getAsJsonPrimitive().isBoolean()) {
                                invalid.add(number); continue;
                            }
                            String value = text.getAsString();
                            if (value.isBlank() || value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl)) {
                                invalid.add(number); continue;
                            }
                            result.put(number, new Tracking(value, verified.getAsBoolean()));
                        } catch (RuntimeException malformed) { invalid.add(postingByKey.get(rows.getString(1))); }
                    }
                }
            }
        }
        return result;
    }

    private static String key(int shopId, String owner, String postingNumber) {
        return PREFIX + shopId + ":" + owner + ":" + ListingIdentity.hash(postingNumber);
    }
}
