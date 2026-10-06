package com.tuandev.fbsbarcode.features.print;

public record PrintJobOptions(
        PrintPageOrder pageOrder,
        int barcodeCopies
) {
    public static PrintJobOptions defaults() {
        return new PrintJobOptions(PrintPageOrder.BARCODE_THEN_STICKER, 1);
    }

    public PrintJobOptions normalized() {
        return new PrintJobOptions(
                pageOrder == null ? PrintPageOrder.BARCODE_THEN_STICKER : pageOrder,
                Math.max(1, barcodeCopies)
        );
    }
}
