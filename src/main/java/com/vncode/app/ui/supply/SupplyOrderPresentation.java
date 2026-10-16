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
    static String status(Order order){
        if(order==null)return "";
        var i18n=com.vncode.app.shared.I18nService.getInstance();var values=new java.util.ArrayList<String>();
        if(order.getSupplierStatus()!=null&&!order.getSupplierStatus().isBlank())values.add(i18n.tr("supply.status.seller")+": "+i18n.tr("supply.order_status."+order.getSupplierStatus(),order.getSupplierStatus()));
        if(order.getWbStatus()!=null&&!order.getWbStatus().isBlank())values.add("WB: "+i18n.tr("supply.order_status."+order.getWbStatus(),order.getWbStatus()));
        return String.join(" · ",values);
    }
}
