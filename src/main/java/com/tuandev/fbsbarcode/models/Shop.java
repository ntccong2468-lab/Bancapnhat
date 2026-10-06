package com.tuandev.fbsbarcode.models;

import com.tuandev.fbsbarcode.integration.marketplace.Marketplace;

public class Shop {
    private int id;
    private String name;
    private Marketplace marketplace;
    private String clientId;
    private String apiKey;

    public Shop() {
        this.marketplace = Marketplace.WILDBERRIES;
    }

    public Shop(String name, String apiKey) {
        this(0, name, Marketplace.WILDBERRIES, null, apiKey);
    }

    public Shop(int id, String name, String apiKey) {
        this(id, name, Marketplace.WILDBERRIES, null, apiKey);
    }

    public Shop(String name, Marketplace marketplace, String clientId, String apiKey) {
        this(0, name, marketplace, clientId, apiKey);
    }

    public Shop(int id, String name, Marketplace marketplace, String clientId, String apiKey) {
        this.id = id;
        this.name = name;
        this.marketplace = marketplace == null ? Marketplace.WILDBERRIES : marketplace;
        this.clientId = this.marketplace == Marketplace.OZON ? normalizeNullable(clientId) : null;
        this.apiKey = apiKey;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Marketplace getMarketplace() {
        return marketplace == null ? Marketplace.WILDBERRIES : marketplace;
    }

    public void setMarketplace(Marketplace marketplace) {
        Marketplace normalized = marketplace == null ? Marketplace.WILDBERRIES : marketplace;
        if (id > 0 && this.marketplace != null && this.marketplace != normalized) {
            throw new IllegalStateException("Marketplace cannot be changed after a shop is created.");
        }
        this.marketplace = normalized;
        if (normalized == Marketplace.WILDBERRIES) {
            this.clientId = null;
        }
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = getMarketplace() == Marketplace.OZON ? normalizeNullable(clientId) : null;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isCredentialConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return getMarketplace() != Marketplace.OZON || (clientId != null && !clientId.isBlank());
    }

    public void validateForCreate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Shop name is required.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(getMarketplace() == Marketplace.OZON
                    ? "Ozon API key is required." : "Wildberries API token is required.");
        }
        if (getMarketplace() == Marketplace.OZON && (clientId == null || clientId.isBlank())) {
            throw new IllegalArgumentException("Ozon Client ID is required.");
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "Shop{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", marketplace=" + getMarketplace() +
                ", clientIdConfigured=" + (clientId != null && !clientId.isBlank()) +
                ", apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank()) +
                '}';
    }
}
