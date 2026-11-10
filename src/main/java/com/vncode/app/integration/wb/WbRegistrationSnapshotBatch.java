package com.vncode.app.integration.wb;

import com.google.gson.*;
import com.vncode.app.features.products.ProductKey;
import com.vncode.app.features.products.ProductSnapshot;
import com.vncode.app.integration.listing.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static com.vncode.app.integration.listing.ListingJson.*;

/** One fresh active-WB scan for a frozen registration selection. No trash, mutation,
 * persistent cache fallback or Catalog request. Only selected cards survive a page. */
public final class WbRegistrationSnapshotBatch implements AutoCloseable {
    private static final long MEMORY_LIMIT=16L*1024*1024, TOTAL_LIMIT=512L*1024*1024, CARD_LIMIT=20L*1024*1024;
    private final Shop shop;
    private final Set<ProductKey> selected;
    private final Set<String> targets;
    private final ListingTransport client;
    private final WbProductSnapshotReader reader;
    private final RawCards cards;
    private boolean scanned,closed,failed;

    public WbRegistrationSnapshotBatch(Shop shop,List<ProductKey> selected){this(shop,selected,new ListingHttpClient(shop));}
    /** Explicit transport seam; production and contract checks share the same scan and parser. */
    public WbRegistrationSnapshotBatch(Shop shop,List<ProductKey> selected,ListingTransport client){this(shop,selected,client,MEMORY_LIMIT);}
    WbRegistrationSnapshotBatch(Shop owner,List<ProductKey> keys,ListingTransport client,long memoryLimit){
        ListingIdentity.require(owner);if(owner.getMarketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("Not a WB registration");
        shop=new Shop(owner.getId(),owner.getName(),owner.getMarketplace(),owner.getClientId(),owner.getApiKey());
        var unique=new LinkedHashSet<ProductKey>(Objects.requireNonNull(keys));
        if(unique.isEmpty()||unique.size()>10_000)throw new IllegalArgumentException("REGISTRATION_SELECTION_TOO_LARGE");
        var ids=new LinkedHashSet<String>();for(var key:unique){
            if(key.shopId()!=shop.getId()||key.marketplace()!=shop.getMarketplace())throw new SecurityException("Foreign listing owner");
            if(!key.cardId().matches("[1-9][0-9]{0,19}"))throw new IllegalArgumentException("Invalid WB card identity");ids.add(key.cardId());
        }
        this.selected=Set.copyOf(unique);targets=Set.copyOf(ids);this.client=Objects.requireNonNull(client);
        reader=new WbProductSnapshotReader(s->client);cards=new RawCards(memoryLimit);
    }
    public ProductSnapshot read(ProductKey key)throws IOException {
        var result=reader.registrationCard(shop,key,observedCard(key));ListingIdentity.require(shop,key);return result;
    }
    /** Raw fresh lookup also recovers WB's occasionally empty exact-nmID search. */
    JsonObject observedCard(ProductKey key)throws IOException {
        requireOpen();if(!selected.contains(key))throw new SecurityException("WB_REGISTRATION_UNSELECTED_PRODUCT");
        ListingIdentity.require(shop,key);WbProductSnapshotReader.checkCancelled();
        if(!scanned)scan();
        var card=cards.get(key.cardId());if(card==null)throw new WbProductSnapshotReader.CardReadException("WB_CARD_NOT_FOUND",key,List.of());
        ListingIdentity.require(shop,key);return card.deepCopy();
    }
    private void scan()throws IOException {
        var remaining=new HashSet<>(targets);var observed=new HashSet<String>();var seenCursors=new HashSet<String>();var cursor=new JsonObject();cursor.addProperty("limit",100);
        try{
            for(int page=0;page<20_000;page++){
                WbProductSnapshotReader.checkCancelled();ListingIdentity.require(shop);
                var settings=new JsonObject();settings.add("cursor",cursor);var filter=new JsonObject();filter.addProperty("withPhoto",-1);settings.add("filter",filter);
                var sort=new JsonObject();sort.addProperty("ascending",true);settings.add("sort",sort);var body=new JsonObject();body.add("settings",settings);
                var response=WbProductSnapshotReader.page(client.call("content/v2/get/cards/list",body,false));
                WbProductSnapshotReader.checkCancelled();ListingIdentity.require(shop);
                var pageCards=response.getAsJsonArray("cards");requireCount(response,pageCards.size());
                for(var raw:pageCards){
                    if(!raw.isJsonObject())throw new IOException("WB_CATALOG_RESPONSE_INVALID");var card=raw.getAsJsonObject();String id=text(card,"nmID");
                    if(!id.matches("[1-9][0-9]{0,19}"))throw new IOException("WB_CATALOG_RESPONSE_INVALID");
                    if(!targets.contains(id))continue; // Incomplete unrelated products do not block this selection.
                    if(!observed.add(id))throw new IOException("WB_CATALOG_DUPLICATE_PRODUCT");
                    cards.put(id,card);remaining.remove(id);
                }
                if(remaining.isEmpty()||pageCards.isEmpty()){scanned=true;return;}
                // WB may return a capped/short page; only an empty page establishes absence.
                cursor=WbProductSnapshotReader.nextCursor(response,pageCards.size(),seenCursors);
            }
            throw new IOException("WB_CATALOG_PAGE_LIMIT");
        }catch(IOException|RuntimeException failure){failed=true;throw failure;}
    }
    static void requireCount(JsonObject response,int count)throws IOException {
        var total=obj(response,"cursor").get("total");
        try{if(total==null||!total.isJsonPrimitive()||!total.getAsJsonPrimitive().isNumber()||total.getAsBigDecimal().intValueExact()!=count)throw new ArithmeticException();}
        catch(RuntimeException invalid){throw new IOException("WB_CATALOG_CURSOR_INVALID",invalid);}
    }
    private void requireOpen()throws IOException {if(closed)throw new IOException("WB_REGISTRATION_BATCH_CLOSED");if(failed)throw new IOException("WB_REGISTRATION_BATCH_FAILED");}
    long retainedBytes(){return cards.memoryBytes;}
    Path spillDirectory(){return cards.directory;}
    @Override public void close()throws IOException {if(!closed){closed=true;cards.close();}}

    /** Bounded selected-only memory with private run-local spill files; never resumed as fresh proof. */
    private static final class RawCards implements AutoCloseable {
        private record Entry(JsonObject value,long bytes){}
        private final long memoryLimit;
        private final LinkedHashMap<String,Entry> memory=new LinkedHashMap<>(128,.75f,true);
        private final Map<String,Path> files=new HashMap<>();
        private long memoryBytes,totalBytes;private Path directory;
        RawCards(long memoryLimit){if(memoryLimit<1||memoryLimit>MEMORY_LIMIT)throw new IllegalArgumentException("Invalid WB snapshot memory limit");this.memoryLimit=memoryLimit;}
        void put(String key,JsonObject value)throws IOException {
            var encoded=value.toString().getBytes(StandardCharsets.UTF_8);long size=encoded.length;
            if(size>CARD_LIMIT||totalBytes+size>TOTAL_LIMIT)throw new IOException("REGISTRATION_REVIEW_TOO_LARGE");
            while(memoryBytes+size>memoryLimit&&!memory.isEmpty()){
                var oldest=memory.entrySet().iterator().next();spill(oldest.getKey(),oldest.getValue().value());memoryBytes-=oldest.getValue().bytes();memory.remove(oldest.getKey());
            }
            if(size<=memoryLimit){memory.put(key,new Entry(value.deepCopy(),size));memoryBytes+=size;}else spill(key,value);
            totalBytes+=size;
        }
        JsonObject get(String key)throws IOException {
            var entry=memory.get(key);if(entry!=null)return entry.value();var file=files.get(key);if(file==null)return null;
            if(Files.size(file)>CARD_LIMIT)throw new IOException("WB_REGISTRATION_CACHE_INVALID");
            try{var value=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8));if(!value.isJsonObject())throw new JsonParseException("Expected object");return value.getAsJsonObject();}
            catch(JsonParseException invalid){throw new IOException("WB_REGISTRATION_CACHE_INVALID",invalid);}
        }
        private void spill(String key,JsonObject value)throws IOException {
            if(directory==null){directory=Files.createTempDirectory("vncode-wb-registration-");privateAccess(directory,true);}
            var file=Files.createTempFile(directory,"card-",".json");
            try{privateAccess(file,false);Files.writeString(file,value.toString(),StandardCharsets.UTF_8);files.put(key,file);}
            catch(IOException|RuntimeException failure){try{Files.deleteIfExists(file);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        private static void privateAccess(Path path,boolean directory)throws IOException {
            var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class);
            if(posix!=null){posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return;}
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
            if(acl==null)throw new IOException("WB_REGISTRATION_PRIVATE_STORAGE_UNAVAILABLE");
            var owner=Files.getOwner(path);var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(directory)entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);acl.setAcl(List.of(entry.build()));
        }
        @Override public void close()throws IOException {
            memory.clear();memoryBytes=0;totalBytes=0;IOException failure=null;
            for(var path:files.values())try{Files.deleteIfExists(path);}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            files.clear();if(directory!=null)try{Files.deleteIfExists(directory);}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            directory=null;if(failure!=null)throw failure;
        }
    }
}
