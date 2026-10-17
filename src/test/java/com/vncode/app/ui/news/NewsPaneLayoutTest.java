package com.vncode.app.ui.news;
import com.vncode.app.config.Database;
import com.vncode.app.features.news.*;
import com.vncode.app.shared.ThemeService;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class NewsPaneLayoutTest {
 @TempDir Path root;
 @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
 static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
 static void scrollLayout(javafx.scene.layout.VBox frame){var scroll=(ScrollPane)frame.lookup("#newsPreviewScroll");if(scroll!=null){scroll.applyCss();scroll.layout();if(scroll.getContent() instanceof javafx.scene.Parent content){content.applyCss();content.layout();}}}
 @Test void longVietnameseNewsStaysInsideDashboardFrameAndCanScroll()throws Exception{
  System.setProperty("vncode.appdata.dir",root.toString());Database.initDatabase();
  try{fx(()->{
   var items=List.of(new NewsItem("one",Instant.now(),"Quy định giấy tờ hàng hóa và GTIN ".repeat(14),"Hướng dẫn kiểm tra ".repeat(100)),new NewsItem("two",Instant.now(),"Tin tức khác ".repeat(25),"Nội dung ".repeat(100)));
   var pane=new NewsPane(new NewsService(items,new NewsRepository(root)),()->{},n->{});var frame=pane.preview();var window=new Stage();window.setScene(new Scene(frame,330,420));ThemeService.applyTheme(window.getScene());frame.getStyleClass().add("liquid-glass");window.getScene().getStylesheets().add(NewsPane.class.getResource("/com/vncode/app/styles/liquid-glass.css").toExternalForm());window.show();for(int i=0;i<3;i++){frame.applyCss();frame.layout();scrollLayout(frame);}
   var scroll=(ScrollPane)frame.lookup("#newsPreviewScroll");assertNotNull(scroll);assertEquals(ScrollPane.ScrollBarPolicy.NEVER,scroll.getHbarPolicy());
   assertTrue(scroll.getContent().getBoundsInLocal().getHeight()>scroll.getViewportBounds().getHeight(),"content="+scroll.getContent().getBoundsInLocal()+" viewport="+scroll.getViewportBounds());
   for(var node:frame.lookupAll(".news-card")){var card=(Button)node;assertTrue(card.getLayoutX()+card.getWidth()<=scroll.getViewportBounds().getWidth()+2,"card="+card.getLayoutX()+"+"+card.getWidth()+" viewport="+scroll.getViewportBounds());assertTrue(card.getGraphic().getLayoutBounds().getWidth()<=card.getWidth(),"graphic="+card.getGraphic().getLayoutBounds()+" width="+card.getWidth());assertTrue(card.getBackground().getFills().stream().allMatch(fill->fill.getRadii().getTopLeftHorizontalRadius()<=24),"A news card must not inherit the capsule radius of action buttons");}
   var footer=(javafx.scene.layout.Region)frame.getChildren().getLast();assertTrue(footer.getLayoutY()+footer.getHeight()<=frame.getHeight()+1);pane.dispose();window.close();return null;
  });}finally{System.clearProperty("vncode.appdata.dir");}
 }
}
