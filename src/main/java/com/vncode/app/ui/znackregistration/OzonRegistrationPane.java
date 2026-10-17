package com.vncode.app.ui.znackregistration;

import com.vncode.app.features.products.*;
import com.vncode.app.features.sync.*;
import com.vncode.app.integration.marketplace.Marketplace;
import com.vncode.app.integration.znack.ZnackModels;
import com.vncode.app.integration.znack.registration.*;
import com.vncode.app.integration.znack.registration.ZnackCardRegistrationModels.*;
import com.vncode.app.models.Shop;
import com.vncode.app.shared.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Native Ozon identities and reviewed National Catalog attributes; no WB identifier conversion. */
public final class OzonRegistrationPane extends VBox {
    private final RegistrationSources sources=new RegistrationSources();
    private RegistrationSources.Readiness readiness;
    private RegistrationCardIndex cardIndex;
    private final Map<ProductKey,String> skus=new HashMap<>();
    private final Shop owner;
    private final Runnable openGtin;
    private final ProductRepository products=new ProductRepository();
    private final OzonCardRegistrationRepository registrations=new OzonCardRegistrationRepository();
    private final OzonCardRegistrationWorkflow workflow=new OzonCardRegistrationWorkflow();
    private final TableView<ProductRow> table=new TableView<>();
    private final Map<ProductKey,OzonCardRegistrationRepository.Row> states=new HashMap<>();
    private final TextField search=new TextField();
    private final CheckBox autoSign=new CheckBox(),allFiltered=new CheckBox();
    private final Label title=new Label(),status=new Label(),count=new Label(),selection=new Label();
    private final Button report=new Button(),documents=new Button(),refresh=new Button(),sync=new Button(),sign=new Button(),gtin=new Button(),all=new Button(),clear=new Button(),register=new Button(),newCards=new Button(),stopBatch=new Button(),more=new Button();
    private final PauseTransition debounce=new PauseTransition(Duration.millis(250)),refreshDebounce=new PauseTransition(Duration.millis(400));
    private List<ProductKey> frozen=List.of();
    private String cursor;
    private long total,generation;
    private boolean busy,disposed,programmaticSelection,batchReviewing,deferredReload;
    private volatile long lastBatchProgress;
    private final java.util.concurrent.atomic.AtomicBoolean cancelBatch=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean progressQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reviewPending=new java.util.concurrent.atomic.AtomicBoolean();

    public OzonRegistrationPane(Shop shop){this(shop,null);}
    public OzonRegistrationPane(Shop shop,Runnable openGtin) {
        if(shop==null||shop.getMarketplace()!=Marketplace.OZON)throw new IllegalArgumentException("shop_mismatch");
        this.owner=new Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());
        this.openGtin=openGtin==null?()->GtinBackgroundStatus.start((StackPane)table.getParent(),owner,new HashSet<>(selected()),this::refreshLocal):openGtin;
        setId("ozonRegistrationPane");setSpacing(8);setPadding(new Insets(12));
        title.getStyleClass().add("h2");status.setWrapText(true);status.getStyleClass().add("text-muted");
        title.setMinWidth(210);title.setPrefWidth(240);title.setMaxWidth(260);var space=new Region();HBox.setHgrow(space,Priority.ALWAYS);
        var header=new HBox(10,title,space,documents,sign,gtin,sync);header.setId("ozonRegistrationHeader");header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        for(var button:List.of(documents,sign,gtin,sync)){button.setMinWidth(100);button.setPrefWidth(210);button.setWrapText(true);}
        var options=new ContextMenu();for(var node:List.<javafx.scene.Node>of(refresh,report,newCards,autoSign,clear,stopBatch,selection))options.getItems().add(new CustomMenuItem(node,false));
        title.setContextMenu(options);title.setFocusTraversable(true);title.setTooltip(new Tooltip(tr("znack.registration.more_actions_hint")));
        var clearFilters=new Button(tr("znack.registration.clear"));clearFilters.setOnAction(e->{search.clear();reload();});
        var filters=new FlowPane(8,8,search,clearFilters);autoSign.setSelected(true);autoSign.setWrapText(true);autoSign.setId("ozonRegistrationAutoSign");search.setPrefWidth(375);
        allFiltered.setOnAction(e->{if(allFiltered.isSelected())all.fire();else clear.fire();});
        var footer=new HBox(10,allFiltered,register,count,more);footer.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(search,Priority.ALWAYS);getChildren().setAll(header,status,filters,new StackPane(table),footer);VBox.setVgrow(table.getParent(),Priority.ALWAYS);
        TableScrollLoader.install(table,()->!disposed&&!busy&&!batchReviewing&&ready()&&cursor!=null,()->load(true));
        table.setId("ozonRegistrationTable");table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<ProductRow>)change->{if(!programmaticSelection){frozen=List.of();buttons();}});
        documents.setOnAction(e->configureDocuments());refresh.setOnAction(e->reload());sync.setOnAction(e->syncCatalog());sign.setOnAction(e->signApproved());gtin.setOnAction(e->{if(ready()&&!busy)this.openGtin.run();});report.setOnAction(e->RegistrationSyncReportDialog.show(getScene()==null?null:getScene().getWindow(),owner,readiness==null?"":readiness.run()));
        stopBatch.setOnAction(e->{cancelBatch.set(true);stopBatch.setDisable(true);status.setText(tr("znack.registration.batch_stopping"));});all.setOnAction(e->selectAll());clear.setOnAction(e->{frozen=List.of();table.getSelectionModel().clearSelection();buttons();});
        register.setOnAction(e->registerSelection());newCards.setOnAction(e->registerBatch(true));more.setOnAction(e->load(true));
        debounce.setOnFinished(e->reload());refreshDebounce.setOnFinished(e->reload());search.textProperty().addListener((o,a,b)->debounce.playFromStart());
        applyTranslations();reload();
    }
    public void refreshLocal(){if(!busy&&!batchReviewing)refreshDebounce.playFromStart();else deferredReload=true;}
    public void dispose(){disposed=true;cancelBatch.set(true);++generation;debounce.stop();refreshDebounce.stop();}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
    public void applyTranslations() {
        title.setText(tr("znack.registration.title_ozon")+" · "+owner.getName());
        documents.setText(tr("znack.registration.documents"));refresh.setText(tr("znack.registration.refresh"));sync.setText(tr("znack.registration.sync_znack"));report.setText(tr("znack.registration.report"));sign.setText(tr("znack.registration.sign_approved"));
        gtin.setText(tr("znack.registration.update_ozon"));all.setText(tr("znack.registration.select_all"));clear.setText(tr("znack.registration.clear_selection"));
        autoSign.setText(tr("znack.registration.auto_sign"));allFiltered.setText(tr("znack.registration.select_all_filtered"));register.setText(tr("znack.registration.register_short"));newCards.setText(tr("znack.registration.batch_new_cards"));stopBatch.setText(tr("znack.registration.batch_stop"));more.setText(tr("znack.registration.load_more"));search.setPromptText(tr("products.search"));
        table.getColumns().clear();
        var images=new TableColumn<ProductRow,ProductRow>(tr("fbo.column.image"));images.setPrefWidth(65);images.setMaxWidth(80);images.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        var imageService=new com.vncode.app.features.fbo.FboProductImageService();
        images.setCellFactory(column->new TableCell<>(){
            private final javafx.scene.image.ImageView image=new javafx.scene.image.ImageView();private String current="";
            {image.setFitWidth(44);image.setFitHeight(58);image.setPreserveRatio(true);}
            protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);current=empty||row==null?"":safe(row.thumbnailUrl());image.setImage(null);setGraphic(current.isBlank()?null:image);String requested=current;
                if(!requested.isBlank())imageService.loadThumbnailBytes(requested).whenComplete((bytes,error)->Platform.runLater(()->{if(!disposed&&current.equals(requested)&&bytes!=null&&bytes.length>0)image.setImage(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)));}));}
        });table.getColumns().add(images);column("Product ID / offer_id",row->row.key().cardId()+" / "+row.key().variantId());column(tr("fbo.column.name"),row->row.title()+(safe(row.tnved()).isBlank()?"":"\nTN VED: "+row.tnved()));
        column("SKU",row->skus.getOrDefault(row.key(),""));column(tr("products.article"),ProductRow::vendorArticle);column(tr("znack.registration.gender"),ProductRow::gender);column(tr("fbo.column.color"),ProductRow::color);column(tr("fbo.column.size"),ProductRow::size);
        column("Barcode / GTIN",row->String.join(", ",row.barcodes())+(view(row).gtin().isBlank()?"":"\nGTIN: "+view(row).gtin())+candidateText(row.key()));column(tr("znack.registration.status"),row->view(row).published()&&!com.vncode.app.features.gtinsync.RegisteredGtinProof.contains(row.barcodes(),view(row).gtin())?tr("znack.registration.awaiting_ozon_gtin"):tr("znack.registration."+view(row).status()));
        column(tr("jobs.reason"),row->reason(view(row).detail())+(states.get(row.key())==null||safe(states.get(row.key()).error()).isBlank()?"":" · "+com.vncode.app.integration.znack.registration.CatalogFeedback.display(states.get(row.key()).error())));
        var actions=new TableColumn<ProductRow,ProductRow>(tr("znack.registration.action"));actions.setCellValueFactory(cell->new ReadOnlyObjectWrapper<>(cell.getValue()));
        actions.setCellFactory(c->new TableCell<>() {
            final MenuButton menu=new MenuButton();final MenuItem create=new MenuItem(),resume=new MenuItem(),switchCard=new MenuItem(),newRejected=new MenuItem();
            {menu.getItems().setAll(create,resume,switchCard,newRejected);newRejected.setOnAction(e->prepare(getItem().key(),true));create.setOnAction(e->prepare(getItem().key(),state(getItem().key())==Status.PUBLISHED&&!groupReplacement(getItem())));
                resume.setOnAction(e->resume(getItem().key()));switchCard.setOnAction(e->complete(getItem().key()));}
            @Override protected void updateItem(ProductRow row,boolean empty) {
                super.updateItem(row,empty);if(empty||row==null){setGraphic(null);return;}
                Status state=state(row.key());var checkpoint=states.get(row.key());boolean candidate=checkpoint!=null&&!safe(checkpoint.gtin()).isBlank()&&!checkpoint.activeGtin().equals(checkpoint.gtin());
                newRejected.setText(tr("znack.registration.create_new_card"));newRejected.setVisible(state==Status.ERROR);newRejected.setDisable(busy||!ready()||view(row).blocked()||checkpoint==null||checkpoint.feedId().isBlank()||checkpoint.gtin().isBlank());
                menu.setText(tr("znack.registration.action"));menu.setDisable(busy||!ready());create.setText(tr(state==Status.PUBLISHED?"znack.registration.create_new_card":"znack.registration.create"));
                create.setDisable(!ready()||view(row).blocked()||(view(row).exists()&&state!=Status.PUBLISHED&&!groupReplacement(row))||busy||(!Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR,Status.PUBLISHED).contains(state)&&!groupReplacement(row))||(state==Status.PUBLISHED&&candidate&&!groupReplacement(row)));
                resume.setText(tr("znack.registration.resume"));resume.setDisable(!ready()||view(row).blocked()||view(row).published()||busy||checkpoint==null||(safe(checkpoint.feedId()).isBlank()&&state!=Status.READY_TO_SUBMIT)||state==Status.PUBLISHED);
                switchCard.setText(tr("znack.registration.switch_card"));switchCard.setDisable(!ready()||!view(row).published()||busy||state!=Status.PUBLISHED||!candidate);setGraphic(menu);
            }
        });actions.setMinWidth(130);actions.setPrefWidth(145);table.getColumns().add(actions);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setRowFactory(t->new TableRow<>(){@Override protected void updateItem(ProductRow row,boolean empty){super.updateItem(row,empty);var r=row==null?null:states.get(row.key());setTooltip(empty||r==null||safe(r.error()).isBlank()?null:new Tooltip(r.error()));}});buttons();
    }
    private void column(String text,java.util.function.Function<ProductRow,String> value){var column=new TableColumn<ProductRow,String>(text);column.setCellValueFactory(c->new ReadOnlyStringWrapper(value.apply(c.getValue())));column.setPrefWidth(160);if(text.equals(tr("jobs.reason"))){column.setMinWidth(150);RegistrationTextCells.wrap(column);}table.getColumns().add(column);}
    private ProductQuery query(String cursor){return new ProductQuery(owner.getId(),Marketplace.OZON,search.getText(),List.of(),null,false,cursor,300);}
    private void reload(){if(disposed)return;if(batchReviewing){deferredReload=true;return;}debounce.stop();++generation;cursor=null;frozen=List.of();readiness=null;cardIndex=null;table.getItems().clear();total=0;load(false);}
    private record Loaded(RegistrationSources.Readiness readiness,RegistrationCardIndex index,ProductPage page,Map<ProductKey,OzonCardRegistrationRepository.Row> states,Map<ProductKey,String> skus){}
    private void load(boolean append) {
        if(disposed||(append&&(busy||cursor==null)))return;
        ProductQuery query=query(append?cursor:null);
        background(()->{
            var ready=sources.check(owner);if(!ready.ready())return new Loaded(ready,null,null,Map.of(),Map.of());
            var index=new RegistrationCardIndex(owner);var page=products.loadPage(query,ready.run());
            var keys=page.rows().stream().map(ProductRow::key).toList();var nativeSkus=new HashMap<ProductKey,String>();
            if(!keys.isEmpty())try(var c=com.vncode.app.config.Database.getConnection();var p=c.prepareStatement("SELECT product_id,offer_id,sku FROM ozon_products WHERE shop_id=? AND product_id IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")")){
                p.setInt(1,owner.getId());for(int n=0;n<keys.size();n++)p.setString(n+2,keys.get(n).cardId());
                var selected=new HashSet<>(keys);try(var rows=p.executeQuery()){while(rows.next()){var key=new ProductKey(owner.getId(),Marketplace.OZON,rows.getString(1),rows.getString(2));if(selected.contains(key))nativeSkus.put(key,rows.getString(3));}}
            }
            if(!sources.require(owner).run().equals(ready.run()))throw new IllegalStateException("SOURCE_CONFIGURATION_CHANGED");
            return new Loaded(ready,index,page,registrations.findMany(keys),Map.copyOf(nativeSkus));
        },loaded->{
            readiness=loaded.readiness();cardIndex=loaded.index();
            if(!ready()){table.getItems().clear();states.clear();skus.clear();cursor=null;total=0;status.setText(tr("znack.registration.sources_required")+" · "+readiness.reason());buttons();return;}
            skus.putAll(loaded.skus());
            if(!append){states.clear();table.getItems().clear();}states.putAll(loaded.states());total=loaded.page().total();cursor=loaded.page().nextCursor();
            programmaticSelection=true;try{table.getItems().addAll(loaded.page().rows());}finally{programmaticSelection=false;}
            status.setText(tr("znack.registration.sources_ready")+" · "+readiness.completed()+"\n"+tr("znack.registration.ozon_review_note"));table.refresh();buttons();
        });
    }
    private void selectAll(){if(busy||!ready())return;ProductQuery query=query(null);background(()->products.freezeKeys(query,sources.require(owner).run()),keys->{frozen=keys;programmaticSelection=true;try{table.getSelectionModel().selectAll();}finally{programmaticSelection=false;}buttons();});}
    private List<ProductKey> selected(){return frozen.isEmpty()?table.getSelectionModel().getSelectedItems().stream().map(ProductRow::key).toList():frozen;}
    private boolean groupReplacement(ProductRow product){var row=states.get(product.key());return row!=null&&com.vncode.app.integration.znack.registration.TnvedGroupChange.replaceable(row.status())&&!row.feedId().isBlank()&&com.vncode.app.integration.znack.registration.TnvedGroupChange.changed(com.vncode.app.integration.znack.registration.TnvedGroupChange.payloadCode(row.payload()),product.tnved());}
    private boolean groupReplacement(ProductKey key){return table.getItems().stream().filter(p->p.key().equals(key)).anyMatch(this::groupReplacement);}
    private void registerSelection(){var selected=selected();if(selected.isEmpty()||busy||!ready())return;if(selected.size()>1){registerBatch(false);return;}
        var key=selected.getFirst();var row=states.get(key);
        if(row!=null&&!groupReplacement(key)&&!safe(row.feedId()).isBlank()&&!Set.of(Status.PUBLISHED,Status.FEED_REVIEW_REQUIRED,Status.GTIN_REVIEW_REQUIRED,Status.FEED_SUBMITTING).contains(row.status()))resume(key);
        else prepare(key,false);
    }
    private void registerBatch(boolean newCard) {
        var keys=List.copyOf(selected());if(keys.isEmpty()||busy||!ready()||keys.size()>1000){status.setText(tr("znack.registration.batch_limit"));return;}
        cancelBatch.set(false);batchReviewing=true;buttons();
        background(()->{var allowed=allowedKeys(keys,newCard,false);var review=allowed.isEmpty()?new OzonCardRegistrationWorkflow.BatchReview(List.of(),newCard):workflow.prepareBatch(owner,allowed,newCard,n->{long now=System.nanoTime();if(n==keys.size()||now-lastBatchProgress>=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)){lastBatchProgress=now;Platform.runLater(()->{if(!disposed&&batchReviewing)status.setText(java.text.MessageFormat.format(tr("znack.registration.batch_progress"),n,keys.size()));});}});
            var items=new ArrayList<>(review.items());for(var key:keys)if(!allowed.contains(key))items.add(new OzonCardRegistrationWorkflow.BatchItem(key,null,null,"znack.registration.batch_not_eligible"));
            return new OzonCardRegistrationWorkflow.BatchReview(items,newCard);},review->{
            if(disposed)return;var approved=OzonRegistrationBatchDialog.review(review);
            if(approved.isEmpty()){batchReviewing=false;buttons();if(deferredReload){deferredReload=false;reload();}return;}
            busy=true;buttons();workflow.submitBatchReviewed(owner,approved.stream().map(item->new OzonCardRegistrationWorkflow.BatchItem(item.key(),item.preparation(),item.draft().withAutoSign(autoSign.isSelected()),item.issue())).toList(),newCard,true,listener(),cancelBatch::get).whenComplete((n,error)->Platform.runLater(()->{
                if(disposed)return;busy=false;batchReviewing=false;deferredReload=false;buttons();
                status.setText(error==null?java.text.MessageFormat.format(tr("znack.registration.batch_queued"),n):tr("znack.registration.batch_read_failed"));refreshDebounce.playFromStart();
            }));
        });
    }
    private void prepare(ProductKey key,boolean newCard) {
        if(busy||disposed||!ready())return;
        if((newCard||groupReplacement(key))&&!confirm(tr(newCard&&state(key)==Status.ERROR?"znack.registration.confirm_rejected_replacement":"znack.registration.confirm_replacement")))return;
        background(()->{allowedKeys(List.of(key),newCard);return workflow.prepare(owner,key);},prepared->continuePrepare(key,newCard,prepared));
    }
    private void continuePrepare(ProductKey key,boolean newCard,OzonCardRegistrationWorkflow.Preparation prepared) {
        if(prepared.preflight()==null) {
            var dialog=new TextInputDialog(prepared.tnved());dialog.setTitle(tr("znack.registration.title"));dialog.setHeaderText(tr("znack.registration.ozon_tnved"));
            var value=dialog.showAndWait();if(value.isEmpty())return;
            if(!value.get().strip().matches("[0-9]{10}")){status.setText(tr("znack.registration.ozon_tnved"));return;}
            background(()->{sources.require(owner);return workflow.prepare(owner,key,value.get().strip());},next->continuePrepare(key,newCard,next));return;
        }
        if(prepared.categoryId()==0){status.setText(tr("znack.registration.category_auto_missing"));return;}
        var draft=OzonRegistrationReviewDialog.review(prepared);
        if(draft==null)return;
        var authorised=draft.withAutoSign(autoSign.isSelected());
        background(()->{allowedKeys(List.of(key),newCard);return workflow.registerReviewed(owner,prepared,authorised,true,newCard,listener());},accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});
    }
    private void resume(ProductKey key){if(!confirm(tr("znack.registration.confirm_resume")))return;boolean allowed=autoSign.isSelected();background(()->{sources.require(owner);return workflow.resume(owner,key,allowed,listener());},accepted->refreshDebounce.playFromStart());}
    private void complete(ProductKey key){if(!confirm(tr("znack.registration.confirm_switch")))return;background(()->{sources.require(owner);return workflow.complete(owner,key,true,listener());},accepted->refreshDebounce.playFromStart());}
    private void signApproved(){
        if(busy||!ready())return;var chosen=selected();ProductQuery query=query(null);
        background(()->{
            var keys=chosen.isEmpty()?products.freezeKeys(query,sources.require(owner).run()):chosen;
            var rows=registrations.findMany(keys);
            return keys.stream().filter(key->{var row=rows.get(key);return row!=null&&!row.feedId().isBlank()
                    &&Set.of(Status.READY_TO_SIGN,Status.FEED_SUBMITTED,Status.PROCESSING,Status.SIGNING).contains(row.status());}).toList();
        },keys->{
            if(keys.isEmpty()){status.setText(tr("znack.registration.no_approved"));return;}
            if(keys.size()>10000){status.setText(tr("znack.registration.batch_too_large"));return;}
            if(!confirm(java.text.MessageFormat.format(tr("znack.registration.confirm_sign"),keys.size())))return;
            background(()->{sources.require(owner);return workflow.signApproved(owner,keys,true,listener());},accepted->{status.setText(tr(accepted?"znack.registration.ozon_accepted":"znack.registration.already_running"));refreshDebounce.playFromStart();});
        });
    }
    private void configureDocuments() {
        if(busy)return;
        background(()->new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName())).getSettings(),settings->{
            var dialog=new Dialog<ZnackModels.GoodsDocument>();dialog.setTitle(tr("znack.registration.documents"));
            var type=new ComboBox<String>();type.getItems().setAll("CONFORMITY_DECLARATION","CONFORMITY_CERTIFICATE");type.setValue(settings.documentType()==null||settings.documentType().isBlank()?"CONFORMITY_DECLARATION":settings.documentType());
            type.setConverter(new javafx.util.StringConverter<>(){
                public String toString(String value){return value==null?"":tr("CONFORMITY_CERTIFICATE".equals(value)?"znack.registration.certificate":"znack.registration.declaration");}
                public String fromString(String value){throw new UnsupportedOperationException();}
            });
            var number=new TextField(safe(settings.documentNumber()));var date=new TextField(safe(settings.documentDate()));date.setPromptText("yyyy-MM-dd / dd.MM.yyyy");
            var grid=new GridPane();grid.setHgap(10);grid.setVgap(10);grid.addRow(0,new Label(tr("znack.registration.document_type")),type);grid.addRow(1,new Label(tr("znack.registration.document_number")),number);grid.addRow(2,new Label(tr("znack.registration.document_date")),date);
            var note=new Label(tr("znack.registration.ozon_review_note"));note.setWrapText(true);note.setMaxWidth(600);grid.add(note,0,3,2,1);dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK,ButtonType.CANCEL);
            dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION,event->{try{if(number.getText().isBlank()||date.getText().isBlank()||!type.getItems().contains(type.getValue()))throw new IllegalArgumentException();settings.withDefaultGoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip()).validateDefaultGoodsDocument();}catch(IllegalArgumentException invalid){event.consume();note.setText(tr("znack.registration.config_required"));}});
            dialog.setResultConverter(button->button==ButtonType.OK?new ZnackModels.GoodsDocument(type.getValue(),number.getText().strip(),date.getText().strip()):null);
            var result=dialog.showAndWait();if(result.isEmpty())return;var document=result.get();
            background(()->{com.vncode.app.integration.listing.ListingIdentity.require(owner);var target=new com.vncode.app.integration.znack.ZnackRepository(new ZnackModels.ShopContext(owner.getId(),owner.getName()));target.saveSettings(target.getSettings().withDefaultGoodsDocument(document.type(),document.number(),document.date()));return true;},saved->status.setText(tr("znack.registration.config_saved")));
        });
    }
    private void syncCatalog(){
        if(busy)return;++generation;readiness=null;cardIndex=null;table.getItems().clear();frozen=List.of();status.setText(tr("znack.registration.sources_syncing"));
        background(()->sources.refresh(owner,step->Platform.runLater(()->{if(!disposed)status.setText(tr("znack.registration.sources_syncing")+" · "+tr("znack.registration.step."+step));})),done->reload(),true);
    }
    private java.util.function.BiConsumer<Status,String> listener(){return (state,detail)->{
        if(detail!=null&&!detail.isBlank())reviewPending.set(true);
        if(progressQueued.compareAndSet(false,true))Platform.runLater(()->{
            progressQueued.set(false);if(disposed)return;
            if(reviewPending.getAndSet(false))status.setText(tr("znack.registration.bulk_review"));refreshDebounce.playFromStart();
        });
    };}
    private <T>void background(Callable<T> work,Consumer<T> accept) {
        background(work,accept,false);
    }
    private <T>void background(Callable<T> work,Consumer<T> accept,boolean catalog) {
        if(disposed)return;long current=generation;busy=true;table.setPlaceholder(new LoadingSkeleton(tr("znack.registration.loading")));buttons();Task<T> task=new Task<>(){@Override protected T call()throws Exception{return work.call();}};
        task.setOnSucceeded(e->{if(disposed||current!=generation)return;busy=false;table.setPlaceholder(new Label(tr("znack.registration.empty")));buttons();accept.accept(task.getValue());if(deferredReload&&!busy&&!batchReviewing){deferredReload=false;refreshDebounce.playFromStart();}});
        Consumer<Throwable> failure=error->{if(disposed||current!=generation)return;busy=false;table.setPlaceholder(new Label(tr("znack.registration.empty")));batchReviewing=false;deferredReload=false;status.setText(tr("znack.registration.sources_required")+" · "+SyncFailure.of(error,0).code());buttons();AlertService.showDetailedError(com.vncode.app.integration.znack.ZnackErrorDetails.summary(error),com.vncode.app.integration.znack.ZnackErrorDetails.format(error));};
        task.setOnFailed(e->failure.accept(task.getException()));task.setOnCancelled(e->failure.accept(new java.net.SocketTimeoutException("ZNACK_SYNC_TIMEOUT")));if(catalog)AppTaskExecutor.executeCatalog(task,failure);else AppTaskExecutor.execute(task,failure);
    }
    private void buttons(){allFiltered.setDisable(busy||batchReviewing||!ready());allFiltered.setSelected(total>0&&selected().size()==total);autoSign.setDisable(busy||batchReviewing);stopBatch.setVisible(batchReviewing&&busy);stopBatch.setManaged(stopBatch.isVisible());stopBatch.setDisable(cancelBatch.get());for(var button:List.of(documents,refresh,sync,sign,gtin,all,clear,register,newCards,more))button.setDisable(busy||batchReviewing);register.setDisable(busy||batchReviewing||selected().isEmpty());newCards.setDisable(busy||batchReviewing||selected().isEmpty());clear.setDisable(busy||batchReviewing||selected().isEmpty());for(var button:List.of(sign,gtin,all,register,newCards,more))if(!ready())button.setDisable(true);report.setDisable(false);search.setDisable(batchReviewing);more.setVisible(false);more.setManaged(false);count.setText(java.text.MessageFormat.format(tr("znack.registration.page_count"),table.getItems().size(),total));selection.setText(java.text.MessageFormat.format(tr("znack.registration.selection_count"),selected().size()));}
    private List<ProductKey> allowedKeys(List<ProductKey> selected,boolean replacement)throws Exception {
        return allowedKeys(selected,replacement,true);
    }
    private List<ProductKey> allowedKeys(List<ProductKey> selected,boolean replacement,boolean strict)throws Exception {
        var receipt=sources.require(owner);var index=new RegistrationCardIndex(owner);var wanted=new HashSet<>(selected);var allowed=new ArrayList<ProductKey>();
        String cursor=null;
        do {
            var page=products.loadPage(new ProductQuery(owner.getId(),Marketplace.OZON,"",List.of(),null,false,cursor,300),receipt.run());
            var checkpoints=registrations.findMany(page.rows().stream().map(ProductRow::key).toList());
            for(var row:page.rows())if(wanted.contains(row.key())){
                var nativeRow=checkpoints.get(row.key());Status state=nativeRow==null?Status.NOT_CREATED:nativeRow.status();
                var view=index.viewOzon(state,nativeRow==null?"":nativeRow.gtin(),row,nativeRow==null||index.ownsOzon(nativeRow.owner(),nativeRow.scopeJson()));
                boolean changed=nativeRow!=null&&com.vncode.app.integration.znack.registration.TnvedGroupChange.replaceable(state)&&com.vncode.app.integration.znack.registration.TnvedGroupChange.changed(com.vncode.app.integration.znack.registration.TnvedGroupChange.payloadCode(nativeRow.payload()),row.tnved());
                boolean eligible=replacement?(state==Status.PUBLISHED&&view.published()||state==Status.ERROR&&nativeRow!=null&&!nativeRow.gtin().isBlank()&&!nativeRow.feedId().isBlank()&&!Set.of("SENDING","RECONCILE_REQUIRED").contains(nativeRow.signatureState())):changed||!view.published()&&!(state==Status.NOT_CREATED&&view.exists())&&Set.of(Status.NOT_CREATED,Status.PREFLIGHT_ERROR,Status.PRE_SUBMIT_ERROR).contains(state);
                if(!view.blocked()&&eligible)allowed.add(row.key());
            }
            cursor=page.nextCursor();
        }while(cursor!=null);
        if(strict&&allowed.size()!=wanted.size())throw new IllegalArgumentException("SOURCE_CARD_ACTION_CHANGED");
        return List.copyOf(allowed);
    }
    private boolean ready(){return readiness!=null&&readiness.ready();}
    private RegistrationCardIndex.View view(ProductRow row){
        var checkpoint=states.get(row.key());
        return cardIndex==null?new RegistrationCardIndex.View("source.unverified","","SOURCE_SYNC_REQUIRED",false,false,true)
            :cardIndex.viewOzon(state(row.key()),checkpoint==null?"":checkpoint.gtin(),row,checkpoint==null||cardIndex.ownsOzon(checkpoint.owner(),checkpoint.scopeJson()));
    }
    private Status state(ProductKey key){var row=states.get(key);return row==null?Status.NOT_CREATED:row.status();}
    private String candidateText(ProductKey key){var row=states.get(key);return row==null||safe(row.activeGtin()).isBlank()||safe(row.activeGtin()).equals(safe(row.gtin()))?"":"\n"+tr("znack.registration.active_gtin")+": "+row.activeGtin();}
    private static boolean confirm(String message){var alert=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);return alert.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private static String reason(String code){String key="znack.registration.reason."+code;String text=tr(key);return text.equals(key)?code:text;}
    private static String safe(String value){return value==null?"":value;}
}
