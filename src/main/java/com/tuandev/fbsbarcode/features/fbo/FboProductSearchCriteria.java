package com.tuandev.fbsbarcode.features.fbo;

import java.util.List;

public record FboProductSearchCriteria(
        int shopId,
        String query,
        List<String> subjectNames,
        int limit,
        int offset
) {
}
