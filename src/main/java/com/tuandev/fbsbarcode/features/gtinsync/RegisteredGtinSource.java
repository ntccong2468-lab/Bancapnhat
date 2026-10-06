package com.tuandev.fbsbarcode.features.gtinsync;
import java.util.List;
import com.tuandev.fbsbarcode.features.gtinsync.GtinSyncModels.RegisteredGtin;
public interface RegisteredGtinSource { List<RegisteredGtin> read(int shopId) throws Exception; }
