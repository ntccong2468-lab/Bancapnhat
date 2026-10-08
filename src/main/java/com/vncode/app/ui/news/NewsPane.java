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
  try{service.markRead(item.id());status.setText("");}catch(java.io.IOException e){status.setText(tr("news.save_error"));}
  opened=item;detailShown=true;renderDetail(item);scroll.setContent(detail);scroll.setVvalue(0);more.setVisible(false);more.setManaged(false);renderPreview();unread.accept(service.unreadCount());
 }
 public void back(){detailShown=false;opened=null;scroll.setContent(list);renderList();}
 private void renderList(){list.getChildren().clear();for(var item:visible)list.getChildren().add(row(item));if(visible.isEmpty())list.getChildren().add(new Label(tr("news.empty")));more.setVisible(!ended&&!detailShown);more.setManaged(!ended&&!detailShown);}
 private Node row(NewsItem item){Button button=new Button((service.isRead(item.id())?"":"New · ")+item.title()+"\n"+item.publishedAt().toString().substring(0,10));button.setWrapText(true);button.setMaxWidth(Double.MAX_VALUE);button.setOnAction(e->open(item));return button;}
 private void renderPreview(){preview.getChildren().clear();Label heading=new Label(tr("news.title"));heading.getStyleClass().add("h2");preview.getChildren().add(heading);var page=service.loadPage(null);for(var item:page.items().stream().limit(3).toList()){
  Button button=new Button((service.isRead(item.id())?"":"New · ")+item.title());button.setWrapText(true);button.setMaxWidth(Double.MAX_VALUE);button.setOnAction(e->{open(item);if(onOpenPage!=null)onOpenPage.run();});preview.getChildren().add(button);
 }Button all=new Button(tr("news.all"));all.setOnAction(e->{back();if(onOpenPage!=null)onOpenPage.run();});preview.getChildren().add(all);}
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
