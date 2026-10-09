package com.vncode.app.features.tnved;

import java.time.LocalDate;

public final class TnvedModels {
    private TnvedModels() { }
    public record Version(String label, String sourceUrl, LocalDate validFrom) { }
    public record Node(String code, String parentCode, String type, String section,
                       String nameRu, String nameVi, String descriptionRu, String notes,
                       boolean leaf, boolean active, LocalDate validFrom, LocalDate validTo,
                       String sourceUrl) {
        @Override public String toString() { return code + " — " + nameVi; }
    }
}
