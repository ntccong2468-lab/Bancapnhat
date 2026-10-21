package com.vncode.app.testsupport;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

/** Drive a real modal dialog from the test thread, independently of animation pulses.
 * Assertions run before the real button is fired. No dialog or production action is bypassed. */
public final class FxDialogDriver {
    private FxDialogDriver(){}
    public static void choose(Runnable open,Predicate<DialogPane> expected,Function<DialogPane,ButtonType> choice)throws Exception {
        if(Platform.isFxApplicationThread())throw new IllegalStateException("Dialog driver requires the test thread");
        var opening=new FutureTask<Void>(()->{open.run();return null;});Platform.runLater(opening);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        try{
            while(System.nanoTime()<deadline){
                var poll=new FutureTask<Boolean>(()->{
                    for(var window:List.copyOf(Window.getWindows())){
                        if(!window.isShowing()||window.getScene()==null)continue;
                        var root=window.getScene().getRoot();var node=root instanceof DialogPane?root:root.lookup(".dialog-pane");
                        if(node instanceof DialogPane pane&&expected.test(pane)){
                            var type=choice.apply(pane);var nodeButton=pane.lookupButton(type);
                            if(!(nodeButton instanceof Button button)||button.isDisabled())return false;
                            button.fire();return true;
                        }
                    }return false;
                });Platform.runLater(poll);
                if(poll.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)){
                    opening.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);return;
                }
                if(opening.isDone())opening.get(); // Propagate action failures instead of hiding them as a timeout.
                Thread.sleep(10);
            }
            throw new AssertionError("Expected confirmation dialog was not displayed");
        }finally{
            // A failed assertion must not strand showAndWait and contaminate later tests.
            var close=new FutureTask<Void>(()->{for(var window:List.copyOf(Window.getWindows()))if(window.isShowing()&&window.getScene()!=null){var root=window.getScene().getRoot();var node=root instanceof DialogPane?root:root.lookup(".dialog-pane");if(node instanceof DialogPane pane&&expected.test(pane))window.hide();}return null;});
            Platform.runLater(close);close.get(2,TimeUnit.SECONDS);
        }
    }
}
