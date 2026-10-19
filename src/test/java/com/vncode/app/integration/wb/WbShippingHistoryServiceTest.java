package com.vncode.app.integration.wb;

import com.google.gson.Gson;
import com.vncode.app.config.Database;
import com.vncode.app.features.gtinsync.GtinWriteTestFixture;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WbShippingHistoryServiceTest {
    @TempDir Path root;Shop shop;
    final WbShippingPreferenceService preferences=new WbShippingPreferenceService();
    @BeforeEach void setup()throws Exception{System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();shop=GtinWriteTestFixture.shop(Marketplace.WILDBERRIES);}
    @AfterEach void clear(){System.clearProperty("vncode.appdata.dir");}
    static WbSupplyDto supply(String json){return new Gson().fromJson(json,WbSupplyDto.class);}
    static class Api extends WbApiClient {
        int lists,details;String page="{\"next\":0,\"supplies\":[]}";IOException failure;
        Map<String,WbSupplyDto> supplies=new HashMap<>(Map.of("current",supply("{\"id\":\"current\",\"cargoType\":1}")));
        @Override public WbSupplyDto getSupplyDetail(String token,String id)throws IOException{details++;if(failure!=null)throw failure;return supplies.get(id);}
        @Override public WbSuppliesResponse getSupplies(String token,long next,int limit)throws IOException{lists++;if(failure!=null)throw failure;return new Gson().fromJson(page,WbSuppliesResponse.class);}
        @Override public List<WbShippingContract.Point> getShippingPoints(String token,String city,int cargo){return List.of(new WbShippingContract.Point(123,"WB point","Real fixture address",city,List.of(cargo)),new WbShippingContract.Point(999,"Foreign point","Must not be added",city,List.of(cargo)));}
    }
    @Test void selectsLatestCompletedPointAndResolvesOnlyActualHistoricalAddresses()throws Exception {
        var api=new Api();api.page="{\"next\":0,\"supplies\":[{\"id\":\"old\",\"done\":true,\"closedAt\":\"2026-09-01\",\"shippingPointId\":456,\"shippingType\":\"transportCompany\"},{\"id\":\"recent\",\"done\":true,\"closedAt\":\"2026-10-19\",\"shippingPointId\":123,\"shippingType\":\"selfShipping\"},{\"id\":\"open\",\"done\":false,\"shippingPointId\":999,\"shippingType\":\"selfShipping\"}]}";
        preferences.save(shop.getId(),new WbShippingPreferenceService.Preferences("RU","selfShipping","Москва",999L));
        var rows=new WbShippingHistoryService(api,preferences).load(shop,"current");
        assertEquals(List.of(123L,456L),rows.stream().map(WbShippingPreferenceService.Location::pointId).toList());assertTrue(rows.getFirst().label().contains("Real fixture address"));assertEquals("transportCompany",rows.getLast().shippingType());
    }
    @Test void successHistoryIsAccountScopedDeduplicatedAndDoesNotPersistAnOldDate()throws Exception {
        var first=new WbShippingPreferenceService.Location(123,"selfShipping",null,"First");var last=new WbShippingPreferenceService.Location(456,"transportCompany",null,"Last");
        preferences.remember(shop,first);preferences.remember(shop,last);preferences.remember(shop,first);
        var api=new Api();api.failure=new IOException("No network needed");assertEquals(List.of(first,last),new WbShippingHistoryService(api,preferences).load(shop,"current"));assertEquals(0,api.details);
        try(var c=Database.getConnection();var s=c.createStatement()){s.execute("UPDATE shops SET api_key='rotated' WHERE id="+shop.getId());}
        var rotated=new Shop(shop.getId(),shop.getName(),"rotated");assertTrue(preferences.history(rotated).isEmpty());assertThrows(SecurityException.class,()->preferences.history(shop));
    }
    @Test void missingListFieldsAreReadFromCompletedSupplyDetail()throws Exception {
        var api=new Api();api.page="{\"next\":0,\"supplies\":[{\"id\":\"old\",\"done\":true}]}";api.supplies.put("old",supply("{\"id\":\"old\",\"shippingPointId\":123,\"shippingType\":\"selfShipping\"}"));
        assertEquals(123,new WbShippingHistoryService(api,preferences).load(shop,"current").getFirst().pointId());assertEquals(2,api.details);
    }
    @Test void currentSupplyConfigurationTakesPriorityOverHistory()throws Exception {
        var api=new Api();api.supplies.put("current",supply("{\"id\":\"current\",\"shippingPointId\":456,\"shippingType\":\"selfShipping\"}"));
        assertEquals(456,new WbShippingHistoryService(api,preferences).load(shop,"current").getFirst().pointId());
    }
    @Test void noHistoryDoesNotGuessACountryPointOrShippingMethod()throws Exception {
        var rows=new WbShippingHistoryService(new Api(),preferences).load(shop,"current");assertTrue(rows.isEmpty());assertTrue(preferences.history(shop).isEmpty());
    }
    @Test void wrongSupplyAndReadFailuresAreNotTreatedAsHistoricalSelections() {
        var api=new Api();api.supplies.put("current",supply("{\"id\":\"another\",\"shippingPointId\":123,\"shippingType\":\"selfShipping\"}"));
        assertThrows(IOException.class,()->new WbShippingHistoryService(api,preferences).load(shop,"current"));api.failure=new IOException("HTTP401");assertEquals("HTTP401",assertThrows(IOException.class,()->new WbShippingHistoryService(api,preferences).load(shop,"current")).getMessage());
    }
    @Test void repeatedCursorStopsInsteadOfHangingWhenNoPointIsAvailable() {
        var api=new Api();api.page="{\"next\":7,\"supplies\":[{\"id\":\"open\",\"done\":false}]}";
        assertEquals("WB_SHIPPING_HISTORY_CURSOR_REPEATED",assertThrows(IOException.class,()->new WbShippingHistoryService(api,preferences).load(shop,"current")).getMessage());assertEquals(2,api.lists);
    }
}
