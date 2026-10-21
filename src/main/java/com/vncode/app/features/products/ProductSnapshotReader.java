package com.vncode.app.features.products;

import com.vncode.app.models.Shop;
import java.io.IOException;
@FunctionalInterface
public interface ProductSnapshotReader { ProductSnapshot read(Shop shop,ProductKey key) throws IOException; }
