package com.vncode.app.ui.supply;
import com.vncode.app.models.Order;
import com.vncode.app.features.kiz.KizService;
import java.util.Map;
final class SupplyOrderPresentation {
    static String gtin(Order order,Map<Long,String> mappings) {
        if(order==null)return "";
        String assigned=KizService.extractGtin(order.getKiz());
        if(!assigned.isBlank())return assigned;
        return order.getNmId()==null?"":mappings.getOrDefault(order.getNmId(),"");
    }
}
