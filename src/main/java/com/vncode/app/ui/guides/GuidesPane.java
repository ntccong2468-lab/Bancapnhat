package com.vncode.app.ui.guides;

import com.vncode.app.features.guides.GuideMediaRepository;
import com.vncode.app.shared.I18nService;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.media.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.text.Normalizer;
import java.util.*;

/** Offline text remains available when an optional image/video cannot be loaded. */
public final class GuidesPane extends BorderPane {
    private static final List<String> KEYS=List.of("license","wb","ozon","batch","gs1","labels","gtin","update");
    private final VBox body=new VBox(18);
    private final TextField search=new TextField();
    private final Label title=new Label(),intro=new Label();
    private final List<MediaPlayer> players=new ArrayList<>();
    private final Map<MediaPlayer,javafx.animation.PauseTransition> timeouts=new HashMap<>();
    private final Map<String,Duration> positions=new HashMap<>();
    private final GuideMediaRepository media=new GuideMediaRepository();
    private boolean disposed;
    public GuidesPane() {
        body.getStyleClass().add("welcome-body");title.getStyleClass().add("welcome-title");setId("guidesPane");search.setId("guideSearch");search.setPrefWidth(350);
        var scroll=new ScrollPane(body);scroll.setFitToWidth(true);setCenter(scroll);
        var heading=new VBox(12,title,intro,search);heading.setPadding(new javafx.geometry.Insets(18));setTop(heading);
        body.setPadding(new javafx.geometry.Insets(18));title.getStyleClass().add("h2");intro.setWrapText(true);
        search.textProperty().addListener((o,a,b)->render());sceneProperty().addListener((o,a,b)->{if(b==null)stopMedia();});applyTranslations();
    }
    public static String searchKey(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('đ','d');}
    public void applyTranslations(){title.setText(tr("guides.title"));intro.setText(tr("guides.introduction"));search.setPromptText(tr("guides.search"));render();}
    private void render() {
        if(disposed)return;stopMedia();body.getChildren().clear();String query=searchKey(search.getText());
        var available=media.list();
        for(String key:KEYS) {
            String title=tr("guides."+key+".title"),text=tr("guides."+key+".body");
            if(!searchKey(title+" "+text).contains(query))continue;
            var section=new VBox(12,label(title,"h3"),label(text,"text-secondary"));section.setId("guide_"+key);section.getStyleClass().add("guide-section");section.setMinWidth(0);
            for(var entry:available)if(entry.guide().equals(key)){if(entry.kind()==GuideMediaRepository.Kind.IMAGE)section.getChildren().add(image(entry));else section.getChildren().add(video(entry));}
            var choose=new Button(tr("guides.add_media"));choose.setOnAction(e->chooseMedia(key));section.getChildren().add(choose);body.getChildren().add(section);
        }
        if(body.getChildren().isEmpty())body.getChildren().add(label(tr("guides.no_results"),"text-muted"));
    }
    private javafx.scene.Node image(GuideMediaRepository.Media entry) {
        var view=new ImageView();view.setPreserveRatio(true);view.setFitWidth(800);view.setFitHeight(450);
        try{var img=new Image(entry.uri().toASCIIString(),800,450,true,true,true);view.setImage(img);}catch(RuntimeException unavailable){return label(tr("guides.media_failed"),"text-muted");}
        return view;
    }
    private javafx.scene.Node video(GuideMediaRepository.Media entry) {
        var box=new VBox(8);var status=new Label(tr("guides.video_loading"));status.setWrapText(true);
        var view=new MediaView();view.setPreserveRatio(true);view.fitWidthProperty().bind(box.widthProperty().subtract(12));view.setFitHeight(360);
        Button play=new Button(tr("guides.play_pause")),retry=new Button(tr("guides.retry"));var seek=new Slider();seek.setMin(0);seek.setMax(1);seek.setDisable(true);
        Runnable load=()->{
            var old=view.getMediaPlayer();if(old!=null){positions.put(entry.guide(),old.getCurrentTime());var oldTimeout=timeouts.remove(old);if(oldTimeout!=null)oldTimeout.stop();old.dispose();players.remove(old);}
            status.setText(tr("guides.video_loading"));play.setDisable(true);seek.setDisable(true);retry.setVisible(false);retry.setManaged(false);
            try {
                var player=new MediaPlayer(new Media(entry.uri().toASCIIString()));players.add(player);view.setMediaPlayer(player);
                var timeout=new javafx.animation.PauseTransition(Duration.seconds(20));timeouts.put(player,timeout);timeout.setOnFinished(e->{if(view.getMediaPlayer()==player&&player.getStatus()!=MediaPlayer.Status.READY&&player.getStatus()!=MediaPlayer.Status.PLAYING&&player.getStatus()!=MediaPlayer.Status.PAUSED){status.setText(tr("guides.media_failed"));retry.setVisible(true);retry.setManaged(true);}});timeout.play();
                player.setOnReady(()->{timeout.stop();if(disposed||view.getMediaPlayer()!=player)return;status.setText("");play.setDisable(false);seek.setDisable(false);seek.setMax(Math.max(1,player.getTotalDuration().toSeconds()));var position=positions.get(entry.guide());if(position!=null&&!position.isUnknown())player.seek(position);});
                player.setOnError(()->{timeout.stop();if(view.getMediaPlayer()!=player)return;status.setText(tr("guides.media_failed"));play.setDisable(true);retry.setVisible(true);retry.setManaged(true);});
                player.currentTimeProperty().addListener((o,a,b)->{if(!seek.isValueChanging())seek.setValue(b.toSeconds());});
                play.setOnAction(e->{if(player.getStatus()==MediaPlayer.Status.PLAYING)player.pause();else player.play();});
                seek.setOnMouseReleased(e->player.seek(Duration.seconds(seek.getValue())));
                seek.setOnKeyReleased(e->player.seek(Duration.seconds(seek.getValue())));
            }catch(RuntimeException unavailable){status.setText(tr("guides.media_failed"));retry.setVisible(true);retry.setManaged(true);}
        };
        retry.setOnAction(e->load.run());box.getChildren().setAll(view,status,new HBox(10,play,retry),seek);load.run();return box;
    }
    private void chooseMedia(String guide) {
        var chooser=new FileChooser();chooser.setTitle(tr("guides.add_media"));chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(tr("guides.media_files"),"*.mp4","*.m4v","*.png","*.jpg","*.jpeg"));
        var file=chooser.showOpenDialog(getScene()==null?null:getScene().getWindow());if(file==null)return;
        try{String name=file.getName().toLowerCase(Locale.ROOT);media.save(new GuideMediaRepository.Media(guide,name.endsWith(".mp4")||name.endsWith(".m4v")?GuideMediaRepository.Kind.VIDEO:GuideMediaRepository.Kind.IMAGE,file.toPath().toRealPath().toUri()));render();}
        catch(Exception unavailable){body.getChildren().add(label(tr("guides.media_failed"),"text-muted"));}
    }
    public void stopMedia(){timeouts.values().forEach(javafx.animation.PauseTransition::stop);timeouts.clear();for(var p:List.copyOf(players)){if(p.getMedia()!=null)positions.put(findGuide(p),p.getCurrentTime());p.stop();p.dispose();}players.clear();}
    private String findGuide(MediaPlayer player){String uri=player.getMedia().getSource();return media.list().stream().filter(m->m.uri().toASCIIString().equals(uri)).map(GuideMediaRepository.Media::guide).findFirst().orElse("");}
    public void dispose(){disposed=true;stopMedia();positions.clear();}
    private static Label label(String text,String style){var label=new Label(text);label.setWrapText(true);label.setMinWidth(0);label.setMaxWidth(Double.MAX_VALUE);label.getStyleClass().add(style);return label;}
    private static String tr(String key){return I18nService.getInstance().tr(key);}
}
