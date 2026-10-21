package com.vncode.app.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Profile-local window activation. The existing app-data lock remains the sole writer lock. */
public final class AppInstanceActivation implements AutoCloseable {
    private static final String DIRECTORY = ".activation";
    private static final String OWNER = "owner";
    private static final String UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern OWNER_CONTENT = Pattern.compile("1\\n(" + UUID_TEXT + ")\\n");
    private static final int MAX_BYTES = 256;
    private static final int MAX_REQUESTS = 16;
    private static final Duration MAX_WAIT = Duration.ofSeconds(10);

    private final AppDataLock ownership;
    private final Path directory;
    private final String generation = UUID.randomUUID().toString();
    private final Supplier<? extends CompletionStage<Void>> restore;
    private final ScheduledExecutorService poller;
    private final Set<String> pending = new HashSet<>();
    private boolean closed;

    private AppInstanceActivation(AppDataLock ownership, Path directory,
            Supplier<? extends CompletionStage<Void>> restore) {
        this.ownership = ownership;
        this.directory = directory;
        this.restore = java.util.Objects.requireNonNull(restore, "restore");
        poller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "vncode-window-activation");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Publishes the listener synchronously, while ownership is already held. */
    public static AppInstanceActivation listen(AppDataLock ownership,
            Supplier<? extends CompletionStage<Void>> restore) throws IOException {
        java.util.Objects.requireNonNull(ownership, "ownership");
        Path profile = ownership.requireOwnedAppDataDir();
        safeDirectory(profile);
        Path directory = profile.resolve(DIRECTORY);
        try { Files.createDirectory(directory); } catch (FileAlreadyExistsException existing) { /* Validate below. */ }
        safeDirectory(directory);
        AppInstanceActivation listener = new AppInstanceActivation(ownership, directory, restore);
        try {
            ownership.requireOwnedAppDataDir();
            write(directory.resolve(OWNER), listener.descriptor());
            listener.poller.scheduleWithFixedDelay(listener::poll, 0, 100, TimeUnit.MILLISECONDS);
            return listener;
        } catch (IOException | RuntimeException failure) {
            listener.close();
            throw failure;
        }
    }

    /** Waits off FX for an acknowledgement produced only after the owner's restore completed. */
    public static boolean requestActivation(Path profile, Duration timeout) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(profile, "profile");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Activation timeout must not be negative.");
        if (timeout.isZero()) return false;
        long budget = (timeout.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : timeout).toNanos();
        long started = System.nanoTime();
        Path root = profile.toAbsolutePath().normalize();
        Path directory = root.resolve(DIRECTORY);
        String generation = null, request = null, content = null;
        Path requestFile = null, acknowledgement = null;
        try {
            while (System.nanoTime() - started < budget) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Activation interrupted.");
                if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) safeDirectory(root);
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    safeDirectory(directory);
                    String descriptor = read(directory.resolve(OWNER));
                    var match = OWNER_CONTENT.matcher(descriptor == null ? "" : descriptor);
                    if (match.matches()) {
                        String current = match.group(1);
                        if (!current.equals(generation)) {
                            deleteQuietly(requestFile);
                            deleteQuietly(acknowledgement);
                            generation = current;
                            request = UUID.randomUUID().toString();
                            content = requestContent(generation, request);
                            requestFile = directory.resolve(generation + "." + request + ".request");
                            acknowledgement = directory.resolve(generation + "." + request + ".ack");
                            write(requestFile, content);
                        }
                        String ack = read(acknowledgement);
                        if (content.equals(ack) && descriptor.equals(read(directory.resolve(OWNER)))) return true;
                    }
                }
                long remaining = budget - (System.nanoTime() - started);
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
            return false;
        } finally {
            deleteQuietly(requestFile);
            deleteQuietly(acknowledgement);
        }
    }

    private String descriptor() { return "1\n" + generation + "\n"; }
    private static String requestContent(String generation, String request) { return "1\n" + generation + "\n" + request + "\n"; }

    private void poll() {
        try {
            synchronized (this) { if (!currentOwner()) return; }
            int scanned = 0;
            Pattern name = Pattern.compile(Pattern.quote(generation) + "\\.(" + UUID_TEXT + ")\\.request");
            try (var requests = Files.newDirectoryStream(directory, generation + ".*.request")) {
                for (Path file : requests) {
                    if (++scanned > MAX_REQUESTS) break;
                    var match = name.matcher(file.getFileName().toString());
                    if (!match.matches()) continue;
                    String request = match.group(1);
                    synchronized (this) {
                        if (!currentOwner() || pending.size() >= MAX_REQUESTS) return;
                        if (pending.contains(request)) continue;
                        if (!requestContent(generation, request).equals(read(file))) continue;
                        Files.deleteIfExists(file);
                        pending.add(request);
                    }
                    try {
                        CompletionStage<Void> completion = java.util.Objects.requireNonNull(restore.get(), "restore completion");
                        completion.whenComplete((ignored, failure) -> complete(request, failure));
                    } catch (RuntimeException failed) { complete(request, failed); }
                }
            }
        } catch (IOException | RuntimeException unavailable) { /* A failed convenience signal never weakens ownership. */ }
    }

    private synchronized void complete(String request, Throwable failure) {
        pending.remove(request);
        try {
            if (failure == null && currentOwner()) {
                write(directory.resolve(generation + "." + request + ".ack"), requestContent(generation, request));
            }
        } catch (IOException | RuntimeException unavailable) { /* The second process will time out safely. */ }
    }

    private boolean currentOwner() throws IOException {
        if (closed) return false;
        ownership.requireOwnedAppDataDir();
        return descriptor().equals(read(directory.resolve(OWNER)));
    }

    /** Stops immediately; never waits for a queued FX callback or closes the writer lock. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        poller.shutdownNow();
        pending.clear();
        try {
            ownership.requireOwnedAppDataDir();
            safeDirectory(directory);
            if (descriptor().equals(read(directory.resolve(OWNER)))) Files.deleteIfExists(directory.resolve(OWNER));
            try (var files = Files.newDirectoryStream(directory, generation + ".*")) {
                for (Path file : files) deleteQuietly(file);
            }
        } catch (IOException | RuntimeException unavailable) { /* Do not touch another owner's files. */ }
    }

    private static void safeDirectory(Path directory) throws IOException {
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || !directory.toRealPath().equals(directory.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe application activation directory.");
        }
    }

    private static String read(Path file) throws IOException {
        safeDirectory(file.getParent());
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() > MAX_BYTES) {
                throw new IOException("Unsafe application activation signal.");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Application activation signal is too large.");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (NoSuchFileException missing) { return null; }
    }

    private static void write(Path target, String content) throws IOException {
        safeDirectory(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) read(target);
        Path temporary = target.resolveSibling(".signal-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            safeDirectory(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) read(target);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try { if (read(file) != null) Files.deleteIfExists(file); } catch (IOException | RuntimeException ignored) { }
    }

    public static final class ActivatedException extends IOException {
        public ActivatedException() { super("The existing VNCode window was activated."); }
    }
}
