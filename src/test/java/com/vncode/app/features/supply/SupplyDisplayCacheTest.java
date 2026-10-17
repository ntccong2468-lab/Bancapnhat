package com.vncode.app.features.supply;

import com.vncode.app.models.Order;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SupplyDisplayCacheTest {
    private Order row(long id,long product,String size){var row=new Order();row.setId(id);row.setNmId(product);row.setSize(size);row.setColor("Blue");row.setImageUrl("fixture-image");return row;}
    @Test void localStatusRefreshKeepsExistingPresentationForSameOrderAndVariant(){
        var old=row(1,101,"M");old.setSticker("fixture sticker");old.setStickerCode("fixture barcode");old.setImage(new byte[]{1,2,3});old.setKiz("synthetic-display-only");old.setRequiresKiz(true);
        var fresh=row(1,101,"M");fresh.setSupplierStatus("complete");SupplyLoadWorkflow.preserveDisplayCache(List.of(old),List.of(fresh));
        assertEquals("complete",fresh.getSupplierStatus());assertEquals(old.getSticker(),fresh.getSticker());assertEquals(old.getStickerCode(),fresh.getStickerCode());assertArrayEquals(old.getImage(),fresh.getImage());assertEquals(old.getKiz(),fresh.getKiz());assertTrue(fresh.isRequiresKiz());
    }
    @Test void anotherVariantOrOrderCannotInheritKizOrUnrelatedImage(){
        var old=row(1,101,"M");old.setKiz("synthetic-display-only");old.setImage(new byte[]{1});
        var changed=row(1,101,"XL");changed.setImageUrl("different-image");var other=row(2,101,"M");var changedCode=row(1,101,"M");changedCode.setBarcode("different-gtin");
        SupplyLoadWorkflow.preserveDisplayCache(List.of(old),List.of(changed,other,changedCode));
        assertNull(changed.getKiz());assertNull(changed.getImage());assertNull(other.getKiz());assertNull(changedCode.getKiz());
    }
    @Test void freshEvidenceAlwaysWinsOverDisplayCache(){
        var old=row(1,101,"M");old.setKiz("old-synthetic");old.setSticker("old sticker");
        var fresh=row(1,101,"M");fresh.setKiz("fresh-synthetic");fresh.setSticker("fresh sticker");
        SupplyLoadWorkflow.preserveDisplayCache(List.of(old),List.of(fresh));assertEquals("fresh-synthetic",fresh.getKiz());assertEquals("fresh sticker",fresh.getSticker());
    }
}
