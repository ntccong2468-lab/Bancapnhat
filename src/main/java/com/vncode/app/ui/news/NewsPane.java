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
  scroll.setFitToWidth(true);VBox.setVgrow(scroll,Priority.ALWAYS);
  scroll.vvalueProperty().addListener((o,a,b)->{if(b.doubleValue()>=0.95&&!detailShown)loadMore();});
  more.setOnAction(e->loadMore());back.setOnAction(e->{if(detailShown)back();else returnToDashboard.run();});
  applyTranslations();loadMore();renderPreview();
 }
 public String pageTitle(){return title.getText();}
 public int visibleItems(){return visible.size();}
 public boolean showingDetail(){return detailShown;}
 public void loadMore(){if(ended||busy)return;var page=service.loadPage(cursor);visible.addAll(page.items());cursor=page.nextCursor();ended=cursor==null;renderList();}
 public void refreshAsync(){
  if(busy)return;busy=true;
  Task<Void> task=new Task<>(){protected Void call()throws Exception{service.refresh();return null;}};
  task.setOnSucceeded(e->{busy=false;visible.clear();cursor=null;ended=false;loadMore();status.setText("");renderPreview();unread.accept(service.unreadCount());});
  task.setOnFailed(e->{busy=false;status.setText(tr("news.offline"));renderPreview();unread.accept(service.unreadCount());});
  AppTaskExecutor.execute(task);
 }
 public void applyTranslations(){title.setText(tr("news.title"));back.setText(tr("news.back"));more.setText(tr("news.more"));renderList();renderPreview();if(opened!=null&&detailShown)renderDetail(opened);}
 public VBox preview(){return preview;}
 public void open(NewsItem item){
  if(!detailShown)listScroll=scroll.getVvalue();
  try{service.markRead(item.id());status.setText("");}catch(java.io.IOException e){status.setText(tr("news.save_error"));}
  opened=item;detailShown=true;renderDetail(item);scroll.setContent(detail);scroll.setVvalue(0);more.setVisible(false);more.setManaged(false);renderPreview();unread.accept(service.unreadCount());
 }
 public void back(){detailShown=false;opened=null;scroll.setContent(list);renderList();scroll.setVvalue(listScroll);Platform.runLater(()->{if(!detailShown){scroll.applyCss();scroll.layout();scroll.setVvalue(listScroll);}}); }
 private void renderList(){list.getChildren().clear();for(var item:visible)list.getChildren().add(row(item));if(visible.isEmpty())list.getChildren().add(new Label(tr("news.empty")));more.setVisible(!ended&&!detailShown);more.setManaged(!ended&&!detailShown);}
 private Button newsCard(NewsItem item,boolean dashboard) {
  var heading=new Label(item.title());heading.setWrapText(true);heading.setMinWidth(0);heading.setMaxWidth(Double.MAX_VALUE);heading.getStyleClass().add("news-card-title");
  var badge=new Label(tr("news.new"));badge.getStyleClass().add("unread-badge");badge.setVisible(!service.isRead(item.id()));badge.setManaged(badge.isVisible());badge.setMinWidth(Region.USE_PREF_SIZE);
  var top=new HBox(10,heading,badge);HBox.setHgrow(heading,Priority.ALWAYS);
  String plain=item.body().replaceAll("\\[[^]]+]\\([^)]+\\)"," ").replaceAll("[#*_`>]","").replaceAll("\\s+"," ").strip();
  var summary=new Label(plain.length()>180?plain.substring(0,177)+"…":plain);summary.setWrapText(true);summary.getStyleClass().add("text-secondary");
  var date=new Label(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(java.time.ZoneId.of("Europe/Moscow")).format(item.publishedAt()));date.getStyleClass().add("text-muted");
  var content=new VBox(12,top,summary,date);content.setMinWidth(0);var button=new Button();button.setGraphic(content);button.setWrapText(true);button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);button.setMaxWidth(Double.MAX_VALUE);button.setMinWidth(0);button.getStyleClass().add("news-card");button.setAccessibleText(item.title());
  button.setOnAction(e->{open(item);if(dashboard&&onOpenPage!=null)onOpenPage.run();});return button;
 }
 private Node row(NewsItem item){return newsCard(item,false);}
 private void renderPreview(){
  preview.getChildren().clear();preview.getStyleClass().setAll("news-preview");
  Label heading=new Label(tr("news.title"));heading.getStyleClass().add("h2");var count=new Label(Integer.toString(service.unreadCount()));count.getStyleClass().add("unread-badge");count.setVisible(service.unreadCount()>0);count.setManaged(count.isVisible());
  var space=new Region();HBox.setHgrow(space,Priority.ALWAYS);var refresh=new Button();refresh.setGraphic(new org.kordamp.ikonli.javafx.FontIcon("fth-refresh-cw"));refresh.setTooltip(new Tooltip(tr("news.refresh")));refresh.setAccessibleText(tr("news.refresh"));refresh.setOnAction(e->refreshAsync());
  preview.getChildren().add(new HBox(10,heading,count,space,refresh));var page=service.loadPage(null);
  for(var item:page.items().stream().limit(2).toList())preview.getChildren().add(newsCard(item,true));
  if(page.items().isEmpty()){var empty=new Label(tr("news.empty"));empty.getStyleClass().add("text-muted");preview.getChildren().add(empty);}
  var spacer=new Region();spacer.setMinHeight(24);VBox.setVgrow(spacer,Priority.ALWAYS);preview.getChildren().add(spacer);
  Button all=new Button(tr("news.all"));all.setMaxWidth(Double.MAX_VALUE);all.setPrefHeight(46);all.setOnAction(e->{back();if(onOpenPage!=null)onOpenPage.run();});preview.getChildren().add(all);
 }
 private Runnable onOpenPage;
 public void setOnOpenPage(Runnable callback){onOpenPage=callback;}
 private void renderDetail(NewsItem item){detail.getChildren().clear();Label heading=new Label(item.title());heading.setWrapText(true);heading.getStyleClass().add("h2");detail.getChildren().add(heading);
  for(String line:item.body().split("\\R",-1)){
   boolean isHeading=line.startsWith("#");String normalized=isHeading?line.replaceFirst("^#{1,6}\\s*",""):line.replaceFirst("^[-*]\\s+","• ");
   TextFlow flow=new TextFlow();var pattern=Pattern.compile("\\[([^]\\n]+)]\\(([^)\\s]+)\\)|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*");var matcher=pattern.matcher(normalized);int end=0;
   while(matcher.find()){addText(flow,normalized.substring(end,matcher.start()),isHeading,false);
    if(matcher.group(1)!=null&&NewsService.safeLink(matcher.group(2))){String url=matcher.group(2);Hyperlink link=new Hyperlink(matcher.group(1));link.setOnAction(e->AppTaskExecutor.execute(new Task<Void>(){protected Void call(){try{if(java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));}catch(Exception ignored){}return null;}}));flow.getChildren().add(link);}
    else if(matcher.group(3)!=null)addText(flow,matcher.group(3),true,false);
    else if(matcher.group(4)!=null)addText(flow,matcher.group(4),false,true);
    else addText(flow,matcher.group(),isHeading,false);end=matcher.end();
   }addText(flow,normalized.substring(end),isHeading,false);detail.getChildren().add(flow);
  }
 }
 private static void addText(TextFlow flow,String value,boolean bold,boolean italic){Text text=new Text(value);text.setFont(Font.font("System",bold?FontWeight.BOLD:FontWeight.NORMAL,italic?FontPosture.ITALIC:FontPosture.REGULAR,bold?15:14));text.setStyle("-fx-fill: -text-primary;");flow.getChildren().add(text);}
 private static String tr(String key){return I18nService.getInstance().tr(key);}
}
