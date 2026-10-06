package com.tuandev.fbsbarcode.shared;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFilePublisher {
    private AtomicFilePublisher() {
    }

    public static File stagingFile(File target, String suffix) throws IOException {
        Path absolute = target.toPath().toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Output file has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        return Files.createTempFile(parent, "." + target.getName() + "-", suffix).toFile();
    }

    public static void publish(File staging, File target) throws IOException {
        Path source = staging.toPath();
        Path destination = target.toPath().toAbsolutePath();
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }
}
