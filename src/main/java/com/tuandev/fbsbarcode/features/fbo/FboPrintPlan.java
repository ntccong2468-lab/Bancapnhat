package com.tuandev.fbsbarcode.features.fbo;

import com.tuandev.fbsbarcode.models.Kiz;

import java.util.List;

public record FboPrintPlan(List<FboPrintPage> pages, List<Kiz> usedKizs) {
}
