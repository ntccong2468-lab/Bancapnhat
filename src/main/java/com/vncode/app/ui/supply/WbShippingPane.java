package com.vncode.app.ui.supply;

import com.vncode.app.integration.wb.*;
import com.vncode.app.integration.listing.ListingIdentity;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Latest historical point is selected automatically. No vehicle, country or manufacturing form. */
public final class WbShippingPane extends VBox {
    private final ComboBox<WbShippingPreferenceService.Location> point=new ComboBox<>();
    private final Label status=new Label();
    private final WbShippingHistoryService history;
    private CompletableFuture<List<WbShippingPreferenceService.Location>> loading;
    private Shop shop;
    private String supplyId;
    private long epoch;
    public record Selection(WbShippingContract.Parameters parameters,WbShippingPreferenceService.Location location) { }
    public WbShippingPane(){this(new WbShippingHistoryService());}
    public WbShippingPane(WbShippingHistoryService history) {
        super(6);this.history=history;point.setId("shippingHistoryPoint");point.setPromptText("Điểm giao từ lịch sử");
        point.setMaxWidth(Double.MAX_VALUE);point.setMinWidth(0);status.setWrapText(true);
        getChildren().setAll(point,status);setContext(null,null);
    }
    public void setContext(Shop selected,String supplyId) {
        long request=++epoch;this.shop=selected==null?null:new Shop(selected.getId(),selected.getName(),selected.getMarketplace(),selected.getClientId(),selected.getApiKey());
        this.supplyId=supplyId;point.getItems().clear();point.setValue(null);point.setDisable(true);loading=null;
        setVisible(shop!=null&&supplyId!=null);setManaged(isVisible());status.setText("");if(!isVisible())return;
        Shop owner=shop;status.setText("Đang đọc điểm giao từ lịch sử…");
        loading=ShopReadCoordinator.shared().submit(new ShopReadCoordinator.Key(owner.getId(),"wb-shipping-history",ListingIdentity.fingerprint(owner)+":"+supplyId),true,()->history.load(owner,supplyId));
        loading.whenComplete((rows,error)->Platform.runLater(()->{
            if(request!=epoch)return;
            if(error!=null){status.setText(FriendlyErrorService.format(error));return;}
            point.getItems().setAll(rows);point.setDisable(rows.isEmpty());if(!rows.isEmpty())point.getSelectionModel().selectFirst();
            status.setText(rows.isEmpty()?"Chưa có lịch sử điểm giao. Khi chuyển giao hàng, WB sẽ kiểm tra cấu hình hiện có của lô.":"Đã chọn điểm giao gần nhất. Chỉ cần bấm Chuyển sang giao hàng.");
        }));
    }
    /** Captures the current owner and selection on FX; waiting for reads happens in the delivery worker. */
    public CompletableFuture<Selection> prepareSelection() {
        if(shop==null||supplyId==null||loading==null)return CompletableFuture.failedFuture(new IllegalArgumentException("Chưa chọn lô hàng."));
        String selectedSupply=supplyId;
        var chosen=point.getValue();
        if(chosen!=null)return CompletableFuture.completedFuture(selection(selectedSupply,chosen));
        return loading.thenApply(rows->selection(selectedSupply,rows.isEmpty()?null:rows.getFirst()));
    }
    public Selection selection() {
        if(loading==null||!loading.isDone())throw new IllegalArgumentException("Đang đọc lịch sử điểm giao.");
        return prepareSelection().join();
    }
    private static Selection selection(String supplyId,WbShippingPreferenceService.Location location) {
        return new Selection(location==null?null:new WbShippingContract.Parameters(supplyId,location.shippingType(),LocalDate.now(),location.pointId()),location);
    }
    public void dispose(){epoch++;}
}
