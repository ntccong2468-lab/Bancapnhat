package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TableScrollLoaderTest {
    @BeforeAll static void start(){try{Platform.startup(()->Platform.setImplicitExit(false));}catch(IllegalStateException started){}}
    private static <T>T fx(Callable<T> work)throws Exception{var task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
    @Test void draggingTheRealScrollbarLoadsOnePageAndStopsWhenThereIsNoNextPage()throws Exception {
        var requests=new AtomicInteger();var busy=new AtomicBoolean();var next=new AtomicBoolean(true);var stage=new Stage[1];var table=new TableView<Integer>();
        try {
            fx(()->{
                var column=new TableColumn<Integer,String>("Native rows");column.setCellValueFactory(v->new javafx.beans.property.ReadOnlyStringWrapper(v.getValue().toString()));table.getColumns().add(column);
                for(int n=0;n<300;n++)table.getItems().add(n);
                TableScrollLoader.install(table,()->next.get()&&!busy.get(),()->{if(!busy.compareAndSet(false,true))fail("Repeated concurrent page request");requests.incrementAndGet();});
                stage[0]=new Stage();stage[0].setScene(new Scene(new StackPane(table),600,400));stage[0].show();table.applyCss();table.layout();return null;
            });fx(()->null);
            fx(()->{for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL)bar.setValue(bar.getMax());return null;});fx(()->null);fx(()->null);
            assertEquals(1,requests.get());
            fx(()->{for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL){bar.setValue(bar.getMin());bar.setValue(bar.getMax());}return null;});fx(()->null);assertEquals(1,requests.get(),"Busy page cannot be enqueued twice");
            next.set(false);busy.set(false);
            fx(()->{for(int n=300;n<600;n++)table.getItems().add(n);table.layout();for(var node:table.lookupAll(".scroll-bar"))if(node instanceof ScrollBar bar&&bar.getOrientation()==Orientation.VERTICAL){bar.setValue(bar.getMin());bar.setValue(bar.getMax());}return null;});fx(()->null);assertEquals(1,requests.get());
        }finally{fx(()->{if(stage[0]!=null)stage[0].close();return null;});}
    }
}
