package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.gtinsync.WbOzonGtinMatcher.Candidate;
import com.vncode.app.features.gtinsync.WbOzonGtinMatcher.Result;
import com.vncode.app.features.gtinsync.WbOzonGtinReviewService;
import com.vncode.app.features.gtinsync.WbOzonGtinDispatchService;
import com.vncode.app.features.products.ProductRow;
import com.vncode.app.features.shop.ShopRepository;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.AlertService;
import com.vncode.app.shared.AppTaskExecutor;
import com.vncode.app.shared.FriendlyErrorService;
import com.vncode.app.shared.I18nService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Modeless review: candidate matches are never treated as permission to write. */
public final class WbOzonGtinLinkDialog {
    private WbOzonGtinLinkDialog() {}

    public static void show(Window owner, Shop ozon) {
        Objects.requireNonNull(ozon);
        if (ozon.getMarketplace()!=Marketplace.OZON) throw new IllegalArgumentException("shop_mismatch");
        var dialog=new Dialog<Void>();
        if(owner!=null)dialog.initOwner(owner);
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("wb_ozon.title"));
        var review=new Review(copy(ozon));
        dialog.getDialogPane().setContent(review);
        fit(dialog,owner,1180,720);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnCloseRequest(event->{if(review.applying)event.consume();});
        dialog.setOnHidden(event->review.dispose());
        dialog.show();
    }

    static final class Review extends VBox {
        private record Applied(WbOzonGtinReviewService.ApprovalReport approval,List<WbOzonGtinDispatchService.Report> reports) {}
        interface Backend {
            List<Shop> shops();
            WbOzonGtinReviewService.Snapshot scan(Shop wb,Shop ozon,boolean cached,Consumer<String> progress)throws Exception;
            List<Result> manual(WbOzonGtinReviewService.Snapshot snapshot,List<ProductRow> rows,String wbCardId);
            WbOzonGtinReviewService.ApprovalReport approve(Shop wb,Shop ozon,WbOzonGtinReviewService.Snapshot snapshot,List<Candidate> chosen,Consumer<String> progress)throws Exception;
            List<WbOzonGtinDispatchService.Report> apply(Shop wb,Shop ozon,List<com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link> links,Consumer<WbOzonGtinDispatchService.Report> progress)throws Exception;
        }
        private static final class Production implements Backend {
            private final WbOzonGtinReviewService review=new WbOzonGtinReviewService();
            private final WbOzonGtinDispatchService dispatch=new WbOzonGtinDispatchService();
            public List<Shop> shops(){return new ShopRepository().findAll();}
            public WbOzonGtinReviewService.Snapshot scan(Shop wb,Shop ozon,boolean cached,Consumer<String> progress)throws Exception{return cached?review.scanCached(wb,ozon,progress):review.scan(wb,ozon,progress);}
            public List<Result> manual(WbOzonGtinReviewService.Snapshot snapshot,List<ProductRow> rows,String card){return review.evaluateManualBatch(snapshot,rows,card);}
            public WbOzonGtinReviewService.ApprovalReport approve(Shop wb,Shop ozon,WbOzonGtinReviewService.Snapshot snapshot,List<Candidate> chosen,Consumer<String> progress)throws Exception{return review.approveWithReport(wb,ozon,snapshot,chosen,progress);}
            public List<WbOzonGtinDispatchService.Report> apply(Shop wb,Shop ozon,List<com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link> links,Consumer<WbOzonGtinDispatchService.Report> progress)throws Exception{return dispatch.apply(wb,ozon,links,progress);}
        }
        private final Shop ozon;
        private final Backend backend;
        private java.util.function.Predicate<List<Row>> confirmation=this::confirm;
        private final ComboBox<Shop> wbShops=new ComboBox<>();
        private final TableView<Row> table=new TableView<>();
        private final javafx.collections.ObservableList<Row> rows=FXCollections.observableArrayList();
        private final FilteredList<Row> visibleRows=new FilteredList<>(rows,ignored->true);
        private final TextField search=new TextField();
        private final java.util.Map<com.vncode.app.features.products.ProductKey,Row> rowsByKey=new HashMap<>();
        private final java.util.concurrent.ConcurrentLinkedQueue<WbOzonGtinDispatchService.Report> pendingReports=new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicBoolean reportQueued=new java.util.concurrent.atomic.AtomicBoolean();
        private final Label status=new Label(), hint=new Label();
        private final Button scan=new Button(), scanCached=new Button(), selectSafe=new Button(), selectAll=new Button(), pickWb=new Button(), apply=new Button();
        private WbOzonGtinReviewService.Snapshot snapshot;
        private Task<?> task;
        private boolean busy, applying, disposed, changingSelection;
        private long epoch;

        Review(Shop ozon){this(ozon,new Production());}
        Review(Shop ozon,Backend backend) {
            this.ozon=copy(ozon);
            this.backend=Objects.requireNonNull(backend);
            setId("wbOzonGtinReview");setPadding(new Insets(12));setSpacing(9);
            wbShops.setId("wbOzonWbShop");wbShops.setPrefWidth(260);
            wbShops.setConverter(new StringConverter<>() {
                @Override public String toString(Shop value){return value==null?"":value.getName()+" · WB";}
                @Override public Shop fromString(String text){throw new UnsupportedOperationException();}
            });
            wbShops.valueProperty().addListener((o,a,b)->{if(!busy){snapshot=null;rowsByKey.clear();rows.clear();status.setText("");buttons();}});
            scan.setId("wbOzonScan");scanCached.setId("wbOzonScanCached");selectSafe.setId("wbOzonSelectSafe");selectAll.setId("wbOzonSelectAll");pickWb.setId("wbOzonPickWb");apply.setId("wbOzonApply");
            scan.setText(tr("wb_ozon.scan"));scanCached.setText(tr("wb_ozon.scan_cached"));selectSafe.setText(tr("wb_ozon.select_safe"));selectAll.setText(tr("wb_ozon.select_all"));pickWb.setText(tr("wb_ozon.pick_wb"));apply.setText(tr("wb_ozon.apply"));
            hint.setText(tr("wb_ozon.hint"));hint.setWrapText(true);status.setWrapText(true);
            var ownerLabel=new Label(tr("wb_ozon.ozon_shop")+": "+ozon.getName());
            var filters=new FlowPane(8,8,new Label(tr("wb_ozon.wb_shop")),wbShops,scan,scanCached,selectSafe,selectAll,pickWb,apply);
            search.setId("wbOzonSearch");search.setPromptText(tr("wb_ozon.search_ozon"));
            search.textProperty().addListener((o,a,b)->{String q=Objects.toString(b,"").strip().toLowerCase(java.util.Locale.ROOT);visibleRows.setPredicate(row->q.isBlank()||displayProduct(row.ozon()).toLowerCase(java.util.Locale.ROOT).contains(q)||row.chosen()!=null&&displayProduct(row.chosen().wb()).toLowerCase(java.util.Locale.ROOT).contains(q));buttons();});
            table.setId("wbOzonCandidates");table.setItems(visibleRows);table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            var checks=new TableColumn<Row,Row>("✓");checks.setMinWidth(45);checks.setMaxWidth(50);checks.setSortable(false);
            checks.setCellValueFactory(value->new ReadOnlyObjectWrapper<>(value.getValue()));
            checks.setCellFactory(ignored->new TableCell<>(){
                private final CheckBox check=new CheckBox();
                {check.setOnAction(event->{Row row=getItem();if(row==null)return;if(check.isSelected())table.getSelectionModel().select(row);else table.getSelectionModel().clearSelection(getIndex());table.refresh();});check.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);}
                @Override protected void updateItem(Row row,boolean empty){super.updateItem(row,empty);if(empty||row==null){setGraphic(null);return;}check.setSelected(table.getSelectionModel().getSelectedItems().contains(row));setGraphic(check);}
            });table.getColumns().add(checks);
            column(tr("wb_ozon.ozon_product"),row->displayProduct(row.ozon()));
            column(tr("wb_ozon.variant"),row->variant(row.ozon()));
            column(tr("wb_ozon.wb_product"),row->row.chosen()!=null?displayProduct(row.chosen().wb()):row.saved()==null?"—":row.saved().wbNmId()+" / "+row.saved().wbChrtId());
            column("GTIN",row->row.saved()!=null?row.saved().gtin():row.chosen()==null?"—":row.chosen().catalog().gtin());
            column(tr("wb_ozon.attributes"),row->row.chosen()==null?"—":attributes(row.chosen()));
            column(tr("wb_ozon.evidence"),Row::evidence);
            column(tr("wb_ozon.state"),Row::detail);
            table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<Row>)change->{if(!changingSelection){table.refresh();buttons();}});
            scan.setOnAction(e->scan(false));scanCached.setOnAction(e->scan(true));
            selectSafe.setOnAction(e->{
                int[] indices=java.util.stream.IntStream.range(0,table.getItems().size()).filter(i->{Row row=table.getItems().get(i);return !row.verified()&&(row.saved()!=null||!row.stale()&&row.chosen()!=null&&row.chosen().autoEligible());}).toArray();
                changingSelection=true;try{table.getSelectionModel().clearSelection();if(indices.length>0)table.getSelectionModel().selectIndices(indices[0],java.util.Arrays.copyOfRange(indices,1,indices.length));}
                finally{changingSelection=false;}table.refresh();buttons();
            });
            selectAll.setOnAction(e->{changingSelection=true;try{table.getSelectionModel().selectAll();}finally{changingSelection=false;}table.refresh();buttons();});
            pickWb.setOnAction(e->pickWb());
            apply.setOnAction(e->apply());
            getChildren().setAll(ownerLabel,hint,filters,search,status,table);VBox.setVgrow(table,Priority.ALWAYS);
            buttons();loadShops();
        }

        private void loadShops(){
            work(()->backend.shops().stream().filter(s->s.getMarketplace()==Marketplace.WILDBERRIES&&s.isCredentialConfigured()).map(WbOzonGtinLinkDialog::copy).toList(),
                    values->{wbShops.getItems().setAll(values);if(values.size()==1)wbShops.setValue(values.getFirst());status.setText(values.isEmpty()?tr("wb_ozon.no_wb"):tr("wb_ozon.choose_wb"));});
        }
        private void scan(boolean cached){
            Shop wb=wbShops.getValue();if(wb==null||busy)return;
            snapshot=null;rowsByKey.clear();rows.clear();status.setText(tr(cached?"wb_ozon.loading_cached":"wb_ozon.loading"));
            work(()->backend.scan(copy(wb),copy(ozon),cached,this::progress),value->{
                snapshot=value;var rows=new ArrayList<Row>();
                for(Result result:value.rows()){var row=new Row(result);rows.add(row);rowsByKey.put(row.ozon().key(),row);}
                for(var saved:value.savedLinks()){
                    var key=new com.vncode.app.features.products.ProductKey(ozon.getId(),Marketplace.OZON,saved.ozonProductId(),saved.ozonOfferId());
                    var row=rowsByKey.get(key);if(row==null)continue;
                    if(saved.needsReview()){row.setStale(true);row.setDetail(tr("wb_ozon.link_stale"));}
                    else if(saved.current()!=null){row.setSaved(saved.current());if(row.chosen()!=null&&!sameSaved(row.chosen(),saved.current()))row.setChosen(null);row.setDetail(tr("wb_ozon.link_saved"));}
                }
                this.rows.setAll(rows);
                status.setText(tr(cached?"wb_ozon.cached_done":"wb_ozon.scan_done")+" · "+rows.size()+warnings(value.warnings()));
            });
        }
        private void pickWb(){
            if(snapshot==null||busy)return;
            var selected=List.copyOf(table.getSelectionModel().getSelectedItems());if(selected.isEmpty())return;
            ProductRow picked=chooseWbProduct();if(picked==null)return;
            var source=snapshot;
            work(()->{
                return backend.manual(source,selected.stream().map(Row::ozon).toList(),picked.key().cardId());
            },matches->{
                int updated=0;
                for(int i=0;i<selected.size();i++){
                    Row row=selected.get(i);var match=matches.get(i);var choices=match.candidates();
                    row.setSaved(null);row.setStale(false);
                    if(choices.size()==1){row.setChosen(choices.getFirst());row.setDetail(tr("wb_ozon.manual_review")+candidateWarnings(choices.getFirst()));updated++;}
                    else {row.setChosen(null);row.setDetail(choices.isEmpty()?reason(match.reason()):tr("wb_ozon.ambiguous"));}
                }
                table.refresh();status.setText(tr("wb_ozon.manual_done")+" · "+updated+" / "+selected.size());
            });
        }
        private ProductRow chooseWbProduct(){
            var unique=new LinkedHashMap<String,ProductRow>();
            for(var row:snapshot.wbRows())unique.putIfAbsent(row.key().cardId(),row);
            var data=FXCollections.observableArrayList(unique.values());var filtered=new FilteredList<>(data,ignored->true);
            var query=new TextField();query.setPromptText(tr("wb_ozon.search_wb"));
            var list=new ListView<ProductRow>(filtered);list.setPrefSize(720,420);list.setMinSize(0,0);
            list.setCellFactory(v->new ListCell<>(){@Override protected void updateItem(ProductRow value,boolean empty){super.updateItem(value,empty);setText(empty||value==null?null:displayProduct(value));}});
            query.textProperty().addListener((o,a,b)->{String q=Objects.toString(b,"").strip().toLowerCase(java.util.Locale.ROOT);filtered.setPredicate(row->q.isBlank()||displayProduct(row).toLowerCase(java.util.Locale.ROOT).contains(q));});
            var dialog=new Dialog<ProductRow>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());AlertService.applyTheme(dialog);dialog.setTitle(tr("wb_ozon.pick_wb"));
            var content=new VBox(8,query,list);VBox.setVgrow(list,Priority.ALWAYS);
            dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK,ButtonType.CANCEL);fit(dialog,getScene()==null?null:getScene().getWindow(),760,520);
            var ok=dialog.getDialogPane().lookupButton(ButtonType.OK);ok.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
            dialog.setResultConverter(button->button==ButtonType.OK?list.getSelectionModel().getSelectedItem():null);
            return dialog.showAndWait().orElse(null);
        }
        private void apply(){
            if(snapshot==null||busy||wbShops.getValue()==null)return;
            var selected=List.copyOf(table.getSelectionModel().getSelectedItems());
            if(selected.isEmpty()||selected.stream().anyMatch(row->row.chosen()==null&&row.saved()==null))return;
            if(!confirmation.test(selected))return;
            var chosen=selected.stream().filter(row->row.saved()==null).map(Row::chosen).toList();
            var saved=selected.stream().map(Row::saved).filter(Objects::nonNull).toList();
            var wb=copy(wbShops.getValue());var source=snapshot;
            applying=true;
            work(()->{
                var approval=chosen.isEmpty()?new WbOzonGtinReviewService.ApprovalReport(List.of(),List.of()):backend.approve(wb,copy(ozon),source,chosen,this::progress);
                Platform.runLater(()->{if(!disposed){for(var failure:approval.failures())renderFailure(failure);table.refresh();}});
                var links=new ArrayList<com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link>(saved);links.addAll(approval.links());
                var reports=links.isEmpty()?List.<WbOzonGtinDispatchService.Report>of():backend.apply(wb,copy(ozon),List.copyOf(links),this::report);
                return new Applied(approval,reports);
            },result->{for(var failure:result.approval().failures())renderFailure(failure);for(var report:result.reports())renderReport(report);table.refresh();status.setText(tr("wb_ozon.done"));applying=false;});
        }
        void setConfirmation(java.util.function.Predicate<List<Row>> confirmation){this.confirmation=Objects.requireNonNull(confirmation);}
        private boolean confirm(List<Row> selected){
            var consent=new Alert(Alert.AlertType.CONFIRMATION);
            consent.getButtonTypes().setAll(ButtonType.OK,ButtonType.CANCEL);
            consent.setHeaderText(tr("wb_ozon.confirm")+" · "+selected.size());
            String review=selected.stream().limit(100).map(row->row.saved()!=null?savedDetails(row):reviewDetails(row.chosen())).collect(java.util.stream.Collectors.joining("\n\n"));
            if(selected.size()>100)review+="\n\n"+tr("wb_ozon.confirm_more")+" "+(selected.size()-100);
            var details=new TextArea(review);
            details.setEditable(false);details.setWrapText(true);details.setPrefSize(820,420);details.setMinSize(0,0);
            consent.getDialogPane().setContent(details);fit(consent,getScene()==null?null:getScene().getWindow(),860,520);
            if(getScene()!=null)consent.initOwner(getScene().getWindow());AlertService.applyTheme(consent);
            return consent.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
        }
        private void renderFailure(WbOzonGtinReviewService.ApprovalFailure failure){Row row=rowsByKey.get(failure.candidate().ozon().key());if(row!=null)row.setDetail(tr("wb_ozon.result.failed")+" · "+reason(Objects.toString(failure.reason(),"")));}
        private void report(WbOzonGtinDispatchService.Report report){pendingReports.add(report);scheduleReports();}
        private void scheduleReports(){
            if(!reportQueued.compareAndSet(false,true))return;
            Platform.runLater(()->{try{if(!disposed){WbOzonGtinDispatchService.Report item;while((item=pendingReports.poll())!=null)renderReport(item);table.refresh();}else pendingReports.clear();}
                finally{reportQueued.set(false);if(!pendingReports.isEmpty())scheduleReports();}});
        }
        private void renderReport(WbOzonGtinDispatchService.Report report){
            var key=new com.vncode.app.features.products.ProductKey(ozon.getId(),Marketplace.OZON,report.link().ozonProductId(),report.link().ozonOfferId());
            Row row=rowsByKey.get(key);if(row==null)return;
            String value=tr("wb_ozon.result."+report.state().name().toLowerCase(java.util.Locale.ROOT));
            String reason=Objects.toString(report.reason(),"");row.setVerified(report.state()==WbOzonGtinDispatchService.State.VERIFIED);row.setDetail(value+(reason.isBlank()?"":" · "+WbOzonGtinLinkDialog.reason(reason)));
        }
        private void progress(String message){Platform.runLater(()->{if(!disposed)status.setText(RegistrationDiagnostics.reason(Objects.toString(message,"")));});}
        private <T>void work(java.util.concurrent.Callable<T> callable,Consumer<T> done){
            if(busy||disposed)return;busy=true;long token=++epoch;buttons();
            var running=new Task<T>(){@Override protected T call()throws Exception{return callable.call();}};task=running;
            running.setOnSucceeded(e->{if(disposed||token!=epoch)return;busy=false;task=null;done.accept(running.getValue());buttons();});
            Consumer<Throwable> failed=error->{if(disposed||token!=epoch)return;busy=false;applying=false;task=null;status.setText(tr("wb_ozon.failed")+" · "+FriendlyErrorService.format(error));buttons();};
            running.setOnFailed(e->failed.accept(running.getException()));
            running.setOnCancelled(e->failed.accept(new java.net.SocketTimeoutException("WB_OZON_REVIEW_TIMEOUT")));
            // A reviewed multi-card write may exceed the catalog read lane's three-minute
            // deadline. Its service retains per-card outcomes; do not cancel it mid-write.
            if(applying)AppTaskExecutor.execute(running,failed);else AppTaskExecutor.executeCatalog(running,failed);
        }
        private void buttons(){
            boolean available=!busy&&snapshot!=null;
            wbShops.setDisable(busy);scan.setDisable(busy||wbShops.getValue()==null);scanCached.setDisable(busy||wbShops.getValue()==null);
            selectSafe.setDisable(!available||table.getItems().stream().noneMatch(r->!r.verified()&&(r.saved()!=null||!r.stale()&&r.chosen()!=null&&r.chosen().autoEligible())));
            selectAll.setDisable(!available||table.getItems().isEmpty());
            pickWb.setDisable(!available||table.getSelectionModel().isEmpty());
            apply.setDisable(!available||table.getSelectionModel().isEmpty()||table.getSelectionModel().getSelectedItems().stream().anyMatch(r->r.chosen()==null&&r.saved()==null));
        }
        private void dispose(){disposed=true;++epoch;if(task!=null&&!applying)task.cancel(true);}
        private void column(String title,Function<Row,String> getter){var column=new TableColumn<Row,String>(title);column.setCellValueFactory(c->new ReadOnlyStringWrapper(getter.apply(c.getValue())));column.setPrefWidth(155);table.getColumns().add(column);}
    }
    static final class Row {
        private final ProductRow ozon;
        private Candidate chosen;
        private com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link saved;
        private boolean stale,verified;
        private String detail;
        Row(Result result){ozon=result.ozon();chosen=result.candidates().size()==1?result.candidates().getFirst():null;detail=(result.reason().isBlank()?(chosen!=null&&chosen.autoEligible()?tr("wb_ozon.suggested"):tr("wb_ozon.review_required")):reason(result.reason()))+candidateWarnings(chosen);}
        ProductRow ozon(){return ozon;}Candidate chosen(){return chosen;}void setChosen(Candidate value){chosen=value;}com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link saved(){return saved;}void setSaved(com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link value){saved=value;}boolean stale(){return stale;}void setStale(boolean value){stale=value;}boolean verified(){return verified;}void setVerified(boolean value){verified=value;}String detail(){return detail;}void setDetail(String value){detail=value;}
        String evidence(){return saved!=null?tr("wb_ozon.link_saved"):chosen==null?"—":tr("wb_ozon.evidence."+chosen.evidence().name().toLowerCase(java.util.Locale.ROOT));}
    }
    private static String reason(String code){if(code.contains(", "))return java.util.Arrays.stream(code.split(", ")).map(WbOzonGtinLinkDialog::reason).distinct().collect(java.util.stream.Collectors.joining(" · "));return switch(code){case "NO_SAFE_MATCH"->tr("wb_ozon.no_variant");case "AMBIGUOUS","GTIN_MULTIPLE_OZON_VARIANTS"->tr("wb_ozon.ambiguous");case "EXISTING_GTIN_CONFLICT"->tr("wb_ozon.gtin_conflict");case "SELLER_REVIEW_REQUIRED"->tr("wb_ozon.manual_review");case "WB_OZON_LINK_REVIEW_CONFLICT"->tr("wb_ozon.link_conflict");case "WB_OZON_WB_PRODUCT_CHANGED","WB_OZON_WB_VARIANT_CHANGED"->tr("wb_ozon.wb_changed");case "WB_OZON_WB_GTIN_UNVERIFIED"->tr("wb_ozon.wb_gtin_unknown");case "WB_OZON_LINK_REVIEW_CHANGED"->tr("wb_ozon.link_stale");case "WB_OZON_DISPATCH_RECONCILE_REQUIRED"->tr("wb_ozon.reconcile");case "WB_OZON_RESUME_FROM_LINK_WORKFLOW"->tr("wb_ozon.resume");case "API_COOLDOWN"->tr("wb_ozon.cooldown");case "GTIN_TNVED_GROUP_CHANGED","GTIN_TNVED_MISSING","FULL_TNVED_REQUIRED","TNVED_MISMATCH"->tr("wb_ozon.tnved_problem");case "COLOR_MISSING"->tr("wb_ozon.color_missing");case "COLOR_MISMATCH"->tr("wb_ozon.color_mismatch");case "SIZE_MISSING"->tr("wb_ozon.size_missing");case "SIZE_MISMATCH"->tr("wb_ozon.size_mismatch");case "BRAND_MISMATCH"->tr("wb_ozon.brand_mismatch");case "GENDER_MISMATCH"->tr("wb_ozon.gender_mismatch");case "MATERIAL_MISMATCH"->tr("wb_ozon.material_mismatch");case "MULTIPLE_IDENTITY_MISMATCHES"->tr("wb_ozon.multiple_mismatch");default->RegistrationDiagnostics.reason(code);};}
    private static String variant(ProductRow row){return Objects.toString(row.color(),"")+" · "+Objects.toString(row.size(),"");}
    private static String displayProduct(ProductRow row){return Objects.toString(row.title(),"")+"\n"+Objects.toString(row.vendorArticle(),"")+" · "+row.key().cardId()+" / "+row.key().variantId();}
    private static String attributes(Candidate c){return "WB: "+facts(c.wb())+"\nOzon: "+facts(c.ozon())+"\nCatalog: "+String.join(" · ",c.catalog().details().single("brand"),c.catalog().details().single("material"),c.catalog().details().single("gender"),c.catalog().details().single("tnved"));}
    private static String facts(ProductRow row){return String.join(" · ",Objects.toString(row.category(),""),Objects.toString(row.brand(),""),Objects.toString(row.material(),""),Objects.toString(row.gender(),""),Objects.toString(row.tnved(),""));}
    private static String reviewDetails(Candidate c){return "WB: "+displayProduct(c.wb())+" · "+variant(c.wb())+"\nOzon: "+displayProduct(c.ozon())+" · "+variant(c.ozon())+"\nGTIN: "+c.catalog().gtin()+" · good_id: "+c.catalog().details().single("@catalog_id")+"\n"+attributes(c)+candidateWarnings(c);}
    private static String savedDetails(Row row){var link=row.saved();return tr("wb_ozon.link_saved")+"\nWB: "+link.wbNmId()+" / "+link.wbChrtId()+"\nOzon: "+displayProduct(row.ozon())+" · SKU "+link.ozonSku()+"\nGTIN: "+link.gtin()+" · good_id: "+link.goodId();}
    private static boolean sameSaved(Candidate candidate,com.vncode.app.features.gtinsync.WbOzonLinkRepository.Link link){return candidate.wb().key().cardId().equals(link.wbNmId())&&candidate.wb().key().variantId().equals(link.wbChrtId())&&com.vncode.app.features.gtinsync.RegisteredGtinProof.sameCode(candidate.catalog().gtin(),link.gtin())&&candidate.catalog().details().single("@catalog_id").equals(Long.toString(link.goodId()));}
    private static String candidateWarnings(Candidate c){return c==null||c.reviewWarnings().isEmpty()?"":"\n⚠ "+c.reviewWarnings().stream().map(WbOzonGtinLinkDialog::reason).distinct().collect(java.util.stream.Collectors.joining(" · "));}
    private static String warnings(List<String> values){return values.isEmpty()?"":" · "+String.join(" · ",values.stream().map(RegistrationDiagnostics::reason).toList());}
    private static void fit(Dialog<?> dialog,Window owner,double width,double height){
        var screens=owner==null?List.<javafx.stage.Screen>of():javafx.stage.Screen.getScreensForRectangle(owner.getX(),owner.getY(),Math.max(1,owner.getWidth()),Math.max(1,owner.getHeight()));
        var bounds=(screens.isEmpty()?javafx.stage.Screen.getPrimary():screens.getFirst()).getVisualBounds();
        double maxWidth=owner!=null&&owner.getWidth()>0?Math.min(bounds.getWidth(),owner.getWidth()):bounds.getWidth();
        double maxHeight=owner!=null&&owner.getHeight()>0?Math.min(bounds.getHeight(),owner.getHeight()):bounds.getHeight();
        dialog.setResizable(true);dialog.getDialogPane().setPrefSize(Math.max(200,Math.min(width,maxWidth-24)),Math.max(160,Math.min(height,maxHeight-48)));
    }
    private static Shop copy(Shop shop){return new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
