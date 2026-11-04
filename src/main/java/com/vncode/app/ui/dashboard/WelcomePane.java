package com.vncode.app.ui.dashboard;

import com.vncode.app.shared.I18nService;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.javafx.FontIcon;
import java.util.List;

/** Landing page reuses the existing actions and news view. */
public final class WelcomePane extends ScrollPane {
    private final Label title=new Label(),description=new Label(),features=new Label();
    private final VBox introduction=new VBox(12),body=new VBox(16);
    private final HBox wide=new HBox(24);
    private final GridPane cards=new GridPane();
    private final VBox news;
    private javafx.scene.Node dashboard;
    private final List<Button> buttons=new java.util.ArrayList<>();
    private final List<Label> names=new java.util.ArrayList<>(),summaries=new java.util.ArrayList<>();
    private final VBox onboarding=new VBox(10);
    private final Label onboardingTitle=new Label();
    private final FlowPane onboardingActions=new FlowPane(8,8);
    private final List<Button> onboardingButtons=new java.util.ArrayList<>();
    private static final String[] ONBOARDING={"license","shop","znack","products"};
    private static final String[] KEYS={"fbs","fbo","products","registration","kiz_mapping","finance"};
    private static final String[] ICONS={"fth-truck","fth-package","fth-shopping-bag","fth-file-plus","fth-link","fth-bar-chart-2"};
    private boolean compact;
    private int arrangedColumns=-1;
    public WelcomePane(VBox news,List<Runnable> actions) {
        if(actions.size()!=6)throw new IllegalArgumentException("Six existing feature actions required");
        this.news=news;setFitToWidth(true);setHbarPolicy(ScrollBarPolicy.NEVER);getStyleClass().add("welcome-scroll");
        body.getStyleClass().add("welcome-body");title.getStyleClass().add("welcome-title");description.getStyleClass().add("welcome-description");
        description.setWrapText(true);features.getStyleClass().add("h3");cards.setHgap(0);cards.setVgap(0);
        onboardingTitle.setWrapText(true);onboardingTitle.getStyleClass().add("h3");onboarding.getChildren().setAll(onboardingTitle,onboardingActions);onboarding.setVisible(false);onboarding.setManaged(false);
        introduction.setMinWidth(0);introduction.getChildren().setAll(title,description,features,cards,onboarding);
        for(int i=0;i<6;i++) {
            var icon=new FontIcon(ICONS[i]);icon.setIconSize(18);icon.getStyleClass().add("feature-icon");
            var name=new Label();name.getStyleClass().add("feature-title");name.setWrapText(true);
            var summary=new Label();summary.getStyleClass().add("text-secondary");summary.setWrapText(true);
            var copy=new VBox(4,name,summary);copy.setMinWidth(0);HBox.setHgrow(copy,Priority.ALWAYS);
            var content=new HBox(16,icon,copy,new FontIcon("fth-chevron-right"));content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            var button=new Button();button.setGraphic(content);content.prefWidthProperty().bind(button.widthProperty().subtract(20));button.setWrapText(true);button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);button.setMaxWidth(Double.MAX_VALUE);button.setMinWidth(0);
            button.getStyleClass().add("feature-card");Runnable action=actions.get(i);button.setOnAction(e->action.run());
            buttons.add(button);names.add(name);summaries.add(summary);
        }
        news.setMinWidth(0);news.getStyleClass().add("welcome-news");setContent(body);widthProperty().addListener((o,a,b)->arrange(b.doubleValue()));arrange(1100);applyTranslations();
    }
    private void arrange(double width) {
        boolean nextCompact=width<1000;
        int columns=1;
        // Width changes within the same layout do not require removing/recreating the entire node tree.
        if(arrangedColumns==columns&&compact==nextCompact)return;
        compact=nextCompact;arrangedColumns=columns;
        body.getChildren().clear();wide.getChildren().clear();cards.getChildren().clear();cards.getColumnConstraints().clear();
        for(int i=0;i<columns;i++){var constraint=new ColumnConstraints();constraint.setPercentWidth(100.0/columns);constraint.setHgrow(Priority.ALWAYS);cards.getColumnConstraints().add(constraint);}
        for(int i=0;i<buttons.size();i++)cards.add(buttons.get(i),i%columns,i/columns);
        if(compact){news.setPrefWidth(-1);news.setMaxWidth(Double.MAX_VALUE);body.getChildren().setAll(introduction,news);}
        else {news.setPrefWidth(330);news.setMaxWidth(380);HBox.setHgrow(introduction,Priority.ALWAYS);HBox.setHgrow(news,Priority.SOMETIMES);wide.getChildren().setAll(introduction,news);body.getChildren().setAll(wide);}
        if(dashboard!=null)body.getChildren().add(0,dashboard);
    }
    /** Show the shop dashboard on the real welcome route while retaining shortcuts and news. */
    public void setDashboard(javafx.scene.Node dashboard) {
        if(this.dashboard!=null)body.getChildren().remove(this.dashboard);
        this.dashboard=java.util.Objects.requireNonNull(dashboard);
        title.setVisible(false);title.setManaged(false);description.setVisible(false);description.setManaged(false);
        if(dashboard instanceof Region region){region.setMinWidth(0);region.setMinHeight(Region.USE_PREF_SIZE);region.setPrefHeight(Region.USE_COMPUTED_SIZE);region.setMaxHeight(Region.USE_PREF_SIZE);}
        body.getChildren().add(0,dashboard);
    }
    public void applyTranslations(){translateOnboarding();var i=I18nService.getInstance();title.setText(i.tr("welcome.title"));description.setText(i.tr("welcome.description"));features.setText(i.tr("welcome.work"));for(int n=0;n<6;n++){names.get(n).setText(i.tr("welcome."+KEYS[n]+".title"));summaries.get(n).setText(i.tr("welcome."+KEYS[n]+".description"));buttons.get(n).setAccessibleText(names.get(n).getText());}}
    public boolean isCompact(){return compact;}
    public void setOnboardingActions(List<Runnable> actions) {
        if(actions.size()!=4)throw new IllegalArgumentException("Four onboarding actions required");
        onboardingActions.getChildren().clear();onboardingButtons.clear();
        for(int i=0;i<actions.size();i++){var button=new Button();button.setId("onboarding_"+ONBOARDING[i]);button.setWrapText(true);Runnable action=actions.get(i);button.setOnAction(e->action.run());onboardingButtons.add(button);onboardingActions.getChildren().add(button);}
        onboarding.setVisible(true);onboarding.setManaged(true);translateOnboarding();
    }
    private void translateOnboarding(){var i=I18nService.getInstance();onboardingTitle.setText(i.tr("welcome.onboarding.title"));for(int n=0;n<onboardingButtons.size();n++)onboardingButtons.get(n).setText(i.tr("welcome.onboarding."+ONBOARDING[n]));}
    public String welcomeTitle(){return title.getText();}
}
