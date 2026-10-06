package com.tuandev.fbsbarcode.features.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tuandev.fbsbarcode.features.kiz.KizService;
import com.tuandev.fbsbarcode.models.Kiz;
import com.tuandev.fbsbarcode.models.Shop;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KizAttachmentCoordinatorTest {
    private static final String SECRET = "upstream-secret-response-body";

    @Test
    void runsAttachmentsOnTheInjectedExecutorAndPublishesSafeSuccessProgress() {
        List<Runnable> queued = new ArrayList<>();
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        KizAttachmentCoordinator coordinator = new KizAttachmentCoordinator(
                queued::add,
                new KizAttachmentCoordinator.AttachmentClient() {
                    @Override
                    public KizService.RemoveMetaResult remove(String token, long orderId) {
                        throw new AssertionError("remove should not be called");
                    }

                    @Override
                    public KizService.AttachCodeResult attach(String token, long orderId, String code) {
                        assertEquals("token", token);
                        assertEquals(101L, orderId);
                        assertEquals("KIZ-101", code);
                        return new KizService.AttachCodeResult(true, 204, "");
                    }
                });
        coordinator.addListener(progress::add);
        Shop shop = new Shop(7, "Main", "token");
        Kiz source = new Kiz(1, "KIZ-101", "reservation-101");

        coordinator.enqueue(shop, "SUP-1", "Supply", List.of(
                new OrderExportWorkflow.KizAttachmentAssignment(101L, "KIZ-101", source, false)));

        assertEquals(1, queued.size());
        assertTrue(coordinator.hasActiveJobForSupply(7, "SUP-1"));
        queued.getFirst().run();

        assertFalse(coordinator.hasActiveJobs());
        KizAttachmentCoordinator.KizAttachmentProgress completed = progress.getLast();
        assertFalse(completed.active());
        assertEquals(1, completed.completed());
        assertEquals(List.of("KIZ-101"), completed.successfulKizCodes());
        assertTrue(completed.failures().isEmpty());
        assertNull(coordinator.getAttachmentError(101L));
    }

    @Test
    void redactsUpstreamBodiesFromFailureStateAndListenerProgress() {
        List<KizAttachmentCoordinator.KizAttachmentProgress> progress = new ArrayList<>();
        KizAttachmentCoordinator coordinator = new KizAttachmentCoordinator(
                Runnable::run,
                new KizAttachmentCoordinator.AttachmentClient() {
                    @Override
                    public KizService.RemoveMetaResult remove(String token, long orderId) {
                        return new KizService.RemoveMetaResult(false, 500, SECRET);
                    }

                    @Override
                    public KizService.AttachCodeResult attach(String token, long orderId, String code) {
                        throw new AssertionError("attach should not run after remove failure");
                    }
                });
        coordinator.addListener(progress::add);

        coordinator.enqueue(new Shop(7, "Main", "token"), "SUP-1", "Supply", List.of(
                new OrderExportWorkflow.KizAttachmentAssignment(
                        101L, "KIZ-101", new Kiz(1, "KIZ-101", "reservation-101"), true)));

        String stored = coordinator.getAttachmentError(101L);
        assertTrue(stored.contains("HTTP 500"));
        assertFalse(stored.contains(SECRET));
        assertFalse(progress.toString().contains(SECRET));
        assertFalse(coordinator.hasActiveJobs());
    }
}
