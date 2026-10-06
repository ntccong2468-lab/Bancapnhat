package com.tuandev.fbsbarcode.ui.controls;

import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Multi-select category filter behind a {@link MenuButton}: one checkbox per category
 * (the menu stays open while ticking), a clear-filter entry, and a selection counter in
 * the button caption. An empty selection means "no filter".
 */
public class CategoryFilterMenu {
    private final MenuButton button;
    private final Runnable onChange;
    private final Set<String> selected = new LinkedHashSet<>();
    private String baseText = "";
    private String emptyLabel = "";
    private String clearLabel = "";
    private List<String> categories = List.of();

    public CategoryFilterMenu(MenuButton button, Runnable onChange) {
        this.button = button;
        this.onChange = onChange;
        button.setDisable(true);
    }

    /** Localized captions; call again from applyTranslations on language change. */
    public void setTexts(String baseText, String emptyLabel, String clearLabel) {
        this.baseText = baseText;
        this.emptyLabel = emptyLabel;
        this.clearLabel = clearLabel;
        rebuildItems();
    }

    /** Distinct, sorted category values; a trailing "" entry represents products without a category. */
    public static List<String> distinctCategories(Collection<String> raw) {
        TreeSet<String> named = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean hasBlank = false;
        for (String category : raw) {
            if (category == null || category.isBlank()) hasBlank = true;
            else named.add(category.trim());
        }
        List<String> result = new ArrayList<>(named);
        if (hasBlank) result.add("");
        return result;
    }

    public void rebuild(Collection<String> rawCategories) {
        categories = distinctCategories(rawCategories);
        selected.retainAll(categories);
        rebuildItems();
    }

    /** True when no filter is active or the given category is among the ticked ones. */
    public boolean matches(String category) {
        return selected.isEmpty() || selected.contains(category == null ? "" : category.trim());
    }

    /** Immutable snapshot of the currently selected category values. */
    public Set<String> selectedCategories() {
        return Set.copyOf(selected);
    }

    public void clear() {
        if (selected.isEmpty()) return;
        selected.clear();
        rebuildItems();
    }

    private void rebuildItems() {
        button.getItems().clear();
        MenuItem clear = new MenuItem(clearLabel);
        clear.setDisable(selected.isEmpty());
        clear.setOnAction(event -> {
            clear();
            onChange.run();
        });
        button.getItems().addAll(clear, new SeparatorMenuItem());
        for (String category : categories) {
            String label = category.isEmpty() ? emptyLabel : category;
            CheckBox check = new CheckBox(label);
            // Long category names must not stretch the popup across the screen: cap width, ellipsize,
            // and keep the full text reachable via tooltip.
            check.setMaxWidth(260);
            check.setTooltip(new Tooltip(label));
            check.setSelected(selected.contains(category));
            check.selectedProperty().addListener((ignored, old, value) -> {
                if (value) selected.add(category);
                else selected.remove(category);
                clear.setDisable(selected.isEmpty());
                updateCaption();
                onChange.run();
            });
            CustomMenuItem item = new CustomMenuItem(check);
            item.setHideOnClick(false);
            button.getItems().add(item);
        }
        button.setDisable(categories.isEmpty());
        updateCaption();
    }

    private void updateCaption() {
        button.setText(selected.isEmpty() ? baseText : baseText + " (" + selected.size() + ")");
    }
}
