package com.tuandev.fbsbarcode.features.print;

import java.util.ArrayList;
import java.util.List;

public class PrintTemplate {
    private Integer id;
    private String name;
    private double pageWidth;
    private double pageHeight;
    private boolean defaultTemplate;
    private List<PrintTemplateElement> elements = new ArrayList<>();

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getPageWidth() {
        return pageWidth;
    }

    public void setPageWidth(double pageWidth) {
        this.pageWidth = pageWidth;
    }

    public double getPageHeight() {
        return pageHeight;
    }

    public void setPageHeight(double pageHeight) {
        this.pageHeight = pageHeight;
    }

    public boolean isDefaultTemplate() {
        return defaultTemplate;
    }

    public void setDefaultTemplate(boolean defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    public List<PrintTemplateElement> getElements() {
        return elements;
    }

    public void setElements(List<PrintTemplateElement> elements) {
        this.elements = elements == null ? new ArrayList<>() : new ArrayList<>(elements);
    }

    @Override
    public String toString() {
        return name == null ? "Template" : name;
    }
}
