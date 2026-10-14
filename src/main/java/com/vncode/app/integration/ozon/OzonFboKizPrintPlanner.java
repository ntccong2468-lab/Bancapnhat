package com.vncode.app.integration.ozon;

import com.vncode.app.features.fbo.*;
import com.vncode.app.integration.marketplace.Marketplace;
import java.util.List;

/** Ozon uses its native SKU/offer identity and the shared atomic FBO reservation plan. */
public final class OzonFboKizPrintPlanner {
    private final FboKizPrintPlanner planner=new FboKizPrintPlanner();
    public FboPrintPlan plan(int shopId,List<FboBarcodePrintItem> items){return planner.plan(shopId,Marketplace.OZON,items);}
}
