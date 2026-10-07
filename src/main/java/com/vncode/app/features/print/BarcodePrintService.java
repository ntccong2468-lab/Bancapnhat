package com.vncode.app.features.print;

import com.google.zxing.WriterException;
import com.vncode.app.models.Order;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class BarcodePrintService {
    public void export(PrintTemplate template, List<Order> orders, File outputFile) throws IOException, WriterException {
        export(template, orders, outputFile, PrintJobOptions.defaults());
    }

    public void export(PrintTemplate template, List<Order> orders, File outputFile, PrintJobOptions options) throws IOException, WriterException {
        GenerateBarcode.exportTemplateAndSticker(template, orders, outputFile, options);
    }
}
