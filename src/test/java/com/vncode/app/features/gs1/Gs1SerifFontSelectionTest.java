package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Gs1SerifFontSelectionTest {
    @TempDir Path directory;

    @Test void findsMacTimesInAllFourStyles() throws Exception {
        Path mac = Files.createDirectory(directory.resolve("mac"));
        for (String name : List.of("Times New Roman.ttf", "Times New Roman Bold.ttf",
                "Times New Roman Italic.ttf", "Times New Roman Bold Italic.ttf")) {
            Files.createFile(mac.resolve(name));
        }
        assertEquals(mac.resolve("Times New Roman.ttf"), select(null, List.of(), mac, false, false));
        assertEquals(mac.resolve("Times New Roman Bold.ttf"), select(null, List.of(), mac, true, false));
        assertEquals(mac.resolve("Times New Roman Italic.ttf"), select(null, List.of(), mac, false, true));
        assertEquals(mac.resolve("Times New Roman Bold Italic.ttf"), select(null, List.of(), mac, true, true));
        Files.delete(mac.resolve("Times New Roman Bold.ttf"));
        assertNull(select(null, List.of(), mac, true, false));
    }

    @Test void existingWindowsAndLinuxSelectionKeepsPriority() throws Exception {
        Path windows = Files.createDirectory(directory.resolve("windows"));
        Path windowsFonts = Files.createDirectory(windows.resolve("Fonts"));
        Path linux = Files.createDirectory(directory.resolve("linux"));
        Path mac = Files.createDirectory(directory.resolve("mac"));
        Files.createFile(windowsFonts.resolve("timesbd.ttf"));
        Files.createFile(linux.resolve("LiberationSerif-Bold.ttf"));
        Files.createFile(mac.resolve("Times New Roman Bold.ttf"));
        assertEquals(windowsFonts.resolve("timesbd.ttf"), select(windows.toString(), List.of(linux), mac, true, false));
        Files.delete(windowsFonts.resolve("timesbd.ttf"));
        assertEquals(linux.resolve("LiberationSerif-Bold.ttf"), select(windows.toString(), List.of(linux), mac, true, false));
    }

    private static Path select(String windowsRoot, List<Path> linux, Path mac, boolean bold, boolean italic) {
        return Gs1WordPrefixService.findSerifFont(windowsRoot, linux, List.of(mac), bold, italic);
    }
}
