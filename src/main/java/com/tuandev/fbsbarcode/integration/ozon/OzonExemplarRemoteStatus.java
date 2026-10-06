package com.tuandev.fbsbarcode.integration.ozon;

public record OzonExemplarRemoteStatus(
        boolean hasRemoteMarks,
        boolean allMarksPassed,
        boolean rejected,
        boolean shipAvailable,
        String safeStatus) {
    public boolean accepted() {
        return hasRemoteMarks && allMarksPassed && shipAvailable && !rejected;
    }
}
