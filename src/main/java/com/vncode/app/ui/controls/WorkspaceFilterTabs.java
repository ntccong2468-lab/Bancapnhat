package com.vncode.app.ui.controls;

import java.util.Objects;
import java.util.function.Function;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;

/** A compact tab presentation of an existing filter; its value remains the source of truth. */
public final class WorkspaceFilterTabs<T> extends FlowPane {
    private final ComboBox<T> filter;
    private final Function<T, String> label;

    public WorkspaceFilterTabs(ComboBox<T> filter, Function<T, String> label) {
        super(4, 0);
        this.filter = Objects.requireNonNull(filter);
        this.label = Objects.requireNonNull(label);
        getStyleClass().add("workspace-tab-strip");
        setAlignment(Pos.CENTER_LEFT);
        setMinWidth(0);
        filter.setVisible(false);
        filter.setManaged(false);
        filter.getItems().addListener((ListChangeListener<T>) change -> refresh());
        filter.valueProperty().addListener((observable, previous, current) -> updateSelection());
        disableProperty().bind(filter.disableProperty());
        refresh();
    }

    public void refresh() {
        getChildren().clear();
        for (T value : filter.getItems()) {
            var button = new ToggleButton(label.apply(value));
            button.setUserData(value);
            button.getStyleClass().add("workspace-filter-tab");
            button.setOnAction(event -> {
                filter.setValue(value);
                updateSelection();
            });
            getChildren().add(button);
        }
        updateSelection();
    }

    private void updateSelection() {
        for (var node : getChildren()) {
            if (node instanceof ToggleButton button) {
                button.setSelected(Objects.equals(button.getUserData(), filter.getValue()));
            }
        }
    }
}
