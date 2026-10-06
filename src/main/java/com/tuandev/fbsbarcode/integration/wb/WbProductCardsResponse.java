package com.tuandev.fbsbarcode.integration.wb;

import java.util.List;

public class WbProductCardsResponse {
    private List<WbProductCard> cards;
    private Cursor cursor;

    public List<WbProductCard> getCards() {
        return cards;
    }

    public Cursor getCursor() {
        return cursor;
    }

    public static class Cursor {
        private String updatedAt;
        private Long nmID;
        private Integer total;

        public String getUpdatedAt() {
            return updatedAt;
        }

        public Long getNmID() {
            return nmID;
        }

        public Integer getTotal() {
            return total;
        }
    }
}
