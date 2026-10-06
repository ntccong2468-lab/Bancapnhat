package com.tuandev.fbsbarcode.features.fbosupply;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FboSupplyStatusMapperTest {
    @Test
    void mapsEveryWildberriesFbwStatus() {
        assertEquals(FboSupplyStatusGroup.PREPARING, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "1"));
        assertEquals(FboSupplyStatusGroup.READY, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "2"));
        assertEquals(FboSupplyStatusGroup.READY, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "3"));
        assertEquals(FboSupplyStatusGroup.IN_PROGRESS, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "4"));
        assertEquals(FboSupplyStatusGroup.COMPLETED, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "5"));
        assertEquals(FboSupplyStatusGroup.IN_PROGRESS, FboSupplyStatusMapper.map(Marketplace.WILDBERRIES, "6"));
    }

    @Test
    void mapsOzonStatesAndKeepsFutureValuesVisible() {
        assertEquals(FboSupplyStatusGroup.PREPARING,
                FboSupplyStatusMapper.map(Marketplace.OZON, "DATA_FILLING"));
        assertEquals(FboSupplyStatusGroup.READY,
                FboSupplyStatusMapper.map(Marketplace.OZON, "READY_TO_SUPPLY"));
        assertEquals(FboSupplyStatusGroup.IN_PROGRESS,
                FboSupplyStatusMapper.map(Marketplace.OZON, "IN_TRANSIT"));
        assertEquals(FboSupplyStatusGroup.REVIEW,
                FboSupplyStatusMapper.map(Marketplace.OZON, "REPORTS_CONFIRMATION_AWAITING"));
        assertEquals(FboSupplyStatusGroup.ISSUE,
                FboSupplyStatusMapper.map(Marketplace.OZON, "OVERDUE"));
        assertEquals(FboSupplyStatusGroup.CANCELLED,
                FboSupplyStatusMapper.map(Marketplace.OZON, "CANCELLED"));
        assertEquals(FboSupplyStatusGroup.UNKNOWN,
                FboSupplyStatusMapper.map(Marketplace.OZON, "FUTURE_STATE"));
    }
}
