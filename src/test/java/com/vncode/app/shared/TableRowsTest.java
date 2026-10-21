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
}
