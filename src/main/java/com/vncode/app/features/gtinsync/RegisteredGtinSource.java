package com.vncode.app.features.gtinsync;
import java.util.List;
import com.vncode.app.features.gtinsync.GtinSyncModels.RegisteredGtin;
public interface RegisteredGtinSource { List<RegisteredGtin> read(int shopId) throws Exception; }
