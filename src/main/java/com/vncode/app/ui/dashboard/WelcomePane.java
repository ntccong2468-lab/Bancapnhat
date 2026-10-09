package com.vncode.app.ui.dashboard;

import com.vncode.app.shared.I18nService;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import java.util.List;

/** Landing page reuses the existing actions and news view. */
public final class WelcomePane extends ScrollPane {
    private final Label title=new Label(),description=new Label(),features=new Label();
    private final VBox introduction=new VBox(18),body=new VBox(24);
    private final HBox wide=new HBox(40);
    private final GridPane cards=new GridPane();
    private final VBox news;
    private final List<Button> buttons=new java.util.ArrayList<>();
    private final List<Label> names=new java.util.ArrayList<>(),summaries=new java.util.ArrayList<>();
    private static final String[] KEYS={"fbs","kiz","registration","fbo","templates","history"};
    private static final String[] ICONS={"fth-truck","fth-shield","fth-file-plus","fth-package","fth-edit","fth-clock"};
    private boolean compact;
    public WelcomePane(VBox news,List<Runnable> actions) {
        if(actions.size()!=6)throw new IllegalArgumentException("Six existing feature actions required");
        this.news=news;setFitToWidth(true);setHbarPolicy(ScrollBarPolicy.NEVER);getStyleClass().add("welcome-scroll");
        body.getStyleClass().add("welcome-body");title.getStyleClass().add("welcome-title");description.getStyleClass().add("welcome-description");
        description.setWrapText(true);features.getStyleClass().add("h2");cards.setHgap(18);cards.setVgap(18);
        introduction.setMinWidth(0);introduction.getChildren().setAll(title,description,features,cards);
        for(int i=0;i<6;i++) {
            var icon=new FontIcon(ICONS[i]);icon.setIconSize(24);icon.getStyleClass().add("feature-icon");
            var name=new Label();name.getStyleClass().add("feature-title");name.setWrapText(true);
            var summary=new Label();summary.getStyleClass().add("text-secondary");summary.setWrapText(true);
            var content=new VBox(12,icon,name,summary);var button=new Button();button.setGraphic(content);button.setWrapText(true);button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);button.setMaxWidth(Double.MAX_VALUE);button.setMinWidth(0);
            button.getStyleClass().add("feature-card");Runnable action=actions.get(i);button.setOnAction(e->action.run());
            buttons.add(button);names.add(name);summaries.add(summary);
        }
        news.setMinWidth(0);setContent(body);widthProperty().addListener((o,a,b)->arrange(b.doubleValue()));arrange(1100);applyTranslations();
    }
    private void arrange(double width) {
        compact=width<1000;body.getChildren().clear();wide.getChildren().clear();cards.getChildren().clear();cards.getColumnConstraints().clear();
        int columns=width<650?1:2;
        for(int i=0;i<columns;i++){var constraint=new ColumnConstraints();constraint.setPercentWidth(100.0/columns);constraint.setHgrow(Priority.ALWAYS);cards.getColumnConstraints().add(constraint);}
        for(int i=0;i<buttons.size();i++)cards.add(buttons.get(i),i%columns,i/columns);
        if(compact){news.setPrefWidth(-1);news.setMaxWidth(Double.MAX_VALUE);body.getChildren().setAll(introduction,news);}
        else {news.setPrefWidth(420);news.setMaxWidth(480);HBox.setHgrow(introduction,Priority.ALWAYS);HBox.setHgrow(news,Priority.SOMETIMES);wide.getChildren().setAll(introduction,news);body.getChildren().setAll(wide);}
    }
    public void applyTranslations(){var i=I18nService.getInstance();title.setText(i.tr("welcome.title"));description.setText(i.tr("welcome.description"));features.setText(i.tr("welcome.features"));for(int n=0;n<6;n++){names.get(n).setText(i.tr("welcome."+KEYS[n]+".title"));summaries.get(n).setText(i.tr("welcome."+KEYS[n]+".description"));buttons.get(n).setAccessibleText(names.get(n).getText());}}
    public boolean isCompact(){return compact;}
    public String welcomeTitle(){return title.getText();}
}
