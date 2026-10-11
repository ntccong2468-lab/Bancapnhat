package com.vncode.app.features.news;
import com.vncode.app.models.Shop;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.wb.WbNewsClient;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class OfficialNewsService {
 @FunctionalInterface public interface Reader {WbNewsClient.NewsBatch readSince(Shop shop,Instant from,String id)throws IOException;default Duration minimumInterval(){return Duration.ZERO;}}
 private final SellerNewsRepository repository;private final Reader reader;private final Clock clock;private final Map<NewsScope,Object> locks=new ConcurrentHashMap<>();
 public OfficialNewsService(){this(new SellerNewsRepository(),new WbNewsClient(),Clock.systemUTC());}
 public OfficialNewsService(SellerNewsRepository repository,Reader reader,Clock clock){this.repository=Objects.requireNonNull(repository);this.reader=Objects.requireNonNull(reader);this.clock=Objects.requireNonNull(clock);}
 public List<SellerNews> cached(NewsScope scope){return repository.cached(scope);}
 public List<SellerNews> refresh(Shop shop,NewsScope scope,boolean force)throws IOException {
  scope.requireOwner(shop);if(scope.marketplace()!=Marketplace.WILDBERRIES)throw new SecurityException("No WB requests for Ozon shops");
  var owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
  synchronized(locks.computeIfAbsent(scope,key->new Object())) {
   var now=clock.instant();if(repository.retryAt(scope).filter(value->value.isAfter(now)).isPresent())throw new WbNewsClient.RateLimitedException(Duration.between(now,repository.retryAt(scope).orElseThrow()));
   var last=repository.checkedAt(scope);var minimum=reader.minimumInterval();var interval=force?minimum:minimum.compareTo(Duration.ofMinutes(30))>0?minimum:Duration.ofMinutes(30);
   if(last.filter(value->value.plus(interval).isAfter(now)).isPresent())return cached(scope);
   var old=cached(scope);Instant from=old.isEmpty()?now.minus(Duration.ofDays(30)):old.getFirst().publishedAt().minus(Duration.ofDays(1));String id=repository.cursor(scope);var merged=new LinkedHashMap<String,SellerNews>();
   try {
    for(int page=0;page<10;page++) {
     var batch=reader.readSince(owner,from,id);for(var item:batch.items()){if(!scope.equals(item.scope()))throw new SecurityException("News result owner mismatch");merged.put(item.sourceId(),item);}
     String next=batch.nextFromId();boolean more=batch.items().size()==100&&next!=null&&!next.isBlank()&&!next.equals(id);
     if(more&&new java.math.BigInteger(next).compareTo(new java.math.BigInteger(id.isBlank()?"0":id))<=0)throw new IOException("WB news cursor did not advance");
     id=more?next:"";repository.saveBatch(scope,List.copyOf(merged.values()),now,id);
     if(!more||!minimum.isZero())break;
    }
    return cached(scope);
   }catch(WbNewsClient.RateLimitedException rate){repository.retryAfter(scope,now.plus(rate.retryAfter()));throw rate;}
  }
 }
 public boolean isRead(SellerNews item){return repository.isRead(item);}
 public void markRead(SellerNews item){repository.markRead(item.scope(),item.sourceId(),item.revision());}
}
