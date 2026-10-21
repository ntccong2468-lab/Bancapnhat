package com.vncode.app.ui.znackregistration;

import com.vncode.app.integration.znack.registration.v3.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import static com.vncode.app.integration.znack.registration.v3.RegistrationJob.*;
import static com.vncode.app.integration.znack.registration.v3.RegistrationState.*;

/** Seven seller-controlled steps. Only immutable reviews cross a production confirmation dialog. */
public final class RegistrationV3Pane extends BorderPane implements AutoCloseable {
    private final RegistrationStore store;
    private final RegistrationOrchestrator service;
    private final RegistrationGateway gateway;
    private final TableView<RegistrationJob> products=new TableView<>();
    private final javafx.collections.ObservableList<RegistrationJob> allJobs=javafx.collections.FXCollections.observableArrayList();
    private final TabPane steps=new TabPane();
    private final Label status=new Label("Chọn cửa hàng WB và nhập sản phẩm để bắt đầu.");
    private final TextArea log=new TextArea(), payload=new TextArea(), validation=new TextArea(), signing=new TextArea(), completion=new TextArea();
    private final TextField gtin=new TextField(), tnved=new TextField(), title=new TextField(), brand=new TextField();
    private final ComboBox<Category> category=new ComboBox<>();
    private final RegistrationAttributeEditor attributeFields=new RegistrationAttributeEditor();
    private final List<Button> commands=new ArrayList<>();
    private final CheckBox reviewed=new CheckBox("Tôi đã kiểm tra nội dung thẻ và các thông tin pháp lý.");
    private final PauseTransition poll=new PauseTransition(Duration.seconds(15));
    private RegistrationJob selected;
    private final String shopLabel;
    private long epoch=1;
    private boolean closed,busy,updatingRows;

    public RegistrationV3Pane(Shop shop) {
        shopLabel=shop==null?"Chưa chọn cửa hàng":Objects.toString(shop.getName(),"Cửa hàng")+" (#"+shop.getId()+")";
        store=shop==null?null:new RegistrationStore();
        gateway=shop==null?null:new CatalogRegistrationGateway(shop);
        service=shop==null?null:new RegistrationOrchestrator(store,gateway);
        setPadding(new Insets(12));setPrefSize(1120,760);
        var heading=new Label("Đăng ký thẻ National Catalog — V3");heading.getStyleClass().add("h2");
        var boundary=new Label("Gửi thẻ và ký cần xác nhận riêng, đúng tài khoản và chứng thư hợp lệ. Đồng bộ WB trong V3 đang khóa chờ xác minh hợp đồng hiện hành.");
        boundary.setWrapText(true);status.setWrapText(true);
        var account=new Label(shopLabel);account.setId("znackV3Account");
        setTop(new VBox(8,heading,account,boundary,status));
        steps.setId("znackV3Steps");steps.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);setCenter(steps);
        configureProducts();
        var filtered=new javafx.collections.transformation.FilteredList<>(allJobs);products.setItems(filtered);
        var search=new TextField();search.setPromptText("Tìm tên sản phẩm, article, nmID, chrtID hoặc GTIN");
        search.textProperty().addListener((o,before,after)->filtered.setPredicate(job->matches(job,after)));
        var importButton=button("Nhập sản phẩm thật từ WB",()->run(()->service.importProducts(),rows->{allJobs.setAll(rows);status.setText("Đã nhập "+rows.size()+" biến thể. Chưa cấp GTIN hoặc gửi thẻ.");}));
        var restore=button("Khôi phục tác vụ đã lưu",this::restore);
        steps.getTabs().add(tab("1. Sản phẩm",new VBox(8,new HBox(8,importButton,restore),search,products)));
        VBox.setVgrow(products,Priority.ALWAYS);
        var form=new GridPane();form.setHgap(10);form.setVgap(8);
        field(form,0,"GTIN đã có",gtin);field(form,1,"TN VED (10 chữ số)",tnved);field(form,2,"Danh mục Catalog",category);
        field(form,3,"Tên sản phẩm",title);field(form,4,"Thương hiệu",brand);
        var save=button("Lưu bản nháp",()->saveDraft(null));
        var resolve=button("Đối chiếu GTIN đã có",()->{
            String code=gtin.getText().strip();
            saveDraft(job->run(()->service.resolveGtin(job,code),this::show));
        });
        var categories=button("Đọc danh mục theo TN VED",()->{
            var job=selected;String code=tnved.getText().strip();if(job==null)return;
            run(()->service.categories(job,code),choices->{category.getItems().setAll(choices);category.setValue(null);status.setText("Chọn đúng danh mục do Catalog trả về; ứng dụng không tự đoán danh mục pháp lý.");});
        });
        var allocate=button("Xin cấp GTIN…",()->saveDraft(job->review(job,Action.ALLOCATE_GTIN)));
        var schema=button("Đọc thuộc tính Catalog",()->saveDraft(job->run(()->service.attributes(job),this::renderAttributes)));
        var suggestions=button("Đề xuất từ dữ liệu WB",()->saveDraft(job->run(()->service.suggest(job),proposal->{renderAttributes(proposal.schema());attributeFields.suggest(proposal.values(),proposal.types());status.setText("Đây là đề xuất từ WB. Kiểm tra và lưu; quốc gia, giấy tờ và các thông tin pháp lý cần xác nhận riêng.");})));
        var editor=new VBox(10,form,new FlowPane(8,8,categories,save,resolve,allocate,schema,suggestions),attributeFields);
        var editorScroll=new ScrollPane(editor);editorScroll.setFitToWidth(true);
        steps.getTabs().add(tab("2. Tạo thẻ",editorScroll));
        var check=button("Kiểm tra với Catalog",()->{
            if(!reviewed.isSelected()){status.setText("Cần kiểm tra và xác nhận thông tin pháp lý trước khi kiểm tra gửi.");return;}
            saveDraft(job->run(()->service.validate(job),result->{show(result);validation.setText(result.state()==VALIDATED?"Đã kiểm tra dữ liệu, thuộc tính và giấy tờ theo Catalog. Cần xác nhận riêng trước khi gửi thật.":message(result.data().detail()));}));
        });
        steps.getTabs().add(tab("3. Kiểm tra",new VBox(8,reviewed,check,validation)));
        var submit=button("GỬI THẺ THẬT…",()->review(selected,Action.SUBMIT));submit.setId("znackV3Submit");
        steps.getTabs().add(tab("4. Gửi đăng ký",new VBox(8,new Label("Xem payload của đúng biến thể trước khi xác nhận gửi."),payload,submit)));
        var refresh=button("Đọc lại trạng thái",this::reconcile);
        var correct=button("Sửa thẻ bị từ chối",()->{var job=selected;if(job!=null)run(()->service.correct(job),this::show);});
        steps.getTabs().add(tab("5. Kiểm duyệt",new VBox(8,new Label("Nếu kết quả chưa rõ, chỉ đối chiếu máy chủ; không tự gửi lại."),new HBox(8,refresh,correct),log)));
        var sign=button("Xem tài liệu và duyệt ký…",()->review(selected,Action.SIGN));sign.setId("znackV3Sign");
        steps.getTabs().add(tab("6. Ký điện tử",new VBox(8,new Label("Chứng thư phải đúng chủ tài khoản, còn hạn và có khóa riêng. Không nhập PIN vào ứng dụng."),signing,sign)));
        var sync=button("Xem thay đổi và đồng bộ WB…",()->review(selected,Action.SYNC_WB));
        steps.getTabs().add(tab("7. Hoàn thành",new VBox(8,completion,sync)));
        for(var area:List.of(log,payload,validation,signing,completion)){area.setEditable(false);area.setWrapText(true);VBox.setVgrow(area,Priority.ALWAYS);}
        products.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        products.getSelectionModel().selectedItemProperty().addListener((o,old,job)->{if(updatingRows)return;if(job!=null){if(!job.equals(selected)){epoch++;show(job);}}else{epoch++;selected=null;updateControls();}});
        reviewed.selectedProperty().addListener((o,old,value)->updateControls());
        poll.setOnFinished(e->{if(selected!=null&&!busy&&pollable(selected.state()))reconcile();});
        updateControls();if(shop!=null)restore();
    }
    private Tab tab(String name,Node content){return new Tab(name,content);}
    private void field(GridPane form,int row,String name,Node value){form.add(new Label(name),0,row);form.add(value,1,row);GridPane.setHgrow(value,Priority.ALWAYS);}
    private Button button(String label,Runnable action){var b=new Button(label);b.setOnAction(e->{if(!busy&&!closed&&service!=null)action.run();});commands.add(b);return b;}
    private void configureProducts(){
        column("Sản phẩm",j->Objects.toString(j.product().title(),""));column("Article",j->Objects.toString(j.product().vendorCode(),""));
        column("nmID / chrtID",j->j.product().nmId()+" / "+j.product().chrtId());column("Màu / Size / Size RU",j->j.product().color()+" / "+j.product().size()+" / "+j.product().russianSize());
        column("GTIN",j->j.data().gtin());column("Trạng thái",j->j.state().label());
        var image=new TableColumn<RegistrationJob,String>("Ảnh");image.setCellValueFactory(c->new ReadOnlyStringWrapper(c.getValue().product().imageUrl()));
        image.setCellFactory(c->new TableCell<>(){
            protected void updateItem(String url,boolean empty){super.updateItem(url,empty);setGraphic(null);setText("");
                if(!empty&&url!=null&&url.startsWith("https://")){try{var view=new ImageView(new Image(url,52,52,true,true,true));setGraphic(view);}catch(IllegalArgumentException invalid){setText("Chưa tải được ảnh");}}
            }
        });products.getColumns().addFirst(image);products.setPlaceholder(new Label("Chưa có tác vụ. Nhập từ WB hoặc khôi phục dữ liệu đã lưu."));
    }
    private void column(String name,java.util.function.Function<RegistrationJob,String> text){var c=new TableColumn<RegistrationJob,String>(name);c.setPrefWidth(170);c.setCellValueFactory(row->new ReadOnlyStringWrapper(text.apply(row.getValue())));products.getColumns().add(c);}
    long epoch(){return epoch;}
    boolean accepts(long value){return !closed&&epoch==value;}
    private <T> void run(Callable<T> work,Consumer<T> done){
        if(closed||busy||service==null)return;busy=true;updateControls();long ticket=epoch;
        var task=new Task<T>(){protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(e->{busy=false;if(accepts(ticket))done.accept(task.getValue());updateControls();});
        task.setOnFailed(e->{busy=false;if(accepts(ticket)){status.setText(message(task.getException()));validation.setText(status.getText());}updateControls();});
        task.setOnCancelled(e->{busy=false;updateControls();});
        AppTaskExecutor.execute(task,error->{busy=false;if(accepts(ticket))status.setText("Hàng đợi đang đầy. Hãy thử lại sau.");updateControls();});
    }
    private void restore(){run(()->store.list(gateway.owner()),rows->{allJobs.setAll(rows);status.setText("Đã đọc "+rows.size()+" tác vụ. Yêu cầu chưa rõ kết quả cần đọc đối chiếu, không gửi lại.");});}
    static boolean matches(RegistrationJob job,String query){
        String q=Objects.toString(query,"").strip().toLowerCase(Locale.ROOT);var p=job.product();
        return q.isEmpty()||String.join(" ",Objects.toString(p.title(),""),Objects.toString(p.vendorCode(),""),Long.toString(p.nmId()),Long.toString(p.chrtId()),Objects.toString(job.data().gtin(),"")).toLowerCase(Locale.ROOT).contains(q);
    }
    private void show(RegistrationJob job){
        selected=job;status.setText(job.state().label()+" — GTIN: "+Objects.toString(job.data().gtin(),"chưa có"));
        gtin.setText(job.data().gtin());var draft=job.data().draft();
        title.setText(draft==null?job.product().title():draft.goodName());brand.setText(draft==null?job.product().brand():draft.brand());
        tnved.setText(draft==null?"":draft.tnved());
        if(draft==null)category.setValue(null);
        else category.setValue(category.getItems().stream().filter(c->c.id()==draft.categoryId()).findFirst().orElseGet(()->new Category(draft.categoryId(),"Danh mục đã lưu")));
        attributeFields.clear();reviewed.setSelected(false);
        payload.setText(job.data().payload());signing.setText(job.data().signingXml());
        completion.setText("GTIN: "+job.data().gtin()+"\nMã thẻ: "+Objects.toString(job.data().goodId(),"chưa xác định")+"\nFeed: "+job.data().feedId()+"\n"+job.state().label()+"\nLần đọc xác minh công bố: "+(blank(job.data().verifiedAt())?"chưa có":job.data().verifiedAt()));
        int index=-1;for(int i=0;i<allJobs.size();i++)if(allJobs.get(i).id().equals(job.id())){index=i;break;}
        if(index>=0&&!allJobs.get(index).equals(job)){updatingRows=true;try{allJobs.set(index,job);}finally{updatingRows=false;}}
        var currentJob=job;
        // Loading the audit journal is read-only and does not block window rendering.
        if(!busy)run(()->store.events(currentJob.owner(),currentJob.id()),events->{
            log.setText(events.stream().map(event->event.occurredAt()+" — "+RegistrationState.valueOf(event.state()).label()+" "+message(event.detail())).collect(java.util.stream.Collectors.joining("\n")));
            if(pollable(currentJob.state()))poll.playFromStart();
        });
        updateControls();
    }
    private void saveDraft(Consumer<RegistrationJob> after){
        if(selected==null)return;Draft draft;
        try{
            var attrs=new LinkedHashMap<Long,String>();var types=new LinkedHashMap<Long,String>();
            if(selected.data().draft()!=null){attrs.putAll(selected.data().draft().attributes());types.putAll(selected.data().draft().attributeTypes());}
            attributeFields.managedIds().forEach(id->{attrs.remove(id);types.remove(id);});
            attrs.putAll(attributeFields.values());types.putAll(attributeFields.types());
            if(category.getValue()==null)throw new IllegalArgumentException("V3_CATEGORY_REQUIRED");
            draft=new Draft(tnved.getText().strip(),category.getValue().id(),title.getText().strip(),brand.getText().strip(),attrs);
            draft=new Draft(draft.tnved(),draft.feedTnved(),draft.categoryId(),draft.goodName(),draft.brand(),attrs,types,false);
        }catch(RuntimeException invalid){status.setText("Đọc và chọn danh mục Catalog theo TN VED, rồi kiểm tra thông tin thẻ.");return;}
        var job=selected;var frozen=draft;
        run(()->service.saveDraft(job,frozen),saved->{showWithoutJournal(saved);if(after!=null)after.accept(saved);});
    }
    private void showWithoutJournal(RegistrationJob job){boolean wasBusy=busy;busy=true;show(job);busy=wasBusy;updateControls();}
    private void renderAttributes(List<Attribute> attributes){
        attributeFields.setSchema(attributes,selected.data().draft().attributes(),selected.data().draft().attributeTypes());
        status.setText("Đã đọc "+attributes.size()+" thuộc tính. Kiểm tra giấy tờ, quốc gia và các thông tin pháp lý trước khi lưu.");
    }
    private void review(RegistrationJob job,Action action){
        run(()->{var review=service.review(job,action);return Map.entry(review,store.get(review.owner(),review.jobId()));},prepared->{
            var review=prepared.getKey();showWithoutJournal(prepared.getValue());
            var text=new TextArea(review.content());text.setEditable(false);text.setWrapText(true);text.setPrefSize(850,480);
            String caption=switch(action){case ALLOCATE_GTIN->"Xác nhận xin cấp GTIN";case SUBMIT->review.owner().environment().equals("production")?"GỬI THẺ THẬT":"Gửi thẻ sandbox";case SIGN->"Phê duyệt ký tài liệu này";case SYNC_WB->"Phê duyệt thêm GTIN trên WB";};
            var identity=prepared.getValue().product();
            var context=new Label("Cửa hàng: "+shopLabel+"\nSản phẩm: "+identity.vendorCode()+" — nmID "+identity.nmId()+" / chrtID "+identity.chrtId()+" — Size RU "+identity.russianSize()+"\nMôi trường: "+(review.owner().environment().equals("production")?"thật":"thử nghiệm")+". Kiểm tra đúng GTIN và nội dung trước khi xác nhận.");context.setWrapText(true);
            var dialog=new Dialog<Boolean>();dialog.setTitle(caption);dialog.getDialogPane().setContent(new VBox(8,context,text));
            var approve=new ButtonType(caption,ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(approve,new ButtonType("Hủy",ButtonBar.ButtonData.CANCEL_CLOSE));
            dialog.setResultConverter(button->button==approve);DialogLayout.install(dialog.getDialogPane());AlertService.applyTheme(dialog);
            if(dialog.showAndWait().orElse(false))run(()->service.confirm(review),this::show);
            else run(()->store.get(review.owner(),review.jobId()),this::show);
        });
    }
    private void reconcile(){var job=selected;if(job!=null)run(()->service.reconcile(job),this::show);}
    private static boolean pollable(RegistrationState state){return Set.of(SUBMITTED,UNDER_MODERATION,SIGNATURE_SENT,VERIFYING_PUBLICATION).contains(state);}
    private void updateControls(){
        for(var button:commands)button.setDisable(closed||busy||service==null);
        for(var button:commands){
            if("znackV3Submit".equals(button.getId()))button.setDisable(closed||busy||selected==null||!Set.of(VALIDATED,SUBMISSION_PENDING_CONFIRMATION).contains(selected.state()));
            if("znackV3Sign".equals(button.getId()))button.setDisable(closed||busy||selected==null||!Set.of(APPROVED_FOR_SIGNING,SIGNING_PENDING).contains(selected.state()));
        }
        products.setDisable(busy||closed);
    }
    private static String message(Throwable error){return error==null?"Không xác định được kết quả.":message(error.getMessage());}
    private static String message(String code){
        if(code==null||code.isBlank())return "";
        if(code.contains("V3_API_CONTRACT_UNVERIFIED"))return "Chưa thể gửi thật: cần xác minh tài liệu API chính thức trước khi mở thao tác này.";
        if(code.contains("V3_REMOTE_RESULT_UNKNOWN"))return "Chưa rõ kết quả máy chủ. Không gửi lại; đọc đối chiếu hoặc kiểm tra thủ công.";
        if(code.contains("V3_ALLOCATION_MANUAL"))return "Lần cấp GTIN chưa rõ kết quả. Kiểm tra danh sách GTIN trên Catalog trước khi tiếp tục; không xin cấp lại.";
        if(code.contains("V3_"))return "Tác vụ chưa thể tiếp tục. Kiểm tra trạng thái, quyền tài khoản và đọc lại dữ liệu. Mã: "+code.replaceAll("[^A-Z0-9_ ]","");
        return ZnackErrorMessage.safe(code);
    }
    private static final class ZnackErrorMessage {static String safe(String value){return com.vncode.app.integration.znack.ZnackSanitizer.message(value);}}
    @Override public void close(){closed=true;epoch++;poll.stop();updateControls();}
}
