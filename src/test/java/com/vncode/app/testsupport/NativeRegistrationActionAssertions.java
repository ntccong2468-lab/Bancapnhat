package com.vncode.app.testsupport;

import com.vncode.app.shared.I18nService;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.text.Text;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the native skin's displayed text, including ellipsis and clipping after column layout. */
public final class NativeRegistrationActionAssertions {
    private NativeRegistrationActionAssertions() { }
    public static Set<String> assertFullyRendered(TableView<?> table,String context) {
        table.applyCss();table.layout();
        String caption=I18nService.getInstance().tr("znack.registration.action");
        var actions=table.getVisibleLeafColumns().stream().filter(c->caption.equals(c.getText())).findFirst().orElseThrow();
        var header=table.lookupAll(".column-header").stream().filter(TableColumnHeader.class::isInstance).map(TableColumnHeader.class::cast)
                .filter(h->h.getTableColumn()==actions).findFirst().orElseThrow();
        var label=(Label)header.lookup(".label");assertNotNull(label,context+" action header label");
        assertDisplayedText(label,context+" action header");
        Bounds viewport=table.localToScene(table.getBoundsInLocal());
        assertInside(header,viewport,context+" action header stays inside the table viewport");
        var observed=new LinkedHashSet<String>();int checked=0;
        for(Node node:table.lookupAll(".table-cell")) {
            if(!(node instanceof TableCell<?,?> cell)||cell.getTableColumn()!=actions||cell.isEmpty()||cell.getTableRow()==null||cell.getTableRow().isEmpty())continue;
            Bounds cellBounds=cell.localToScene(cell.getBoundsInLocal());
            if(cellBounds.getMinY()<viewport.getMinY()||cellBounds.getMaxY()>viewport.getMaxY()-1)continue;
            assertInside(cell,viewport,context+" row "+cell.getIndex()+" action cell stays inside the table viewport");
            assertInstanceOf(Labeled.class,cell.getGraphic(),context+" row action must have a visible caption");
            var action=(Labeled)cell.getGraphic();
            assertDisplayedText(action,context+" row "+cell.getIndex()+" action");
            assertInside(action,cellBounds,context+" row "+cell.getIndex()+" action stays inside its cell");
            observed.add(action.getText());checked++;
        }
        assertTrue(checked>0,context+" must inspect at least one real populated action cell");return Set.copyOf(observed);
    }
    private static void assertDisplayedText(Labeled control,String context) {
        var texts=control.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                .filter(t->!(t instanceof org.kordamp.ikonli.javafx.FontIcon)&&t.isVisible()).toList();
        String displayed=texts.stream().map(Text::getText).collect(Collectors.joining());
        assertEquals(normalize(control.getText()),normalize(displayed),context+" must display its complete caption; expected='"+control.getText()+"' rendered='"+displayed+"' bounds="+control.getBoundsInLocal());
        assertFalse(displayed.isBlank(),context+" must not render an empty caption");
        Bounds bounds=control.localToScene(control.getBoundsInLocal());
        for(var text:texts)assertInside(text,bounds,context+" text must fit inside its native control");
    }
    private static String normalize(String text) {return text==null?"":text.replaceAll("\\s+","");}
    private static void assertInside(Node node,Bounds available,String context) {
        Bounds actual=node.localToScene(node.getBoundsInLocal());
        assertTrue(actual.getMinX()>=available.getMinX()-1&&actual.getMaxX()<=available.getMaxX()+1
                &&actual.getMinY()>=available.getMinY()-1&&actual.getMaxY()<=available.getMaxY()+1,
                context+": actual="+actual+" available="+available);
    }
}
