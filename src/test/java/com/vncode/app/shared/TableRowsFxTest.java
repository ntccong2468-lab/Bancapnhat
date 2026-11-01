package com.vncode.app.shared;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TableRowsFxTest {
    record Row(int id) { }
    @BeforeAll static void start() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
    }
    private static <T> T fx(Callable<T> action)throws Exception {
        var task=new FutureTask<>(action);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);
    }
    private static ScrollBar vertical(TableView<?> table) {
        return table.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
                .filter(bar->bar.getOrientation()==Orientation.VERTICAL).findFirst().orElseThrow();
    }
    @Test void reversingRowsKeepsSelectedObjectAndScrollPosition()throws Exception {
        var source=java.util.stream.IntStream.range(0,1000).mapToObj(Row::new).toList();
        var stage=new Stage[1];@SuppressWarnings("unchecked") var table=(TableView<Row>[])new TableView<?>[1];
        try {
            fx(()->{
                table[0]=new TableView<>();table[0].setFixedCellSize(28);
                var column=new TableColumn<Row,String>("Order");column.setCellValueFactory(c->new ReadOnlyStringWrapper(Integer.toString(c.getValue().id())));
                table[0].getColumns().add(column);table[0].getItems().setAll(source);
                stage[0]=new Stage();stage[0].setScene(new Scene(new StackPane(table[0]),640,400));stage[0].show();
                table[0].getSelectionModel().select(source.get(500));table[0].scrollTo(500);return null;
            });
            fx(()->{table[0].applyCss();table[0].layout();return null;});fx(()->null);
            double before=fx(()->vertical(table[0]).getValue());
            assertTrue(before>0.2&&before<0.8,"The test must start with a scrolled viewport");
            var incoming=new ArrayList<>(source);Collections.reverse(incoming);
            fx(()->{TableRows.merge(table[0].getItems(),incoming,Row::id);table[0].layout();return null;});fx(()->null);
            fx(()->{assertSame(source.get(500),table[0].getSelectionModel().getSelectedItem(),"Selection must stay on the same order");
                assertEquals(before,vertical(table[0]).getValue(),0.02,"The viewport must not jump");return null;});
        } finally {fx(()->{if(stage[0]!=null)stage[0].close();return null;});}
    }
    @Test void reversingRowsPreservesAllSelectedOrders()throws Exception {
        var source=java.util.stream.IntStream.range(0,1000).mapToObj(Row::new).toList();
        var stage=new Stage[1];@SuppressWarnings("unchecked") var table=(TableView<Row>[])new TableView<?>[1];
        try {
            fx(()->{
                table[0]=new TableView<>();table[0].setFixedCellSize(28);
                var column=new TableColumn<Row,String>("Order");column.setCellValueFactory(c->new ReadOnlyStringWrapper(Integer.toString(c.getValue().id())));
                table[0].getColumns().add(column);table[0].getItems().setAll(source);
                stage[0]=new Stage();stage[0].setScene(new Scene(new StackPane(table[0]),640,400));stage[0].show();
                table[0].getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
                table[0].getSelectionModel().selectIndices(200,800);return null;
            });
            fx(()->{table[0].applyCss();table[0].layout();return null;});fx(()->null);
            var incoming=new ArrayList<>(source);Collections.reverse(incoming);
            fx(()->{TableRows.merge(table[0].getItems(),incoming,Row::id);table[0].layout();return null;});fx(()->null);
            fx(()->{assertEquals(Set.of(source.get(200),source.get(800)),Set.copyOf(table[0].getSelectionModel().getSelectedItems()));return null;});
        } finally {fx(()->{if(stage[0]!=null)stage[0].close();return null;});}
    }
}
