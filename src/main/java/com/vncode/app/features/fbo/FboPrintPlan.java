package com.vncode.app.features.fbo;

import com.vncode.app.models.Kiz;

import java.util.List;

public record FboPrintPlan(List<FboPrintPage> pages, List<Kiz> usedKizs) {
}
