package com.vncode.app.testsupport;

import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in snapshots from synthetic/offline UI tests only, never attached seller profiles. */
public final class UiEvidence {
    private UiEvidence() { }
    public static void capture(Scene scene, String name) throws Exception {
        String directory = System.getProperty("vncode.workbench.capture.dir");
        if (directory == null) return;
        var root = Path.of(directory); Files.createDirectories(root);
        var snapshot = scene.snapshot(null);
        int w = (int) snapshot.getWidth(), h = (int) snapshot.getHeight();
        var png = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        snapshot.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(),
                ((DataBufferInt) png.getRaster().getDataBuffer()).getData(), 0, w);
        ImageIO.write(png, "png", root.resolve(name.replaceAll("[^a-zA-Z0-9_-]", "-") + ".png").toFile());
    }
}
