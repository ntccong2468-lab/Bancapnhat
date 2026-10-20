package com.vncode.app.shared;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Optional optimization: only an exact, unchanged database AND WAL may reuse an update rollback. */
final class UpdateSnapshotHandoff {
    private static final String FILE=".vncode-update-snapshot.properties";
    private static final String NONE="none";
    record Identity(String database,String wal) { }
    private UpdateSnapshotHandoff() { }

    static Identity capture(AppDataLock owner)throws IOException {
        return capture(owner,progress->{});
    }

    static Identity capture(AppDataLock owner,java.util.function.Consumer<LocalDataSnapshotService.Progress> progress)throws IOException {
        Path root=owner.requireOwnedAppDataDir(),db=root.resolve("database.db"),wal=root.resolve("database.db-wal");
        var dbBefore=attributes(db,false);var walBefore=attributes(wal,true);
        long total=dbBefore.size()+(walBefore==null?0:walBefore.size());
        String database=hash(db,progress,0,total),journal=walBefore==null?NONE:hash(wal,progress,dbBefore.size(),total);
        if(!same(dbBefore,attributes(db,false))||!same(walBefore,attributes(wal,true)))throw new IOException("UPDATE_DATA_CHANGED");
        return new Identity(database,journal);
    }

    static void clear(AppDataLock owner)throws IOException {
        Path marker=owner.requireOwnedAppDataDir().resolve(FILE);
        if(Files.notExists(marker,LinkOption.NOFOLLOW_LINKS))return;
        attributes(marker,false);Files.delete(marker);
    }

    static void record(AppDataLock owner,String targetVersion,LocalDataSnapshotService.Snapshot snapshot,Identity before,Identity after)throws IOException {
        clear(owner);
        if(targetVersion==null||!targetVersion.matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")||before==null||!before.equals(after))return;
        String content="targetVersion="+targetVersion+"\nschemaVersion="+snapshot.schemaVersion()+"\nsnapshotId="+snapshot.database().getParent().getFileName()+"\nsnapshotSha256="+snapshot.sha256()+"\ndatabaseSha256="+after.database()+"\nwalSha256="+after.wal()+"\n";
        Path root=owner.requireOwnedAppDataDir(),marker=root.resolve(FILE),temporary=root.resolve(FILE+".tmp");
        if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS))attributes(temporary,false);
        Files.writeString(temporary,content,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        try{Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,marker,StandardCopyOption.REPLACE_EXISTING);}
    }

    static LocalDataSnapshotService.Snapshot reusable(AppDataLock owner,String targetVersion,int schema,LocalDataSnapshotService snapshots) {
        return reusable(owner,targetVersion,schema,snapshots,progress->{});
    }

    static LocalDataSnapshotService.Snapshot reusable(AppDataLock owner,String targetVersion,int schema,LocalDataSnapshotService snapshots,java.util.function.Consumer<LocalDataSnapshotService.Progress> progress) {
        try {
            Path marker=owner.requireOwnedAppDataDir().resolve(FILE);
            if(Files.notExists(marker,LinkOption.NOFOLLOW_LINKS)||attributes(marker,false).size()>4096)return null;
            byte[] content;
            try(var input=Files.newInputStream(marker,LinkOption.NOFOLLOW_LINKS)){content=input.readNBytes(4097);}
            if(content.length>4096)return null;
            var values=new Properties();
            values.load(new java.io.StringReader(new String(content,StandardCharsets.UTF_8)));
            if(!targetVersion.equals(values.getProperty("targetVersion"))||!Integer.toString(schema).equals(values.getProperty("schemaVersion")))return null;
            String id=values.getProperty("snapshotId"),checksum=values.getProperty("snapshotSha256"),database=values.getProperty("databaseSha256"),wal=values.getProperty("walSha256");
            if(id==null||!id.matches("[0-9]{13}-[0-9a-f-]{36}")||!digest(checksum)||!digest(database)||!(NONE.equals(wal)||digest(wal)))return null;
            if(!new Identity(database,wal).equals(capture(owner,progress)))return null;
            var snapshot=snapshots.load(owner,id);
            progress.accept(new LocalDataSnapshotService.Progress("VERIFYING_SNAPSHOT",0,0));
            return snapshot.schemaVersion()==schema&&"signed-update-install".equals(snapshot.reason())&&snapshots.verifyPreviouslyValidated(snapshot,checksum)?snapshot:null;
        }catch(IOException|RuntimeException invalid){return null;}
    }
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static BasicFileAttributes attributes(Path file,boolean optional)throws IOException {
        if(optional&&Files.notExists(file,LinkOption.NOFOLLOW_LINKS))return null;
        var result=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!result.isRegularFile()||result.isSymbolicLink())throw new IOException("UPDATE_DATA_PATH_UNSAFE");return result;
    }
    private static boolean same(BasicFileAttributes first,BasicFileAttributes second){return first==null?second==null:second!=null&&first.size()==second.size()&&first.lastModifiedTime().equals(second.lastModifiedTime())&&Objects.equals(first.fileKey(),second.fileKey());}
    private static String hash(Path file,java.util.function.Consumer<LocalDataSnapshotService.Progress> progress,long offset,long total)throws IOException {
        progress.accept(new LocalDataSnapshotService.Progress("CHECKING_SOURCE",offset,total));
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            var digest=MessageDigest.getInstance("SHA-256");var buffer=new byte[65536];int count;
            while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("UPDATE_CANCELLED");digest.update(buffer,0,count);offset+=count;progress.accept(new LocalDataSnapshotService.Progress("CHECKING_SOURCE",offset,total));}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
