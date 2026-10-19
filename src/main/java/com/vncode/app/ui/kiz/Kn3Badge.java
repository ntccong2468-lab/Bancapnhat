package com.vncode.app.ui.kiz;

import com.vncode.app.features.kiz.status.Kn3Mark;
import com.vncode.app.shared.I18nService;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import java.util.*;

/** All colors are derived from marketplace observations, never from local KIZ availability. */
public final class Kn3Badge extends HBox {
    private final Label label=new Label("KN3");
    private final Button replace=new Button();
    public Kn3Badge(){super(8);setAlignment(Pos.CENTER_LEFT);label.setId("kn3Status");label.getStyleClass().add("badge");replace.setId("kn3Replace");replace.getStyleClass().add("btn-secondary");getChildren().addAll(label,replace);}
    public void show(List<Kn3Mark> marks,boolean locallyAssigned,Runnable action){
        var state=Kn3Mark.summary(marks);boolean visible=locallyAssigned||marks.stream().anyMatch(m->!m.code().isBlank());setVisible(visible);setManaged(visible);
        label.getStyleClass().removeAll("badge-green","badge-red","badge-warning");label.getStyleClass().add(state==Kn3Mark.State.ACCEPTED?"badge-green":state==Kn3Mark.State.REJECTED?"badge-red":"badge-warning");
        String reason=marks.stream().map(Kn3Mark::reason).filter(s->!s.isBlank()).distinct().collect(java.util.stream.Collectors.joining(", "));
        String text=I18nService.getInstance().tr("kn3.state."+(state==Kn3Mark.State.EMPTY?Kn3Mark.State.PENDING:state).name())+(reason.isBlank()?"":" · "+I18nService.getInstance().tr("kn3.reason."+reason,reason));label.setTooltip(new Tooltip(text));label.setAccessibleText("KN3 · "+text);
        replace.setText(I18nService.getInstance().tr("kn3.replace"));replace.setVisible(state==Kn3Mark.State.REJECTED&&action!=null);replace.setManaged(replace.isVisible());replace.setOnAction(e->{if(action!=null)action.run();});
    }
}
