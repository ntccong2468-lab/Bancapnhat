package com.vncode.app.shared;

import javafx.scene.text.Font;

/** Bundled OFL font makes layout independent of fonts installed on the seller's PC. */
public final class UiFonts {
    private static boolean installed;
    private UiFonts() {}
    public static synchronized void install() {
        if(installed)return;
        try(var font=UiFonts.class.getResourceAsStream("/com/vncode/app/assets/fonts/Inter.ttf");
            var bold=UiFonts.class.getResourceAsStream("/com/vncode/app/assets/fonts/Inter-Bold.ttf")) {
            if(font==null||bold==null||Font.loadFont(font,13)==null||Font.loadFont(bold,13)==null)throw new IllegalStateException("Bundled UI font unavailable");
            installed=true;
        }catch(java.io.IOException unavailable){throw new IllegalStateException("Bundled UI font unavailable");}
    }
}
