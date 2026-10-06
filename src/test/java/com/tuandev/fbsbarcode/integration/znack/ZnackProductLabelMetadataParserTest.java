package com.tuandev.fbsbarcode.integration.znack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackKizLabelMetadata;
import org.junit.jupiter.api.Test;

class ZnackProductLabelMetadataParserTest {
    @Test
    void readsGenderAndClothingSizeFromNationalCatalogGoodAttributes() {
        var card = JsonParser.parseString("""
                {"good_attrs":[
                  {"attr_id":35,"attr_name":"Размер одежды / изделия","attr_value":"XL"},
                  {"attr_id":14013,"attr_name":"Целевой пол","attr_value":"МУЖСКОЙ"}
                ]}
                """).getAsJsonObject();

        ZnackKizLabelMetadata metadata = ZnackProductLabelMetadataParser.fromProductCard(card);

        assertEquals("МУЖСКОЙ", metadata.gender());
        assertEquals("XL", metadata.size());
    }

    @Test
    void fallsBackToFootwearAndCompatibleAttributeNames() {
        var footwear = JsonParser.parseString("""
                {"goodAttrs":[
                  {"attrId":13886,"attrName":"Размер в штихмассовой системе","attrValue":"26"},
                  {"attrId":99999,"attrName":"Пол","attrValue":"ДЕТСКИЙ"}
                ]}
                """).getAsJsonObject();

        ZnackKizLabelMetadata metadata = ZnackProductLabelMetadataParser.fromProductCard(footwear);

        assertEquals("ДЕТСКИЙ", metadata.gender());
        assertEquals("26", metadata.size());
    }

    @Test
    void readsGenderAndSizeFromCatalogUiBusinessLayer() {
        var card = JsonParser.parseString("""
                {"businessLayer":{"attrGroup":[{"attributes":[
                  {"id":14013,"name":"Целевой пол","value":"МУЖСКОЙ"},
                  {"id":35,"name":"Размер одежды / изделия","value":"48-56"}
                ]}]}}
                """).getAsJsonObject();

        ZnackKizLabelMetadata metadata = ZnackProductLabelMetadataParser.fromProductCard(card);

        assertEquals("МУЖСКОЙ", metadata.gender());
        assertEquals("48-56", metadata.size());
    }
}
