package com.tuandev.fbsbarcode.features.supply;

import com.tuandev.fbsbarcode.models.Order;
import com.tuandev.fbsbarcode.shared.NaturalOrderComparator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public class OrderSortingService {
    public List<Order> sort(List<Order> orders, OrderSortOptions options) {
        List<Order> sortedOrders = new ArrayList<>(orders == null ? List.of() : orders);
        sortedOrders.sort(buildComparator(options));
        return sortedOrders;
    }

    private Comparator<Order> buildComparator(OrderSortOptions options) {
        Comparator<Order> comparator = Comparator.comparing(Order::getId, Comparator.nullsLast(Long::compareTo));
        if (options.bySize()) {
            comparator = compareByText(comparator, Order::getSize);
        }
        if (options.byColor()) {
            comparator = compareByText(comparator, Order::getColor);
        }
        if (options.byArticle()) {
            comparator = compareByText(comparator, Order::getArticle);
        }
        if (options.bySubject()) {
            comparator = compareByText(comparator, Order::getSubjectName);
        }
        return comparator;
    }

    private Comparator<Order> compareByText(Comparator<Order> fallback, Function<Order, String> extractor) {
        return Comparator.comparing(extractor, (left, right) ->
                        NaturalOrderComparator.compareIgnoreCase(blankToTilde(left), blankToTilde(right)))
                .thenComparing(fallback);
    }

    private String blankToTilde(String value) {
        return value == null || value.isBlank() ? "~~~~" : value;
    }
}
