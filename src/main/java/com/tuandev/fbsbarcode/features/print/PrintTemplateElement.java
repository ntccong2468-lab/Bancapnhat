package com.tuandev.fbsbarcode.features.print;

import java.util.UUID;

public class PrintTemplateElement {
    private String id;
    private PrintElementType type;
    private PrintFieldKey fieldKey;
    private String label;
    private String prefix;
    private String content;
    private double x;
    private double y;
    private double width;
    private double height;
    private boolean visible = true;
    private int zIndex;
    private float fontSize = 8f;
    private boolean bold;
    private PrintTextAlign align = PrintTextAlign.LEFT;
    private boolean showHumanReadable = true;

    public static PrintTemplateElement create(PrintElementType type, String label, double x, double y, double width, double height) {
        PrintTemplateElement element = new PrintTemplateElement();
        element.setId(UUID.randomUUID().toString());
        element.setType(type);
        element.setLabel(label);
        element.setX(x);
        element.setY(y);
        element.setWidth(width);
        element.setHeight(height);
        return element;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public PrintElementType getType() {
        return type;
    }

    public void setType(PrintElementType type) {
        this.type = type;
    }

    public PrintFieldKey getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(PrintFieldKey fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        this.height = height;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public int getZIndex() {
        return zIndex;
    }

    public void setZIndex(int zIndex) {
        this.zIndex = zIndex;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public PrintTextAlign getAlign() {
        return align;
    }

    public void setAlign(PrintTextAlign align) {
        this.align = align;
    }

    public boolean isShowHumanReadable() {
        return showHumanReadable;
    }

    public void setShowHumanReadable(boolean showHumanReadable) {
        this.showHumanReadable = showHumanReadable;
    }
}
