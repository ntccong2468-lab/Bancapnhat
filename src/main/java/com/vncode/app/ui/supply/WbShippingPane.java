package com.vncode.app.ui.supply;

import com.vncode.app.integration.wb.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.FriendlyErrorService;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.time.*;
import java.util.*;

/** Per-supply shipping form. Country is explicit because WB supply type does not identify seller country. */
public final class WbShippingPane extends VBox {
    private final ComboBox<String> country=new ComboBox<>(),method=new ComboBox<>();
    private final ComboBox<Integer> cargo=new ComboBox<>();
    private final DatePicker date=new DatePicker();
    private final TextField city=new TextField(),search=new TextField();
    private final ComboBox<WbShippingContract.Point> point=new ComboBox<>();
    private final Button load=new Button("Tải điểm nhận"),more=new Button("Thêm điểm nhận");
    private final Label status=new Label();
    private final FlowPane fields=new FlowPane(8,8);
    private final WbApiClient api=new WbApiClient();
    private final WbShippingPreferenceService preferences=new WbShippingPreferenceService();
    private List<WbShippingContract.Point> points=List.of();
    private String loadedCity;
    private Integer loadedCargo;
    private Long restorePoint;
    private Shop shop;
    private String supplyId;
    private long epoch;
    public record Selection(WbShippingContract.Parameters parameters,WbShippingPreferenceService.Preferences preferences) { }
    public WbShippingPane() {
        super(6);country.getItems().setAll("RU","OTHER");country.setPromptText("Quốc gia của người bán");
        country.setConverter(converter(Map.of("RU","Nga","OTHER","Ngoài Nga")));
        method.getItems().setAll("selfShipping","transportCompany");method.setPromptText("Cách giao");
        method.setConverter(converter(Map.of("selfShipping","Tự giao","transportCompany","Công ty vận chuyển")));
        cargo.getItems().setAll(1,2,3);cargo.setPromptText("Loại hàng 1/2/3");date.setPromptText("Ngày giao");
        date.setDayCellFactory(picker->new DateCell(){@Override public void updateItem(LocalDate value,boolean empty){super.updateItem(value,empty);setDisable(empty||value.isBefore(LocalDate.now()));}});
        city.setPromptText("Thành phố bằng tiếng Nga");search.setPromptText("Tìm điểm nhận");point.setPromptText("Điểm nhận");point.setPrefWidth(300);
        fields.getChildren().setAll(method,date,cargo,city,load,search,point,more);
        getChildren().setAll(new HBox(8,new Label("Thông số giao WB FBS"),country),fields,status);
        country.valueProperty().addListener((o,a,b)->updateCountry());updateCountry();
        city.textProperty().addListener((o,a,b)->invalidatePoints());cargo.valueProperty().addListener((o,a,b)->invalidatePoints());
        search.textProperty().addListener((o,a,b)->showPoints(false));
        load.setOnAction(e->loadPoints());more.setOnAction(e->showPoints(true));
    }
    private static StringConverter<String> converter(Map<String,String> labels){return new StringConverter<>(){public String toString(String key){return labels.getOrDefault(key,"");}public String fromString(String value){return labels.entrySet().stream().filter(e->e.getValue().equals(value)).map(Map.Entry::getKey).findFirst().orElse(null);}};}
    public void setContext(Shop shop,String supplyId) {
        long request=++epoch;load.setDisable(false);this.shop=shop;this.supplyId=supplyId;date.setValue(null);country.setValue(null);method.setValue(null);cargo.setValue(null);city.clear();search.clear();restorePoint=null;invalidatePoints();
        setVisible(shop!=null&&supplyId!=null);setManaged(isVisible());status.setText("");if(shop==null||supplyId==null)return;
        var task=new Task<WbShippingPreferenceService.Preferences>(){
            private Integer supplyCargo;
            @Override protected WbShippingPreferenceService.Preferences call()throws Exception {
                var saved=preferences.load(shop.getId());var detail=api.getSupplyDetail(shop.getApiKey(),supplyId);
                if(detail==null||!supplyId.equals(detail.getId()))throw new java.io.IOException("WB_SUPPLY_ID_MISMATCH");supplyCargo=detail.getCargoType();return saved;
            }
            @Override protected void succeeded(){if(request!=epoch)return;var saved=getValue();country.setValue(saved.country());method.setValue(saved.shippingType());city.setText(Objects.toString(saved.city(),""));cargo.setValue(supplyCargo);restorePoint=saved.pointId();if("RU".equals(saved.country())&&saved.city()!=null&&supplyCargo!=null)loadPoints();}
            @Override protected void failed(){if(request==epoch)status.setText(FriendlyErrorService.format(getException()));}
        };AppTaskExecutor.execute(task);
    }
    private void updateCountry(){boolean ru="RU".equals(country.getValue());fields.setVisible(ru);fields.setManaged(ru);}
    private void invalidatePoints(){points=List.of();loadedCity=null;loadedCargo=null;point.getItems().clear();point.setValue(null);more.setDisable(true);}
    private void loadPoints() {
        if(shop==null||cargo.getValue()==null||city.getText().isBlank()){status.setText("Chọn loại hàng và thành phố trước.");return;}
        Shop selected=shop;String selectedCity=city.getText().strip();Integer selectedCargo=cargo.getValue();long request=epoch;load.setDisable(true);
        var task=new Task<List<WbShippingContract.Point>>(){@Override protected List<WbShippingContract.Point> call()throws Exception{return api.getShippingPoints(selected.getApiKey(),selectedCity,selectedCargo);}};
        task.setOnSucceeded(e->{if(request!=epoch)return;if(!selectedCity.equals(city.getText().strip())||!selectedCargo.equals(cargo.getValue())){load.setDisable(false);return;}points=task.getValue();loadedCity=selectedCity;loadedCargo=selectedCargo;load.setDisable(false);showPoints(false);status.setText(points.isEmpty()?"Không có điểm nhận phù hợp.":"");});
        task.setOnFailed(e->{if(request==epoch){load.setDisable(false);status.setText(FriendlyErrorService.format(task.getException()));}});AppTaskExecutor.execute(task);
    }
    private void showPoints(boolean append) {
        var chosen=point.getValue();int offset=append?point.getItems().size():0;if(!append)point.getItems().clear();
        var rows=WbShippingContract.page(points,search.getText(),offset);point.getItems().addAll(rows);more.setDisable(rows.size()<20);
        if(chosen!=null&&point.getItems().contains(chosen))point.setValue(chosen);
        else if(restorePoint!=null){Long remembered=restorePoint;restorePoint=null;points.stream().filter(p->p.id()==remembered).findFirst().ifPresent(point::setValue);}
    }
    public Selection selection() {
        if(shop==null||supplyId==null||country.getValue()==null)throw new IllegalArgumentException("Chọn quốc gia của người bán trước khi giao.");
        if("OTHER".equals(country.getValue()))return new Selection(null,new WbShippingPreferenceService.Preferences("OTHER",null,null,null));
        var selected=point.getValue();if(selected==null||!points.contains(selected)||!Objects.equals(loadedCity,city.getText().strip())||!Objects.equals(loadedCargo,cargo.getValue()))throw new IllegalArgumentException("Chọn điểm nhận hợp lệ cho thành phố và loại hàng.");
        date.commitValue();
        var parameters=new WbShippingContract.Parameters(supplyId,method.getValue(),date.getValue(),selected.id());parameters.payload(Clock.systemDefaultZone());
        return new Selection(parameters,new WbShippingPreferenceService.Preferences("RU",method.getValue(),loadedCity,selected.id()));
    }
}
