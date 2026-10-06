package com.tuandev.fbsbarcode.ui.print;

import com.tuandev.fbsbarcode.features.print.PrintElementType;
import com.tuandev.fbsbarcode.features.fbo.FboPrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTemplate;
import com.tuandev.fbsbarcode.features.print.PrintTemplateElement;
import com.tuandev.fbsbarcode.features.print.PrintTemplateService;
import com.tuandev.fbsbarcode.features.print.PrintTextAlign;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

public class PrintTemplateDesignerController implements Initializable {
    private static final double PREVIEW_SCALE = 3d;
    private static final double DRAG_THRESHOLD_PX = 3d;

    private PrintTemplateService templateService = new PrintTemplateService();
    private List<PrintTemplateService.ElementPaletteItem> paletteItems = List.of();
    private final I18nService i18n = I18nService.getInstance();

    @FXML
    private ComboBox<TemplateMode> templateTypeComboBox;
    @FXML
    private ComboBox<PrintTemplate> templateComboBox;
    @FXML
    private ComboBox<PrintTemplateService.ElementPaletteItem> addElementComboBox;
    @FXML
    private ListView<PrintTemplateElement> elementListView;
    @FXML
    private AnchorPane previewPane;
    @FXML
    private Pane gridPane;
    @FXML
    private Pane designPane;
    @FXML
    private HBox topRulerBox;
    @FXML
    private VBox leftRulerBox;
    @FXML
    private Label templateMetaLabel;
    @FXML
    private TextField labelField;
    @FXML
    private TextField prefixField;
    @FXML
    private Label contentLabel;
    @FXML
    private TextField contentField;
    @FXML
    private CheckBox visibleCheckBox;
    @FXML
    private TextField xField;
    @FXML
    private TextField yField;
    @FXML
    private TextField widthField;
    @FXML
    private TextField heightField;
    @FXML
    private TextField fontSizeField;
    @FXML
    private Label fieldValidationLabel;
    @FXML
    private CheckBox snapToGridCheckBox;
    @FXML
    private CheckBox boldCheckBox;
    @FXML
    private CheckBox humanReadableCheckBox;
    @FXML
    private ComboBox<PrintTextAlign> alignComboBox;
    @FXML
    private Button copyElementButton;
    @FXML
    private Button pasteElementButton;
    @FXML
    private Button deleteElementButton;

    private PrintTemplate workingTemplate;
    private PrintTemplateElement selectedElement;
    private PrintTemplateElement clipboardElement;
    private boolean updatingFields;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        previewPane.setPrefSize(
                PrintTemplateService.PAGE_WIDTH * PREVIEW_SCALE,
                PrintTemplateService.PAGE_HEIGHT * PREVIEW_SCALE
        );
        previewPane.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        previewPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        previewPane.setFocusTraversable(true);
        designPane.setFocusTraversable(true);
        gridPane.setPrefSize(previewPane.getPrefWidth(), previewPane.getPrefHeight());
        designPane.setPrefSize(previewPane.getPrefWidth(), previewPane.getPrefHeight());
        AnchorPane.setTopAnchor(gridPane, 0d);
        AnchorPane.setLeftAnchor(gridPane, 0d);
        AnchorPane.setTopAnchor(designPane, 0d);
        AnchorPane.setLeftAnchor(designPane, 0d);
        renderRulersAndGrid();
        bindEditorShortcuts(previewPane);
        bindEditorShortcuts(designPane);
        bindEditorShortcuts(elementListView);

        templateTypeComboBox.getItems().setAll(TemplateMode.values());
        templateTypeComboBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(TemplateMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label(i18n));
            }
        });
        templateTypeComboBox.setButtonCell(templateTypeComboBox.getCellFactory().call(null));
        templateTypeComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                switchTemplateMode(newValue);
            }
        });

        templateComboBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PrintTemplate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.getName() + (item.isDefaultTemplate() ? " (" + i18n.tr("template.default_suffix") + ")" : ""));
            }
        });
        templateComboBox.setButtonCell(templateComboBox.getCellFactory().call(null));

        addElementComboBox.setCellFactory(list -> new ListCell<>() {
            @Override
                    protected void updateItem(PrintTemplateService.ElementPaletteItem item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.label());
                    }
                });
        addElementComboBox.setButtonCell(addElementComboBox.getCellFactory().call(null));
        addElementComboBox.setPromptText(i18n.tr("template.add_element_prompt"));

        elementListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PrintTemplateElement item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.getLabel() + (item.isVisible() ? "" : " (" + i18n.tr("template.hidden_suffix") + ")"));
            }
        });
        alignComboBox.getItems().setAll(PrintTextAlign.values());

        elementListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> selectElement(newValue));
        templateComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                workingTemplate = copyTemplate(newValue);
                selectedElement = null;
                refreshTemplateView();
            }
        });
        addElementComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                addSelectedPaletteElement(newValue);
                addElementComboBox.getSelectionModel().clearSelection();
            }
        });

        setupPropertyBindings();
        templateTypeComboBox.getSelectionModel().select(TemplateMode.FBS);
    }

    private void switchTemplateMode(TemplateMode mode) {
        templateService = mode == TemplateMode.FBO ? new FboPrintTemplateService() : new PrintTemplateService();
        paletteItems = templateService.getPaletteItems();
        addElementComboBox.getItems().setAll(paletteItems);
        workingTemplate = null;
        selectedElement = null;
        clipboardElement = null;
        reloadTemplates();
    }

    @FXML
    private void onCreateTemplate() {
        promptTemplateName(
                i18n.tr("template.dialog.create.title"),
                i18n.tr("template.dialog.create.header"),
                i18n.tr("template.dialog.create.initial"),
                name -> {
            PrintTemplate template = templateService.createTemplate(name);
            reloadTemplates();
            selectTemplate(template.getId());
        });
    }

    @FXML
    private void onDuplicateTemplate() {
        if (workingTemplate == null) {
            return;
        }
        promptTemplateName(
                i18n.tr("template.dialog.duplicate.title"),
                i18n.tr("template.dialog.duplicate.header"),
                workingTemplate.getName() + " " + i18n.tr("template.dialog.duplicate.suffix"),
                name -> {
            PrintTemplate template = templateService.duplicateTemplate(workingTemplate.getId(), name);
            reloadTemplates();
            selectTemplate(template.getId());
        });
    }

    @FXML
    private void onRenameTemplate() {
        if (workingTemplate == null) {
            return;
        }
        promptTemplateName(
                i18n.tr("template.dialog.rename.title"),
                i18n.tr("template.dialog.rename.header"),
                workingTemplate.getName(),
                name -> {
            templateService.renameTemplate(workingTemplate.getId(), name);
            reloadTemplates();
            selectTemplate(workingTemplate.getId());
        });
    }

    @FXML
    private void onDeleteTemplate() {
        if (workingTemplate == null) {
            return;
        }
        try {
            templateService.deleteTemplate(workingTemplate.getId());
            reloadTemplates();
        } catch (RuntimeException ex) {
            AlertService.showError(ex.getMessage());
        }
    }

    @FXML
    private void onSetDefaultTemplate() {
        if (workingTemplate == null) {
            return;
        }
        templateService.setDefaultTemplate(workingTemplate.getId());
        reloadTemplates();
        selectTemplate(workingTemplate.getId());
    }

    @FXML
    private void onResetTemplate() {
        if (workingTemplate == null) {
            return;
        }
        templateService.resetTemplateToSystemDefault(workingTemplate.getId());
        reloadTemplates();
        selectTemplate(workingTemplate.getId());
    }

    @FXML
    private void onSaveTemplate() {
        if (workingTemplate == null) {
            return;
        }
        try {
            templateService.saveTemplate(workingTemplate);
            reloadTemplates();
            selectTemplate(workingTemplate.getId());
        } catch (RuntimeException ex) {
            AlertService.showError(ex.getMessage());
        }
    }

    @FXML
    private void onAddElement() {
        if (workingTemplate == null) {
            return;
        }
        PrintTemplateService.ElementPaletteItem paletteElement = addElementComboBox.getValue();
        if (paletteElement == null) {
            return;
        }
        addSelectedPaletteElement(paletteElement);
    }

    @FXML
    private void onDeleteElement() {
        if (workingTemplate == null || selectedElement == null) {
            return;
        }
        workingTemplate.getElements().removeIf(item -> Objects.equals(item.getId(), selectedElement.getId()));
        selectedElement = null;
        refreshTemplateView();
    }

    @FXML
    private void onCopyElement() {
        if (selectedElement == null) {
            return;
        }
        clipboardElement = deepCopyElement(selectedElement);
        updateActionButtons();
    }

    @FXML
    private void onPasteElement() {
        if (workingTemplate == null || clipboardElement == null) {
            return;
        }
        PrintTemplateElement pasted = deepCopyElement(clipboardElement);
        pasted.setId(newElementId());
        pasted.setLabel(nextPastedLabel(pasted.getLabel()));
        pasted.setX(clampAndSnapX(pasted.getX() + PrintTemplateService.POINTS_PER_MM, pasted.getWidth()));
        pasted.setY(clampAndSnapY(pasted.getY() + PrintTemplateService.POINTS_PER_MM, pasted.getHeight()));
        pasted.setZIndex(nextZIndex());
        workingTemplate.getElements().add(pasted);
        refreshTemplateView();
        selectElement(pasted);
        elementListView.getSelectionModel().select(pasted);
    }

    private void reloadTemplates() {
        List<PrintTemplate> templates = templateService.loadTemplates();
        templateComboBox.getItems().setAll(templates);
        if (!templates.isEmpty()) {
            PrintTemplate preferred = templates.stream()
                    .filter(PrintTemplate::isDefaultTemplate)
                    .findFirst()
                    .orElse(templates.getFirst());
            templateComboBox.getSelectionModel().select(preferred);
        }
    }

    private void addSelectedPaletteElement(PrintTemplateService.ElementPaletteItem paletteElement) {
        if (workingTemplate == null || paletteElement == null) {
            return;
        }
        workingTemplate.getElements().add(templateService.createElementFromPalette(paletteElement, workingTemplate.getElements().size() + 1));
        refreshTemplateView();
        elementListView.getSelectionModel().selectLast();
    }

    private void selectTemplate(int templateId) {
        templateComboBox.getItems().stream()
                .filter(item -> item.getId() == templateId)
                .findFirst()
                .ifPresent(item -> templateComboBox.getSelectionModel().select(item));
    }

    private void refreshTemplateView() {
        if (workingTemplate == null) {
            return;
        }
        workingTemplate.getElements().sort(Comparator.comparingInt(PrintTemplateElement::getZIndex));
        elementListView.getItems().setAll(workingTemplate.getElements());
        designPane.getChildren().clear();
        templateMetaLabel.setText(String.format(i18n.tr("template.meta"),
                workingTemplate.getPageWidth(),
                workingTemplate.getPageHeight(),
                workingTemplate.getElements().size()));

        for (PrintTemplateElement element : workingTemplate.getElements()) {
            Pane node = createPreviewNode(element);
            designPane.getChildren().add(node);
        }

        if (selectedElement != null) {
            selectElement(findElement(selectedElement.getId()));
        } else {
            updatePropertyEditor(null);
        }
        updateActionButtons();
    }

    private Pane createPreviewNode(PrintTemplateElement element) {
        StackPane node = new StackPane();
        node.setLayoutX(element.getX() * PREVIEW_SCALE);
        node.setLayoutY(element.getY() * PREVIEW_SCALE);
        node.setPrefWidth(Math.max(12, element.getWidth() * PREVIEW_SCALE));
        node.setPrefHeight(Math.max(8, element.getHeight() * PREVIEW_SCALE));
        node.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        node.setAlignment(previewAlignment(element));
        node.setCursor(Cursor.MOVE);
        node.setPadding(new Insets(1.5));
        node.setUserData(element.getType());
        applyNodeStyle(node, element.equals(selectedElement), element.isVisible());

        Label label = new Label(previewText(element));
        label.setWrapText(true);
        label.setMouseTransparent(true);
        label.setMaxWidth(Math.max(4, element.getWidth() * PREVIEW_SCALE - 3));
        label.setMaxHeight(Math.max(4, element.getHeight() * PREVIEW_SCALE - 3));
        label.setAlignment(previewAlignment(element));
        label.setTextAlignment(previewTextAlignment(element));
        StackPane.setAlignment(label, previewAlignment(element));
        applyPreviewLabelStyle(label, element);
        node.getChildren().add(label);
        addResizeHandles(node, element);

        final double[] start = new double[2];
        final boolean[] dragged = new boolean[1];
        node.setOnMousePressed(event -> {
            start[0] = event.getSceneX();
            start[1] = event.getSceneY();
            dragged[0] = false;
            selectElement(element);
            previewPane.requestFocus();
            event.consume();
        });
        node.setOnMouseDragged(event -> {
            double dx = (event.getSceneX() - start[0]) / PREVIEW_SCALE;
            double dy = (event.getSceneY() - start[1]) / PREVIEW_SCALE;
            if (!dragged[0]
                    && Math.abs(event.getSceneX() - start[0]) < DRAG_THRESHOLD_PX
                    && Math.abs(event.getSceneY() - start[1]) < DRAG_THRESHOLD_PX) {
                event.consume();
                return;
            }
            dragged[0] = true;
            start[0] = event.getSceneX();
            start[1] = event.getSceneY();
            element.setX(clamp(element.getX() + dx, 0, PrintTemplateService.PAGE_WIDTH - element.getWidth()));
            element.setY(clamp(element.getY() + dy, 0, PrintTemplateService.PAGE_HEIGHT - element.getHeight()));
            updateNodeGeometry(node, element);
            event.consume();
        });
        node.setOnMouseReleased(event -> {
            if (dragged[0]) {
                snapElementToGridIfEnabled(element);
                updateNodeGeometry(node, element);
                syncSelectedFieldsForGeometry(element);
                refreshNodeStyles();
            }
            event.consume();
        });
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            selectElement(element);
            previewPane.requestFocus();
            event.consume();
        });
        return node;
    }

    private void addResizeHandles(StackPane node, PrintTemplateElement element) {
        node.getChildren().add(createResizeHandle(node, element, HandlePosition.TOP_LEFT));
        node.getChildren().add(createResizeHandle(node, element, HandlePosition.TOP_RIGHT));
        node.getChildren().add(createResizeHandle(node, element, HandlePosition.BOTTOM_LEFT));
        node.getChildren().add(createResizeHandle(node, element, HandlePosition.BOTTOM_RIGHT));
    }

    private Pane createResizeHandle(StackPane owner, PrintTemplateElement element, HandlePosition position) {
        Pane handle = new Pane();
        handle.setPrefSize(10, 10);
        handle.setMinSize(10, 10);
        handle.setMaxSize(10, 10);
        handle.setStyle("-fx-background-color: white; -fx-border-color: #0d6efd; -fx-border-width: 1; -fx-background-radius: 100; -fx-border-radius: 100;");
        handle.setCursor(position.cursor);
        handle.setManaged(false);
        handle.setMouseTransparent(false);
        StackPane.setAlignment(handle, position.alignment);

        final double[] start = new double[2];
        final boolean[] resized = new boolean[1];
        handle.setOnMousePressed(event -> {
            start[0] = event.getSceneX();
            start[1] = event.getSceneY();
            resized[0] = false;
            selectElement(element);
            previewPane.requestFocus();
            event.consume();
        });
        handle.setOnMouseDragged(event -> {
            double dx = (event.getSceneX() - start[0]) / PREVIEW_SCALE;
            double dy = (event.getSceneY() - start[1]) / PREVIEW_SCALE;
            if (!resized[0]
                    && Math.abs(event.getSceneX() - start[0]) < DRAG_THRESHOLD_PX
                    && Math.abs(event.getSceneY() - start[1]) < DRAG_THRESHOLD_PX) {
                event.consume();
                return;
            }
            resized[0] = true;
            start[0] = event.getSceneX();
            start[1] = event.getSceneY();
            resizeElement(element, dx, dy, position);
            updateNodeGeometry(owner, element);
            event.consume();
        });
        handle.setOnMouseReleased(event -> {
            if (resized[0]) {
                snapElementToGridIfEnabled(element);
                updateNodeGeometry(owner, element);
                syncSelectedFieldsForGeometry(element);
                refreshNodeStyles();
            }
            event.consume();
        });
        return handle;
    }

    private void resizeElement(PrintTemplateElement element, double dx, double dy, HandlePosition position) {
        double x = element.getX();
        double y = element.getY();
        double width = element.getWidth();
        double height = element.getHeight();

        switch (position) {
            case TOP_LEFT -> {
                x += dx;
                y += dy;
                width -= dx;
                height -= dy;
            }
            case TOP_RIGHT -> {
                y += dy;
                width += dx;
                height -= dy;
            }
            case BOTTOM_LEFT -> {
                x += dx;
                width -= dx;
                height += dy;
            }
            case BOTTOM_RIGHT -> {
                width += dx;
                height += dy;
            }
        }

        if (width < 0) {
            x += width;
            width = 0;
        }
        if (height < 0) {
            y += height;
            height = 0;
        }

        x = clamp(x, 0, PrintTemplateService.PAGE_WIDTH);
        y = clamp(y, 0, PrintTemplateService.PAGE_HEIGHT);
        width = clamp(width, 0, PrintTemplateService.PAGE_WIDTH - x);
        height = clamp(height, 0, PrintTemplateService.PAGE_HEIGHT - y);

        element.setX(x);
        element.setY(y);
        element.setWidth(width);
        element.setHeight(height);
    }

    private void applyNodeStyle(Pane node, boolean selected, boolean visible) {
        String border = selected ? "#0d6efd" : "#94a3b8";
        String fill;
        if (!visible) {
            fill = "#f1f5f9";
        } else {
            fill = switch (selectedElementType(node)) {
                case KIZ_DATAMATRIX -> "#dcfce7";
                case BARCODE_CODE128 -> "#fef3c7";
                case STICKER_TAIL -> "#fee2e2";
                case SEPARATOR_LINE -> "#e2e8f0";
                case TEXT_FIELD -> "#dbeafe";
                case STATIC_TEXT -> "#f5d0fe";
            };
        }
        node.setStyle("-fx-background-color: " + fill + "; -fx-border-color: " + border + "; -fx-border-width: 1.2; -fx-background-radius: 4; -fx-border-radius: 4;");
    }

    private PrintElementType selectedElementType(Pane node) {
        if (node.getUserData() instanceof PrintElementType type) {
            return type;
        }
        return PrintElementType.TEXT_FIELD;
    }

    private void selectElement(PrintTemplateElement element) {
        selectedElement = element;
        if (element != null && elementListView.getSelectionModel().getSelectedItem() != element) {
            elementListView.getSelectionModel().select(element);
        }
        updatePropertyEditor(element);
        refreshNodeStyles();
        updateActionButtons();
    }

    private void refreshNodeStyles() {
        for (int i = 0; i < designPane.getChildren().size(); i++) {
            if (!(designPane.getChildren().get(i) instanceof Pane pane)) {
                continue;
            }
            if (i >= workingTemplate.getElements().size()) {
                continue;
            }
            PrintTemplateElement element = workingTemplate.getElements().get(i);
            pane.setUserData(element.getType());
            applyNodeStyle(pane, selectedElement != null && Objects.equals(selectedElement.getId(), element.getId()), element.isVisible());
        }
    }

    private void updatePropertyEditor(PrintTemplateElement element) {
        updatingFields = true;
        boolean hasElement = element != null;
        boolean isStaticText = hasElement && element.getType() == PrintElementType.STATIC_TEXT;
        labelField.setDisable(!hasElement || isStaticText);
        prefixField.setDisable(!hasElement || !supportsPrefix(element));
        contentField.setDisable(!isStaticText);
        contentField.setVisible(isStaticText);
        contentField.setManaged(isStaticText);
        contentLabel.setVisible(isStaticText);
        contentLabel.setManaged(isStaticText);
        visibleCheckBox.setDisable(!hasElement);
        xField.setDisable(!hasElement);
        yField.setDisable(!hasElement);
        widthField.setDisable(!hasElement);
        heightField.setDisable(!hasElement);
        fontSizeField.setDisable(!hasElement);
        boldCheckBox.setDisable(!hasElement);
        humanReadableCheckBox.setDisable(!hasElement || element.getType() != PrintElementType.BARCODE_CODE128);
        alignComboBox.setDisable(!hasElement || element.getType() == PrintElementType.KIZ_DATAMATRIX || element.getType() == PrintElementType.SEPARATOR_LINE || element.getType() == PrintElementType.BARCODE_CODE128);
        deleteElementButton.setDisable(!hasElement);
        copyElementButton.setDisable(!hasElement);
        pasteElementButton.setDisable(clipboardElement == null);

        labelField.setText(hasElement ? element.getLabel() : "");
        prefixField.setText(hasElement ? safeText(element.getPrefix()) : "");
        contentField.setText(isStaticText ? safeText(element.getContent()) : "");
        visibleCheckBox.setSelected(hasElement && element.isVisible());
        xField.setText(hasElement ? format(element.getX()) : "");
        yField.setText(hasElement ? format(element.getY()) : "");
        widthField.setText(hasElement ? format(element.getWidth()) : "");
        heightField.setText(hasElement ? format(element.getHeight()) : "");
        fontSizeField.setText(hasElement ? format(element.getFontSize()) : "");
        boldCheckBox.setSelected(hasElement && element.isBold());
        humanReadableCheckBox.setSelected(hasElement && element.isShowHumanReadable());
        alignComboBox.getSelectionModel().select(hasElement ? element.getAlign() : null);
        clearValidationState();
        updatingFields = false;
    }

    private void setupPropertyBindings() {
        visibleCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> mutateSelected(element -> element.setVisible(newValue)));
        boldCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> mutateSelected(element -> element.setBold(newValue)));
        humanReadableCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> mutateSelected(element -> element.setShowHumanReadable(newValue)));
        alignComboBox.valueProperty().addListener((obs, oldValue, newValue) -> mutateSelected(element -> element.setAlign(newValue)));

        labelField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!updatingFields) {
                mutateSelected(element -> {
                    if (element.getType() != PrintElementType.STATIC_TEXT && newValue != null && !newValue.isBlank()) {
                        element.setLabel(newValue.trim());
                    }
                });
            }
        });
        prefixField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!updatingFields) {
                mutateSelected(element -> {
                    if (supportsPrefix(element)) {
                        element.setPrefix(newValue == null || newValue.isBlank() ? null : newValue.trim());
                    }
                });
            }
        });
        contentField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!updatingFields) {
                mutateSelected(element -> {
                    if (element.getType() == PrintElementType.STATIC_TEXT) {
                        String trimmed = newValue == null ? "" : newValue.trim();
                        element.setContent(trimmed);
                        element.setLabel(trimmed.isBlank() ? i18n.tr("template.palette.static_text") : trimmed);
                    }
                });
            }
        });
        bindNumericField(
                xField,
                i18n.tr("template.field.x"),
                (element, value) -> element.setX(clamp(value, 0, PrintTemplateService.PAGE_WIDTH - element.getWidth())),
                value -> value >= 0 && value <= PrintTemplateService.PAGE_WIDTH
        );
        bindNumericField(
                yField,
                i18n.tr("template.field.y"),
                (element, value) -> element.setY(clamp(value, 0, PrintTemplateService.PAGE_HEIGHT - element.getHeight())),
                value -> value >= 0 && value <= PrintTemplateService.PAGE_HEIGHT
        );
        bindNumericField(
                widthField,
                i18n.tr("template.field.width"),
                (element, value) -> element.setWidth(clamp(value, 0, PrintTemplateService.PAGE_WIDTH - element.getX())),
                value -> value >= 0 && value <= PrintTemplateService.PAGE_WIDTH
        );
        bindNumericField(
                heightField,
                i18n.tr("template.field.height"),
                (element, value) -> element.setHeight(clamp(value, 0, PrintTemplateService.PAGE_HEIGHT - element.getY())),
                value -> value >= 0 && value <= PrintTemplateService.PAGE_HEIGHT
        );
        bindNumericField(
                fontSizeField,
                i18n.tr("template.field.font"),
                (element, value) -> element.setFontSize((float) value),
                value -> value >= 0 && value <= 200
        );
    }

    private void bindNumericField(
            TextField field,
            String label,
            NumericMutator mutator,
            NumericValidator validator
    ) {
        field.textProperty().addListener((obs, oldValue, newValue) -> {
            if (!updatingFields) {
                applyFieldImmediately(field, label, mutator, validator);
            }
        });
        field.focusedProperty().addListener((obs, oldValue, newValue) -> {
            if (!newValue && !updatingFields) {
                formatFieldIfValid(field, validator);
            }
        });
    }

    private void applyFieldImmediately(
            TextField field,
            String label,
            NumericMutator mutator,
            NumericValidator validator
    ) {
        if (selectedElement == null) {
            return;
        }

        String raw = field.getText() == null ? "" : field.getText().trim();
        if (raw.isBlank() || "-".equals(raw) || ".".equals(raw) || "-.".equals(raw)) {
            clearFieldError(field);
            fieldValidationLabel.setText("");
            fieldValidationLabel.setVisible(false);
            fieldValidationLabel.setManaged(false);
            return;
        }

        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            showFieldError(field, java.text.MessageFormat.format(i18n.tr("template.validation.number"), label));
            return;
        }

        if (!validator.isValid(value)) {
            showFieldError(field, java.text.MessageFormat.format(i18n.tr("template.validation.range"), label));
            return;
        }

        clearValidationState();
        mutator.apply(selectedElement, value);
        updatePreviewForSelection();
        elementListView.refresh();
    }

    private void mutateSelected(ElementMutator mutator) {
        if (updatingFields || selectedElement == null) {
            return;
        }
        mutator.apply(selectedElement);
        updatePreviewForSelection();
        elementListView.refresh();
        refreshNodeStyles();
    }

    private void updatePreviewForSelection() {
        if (selectedElement == null) {
            return;
        }
        Pane node = findSelectedNode();
        if (node == null) {
            refreshTemplateView();
            return;
        }
        updateNodeGeometry(node, selectedElement);
        updateNodeContent(node, selectedElement);
        refreshNodeStyles();
    }

    private void updateNodeGeometry(Pane node, PrintTemplateElement element) {
        node.setLayoutX(element.getX() * PREVIEW_SCALE);
        node.setLayoutY(element.getY() * PREVIEW_SCALE);
        double width = element.getWidth() * PREVIEW_SCALE;
        double height = element.getHeight() * PREVIEW_SCALE;
        node.setPrefWidth(width <= 0 ? 10 : width);
        node.setPrefHeight(height <= 0 ? 10 : height);
        node.setOpacity((element.getWidth() <= 0 || element.getHeight() <= 0) ? 0.45 : 1d);
    }

    private void updateNodeContent(Pane node, PrintTemplateElement element) {
        if (!(node instanceof StackPane stackPane) || stackPane.getChildren().isEmpty()) {
            return;
        }
        for (Node child : stackPane.getChildren()) {
            if (!(child instanceof Label label)) {
                continue;
            }
            label.setText(previewText(element));
            label.setMaxWidth(Math.max(4, element.getWidth() * PREVIEW_SCALE - 3));
            label.setMaxHeight(Math.max(4, element.getHeight() * PREVIEW_SCALE - 3));
            label.setAlignment(previewAlignment(element));
            label.setTextAlignment(previewTextAlignment(element));
            StackPane.setAlignment(label, previewAlignment(element));
            applyPreviewLabelStyle(label, element);
            return;
        }
    }

    private void syncSelectedFieldsForGeometry(PrintTemplateElement element) {
        if (selectedElement == null || !Objects.equals(selectedElement.getId(), element.getId())) {
            return;
        }
        updatingFields = true;
        xField.setText(format(element.getX()));
        yField.setText(format(element.getY()));
        widthField.setText(format(element.getWidth()));
        heightField.setText(format(element.getHeight()));
        updatingFields = false;
    }

    private void snapElementToGridIfEnabled(PrintTemplateElement element) {
        if (element == null || snapToGridCheckBox == null || !snapToGridCheckBox.isSelected()) {
            return;
        }
        element.setX(clampAndSnapX(element.getX(), element.getWidth()));
        element.setY(clampAndSnapY(element.getY(), element.getHeight()));
        element.setWidth(clamp(snapIfEnabled(element.getWidth()), 0, PrintTemplateService.PAGE_WIDTH - element.getX()));
        element.setHeight(clamp(snapIfEnabled(element.getHeight()), 0, PrintTemplateService.PAGE_HEIGHT - element.getY()));
    }

    private void formatFieldIfValid(TextField field, NumericValidator validator) {
        String raw = field.getText() == null ? "" : field.getText().trim();
        if (raw.isBlank() || "-".equals(raw) || ".".equals(raw) || "-.".equals(raw)) {
            return;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!validator.isValid(value)) {
                return;
            }
            updatingFields = true;
            field.setText(format(value));
            updatingFields = false;
        } catch (NumberFormatException ignored) {
        }
    }

    private PrintTemplate copyTemplate(PrintTemplate template) {
        List<PrintTemplateElement> copies = new ArrayList<>();
        for (PrintTemplateElement element : template.getElements()) {
            copies.add(deepCopyElement(element));
        }
        PrintTemplate copy = new PrintTemplate();
        copy.setId(template.getId());
        copy.setName(template.getName());
        copy.setPageWidth(template.getPageWidth());
        copy.setPageHeight(template.getPageHeight());
        copy.setDefaultTemplate(template.isDefaultTemplate());
        copy.setElements(copies);
        return copy;
    }

    private PrintTemplateElement findElement(String elementId) {
        return workingTemplate.getElements().stream()
                .filter(item -> Objects.equals(item.getId(), elementId))
                .findFirst()
                .orElse(null);
    }

    private Pane findSelectedNode() {
        if (selectedElement == null) {
            return null;
        }
        int index = workingTemplate.getElements().indexOf(selectedElement);
        if (index < 0 || index >= designPane.getChildren().size()) {
            return null;
        }
        if (designPane.getChildren().get(index) instanceof Pane pane) {
            return pane;
        }
        return null;
    }

    private void promptTemplateName(String title, String header, String initialValue, NameConsumer consumer) {
        TextInputDialog dialog = new TextInputDialog(initialValue);
        AlertService.applyTheme(dialog);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(i18n.tr("template.dialog.name"));
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> {
                    try {
                        consumer.accept(value);
                    } catch (RuntimeException ex) {
                        AlertService.showError(ex.getMessage());
                    }
                });
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : String.format("%.1f", value);
    }

    private double snapIfEnabled(double value) {
        if (snapToGridCheckBox == null || !snapToGridCheckBox.isSelected()) {
            return value;
        }
        double step = PrintTemplateService.POINTS_PER_MM;
        return Math.round(value / step) * step;
    }

    private double clampAndSnapX(double x, double width) {
        return clamp(snapIfEnabled(x), 0, PrintTemplateService.PAGE_WIDTH - width);
    }

    private double clampAndSnapY(double y, double height) {
        return clamp(snapIfEnabled(y), 0, PrintTemplateService.PAGE_HEIGHT - height);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showFieldError(TextField field, String message) {
        field.setStyle("-fx-border-color: #dc2626; -fx-border-width: 1.2;");
        fieldValidationLabel.setText(message);
        fieldValidationLabel.setVisible(true);
        fieldValidationLabel.setManaged(true);
    }

    private void clearFieldError(TextField field) {
        field.setStyle("");
    }

    private void clearValidationState() {
        clearFieldError(xField);
        clearFieldError(yField);
        clearFieldError(widthField);
        clearFieldError(heightField);
        clearFieldError(fontSizeField);
        fieldValidationLabel.setText("");
        fieldValidationLabel.setVisible(false);
        fieldValidationLabel.setManaged(false);
    }

    private void bindEditorShortcuts(Region region) {
        region.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DELETE && selectedElement != null) {
                onDeleteElement();
                event.consume();
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.C && selectedElement != null) {
                onCopyElement();
                event.consume();
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.V && clipboardElement != null) {
                onPasteElement();
                event.consume();
            }
        });
    }

    private void updateActionButtons() {
        if (copyElementButton != null) {
            copyElementButton.setDisable(selectedElement == null);
        }
        if (pasteElementButton != null) {
            pasteElementButton.setDisable(clipboardElement == null || workingTemplate == null);
        }
        if (deleteElementButton != null) {
            deleteElementButton.setDisable(selectedElement == null);
        }
    }

    private PrintTemplateElement deepCopyElement(PrintTemplateElement element) {
        PrintTemplateElement copy = new PrintTemplateElement();
        copy.setId(element.getId());
        copy.setType(element.getType());
        copy.setFieldKey(element.getFieldKey());
        copy.setLabel(element.getLabel());
        copy.setPrefix(element.getPrefix());
        copy.setContent(element.getContent());
        copy.setX(element.getX());
        copy.setY(element.getY());
        copy.setWidth(element.getWidth());
        copy.setHeight(element.getHeight());
        copy.setVisible(element.isVisible());
        copy.setZIndex(element.getZIndex());
        copy.setFontSize(element.getFontSize());
        copy.setBold(element.isBold());
        copy.setAlign(element.getAlign());
        copy.setShowHumanReadable(element.isShowHumanReadable());
        return copy;
    }

    private String newElementId() {
        return "element-" + UUID.randomUUID();
    }

    private int nextZIndex() {
        return workingTemplate.getElements().stream()
                .mapToInt(PrintTemplateElement::getZIndex)
                .max()
                .orElse(0) + 1;
    }

    private String nextPastedLabel(String baseLabel) {
        if (baseLabel == null || baseLabel.isBlank()) {
            return i18n.tr("template.copy_default_name");
        }
        return baseLabel + " " + i18n.tr("template.copy_suffix");
    }

    private boolean supportsPrefix(PrintTemplateElement element) {
        return element != null && (element.getType() == PrintElementType.TEXT_FIELD || element.getType() == PrintElementType.STATIC_TEXT || element.getType() == PrintElementType.STICKER_TAIL);
    }

    private String previewText(PrintTemplateElement element) {
        if (element == null) {
            return "";
        }
        if (element.getType() == PrintElementType.STATIC_TEXT) {
            return withPrefix(element.getPrefix(), safeText(element.getContent()));
        }
        if (element.getType() == PrintElementType.TEXT_FIELD) {
            return withPrefix(element.getPrefix(), sampleValue(element));
        }
        if (element.getType() == PrintElementType.STICKER_TAIL) {
            return withPrefix(element.getPrefix(), "0001");
        }
        String prefix = safeText(element.getPrefix()).trim();
        if (supportsPrefix(element) && !prefix.isBlank()) {
            return prefix + ":";
        }
        return safeText(element.getLabel());
    }

    private String sampleValue(PrintTemplateElement element) {
        if (element == null || element.getFieldKey() == null) {
            return safeText(element == null ? null : element.getLabel());
        }
        return switch (element.getFieldKey()) {
            case BRAND -> "Brand";
            case NAME -> i18n.tr("template.palette.name");
            case SUBJECT_NAME -> i18n.tr("template.palette.subject");
            case COLOR -> i18n.tr("template.sample.color");
            case ARTICLE -> "ABC-123456";
            case SIZE -> "42";
            case RU_SIZE -> "44-46";
            case BARCODE -> "4600000000000";
            case STICKER_TAIL -> "1";
        };
    }

    private void applyPreviewLabelStyle(Label label, PrintTemplateElement element) {
        double previewFont = element.getFontSize() <= 0 ? 8d : element.getFontSize() * PREVIEW_SCALE;
        label.setStyle("-fx-font-family: 'Arial'; -fx-font-size: %.1fpx; -fx-text-fill: #000000; -fx-font-weight: %s;"
                .formatted(previewFont, element.isBold() ? "700" : "400"));
    }

    private Pos previewAlignment(PrintTemplateElement element) {
        PrintTextAlign align = element == null ? PrintTextAlign.LEFT : element.getAlign();
        boolean bottom = bottomAlignedTwoLineField(element);
        if (align == PrintTextAlign.RIGHT) {
            return bottom ? Pos.BOTTOM_RIGHT : Pos.TOP_RIGHT;
        }
        if (align == PrintTextAlign.CENTER) {
            return bottom ? Pos.BOTTOM_CENTER : Pos.TOP_CENTER;
        }
        return bottom ? Pos.BOTTOM_LEFT : Pos.TOP_LEFT;
    }

    private TextAlignment previewTextAlignment(PrintTemplateElement element) {
        PrintTextAlign align = element == null ? PrintTextAlign.LEFT : element.getAlign();
        if (align == PrintTextAlign.RIGHT) {
            return TextAlignment.RIGHT;
        }
        if (align == PrintTextAlign.CENTER) {
            return TextAlignment.CENTER;
        }
        return TextAlignment.LEFT;
    }

    private boolean bottomAlignedTwoLineField(PrintTemplateElement element) {
        return element != null
                && element.getType() == PrintElementType.TEXT_FIELD
                && (element.getFieldKey() == com.tuandev.fbsbarcode.features.print.PrintFieldKey.ARTICLE
                || element.getFieldKey() == com.tuandev.fbsbarcode.features.print.PrintFieldKey.COLOR);
    }

    private String withPrefix(String prefix, String value) {
        String safeValue = safeText(value).trim();
        if (safeValue.isBlank()) {
            return "";
        }
        String safePrefix = safeText(prefix).trim();
        return safePrefix.isBlank() ? safeValue : safePrefix + ": " + safeValue;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private void renderRulersAndGrid() {
        topRulerBox.getChildren().clear();
        leftRulerBox.getChildren().clear();
        gridPane.getChildren().clear();

        double oneMm = PREVIEW_SCALE * PrintTemplateService.POINTS_PER_MM;
        for (int mm = 0; mm <= (int) PrintTemplateService.PAGE_WIDTH_MM; mm += 5) {
            Label label = new Label(Integer.toString(mm));
            label.setPrefWidth(oneMm * 5);
            label.setAlignment(Pos.CENTER_LEFT);
            label.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
            topRulerBox.getChildren().add(label);
        }
        for (int mm = 0; mm <= (int) PrintTemplateService.PAGE_HEIGHT_MM; mm += 5) {
            Label label = new Label(Integer.toString(mm));
            label.setPrefHeight(oneMm * 5);
            label.setAlignment(Pos.TOP_CENTER);
            label.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
            leftRulerBox.getChildren().add(label);
        }

        for (int mm = 0; mm <= (int) PrintTemplateService.PAGE_WIDTH_MM; mm++) {
            Pane line = new Pane();
            line.setManaged(false);
            line.setLayoutX(mm * oneMm);
            line.setLayoutY(0);
            line.setPrefWidth(mm % 5 == 0 ? 1.2 : 0.6);
            line.setPrefHeight(previewPane.getPrefHeight());
            line.setStyle("-fx-background-color: " + (mm % 5 == 0 ? "#cbd5e1" : "#e2e8f0") + ";");
            gridPane.getChildren().add(line);
        }
        for (int mm = 0; mm <= (int) PrintTemplateService.PAGE_HEIGHT_MM; mm++) {
            Pane line = new Pane();
            line.setManaged(false);
            line.setLayoutX(0);
            line.setLayoutY(mm * oneMm);
            line.setPrefWidth(previewPane.getPrefWidth());
            line.setPrefHeight(mm % 5 == 0 ? 1.2 : 0.6);
            line.setStyle("-fx-background-color: " + (mm % 5 == 0 ? "#cbd5e1" : "#e2e8f0") + ";");
            gridPane.getChildren().add(line);
        }
    }

    @FunctionalInterface
    private interface ElementMutator {
        void apply(PrintTemplateElement element);
    }

    @FunctionalInterface
    private interface NumericMutator {
        void apply(PrintTemplateElement element, double value);
    }

    @FunctionalInterface
    private interface NumericValidator {
        boolean isValid(double value);
    }

    @FunctionalInterface
    private interface NameConsumer {
        void accept(String name);
    }

    private enum HandlePosition {
        TOP_LEFT(Pos.TOP_LEFT, Cursor.NW_RESIZE),
        TOP_RIGHT(Pos.TOP_RIGHT, Cursor.NE_RESIZE),
        BOTTOM_LEFT(Pos.BOTTOM_LEFT, Cursor.SW_RESIZE),
        BOTTOM_RIGHT(Pos.BOTTOM_RIGHT, Cursor.SE_RESIZE);

        private final Pos alignment;
        private final Cursor cursor;

        HandlePosition(Pos alignment, Cursor cursor) {
            this.alignment = alignment;
            this.cursor = cursor;
        }
    }

    private enum TemplateMode {
        FBS,
        FBO;

        private String label(I18nService i18n) {
            return switch (this) {
                case FBS -> i18n.tr("template.type.fbs");
                case FBO -> i18n.tr("template.type.fbo");
            };
        }
    }
}
