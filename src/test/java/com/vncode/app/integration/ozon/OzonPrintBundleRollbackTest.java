package com.vncode.app.integration.ozon;

import static org.junit.jupiter.api.Assertions.*;

import com.vncode.app.shared.AtomicFilePublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OzonPrintBundleRollbackTest {
    @TempDir Path directory;

    @Test void failedRestoreKeepsBackupOfPreviousPickingPdf() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking"); Files.writeString(label,"previous label");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        AtomicInteger attempts=new AtomicInteger();
        AtomicReference<Path> backup=new AtomicReference<>();
        IOException error=assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    switch(attempts.incrementAndGet()) {
                        case 1 -> AtomicFilePublisher.publish(source,target);
                        case 2 -> throw new IOException("synthetic label failure");
                        case 3 -> {backup.set(source.toPath());throw new IOException("synthetic restore failure");}
                        case 4 -> AtomicFilePublisher.publish(source,target);
                        default -> fail("unexpected publish");
                    }
                }));
        assertEquals(4,attempts.get());
        assertTrue(Files.exists(backup.get()),"must retain the only backup after restore fails");
        assertEquals("previous picking",Files.readString(backup.get()));
        assertTrue(error.getMessage().contains(backup.get().toAbsolutePath().toString()),"error must identify the recovery file");
        assertEquals("previous label",Files.readString(label));
    }

    @Test void successfulRollbackRestoresPreviousPickingAndRemovesTemporaryBackup() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking"); Files.writeString(label,"previous label");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        AtomicInteger attempts=new AtomicInteger(); AtomicReference<Path> backup=new AtomicReference<>();
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    int attempt=attempts.incrementAndGet();
                    if(attempt==2)throw new IOException("synthetic label failure");
                    if(attempt==3)backup.set(source.toPath());
                    AtomicFilePublisher.publish(source,target);
                }));
        assertEquals(4,attempts.get());
        assertEquals("previous picking",Files.readString(picking));
        assertEquals("previous label",Files.readString(label));
        assertFalse(Files.exists(backup.get()));
    }

    @Test void failedFirstPublishKeepsPreviousPickingWhenDestinationMayHaveChanged() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking"); Files.writeString(label,"previous label");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        IOException error=assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    // A non-atomic fallback can fail after changing the destination.
                    Files.writeString(target.toPath(),"partial output");
                    throw new IOException("synthetic picking failure");
                }));
        Path backup;
        try(var files=Files.list(directory)) {
            backup=files.filter(path->path.getFileName().toString().endsWith(".previous.pdf"))
                    .findFirst().orElseThrow(()->new AssertionError("previous picking backup was deleted"));
        }
        assertEquals("previous picking",Files.readString(backup));
        assertTrue(error.getMessage().contains(backup.toAbsolutePath().toString()));
        assertEquals("previous label",Files.readString(label));
    }

    @Test void labelFailureAfterDestinationChangedRestoresBothPreviousFiles() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking"); Files.writeString(label,"previous label");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        AtomicInteger attempts=new AtomicInteger();
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    if(attempts.incrementAndGet()==2){Files.writeString(target.toPath(),"partial label");throw new IOException("synthetic label failure");}
                    AtomicFilePublisher.publish(source,target);
                }));
        assertEquals("previous picking",Files.readString(picking));
        assertEquals("previous label",Files.readString(label));
    }

    @Test void bothBackupsAreRetainedWhenBothRestoresFail() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking"); Files.writeString(label,"previous label");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        AtomicInteger attempts=new AtomicInteger();
        AtomicReference<Path> pickingBackup=new AtomicReference<>(),labelBackup=new AtomicReference<>();
        IOException error=assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    int attempt=attempts.incrementAndGet();
                    if(attempt==1)AtomicFilePublisher.publish(source,target);
                    else if(attempt==2){Files.writeString(target.toPath(),"partial label");throw new IOException("synthetic label failure");}
                    else if(attempt==3){pickingBackup.set(source.toPath());throw new IOException("synthetic picking restore failure");}
                    else if(attempt==4){labelBackup.set(source.toPath());throw new IOException("synthetic label restore failure");}
                    else fail("unexpected publish");
                }));
        assertEquals("previous picking",Files.readString(pickingBackup.get()));
        assertEquals("previous label",Files.readString(labelBackup.get()));
        assertTrue(error.getMessage().contains(pickingBackup.get().toAbsolutePath().toString()));
        assertTrue(error.getMessage().contains(labelBackup.get().toAbsolutePath().toString()));
    }

    @Test void failedLabelPublishRemovesPartialLabelWhenThereWasNoPriorFile() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf");
        Path nextPicking=directory.resolve("next-picking"), nextLabel=directory.resolve("next-label");
        Files.writeString(picking,"previous picking");
        Files.writeString(nextPicking,"new picking"); Files.writeString(nextLabel,"new label");
        AtomicInteger attempts=new AtomicInteger();
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                nextPicking.toFile(),picking.toFile(),nextLabel.toFile(),label.toFile(),(source,target)->{
                    if(attempts.incrementAndGet()==2){Files.writeString(target.toPath(),"partial label");throw new IOException("synthetic label failure");}
                    AtomicFilePublisher.publish(source,target);
                }));
        assertEquals("previous picking",Files.readString(picking));
        assertFalse(Files.exists(label));
    }

    @Test void identicalTargetsAreRejectedBeforePublishing() throws Exception {
        Path picking=directory.resolve("picking.pdf"), staged=directory.resolve("staged");
        Files.writeString(picking,"previous picking"); Files.writeString(staged,"new data");
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                staged.toFile(),picking.toFile(),staged.toFile(),picking.toFile(),
                (source,target)->fail("must not publish into the same file")));
        assertEquals("previous picking",Files.readString(picking));
    }

    @Test void filesystemRootIsRejectedAsTargetWithoutNullPointer() throws Exception {
        Path root=directory.toAbsolutePath().getRoot(), staged=directory.resolve("staged");
        Files.writeString(staged,"new data");
        IOException error=assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                staged.toFile(),root.toFile(),staged.toFile(),directory.resolve("label.pdf").toFile(),
                (source,target)->fail("must not publish a directory")));
        assertEquals("OZON_PDF_TARGET_IS_DIRECTORY",error.getMessage());
    }

    @Test void hardLinkedTargetsAreRejectedBeforePublishing() throws Exception {
        Path picking=directory.resolve("picking.pdf"), label=directory.resolve("label.pdf"), staged=directory.resolve("staged");
        Files.writeString(picking,"previous picking"); Files.writeString(staged,"new data");
        try {Files.createLink(label,picking);} catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Hard links unavailable in this test environment");
        }
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                staged.toFile(),picking.toFile(),staged.toFile(),label.toFile(),
                (source,target)->fail("must not publish into hard-linked files")));
        assertEquals("previous picking",Files.readString(picking));
    }

    @Test void directoryAliasesToSameNewTargetAreRejected() throws Exception {
        Path actual=Files.createDirectory(directory.resolve("actual"));
        Path alias=directory.resolve("alias"), staged=directory.resolve("staged");
        try {Files.createSymbolicLink(alias,actual);} catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable in this test environment");
        }
        Files.writeString(staged,"new data");
        assertThrows(IOException.class,()->OzonPrintBundleService.publishPdfPair(
                staged.toFile(),actual.resolve("same.pdf").toFile(),staged.toFile(),alias.resolve("same.pdf").toFile(),
                (source,target)->fail("must not publish into aliased files")));
        assertFalse(Files.exists(actual.resolve("same.pdf")));
    }
}
