package com.vncode.app.shared;

import java.util.*;
import java.util.function.ToLongFunction;

/** Access-order LRU with a measured memory weight; oversized entries are never retained. */
public final class ThumbnailCache<V> {
    private record Entry<V>(V value,long bytes){}
    private final LinkedHashMap<String,Entry<V>> entries=new LinkedHashMap<>(16,0.75f,true);
    private final long budget;private final ToLongFunction<V> weigh;private long bytes;
    public ThumbnailCache(long budget,ToLongFunction<V> weigh){if(budget<1)throw new IllegalArgumentException("Invalid cache budget");this.budget=budget;this.weigh=Objects.requireNonNull(weigh);}
    public synchronized V get(String key){var entry=entries.get(key);return entry==null?null:entry.value();}
    public synchronized void put(String key,V value) {
        Objects.requireNonNull(key);Objects.requireNonNull(value);long weight=weigh.applyAsLong(value);if(weight<0)throw new IllegalArgumentException("Negative cache weight");
        var previous=entries.remove(key);if(previous!=null)bytes-=previous.bytes();
        if(weight>budget)return;
        while(bytes>budget-weight&&!entries.isEmpty()){var it=entries.entrySet().iterator();var entry=it.next();bytes-=entry.getValue().bytes();it.remove();}
        entries.put(key,new Entry<>(value,weight));bytes+=weight;
    }
    public long budgetBytes(){return budget;}
    public synchronized long retainedBytes(){return bytes;}
    public synchronized int size(){return entries.size();}
    public synchronized void clear(){entries.clear();bytes=0;}
}
