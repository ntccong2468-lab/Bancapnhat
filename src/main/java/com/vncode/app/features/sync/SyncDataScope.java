package com.vncode.app.features.sync;

import com.vncode.app.shared.AppPaths;
import java.nio.file.Path;

/** A late background response may never write into a different application data directory. */
public final class SyncDataScope implements AutoCloseable {
    private static final ThreadLocal<Path> EXPECTED=new ThreadLocal<>();
    private final Path prior;
    private SyncDataScope(Path directory){prior=EXPECTED.get();EXPECTED.set(directory.toAbsolutePath().normalize());}
    public static SyncDataScope enter(Path directory){return new SyncDataScope(directory);}
    public static void requireCurrent(){var expected=EXPECTED.get();if(expected!=null&&!expected.equals(AppPaths.appDataDir().toAbsolutePath().normalize()))throw new IllegalStateException("SYNC_DATA_DIRECTORY_CHANGED");}
    @Override public void close(){if(prior==null)EXPECTED.remove();else EXPECTED.set(prior);}
}
