package com.vncode.app.shared;
import javafx.collections.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class TableRowsTest {
 record Row(int id,String value){}
 @Test void unchangedSnapshotEmitsNoChangesAndOneUpdatedRowDoesNotReplaceItsNeighbors(){
  var original=java.util.stream.IntStream.range(0,1000).mapToObj(i->new Row(i,"value")).toList();var rows=FXCollections.observableArrayList(original);var events=new AtomicInteger();rows.addListener((ListChangeListener<Row>)change->events.incrementAndGet());
  assertEquals(0,TableRows.merge(rows,original.stream().map(r->new Row(r.id(),r.value())).toList(),Row::id));assertEquals(0,events.get());
  var next=new ArrayList<>(original);next.set(500,new Row(500,"updated"));assertEquals(1,TableRows.merge(rows,next,Row::id));assertEquals(1,events.get());assertSame(original.get(499),rows.get(499));assertEquals("updated",rows.get(500).value());
 }
 @Test void insertRemoveAndReorderingKeepTheExactIncomingOrder(){
  var rows=FXCollections.observableArrayList(new Row(1,"a"),new Row(2,"b"),new Row(3,"c"));
  var next=List.of(new Row(4,"d"),new Row(3,"new"),new Row(1,"a"));TableRows.merge(rows,next,Row::id);assertEquals(next,rows);
  var duplicates=FXCollections.observableArrayList(new Row(1,"a"),new Row(1,"a"));TableRows.merge(duplicates,List.of(new Row(1,"a")),Row::id);assertEquals(1,duplicates.size());
 }
 @Test void aMutableOrderChangeStillNotifiesItsAlreadyRenderedRow(){
  var order=new com.vncode.app.models.Order();order.setId(1L);var rows=FXCollections.observableArrayList(order);var tracked=new TableRows.Tracked<com.vncode.app.models.Order,Long>();tracked.merge(rows,List.of(order),com.vncode.app.models.Order::getId,FbsRowValues::snapshot);var changed=new AtomicInteger();rows.addListener((ListChangeListener<com.vncode.app.models.Order>)c->changed.incrementAndGet());order.setKiz("synthetic");assertEquals(1,tracked.merge(rows,List.of(order),com.vncode.app.models.Order::getId,FbsRowValues::snapshot));assertEquals(1,changed.get());
 }
 @Test void wbValueComparisonDetectsStatusKizAndImageChangesWithoutChangingOrderIdentity(){
  var first=new com.vncode.app.models.Order();first.setId(1L);var same=new com.vncode.app.models.Order();same.setId(1L);assertTrue(FbsRowValues.sameOrder(first,same));same.setRequiresKiz(true);assertFalse(FbsRowValues.sameOrder(first,same));same.setRequiresKiz(false);same.setImage(new byte[]{1});assertFalse(FbsRowValues.sameOrder(first,same));
 }
 @Test void broadeningLargeFilteredQueueDoesNotRescanOldRowsForEveryInsertion(){
  var old=java.util.stream.IntStream.range(0,10_000).filter(i->i%2==1).mapToObj(i->new Row(i,"value")).toList();
  var rows=FXCollections.observableArrayList(old);var incoming=java.util.stream.IntStream.range(0,10_000).mapToObj(i->new Row(i,"value")).toList();
  var lookups=new AtomicInteger();var removed=new java.util.concurrent.atomic.AtomicBoolean();
  rows.addListener((ListChangeListener<Row>)change->{while(change.next())if(change.wasRemoved())removed.set(true);});
  assertEquals(5_000,TableRows.merge(rows,incoming,row->{lookups.incrementAndGet();return row.id();}));
  assertEquals(incoming,rows);assertFalse(removed.get(),"Restoring filtered rows must not replace existing rows");
  for(int i=0;i<old.size();i++)assertSame(old.get(i),rows.get(2*i+1));
  assertTrue(lookups.get()<100_000,"New rows should not repeatedly scan the filtered list: "+lookups.get());
 }
 @Test void reorderingKeepsEqualRowIdentityAndDuplicateOrNullKeysKeepFallback(){
  var a=new Row(1,"a");var b=new Row(2,"b");var c=new Row(3,"c");var rows=FXCollections.observableArrayList(a,b,c);
  var next=List.of(new Row(4,"d"),new Row(3,"c"),new Row(1,"a"));TableRows.merge(rows,next,Row::id);
  assertEquals(next,rows);assertSame(c,rows.get(1));assertSame(a,rows.get(2));
  record NullableRow(Integer id,String value){};
  var nullable=FXCollections.observableArrayList(new NullableRow(1,"old"));
  var duplicate=List.of(new NullableRow(1,"one"),new NullableRow(1,"two"));TableRows.merge(nullable,duplicate,NullableRow::id);assertEquals(duplicate,nullable);
  var withNull=List.of(new NullableRow(null,"missing"),new NullableRow(2,"two"));TableRows.merge(nullable,withNull,NullableRow::id);assertEquals(withNull,nullable);
 }
 @Test void largeReorderingUsesPermutationWithoutRescanningOrReplacingRows(){
  var original=java.util.stream.IntStream.range(0,10_000).mapToObj(i->new Row(i,"value")).toList();
  var rows=FXCollections.observableArrayList(original);var incoming=new ArrayList<>(original);Collections.reverse(incoming);
  var lookups=new AtomicInteger();var permutations=new AtomicInteger();var replaced=new AtomicInteger();
  rows.addListener((ListChangeListener<Row>)change->{while(change.next()){if(change.wasPermutated())permutations.incrementAndGet();if(change.wasRemoved()||change.wasAdded())replaced.incrementAndGet();}});
  assertEquals(9_999,TableRows.merge(rows,incoming,row->{lookups.incrementAndGet();return row.id();}));
  assertEquals(incoming,rows);for(int i=0;i<original.size();i++)assertSame(original.get(i),rows.get(original.size()-1-i));
  assertEquals(1,permutations.get(),"Existing rows should move as one observable permutation");
  assertEquals(0,replaced.get(),"Reordering should not replace existing rows");
  assertTrue(lookups.get()<500_000,"Reordering must not scan the remainder for each row: "+lookups.get());
 }
 @Test void permutationCountsOnlyMovesOrUpdatesWithoutDoubleCounting(){
  var original=java.util.stream.IntStream.range(0,100).mapToObj(i->new Row(i,"old")).toList();
  var rows=FXCollections.observableArrayList(original);var incoming=new ArrayList<>(original);
  var permutations=new AtomicInteger();rows.addListener((ListChangeListener<Row>)change->{while(change.next())if(change.wasPermutated())permutations.incrementAndGet();});
  incoming.removeLast();incoming.addFirst(new Row(99,"changed while moved"));incoming.set(30,new Row(29,"changed in place"));
  assertEquals(2,TableRows.merge(rows,incoming,Row::id));
  assertEquals(0,permutations.get(),"Changed rows must use the existing incremental path");
  assertEquals(incoming,rows);assertSame(original.get(0),rows.get(1));assertNotSame(original.get(99),rows.getFirst());
 }
 @Test void customObservableListKeepsConservativeReorderBehavior(){
  var original=java.util.stream.IntStream.range(0,100).mapToObj(i->new Row(i,"value")).toList();
  var rows=FXCollections.observableList(new LinkedList<>(original));var incoming=new ArrayList<>(original);Collections.reverse(incoming);
  assertEquals(99,TableRows.merge(rows,incoming,Row::id));assertEquals(incoming,rows);
  for(int i=0;i<original.size();i++)assertSame(original.get(i),rows.get(original.size()-1-i));
 }
 @Test void unchangedLargeQueueDoesNotPayForReorderingIndex(){
  var source=java.util.stream.IntStream.range(0,10_000).mapToObj(i->new Row(i,"value")).toList();
  var rows=FXCollections.observableArrayList(source);var lookups=new AtomicInteger();var events=new AtomicInteger();
  rows.addListener((ListChangeListener<Row>)change->events.incrementAndGet());
  assertEquals(0,TableRows.merge(rows,source,row->{lookups.incrementAndGet();return row.id();}));
  assertEquals(0,events.get());assertTrue(lookups.get()<=50_000,"Unchanged data should not build a reorder index: "+lookups.get());
 }
 @Test void trackedFbsRowsPreserveIdentityWhenOnlyTheirOrderChanges(){
  var original=java.util.stream.IntStream.range(0,1000).mapToObj(i->new Row(i,"value")).toList();
  var rows=FXCollections.observableArrayList(original);var tracked=new TableRows.Tracked<Row,Integer>();
  tracked.merge(rows,original,Row::id,Row::value);
  var events=new AtomicInteger();rows.addListener((ListChangeListener<Row>)change->{while(change.next())if(change.wasPermutated())events.incrementAndGet();});
  var incoming=original.stream().map(row->new Row(row.id(),row.value())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));Collections.reverse(incoming);
  assertEquals(999,tracked.merge(rows,incoming,Row::id,Row::value));assertEquals(1,events.get());
  for(int i=0;i<original.size();i++)assertSame(original.get(i),rows.get(original.size()-1-i));
 }
}
