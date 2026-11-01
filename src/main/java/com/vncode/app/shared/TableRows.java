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
        if(rows.size()==next.size()){
            boolean sameOrder=true;
            for(int i=0;i<next.size();i++)if(!Objects.equals(key.apply(rows.get(i)),key.apply(next.get(i)))){sameOrder=false;break;}
            if(sameOrder){int changed=0;for(int i=0;i<next.size();i++)if(!same.test(rows.get(i),next.get(i))){rows.set(i,next.get(i));changed++;}return changed;}
        }
        Integer reordered=permuteExistingRows(rows,next,key,same);
        if(reordered!=null)return reordered;
        int changed=rows.size();rows.removeIf(row->!keys.contains(key.apply(row)));changed-=rows.size();
        // Broadening a filter often inserts keys that are absent from the old view.
        // Index those keys once instead of scanning all remaining rows per insertion.
        var existingKeys=new HashSet<K>();
        for(var row:rows)existingKeys.add(key.apply(row));
        for(int i=0;i<next.size();i++){
            var value=next.get(i);var id=key.apply(value);
            if(i<rows.size()&&Objects.equals(id,key.apply(rows.get(i)))){
                if(!same.test(rows.get(i),value)){rows.set(i,value);changed++;}
                continue;
            }
            if(!existingKeys.contains(id)){rows.add(i,value);existingKeys.add(id);changed++;continue;}
            int existing=-1;
            for(int j=i+1;j<rows.size();j++)if(Objects.equals(id,key.apply(rows.get(j)))){existing=j;break;}
            if(existing>=0){var previous=rows.remove(existing);rows.add(i,same.test(previous,value)?previous:value);}
            else rows.add(i,value);
            changed++;
        }
        if(rows.size()>next.size()){changed+=rows.size()-next.size();rows.remove(next.size(),rows.size());}
        return changed;
    }

    /**
     * The default JavaFX table/list backing array emits a single permutation on sort,
     * allowing selection models to follow the same objects. Other ObservableList
     * implementations may implement sort by setAll (a destructive replacement), so
     * retain the conservative existing path for those and for membership changes.
     */
    private static <T,K> Integer permuteExistingRows(ObservableList<T> rows,List<T> next,Function<T,K> key,BiPredicate<T,T> same){
        if(rows.size()<64||rows.size()!=next.size()||!rows.getClass().getName().equals("com.sun.javafx.collections.ObservableListWrapper"))return null;
        var oldIndex=new HashMap<K,Integer>();
        for(int i=0;i<rows.size();i++){K id=key.apply(rows.get(i));if(id==null||oldIndex.putIfAbsent(id,i)!=null)return null;}
        var rank=new HashMap<K,Integer>();var consumed=new boolean[rows.size()];
        int first=0,changed=0;
        for(int i=0;i<next.size();i++){
            K id=key.apply(next.get(i));Integer old=oldIndex.get(id);if(old==null)return null;
            // Replacing a selected TableView item can clear selection.  Keep the
            // existing incremental path if any displayed value also changed.
            if(!same.test(rows.get(old),next.get(i)))return null;
            rank.put(id,i);if(old!=first)changed++;
            consumed[old]=true;while(first<consumed.length&&consumed[first])first++;
        }
        if(changed==0)return null;
        rows.sort(Comparator.comparingInt(row->rank.get(key.apply(row))));
        return changed;
    }
}
