package com.tuandev.fbsbarcode.features.supply;

import com.tuandev.fbsbarcode.models.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderSortingServiceTest {
    private final OrderSortingService service = new OrderSortingService();

    @Test
    void shouldSortBySubjectArticleColorAndSizeInConfiguredOrder() {
        Order a = order(3L, "B", "A-2", "Red", "L");
        Order b = order(2L, "A", "B-1", "Black", "M");
        Order c = order(1L, "A", "A-1", "White", "S");

        List<Order> sorted = service.sort(
                List.of(a, b, c),
                new OrderSortOptions(true, true, true, true)
        );

        assertEquals(List.of(c, b, a), sorted);
    }

    @Test
    void shouldFallbackToOrderIdWhenNoSortOptionSelected() {
        Order a = order(3L, "B", "A-2", "Red", "L");
        Order b = order(2L, "A", "B-1", "Black", "M");
        Order c = order(1L, "A", "A-1", "White", "S");

        List<Order> sorted = service.sort(
                List.of(a, b, c),
                new OrderSortOptions(false, false, false, false)
        );

        assertEquals(List.of(c, b, a), sorted);
    }

    private Order order(long id, String subject, String article, String color, String size) {
        Order order = new Order();
        order.setId(id);
        order.setSubjectName(subject);
        order.setArticle(article);
        order.setColor(color);
        order.setSize(size);
        return order;
    }
}
