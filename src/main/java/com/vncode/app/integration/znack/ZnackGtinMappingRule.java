package com.vncode.app.integration.znack;

import java.time.Instant;

public record ZnackGtinMappingRule(int shopId, String gtin, String subjectName, String genderValue,
                                   boolean wildcardGender, Instant updatedAt) {
}
