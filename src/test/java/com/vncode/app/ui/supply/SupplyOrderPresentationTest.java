package com.vncode.app.ui.supply;
import com.vncode.app.models.Order;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class SupplyOrderPresentationTest {
 @Test void gtinComesFromAssignedKizOrTheMappingForThisProduct() {
  var order=new Order();order.setNmId(10L);order.setBarcode("2047861508465");
  assertEquals("04610689132457",SupplyOrderPresentation.gtin(order,Map.of(10L,"04610689132457")));
  assertEquals("",SupplyOrderPresentation.gtin(order,Map.of(11L,"other-product")));
  order.setKiz("010001234567890521fixture");assertEquals("00012345678905",SupplyOrderPresentation.gtin(order,Map.of()));
 }
}
