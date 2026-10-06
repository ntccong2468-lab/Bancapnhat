package com.tuandev.fbsbarcode.shared;

import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class I18nService {
    private static final String BUNDLE_BASE = "com.tuandev.fbsbarcode.i18n.messages";
    private static final I18nService INSTANCE = new I18nService();

    private final List<Consumer<AppLanguage>> listeners = new CopyOnWriteArrayList<>();
    private volatile AppLanguage currentLanguage;
    private volatile ResourceBundle bundle;

    private I18nService() {
        String configuredLanguage = null;
        try {
            configuredLanguage = ConfigService.getAppLanguage();
        } catch (Exception ignored) {
        }
        currentLanguage = AppLanguage.fromCode(configuredLanguage);
        bundle = loadBundle(currentLanguage);
    }

    public static I18nService getInstance() {
        return INSTANCE;
    }

    public AppLanguage getCurrentLanguage() {
        return currentLanguage;
    }

    public void setLanguage(AppLanguage language) {
        AppLanguage resolved = language == null ? AppLanguage.RU : language;
        if (resolved == currentLanguage) {
            return;
        }
        currentLanguage = resolved;
        bundle = loadBundle(resolved);
        ConfigService.setAppLanguage(resolved.code());
        for (Consumer<AppLanguage> listener : listeners) {
            listener.accept(resolved);
        }
    }

    public void addListener(Consumer<AppLanguage> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AppLanguage> listener) {
        listeners.remove(listener);
    }

    public String tr(String key) {
        return tr(key, key);
    }

    public String tr(String key, String fallback) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException ex) {
            return fallback;
        }
    }

    public ResourceBundle getBundle() {
        return bundle;
    }

    private ResourceBundle loadBundle(AppLanguage language) {
        return ResourceBundle.getBundle(BUNDLE_BASE, language.toLocale());
    }
}
