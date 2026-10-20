package com.vncode.app.shared;

import javafx.collections.ObservableList;
import java.util.*;
import java.util.function.*;

/** Applies an owned page snapshot without notifying unchanged rows or resetting its viewport. */
public final class TableRows {
    private TableRows() {}
    /** Retains last rendered values when a legacy model can be mutated in place. */
    public static final class Tracked<T,K>{
        private final Map<K,Object> rendered=new HashMap<>();
        public void clear(){rendered.clear();}
        public int merge(ObservableList<T> rows,List<T> incoming,Function<T,K> key,Function<T,Object> snapshot){
            var next=incoming==null?List.<T>of():List.copyOf(incoming);var values=new HashMap<K,Object>();
            next.forEach(row->values.put(key.apply(row),snapshot.apply(row)));
            int changed=TableRows.merge(rows,next,key,(a,b)->Objects.equals(rendered.get(key.apply(a)),values.get(key.apply(b))));
            rendered.clear();rendered.putAll(values);return changed;
        }
    }
    public static <T,K> int merge(ObservableList<T> rows,List<T> next,Function<T,K> key){
        return merge(rows,next,key,Objects::equals);
    }
    public static <T,K> int merge(ObservableList<T> rows,List<T> incoming,Function<T,K> key,BiPredicate<T,T> same){
        var next=incoming==null?List.<T>of():List.copyOf(incoming);
        var keys=new HashSet<K>();
        for(var row:next)if(key.apply(row)==null||!keys.add(key.apply(row))){rows.setAll(next);return next.size();}
        if(rows.isEmpty()){rows.setAll(next);return next.size();}
        int changed=rows.size();rows.removeIf(row->!keys.contains(key.apply(row)));changed-=rows.size();
        for(int i=0;i<next.size();i++){
            var value=next.get(i);var id=key.apply(value);
            if(i<rows.size()&&Objects.equals(id,key.apply(rows.get(i)))){
                if(!same.test(rows.get(i),value)){rows.set(i,value);changed++;}
                continue;
            }
            int existing=-1;
            for(int j=i+1;j<rows.size();j++)if(Objects.equals(id,key.apply(rows.get(j)))){existing=j;break;}
            if(existing>=0){var previous=rows.remove(existing);rows.add(i,same.test(previous,value)?previous:value);}
            else rows.add(i,value);
            changed++;
        }
        if(rows.size()>next.size()){changed+=rows.size()-next.size();rows.remove(next.size(),rows.size());}
        return changed;
    }
}
