package com.vncode.app.ui.news;
import com.vncode.app.features.news.*;
import com.vncode.app.shared.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** News is rendered as JavaFX text, never as executable HTML. */
public final class NewsPane extends VBox {
 private final NewsService service;
 private final OfficialNewsService official=new OfficialNewsService();
 private com.vncode.app.models.Shop shop;
 private NewsScope scope;
 private volatile long generation;private volatile boolean disposed;private boolean showOriginal;
 private final Map<String,NewsTranslationService.TranslatedNews> translated=new LinkedHashMap<>();
 private final Set<String> translating=new HashSet<>();
 private final java.util.concurrent.ThreadPoolExecutor translationWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(64),r->{var thread=new Thread(r,"public-news-translation");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
 private volatile NewsTranslationService translationService;
 private final javafx.animation.PauseTransition translationRedraw=new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
 private final IntConsumer unread;
 private final Runnable returnToDashboard;
 private final Label title=new Label(),status=new Label();
 private final Button back=new Button(),more=new Button();
 private final VBox list=new VBox(10),detail=new VBox(10),preview=new VBox(8);
 private final ScrollPane scroll=new ScrollPane(list);
 private String cursor;private boolean ended,busy,detailShown;
 private final List<NewsItem> visible=new ArrayList<>();
 private NewsItem opened;
 private double listScroll;
 public NewsPane(NewsService service,Runnable returnToDashboard,IntConsumer unread) {
  this.service=service;this.returnToDashboard=returnToDashboard;this.unread=unread;
  setSpacing(12);setStyle("-fx-padding: 16;");title.getStyleClass().add("h2");
  HBox header=new HBox(12,back,title);getChildren().addAll(header,status,scroll,more);
  scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);list.setMinWidth(0);detail.setMinWidth(0);preview.setMinWidth(0);setMinWidth(0);VBox.setVgrow(scroll,Priority.ALWAYS);
  scroll.vvalueProperty().addListener((o,a,b)->{if(b.doubleValue()>=0.95&&!detailShown)loadMore();});
  more.setOnAction(e->loadMore());back.setOnAction(e->{if(detailShown)back();else returnToDashboard.run();});
  translationRedraw.setOnFinished(e->{if(!disposed){renderList();renderPreview();if(opened!=null&&detailShown)renderDetail(opened);}});
  sceneProperty().addListener((o,a,b)->{if(b!=null&&!disposed)renderList();});
  applyTranslations();loadMore();renderPreview();
 }
 public String pageTitle(){return title.getText();}
 public int visibleItems(){return visible.size();}
 public boolean showingDetail(){return detailShown;}
 public void loadMore(){if(ended||busy)return;var page=service.loadPage(cursor);visible.addAll(page.items());cursor=page.nextCursor();ended=cursor==null;renderList();}
 public void refreshAsync(){
  if(disposed)return;refreshOfficial(false);
  if(busy)return;busy=true;renderList();long epoch=generation;
  Task<Void> task=new Task<>(){protected Void call()throws Exception{service.refresh();return null;}};
  task.setOnSucceeded(e->{if(disposed||epoch!=generation)return;busy=false;visible.clear();cursor=null;ended=false;loadMore();status.setText("");renderPreview();unread.accept(service.unreadCount());});
  java.util.function.Consumer<Throwable> taskFailure = rejection ->{if(disposed||epoch!=generation)return;busy=false;status.setText(tr("news.offline"));renderList();renderPreview();unread.accept(service.unreadCount());};
        task.setOnFailed(e -> taskFailure.accept(task.getException()));
  AppTaskExecutor.execute(task, taskFailure);
 }
 public void setShop(com.vncode.app.models.Shop selected){
  generation++;translated.clear();translating.clear();busy=false;shop=selected;scope=selected!=null&&selected.getMarketplace()==com.vncode.app.integration.marketplace.Marketplace.WILDBERRIES?NewsScope.forShop(selected):null;opened=null;detailShown=false;scroll.setContent(list);service.setOfficial(List.of(),official);visible.clear();cursor=null;ended=false;loadMore();renderPreview();unread.accept(service.unreadCount());
  if(scope==null)return;var owner=selected;var expected=scope;long epoch=generation;
  ShopReadCoordinator.shared().submitLocal(new ShopReadCoordinator.Key(owner.getId(),"news-cache",expected.ownerFingerprint()),()->official.cached(expected)).whenComplete((news,error)->Platform.runLater(()->{if(disposed||epoch!=generation||error!=null)return;replaceOfficial(news);if(getScene()!=null||preview.getScene()!=null)refreshOfficial(false);}));
 }
 private void refreshOfficial(boolean force){
  if(disposed||shop==null||scope==null)return;var owner=new com.vncode.app.models.Shop(shop.getId(),shop.getName(),shop.getMarketplace(),shop.getClientId(),shop.getApiKey());var expected=scope;long epoch=generation;
  ShopReadCoordinator.shared().submit(new ShopReadCoordinator.Key(owner.getId(),"wb-official-news",expected.ownerFingerprint()),false,()->official.refresh(owner,expected,force)).whenComplete((news,error)->Platform.runLater(()->{
   if(disposed||epoch!=generation)return;if(error!=null){status.setText(tr("news.wb_unavailable"));return;}replaceOfficial(news);
  }));
 }
 private void replaceOfficial(List<SellerNews> news){service.setOfficial(news,official);visible.clear();cursor=null;ended=false;loadMore();renderPreview();unread.accept(service.unreadCount());for(var item:visible)requestTranslation(item);}
 public void dispose(){disposed=true;generation++;translationWorker.shutdownNow();translationRedraw.stop();translated.clear();translating.clear();if(translationService!=null)translationService.close();translationService=null;}
 public void translationSettingsChanged(){generation++;if(translationService!=null)translationService.close();translationService=null;translated.clear();translating.clear();renderList();renderPreview();}
 private synchronized NewsTranslationService translator(){if(disposed)throw new java.util.concurrent.CancellationException();if(translationService==null){long epoch=generation;com.vncode.app.integration.translation.NewsTranslator provider=new com.vncode.app.integration.translation.AutomaticNewsTranslator();try{var settings=new com.vncode.app.integration.translation.TranslationSettingsRepository().load();if(settings.isPresent())provider=new com.vncode.app.integration.translation.YandexNewsTranslator(settings.get());}catch(java.io.IOException|RuntimeException unavailable){}if(disposed||epoch!=generation){if(provider!=null)provider.close();throw new java.util.concurrent.CancellationException();}translationService=new NewsTranslationService(provider);}return translationService;}
 private void requestTranslation(NewsItem item){
  var metadata=service.sellerNews(item.id());if(metadata.isEmpty())return;var news=metadata.get();String key=item.id()+":"+news.revision();if(translated.containsKey(key)||!translating.add(key))return;long epoch=generation;
  translation(news,epoch,key).whenComplete((result,error)->Platform.runLater(()->{
   if(disposed||epoch!=generation)return;translating.remove(key);if(error!=null)return;translated.put(key,result);while(translated.size()>1000)translated.remove(translated.keySet().iterator().next());translationRedraw.playFromStart();
  }));
 }
 private java.util.concurrent.CompletableFuture<NewsTranslationService.TranslatedNews> translation(SellerNews news,long epoch,String key){
  try{return java.util.concurrent.CompletableFuture.supplyAsync(()->{if(disposed||epoch!=generation)throw new java.util.concurrent.CancellationException();return translator().translate(news,"vi");},translationWorker);}
  catch(java.util.concurrent.RejectedExecutionException full){translating.remove(key);return java.util.concurrent.CompletableFuture.failedFuture(full);}
 }
 private NewsTranslationService.TranslatedNews translation(NewsItem item){return service.sellerNews(item.id()).map(news->translated.get(item.id()+":"+news.revision())).orElse(null);}
 private NewsItem display(NewsItem item){var result=translation(item);return result!=null&&result.state()==NewsTranslationService.State.TRANSLATED?new NewsItem(item.id(),item.publishedAt(),result.translatedTitle(),result.translatedBody()):item;}

 public void applyTranslations(){title.setText(tr("news.title"));back.setText(tr("news.back"));more.setText(tr("news.more"));renderList();renderPreview();if(opened!=null&&detailShown)renderDetail(opened);}
 public VBox preview(){return preview;}
 public void open(NewsItem item){
  if(!detailShown)listScroll=scroll.getVvalue();
  long epoch=generation;var official=service.sellerNews(item.id());SellerMutationExecutor.submit(()->{if(official.isPresent())service.markRead(official.get());else service.markRead(item.id());return true;}).whenComplete((saved,error)->Platform.runLater(()->{if(disposed||epoch!=generation)return;status.setText(error==null?"":tr("news.save_error"));renderPreview();unread.accept(service.unreadCount());}));
  opened=item;showOriginal=false;requestTranslation(item);detailShown=true;renderDetail(item);scroll.setContent(detail);scroll.setVvalue(0);more.setVisible(false);more.setManaged(false);renderPreview();unread.accept(service.unreadCount());
 }
 public void back(){detailShown=false;opened=null;scroll.setContent(list);renderList();scroll.setVvalue(listScroll);Platform.runLater(()->{if(!detailShown){scroll.applyCss();scroll.layout();scroll.setVvalue(listScroll);}}); }
 private void renderList(){list.getChildren().clear();for(var item:visible)list.getChildren().add(row(item));if(visible.isEmpty())list.getChildren().add(busy?new LoadingSkeleton(tr("znack.registration.loading")):new Label(tr("news.empty")));more.setVisible(!ended&&!detailShown);more.setManaged(!ended&&!detailShown);}
 private Button newsCard(NewsItem item,boolean dashboard) {
  if(dashboard||getScene()!=null)requestTranslation(item);var shown=display(item);
  var heading=new Label(shown.title());heading.setWrapText(true);heading.setMinWidth(0);heading.setMaxWidth(Double.MAX_VALUE);heading.getStyleClass().add("news-card-title");
  var badge=new Label(tr("news.new"));badge.getStyleClass().add("unread-badge");badge.setVisible(!service.isRead(item.id()));badge.setManaged(badge.isVisible());badge.setMinWidth(Region.USE_PREF_SIZE);
  var top=new HBox(10,heading,badge);HBox.setHgrow(heading,Priority.ALWAYS);
  String plain=shown.body().replaceAll("\\[[^]]+]\\([^)]+\\)"," ").replaceAll("[#*_`>]","").replaceAll("\\s+"," ").strip();
  var summary=new Label(plain.length()>180?plain.substring(0,177)+"…":plain);summary.setWrapText(true);summary.setMinWidth(0);summary.setMaxWidth(Double.MAX_VALUE);summary.getStyleClass().add("text-secondary");
  var date=new Label(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(java.time.ZoneId.of("Europe/Moscow")).format(item.publishedAt()));date.getStyleClass().add("text-muted");
  var source=new Label(tr(service.sellerNews(item.id()).isPresent()?"news.source.wb":"news.source.vn")+(service.sellerNews(item.id()).isPresent()?" · "+tr(translation(item)!=null&&translation(item).state()==NewsTranslationService.State.TRANSLATED?"news.translated":"news.translation_missing"):""));source.getStyleClass().add("text-muted");var content=new VBox(12,top,summary,date,source);content.setMinWidth(0);var button=new Button();button.setGraphic(content);button.setWrapText(true);button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);button.setMaxWidth(Double.MAX_VALUE);button.setMinWidth(0);button.getStyleClass().add("news-card");button.setAccessibleText(shown.title());content.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0,button.widthProperty().subtract(36)));content.maxWidthProperty().bind(content.prefWidthProperty());button.setMinHeight(Region.USE_PREF_SIZE);
  button.setOnAction(e->{open(item);if(dashboard&&onOpenPage!=null)onOpenPage.run();});return button;
 }
 private Node row(NewsItem item){return newsCard(item,false);}
 private void renderPreview(){
  preview.getChildren().clear();preview.getStyleClass().setAll("news-preview");preview.setMinSize(0,0);
  Label heading=new Label(tr("news.title"));heading.getStyleClass().add("h2");var count=new Label(Integer.toString(service.unreadCount()));count.getStyleClass().add("unread-badge");count.setVisible(service.unreadCount()>0);count.setManaged(count.isVisible());
  var space=new Region();HBox.setHgrow(space,Priority.ALWAYS);var refresh=new Button();refresh.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("fth-refresh-cw"));refresh.setTooltip(new Tooltip(tr("news.refresh")));refresh.setAccessibleText(tr("news.refresh"));refresh.setOnAction(e->refreshAsync());
  preview.getChildren().add(new HBox(10,heading,count,space,refresh));var page=service.loadPage(null);
  var cards=new VBox(10);cards.setMinWidth(0);for(var item:page.items().stream().limit(2).toList())cards.getChildren().add(newsCard(item,true));
  var feed=new ScrollPane(cards);feed.setId("newsPreviewScroll");feed.setFitToWidth(true);feed.setMinSize(0,0);feed.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);VBox.setVgrow(feed,Priority.ALWAYS);preview.getChildren().add(feed);
  if(page.items().isEmpty()){var empty=new Label(tr("news.empty"));empty.getStyleClass().add("text-muted");preview.getChildren().add(empty);}

  Button all=new Button(tr("news.all"));all.setMaxWidth(Double.MAX_VALUE);all.setPrefHeight(46);all.setOnAction(e->{back();if(onOpenPage!=null)onOpenPage.run();});preview.getChildren().add(all);
 }
 private Runnable onOpenPage;
 public void setOnOpenPage(Runnable callback){onOpenPage=callback;}
 private void renderDetail(NewsItem item){detail.getChildren().clear();var shown=showOriginal?item:display(item);Label heading=new Label(shown.title());heading.setWrapText(true);heading.getStyleClass().add("h2");detail.getChildren().add(heading);
  var metadata=service.sellerNews(item.id());detail.getChildren().add(new Label(tr(metadata.isPresent()?"news.source.wb":"news.source.vn")));
  metadata.ifPresent(value->{var link=new Hyperlink(tr("news.original_source"));link.setOnAction(e->AppTaskExecutor.execute(new Task<Void>(){protected Void call(){try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().browse(value.sourceURL());}catch(Exception ignored){}return null;}},rejection->status.setText(tr("runtime.queue_full"))));detail.getChildren().add(link);});
  if(metadata.isPresent()){var state=translation(item);detail.getChildren().add(new Label(tr(state!=null&&state.state()==NewsTranslationService.State.TRANSLATED?"news.translated":"news.translation_missing")));var toggle=new Button(tr(showOriginal?"news.view_translation":"news.view_original"));toggle.setOnAction(e->{showOriginal=!showOriginal;renderDetail(item);});toggle.setDisable(state==null||state.state()!=NewsTranslationService.State.TRANSLATED);detail.getChildren().add(toggle);}
  for(String line:shown.body().split("\\R",-1)){
   boolean isHeading=line.startsWith("#");String normalized=isHeading?line.replaceFirst("^#{1,6}\\s*",""):line.replaceFirst("^[-*]\\s+","• ");
   TextFlow flow=new TextFlow();flow.setMinWidth(0);flow.setMaxWidth(Double.MAX_VALUE);var pattern=Pattern.compile("\\[([^]\\n]+)]\\(([^)\\s]+)\\)|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*");var matcher=pattern.matcher(normalized);int end=0;
   while(matcher.find()){addText(flow,normalized.substring(end,matcher.start()),isHeading,false);
    if(matcher.group(1)!=null&&NewsService.safeLink(matcher.group(2))){String url=matcher.group(2);Hyperlink link=new Hyperlink(matcher.group(1));link.setWrapText(true);link.setMinWidth(0);link.maxWidthProperty().bind(detail.widthProperty());link.setOnAction(e->AppTaskExecutor.execute(new Task<Void>(){protected Void call(){try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));}catch(Exception ignored){}return null;}},rejection->status.setText(tr("runtime.queue_full"))));flow.getChildren().add(link);}
    else if(matcher.group(3)!=null)addText(flow,matcher.group(3),true,false);
    else if(matcher.group(4)!=null)addText(flow,matcher.group(4),false,true);
    else addText(flow,matcher.group(),isHeading,false);end=matcher.end();
   }addText(flow,normalized.substring(end),isHeading,false);detail.getChildren().add(flow);
  }
 }
 private static void addText(TextFlow flow,String value,boolean bold,boolean italic){Text text=new Text(value);text.setFont(Font.font("System",bold?FontWeight.BOLD:FontWeight.NORMAL,italic?FontPosture.ITALIC:FontPosture.REGULAR,bold?15:14));text.setStyle("-fx-fill: -text-primary;");flow.getChildren().add(text);}
 private static String tr(String key){return I18nService.getInstance().tr(key);}
}
