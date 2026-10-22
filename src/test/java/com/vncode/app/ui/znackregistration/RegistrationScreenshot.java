package com.vncode.app.ui.znackregistration;

import javafx.scene.Parent;
import javafx.scene.Scene;
import java.nio.file.*;

/** Optional evidence from synthetic UI fixtures; no production account is opened. Call on FX. */
final class RegistrationScreenshot {
    static void capture(Parent root,String name)throws Exception {
        String directory=System.getProperty("vncode.item4.capture.dir");if(directory==null)return;
        var scene=new Scene(root,1336,826);com.vncode.app.shared.ThemeService.initializeAppearance();com.vncode.app.shared.ThemeService.applyTheme(scene);
        root.applyCss();root.layout();var image=scene.snapshot(null);
        var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        Files.createDirectories(Path.of(directory));javax.imageio.ImageIO.write(png,"png",Path.of(directory,name+".png").toFile());
    }
}
