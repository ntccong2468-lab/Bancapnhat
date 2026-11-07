package com.vncode.app.integration.ozon;

import java.io.IOException;

/** Exact rejections exhausted this turn's budget; other postings run before this job resumes. */
public final class OzonKizReplacementDeferredException extends IOException {
    public OzonKizReplacementDeferredException() {
        super("OZON_ACCEPT_KIZ_REJECTED");
    }
}
