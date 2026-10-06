package com.tuandev.fbsbarcode.integration.ozon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OzonProductCardAttributeParserTest {
    @Test
    void resolvesArticleColorAndRussianSizeFromProductCardMetadata() {
        var cardsResponse = JsonParser.parseString("""
                {"result":[{"id":"101","offer_id":"fallback-offer",
                  "description_category_id":"17000001","type_id":"90001","attributes":[
                    {"id":10096,"values":[{"value":"глубокий черный"}]},
                    {"id":10097,"values":[{"value":"черный"}]},
                    {"id":4295,"values":[{"value":"176"}]},
                    {"id":9533,"values":[{"value":"XXL"}]},
                    {"id":4508,"values":[{"value":"170"}]},
                    {"id":9163,"values":[{"value":"Женский"}]},
                    {"id":9024,"values":[{"value":"seller-article"}]}
                  ]}]}
                """).getAsJsonObject();
        var definitionsResponse = JsonParser.parseString("""
                {"result":[
                  {"id":10096,"name":"Цвет товара"},
                  {"id":10097,"name":"Название цвета"},
                  {"id":4295,"name":"Российский размер"},
                  {"id":9533,"name":"Размер производителя"},
                  {"id":4508,"name":"Размер на модели"},
                  {"id":9163,"name":"Пол"},
                  {"id":9024,"name":"Код продавца"}
                ]}
                """).getAsJsonObject();

        OzonProductCardAttributeParser.Card card =
                OzonProductCardAttributeParser.parseCards(cardsResponse).getFirst();
        OzonProductCardAttributeParser.Resolved result = OzonProductCardAttributeParser.resolve(
                card, OzonProductCardAttributeParser.parseDefinitionNames(definitionsResponse), "Sportswear");

        assertEquals("seller-article", result.article());
        assertEquals("глубокий черный", result.color());
        assertEquals("176", result.size());
        assertEquals("Sportswear", result.category());
        assertEquals("Женский", result.gender());
    }

    @Test
    void neverTreatsModelSizeAsProductSize() {
        var cardsResponse = JsonParser.parseString("""
                {"result":[{"id":"102","offer_id":"article-102",
                  "description_category_id":"17000001","type_id":"90001","attributes":[
                    {"id":4508,"values":[{"value":"190"}]},
                    {"id":9533,"values":[{"value":"L"}]}
                  ]}]}
                """).getAsJsonObject();
        OzonProductCardAttributeParser.Card card =
                OzonProductCardAttributeParser.parseCards(cardsResponse).getFirst();

        OzonProductCardAttributeParser.Resolved result = OzonProductCardAttributeParser.resolve(
                card, Map.of("4508", "Размер на модели", "9533", "Размер производителя"));

        assertEquals("L", result.size());
        assertEquals("article-102", result.article());
    }


    @Test
    void categoryTreeMapsProductTypeToVisibleCategoryName() {
        var tree = JsonParser.parseString("""
                {"result":[{"description_category_id":17000001,"category_name":"Clothing","children":[
                  {"description_category_id":17000002,"category_name":"Apparel","children":[
                    {"type_id":90001,"type_name":"Sportswear","children":[]}
                  ]}
                ]}]}
                """).getAsJsonObject();

        assertEquals("Sportswear", OzonProductCategoryTree.parse(tree).get(
                new OzonProductCardAttributeParser.CategoryKey("17000001", "90001")));
    }
}
