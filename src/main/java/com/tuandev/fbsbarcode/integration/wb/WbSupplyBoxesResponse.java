package com.tuandev.fbsbarcode.integration.wb;

import java.util.Collections;
import java.util.List;

public class WbSupplyBoxesResponse {
    private List<WbSupplyBoxDto> boxes;
    private List<WbSupplyBoxDto> trbxes;

    public List<WbSupplyBoxDto> getBoxes() {
        if (boxes != null) {
            return boxes;
        }
        return trbxes == null ? Collections.emptyList() : trbxes;
    }
}
