package com.vncode.app.integration.znack.registration;

import com.google.gson.*;
import com.vncode.app.features.gtinsync.*;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.integration.znack.GtinNormalizer;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Official etagslist + feed-product/good_ids fallback beyond product-list's 10k window. */
final class NationalCatalogVersionSource implements RegisteredGtinSource {
    @FunctionalInterface interface VersionReader{JsonElement read(int offset)throws Exception;}
    @FunctionalInterface interface IdReader{JsonElement read(List<String> ids)throws Exception;}
    private final int shop;private final String scope;private final VersionReader versions;private final IdReader cards;
    private final NationalCatalogGtinSource.CardReader gtinCards,documents;
    private final NationalCatalogGtinSource.AdditionalGtinReader additional;
    private final Consumer<NationalCatalogGtinSource.Progress> progress;private final Runnable guard;
    private final Consumer<NationalCatalogGtinSource.CardIssue> issues;private final boolean resume;
    NationalCatalogVersionSource(int shop,String scope,VersionReader versions,IdReader cards,
            NationalCatalogGtinSource.CardReader gtinCards,NationalCatalogGtinSource.AdditionalGtinReader additional,
            NationalCatalogGtinSource.CardReader documents,Consumer<NationalCatalogGtinSource.CardIssue> issues,
            Consumer<NationalCatalogGtinSource.Progress> progress,Runnable guard,boolean resume){
        this.shop=shop;this.scope=scope;this.versions=versions;this.cards=cards;this.gtinCards=gtinCards;this.additional=additional;
        this.documents=documents;this.issues=issues;this.progress=progress;this.guard=guard;this.resume=resume;
    }
    public List<RegisteredGtin> read(int requested)throws Exception{
        if(requested!=shop)throw new IllegalArgumentException("shop_mismatch");
        guard.run();
        // A fresh no-match lookup must not clear or borrow an ongoing background scan.
        var checkpoint=new CatalogVersionCheckpoint(shop,resume?scope:scope+"#lookup:"+UUID.randomUUID());var state=checkpoint.load(resume);
        long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
        try{
            while(!state.listed()){
                check(deadline);progress.accept(new NationalCatalogGtinSource.Progress("READING_VERSIONS",state.offset(),state.total()==null?0:state.total()));
                var response=versions.read(state.offset());guard.run();
                var result=object(response,"result");var goods=array(result,"goods");
                int total=number(result,"total"),next=number(result,"last_product_number");
                if(total<0||total>100_000||state.total()!=null&&total!=state.total()||goods.size()>100
                        ||next!=state.offset()+goods.size()||next>total||goods.isEmpty()&&next<total
                        ||result.has("offset")&&number(result,"offset")!=state.offset()
                        ||result.has("goods_count")&&number(result,"goods_count")!=goods.size())throw invalid("CATALOG_INDEX_CHANGED");
                var entries=new ArrayList<CatalogVersionCheckpoint.Entry>();var unique=new HashSet<String>();
                for(var element:goods){var row=element.getAsJsonObject();String id=text(row,"good_id"),etag=text(row,"etag");
                    if(!id.matches("[1-9][0-9]{0,63}")||etag.isBlank()||etag.length()>512||!unique.add(id))throw invalid("CATALOG_INDEX_INVALID");
                    entries.add(new CatalogVersionCheckpoint.Entry(id,etag,null,null));
                }
                checkpoint.append(state,total,entries);state=new CatalogVersionCheckpoint.State(next,total,next==total);
            }
            for(var pending=checkpoint.entries(true);!pending.isEmpty();pending=checkpoint.entries(true)){
                check(deadline);progress.accept(new NationalCatalogGtinSource.Progress("READING_CARDS",state.offset()-remaining(checkpoint),state.offset()));
                var response=cards.read(pending.stream().map(CatalogVersionCheckpoint.Entry::id).toList());guard.run();Instant observed=Instant.now();
                var rows=array(response.getAsJsonObject(),"result");var expected=new HashMap<String,CatalogVersionCheckpoint.Entry>();pending.forEach(e->expected.put(e.id(),e));
                var observedRows=new ArrayList<CatalogVersionCheckpoint.Entry>();var seen=new HashSet<String>();
                for(var element:rows){var card=element.getAsJsonObject();String id=text(card,"good_id");var entry=expected.get(id);
                    if(entry==null||!seen.add(id))throw invalid("CATALOG_INDEX_CARD_IDENTITY_CHANGED");
                    if(card.has("_etag")&&!entry.etag().equals(text(card,"_etag")))throw invalid("CATALOG_INDEX_CHANGED");
                    observedRows.add(new CatalogVersionCheckpoint.Entry(id,entry.etag(),card,observed));
                }
                // A listed but not yet readable card does not prove a complete Catalog.
                if(seen.size()!=expected.size())throw new java.io.IOException("CATALOG_INDEX_CARD_PENDING");
                checkpoint.observed(observedRows);
            }
            var byGtin=new LinkedHashMap<String,CatalogVersionCheckpoint.Entry>();
            for(var entry:checkpoint.entries(false)){
                var codes=new LinkedHashSet<String>();String top=text(entry.card(),"gtin");if(!top.isBlank())codes.add(top);
                if(entry.card().has("identified_by"))for(var value:array(entry.card(),"identified_by")){
                    var id=value.getAsJsonObject();if("gtin".equalsIgnoreCase(text(id,"type")))codes.add(text(id,"value"));
                }
                if(codes.isEmpty())throw invalid("CATALOG_INDEX_CARD_GTIN_MISSING");
                for(String code:codes){if(!RegisteredGtinValidator.isValid(code))throw invalid("CATALOG_INDEX_CARD_GTIN_INVALID");
                    var previous=byGtin.putIfAbsent(GtinNormalizer.normalize(code),entry);if(previous!=null&&!previous.id().equals(entry.id()))throw invalid("CATALOG_INDEX_GTIN_AMBIGUOUS");
                }
            }
            var gtins=List.copyOf(byGtin.keySet());
            var cached=new NationalCatalogGtinSource.CardReader(){
                Instant time;
                public void prepare(Collection<String> values){gtinCards.prepare(values.stream().filter(g->!byGtin.containsKey(GtinNormalizer.normalize(g))).toList());}
                public JsonElement read(String code)throws Exception{guard.run();var entry=byGtin.get(GtinNormalizer.normalize(code));if(entry==null){var response=gtinCards.read(code);time=gtinCards.observedAt();return response;}var result=new JsonObject();var rows=new JsonArray();rows.add(entry.card());result.add("result",rows);time=entry.observedAt();return result;}
                public Instant observedAt(){return time;}
            };
            var parser=new NationalCatalogGtinSource(shop,page->{var result=new JsonObject();var rows=new JsonArray();int start=page*10_000;for(String code:gtins.subList(Math.min(start,gtins.size()),Math.min(start+10_000,gtins.size()))){var row=new JsonObject();row.addProperty("gtin",code);rows.add(row);}result.add("results",rows);result.addProperty("total",gtins.size());return result;},cached,scope,additional,documents).progress(progress).tolerate(issues);
            var result=parser.read(shop);guard.run();checkpoint.clear();return result;
        }catch(Exception failure){
            // Reads may resume; a malformed/changing index starts fresh. Neither path touches
            // the last complete product cache or the mutation/signature journals.
            if(!resume||!CatalogFeedback.retryableRead(failure)||failure instanceof InterruptedException)checkpoint.clear();
            throw failure;
        }
    }
    private static int remaining(CatalogVersionCheckpoint checkpoint){return checkpoint.pendingCount();}
    private void check(long deadline)throws Exception{guard.run();LicenseRuntime.require(Permission.SELLER_API);if(Thread.currentThread().isInterrupted())throw new InterruptedException("ZNACK_SYNC_CANCELLED");if(System.nanoTime()>deadline)throw new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT");}
    private static JsonObject object(JsonElement value,String field){try{return value.getAsJsonObject().getAsJsonObject(field);}catch(RuntimeException e){throw invalid("CATALOG_INDEX_INVALID");}}
    private static JsonArray array(JsonObject value,String field){try{var rows=value.getAsJsonArray(field);if(rows==null)throw invalid("CATALOG_INDEX_INVALID");return rows;}catch(RuntimeException e){throw invalid("CATALOG_INDEX_INVALID");}}
    private static int number(JsonObject row,String name){try{return row.get(name).getAsBigDecimal().intValueExact();}catch(RuntimeException e){throw invalid("CATALOG_INDEX_INVALID");}}
    private static String text(JsonObject row,String name){try{return row.has(name)&&!row.get(name).isJsonNull()?row.get(name).getAsString().strip():"";}catch(RuntimeException e){throw invalid("CATALOG_INDEX_INVALID");}}
    private static IllegalStateException invalid(String code){return new IllegalStateException(code);}
}
