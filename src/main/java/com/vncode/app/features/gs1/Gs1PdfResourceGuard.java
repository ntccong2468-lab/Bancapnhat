package com.vncode.app.features.gs1;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Bounds raw resources of the page being previewed before PDFBox allocates decoded image pixels.
 * This is not a general PDF sanitizer. Legal shared resource dictionaries are visited once;
 * PDFBox retains responsibility for its own actual content/form invocation recursion limits. */
public final class Gs1PdfResourceGuard {
    private static final int MAX_PAGES = 200, MAX_CONTAINERS = 4096, MAX_DEPTH = 48, MAX_FORMS = 8;
    private static final int MAX_ARRAY_ENTRIES = 65_536, MAX_VISITS = 200_000, MAX_ANNOTATIONS = 2048;
    private static final int MAX_IMAGE_SIDE = 10_000;
    private static final long MAX_IMAGE_PIXELS = 25_000_000, MAX_PAGE_IMAGE_PIXELS = 50_000_000;

    private Gs1PdfResourceGuard() {}

    public static void requireSafe(PDDocument document, int index) throws IOException {
        int count = document.getNumberOfPages();
        if (document.isEncrypted() || count < 1 || count > MAX_PAGES || index < 0 || index >= count) reject();
        PDPage page = document.getPage(index);
        var bounds = page.getCropBox();
        float width = bounds.getWidth(), height = bounds.getHeight();
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0
                || width > 20_000 || height > 20_000) reject();
        Limits limits = new Limits();
        if (page.getResources() != null) limits.inspect(page.getResources().getCOSObject(), 0, 0);
        // Read appearance dictionaries without instantiating or decoding their image objects.
        COSBase annotations = page.getCOSObject().getDictionaryObject(COSName.ANNOTS);
        if (annotations != null && !(annotations instanceof COSArray)) reject();
        if (annotations instanceof COSArray array) {
            if (array.size() > MAX_ANNOTATIONS) reject();
            for (int position = 0; position < array.size(); position++) {
                COSBase entry = array.getObject(position);
                if (!(entry instanceof COSDictionary annotation)) reject();
                else {
                    COSBase appearance = annotation.getDictionaryObject(COSName.AP);
                    limits.inspect(appearance, 0, 0);
                    // PDFBox's NoRotate transparency check recursively follows Form resources.
                    // Ordinary shared resource backreferences are legal; this particular render
                    // route needs a separate cycle check to avoid a StackOverflowError.
                    if ((annotation.getInt(COSName.F) & 16) != 0 && page.getRotation() != 0
                            && appearance instanceof COSDictionary appearances) {
                        COSBase normal = appearances.getDictionaryObject(COSName.N);
                        if (normal instanceof COSDictionary states && !(normal instanceof COSStream)) {
                            COSName state = annotation.getCOSName(COSName.AS);
                            normal = state == null ? null : states.getDictionaryObject(state);
                        }
                        if (normal instanceof COSStream stream)
                            new TransparencyLimits().inspect(stream.getCOSDictionary(COSName.RESOURCES), 0);
                    }
                }
            }
        }
    }

    private static final class Limits {
        private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSDictionary> checkedImages = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSDictionary> activeImages = Collections.newSetFromMap(new IdentityHashMap<>());
        private long imagePixels;
        private int visits;

        private void inspect(COSBase value, int depth, int forms) {
            if (value == null) return;
            if (++visits > MAX_VISITS) reject();
            // Font width tables contain thousands of scalar numbers in otherwise small, valid
            // PDFs. Bound their traversal separately rather than counting them as image resources.
            if (!(value instanceof COSObject || value instanceof COSArray || value instanceof COSDictionary)) return;
            if (visited.contains(value)) return;
            if (depth > MAX_DEPTH || forms > MAX_FORMS || !visited.add(value) || visited.size() > MAX_CONTAINERS) reject();
            if (value instanceof COSObject reference) {
                inspect(reference.getObject(), depth + 1, forms);
            } else if (value instanceof COSArray array) {
                if (array.size() > MAX_ARRAY_ENTRIES) reject();
                for (int index = 0; index < array.size(); index++) inspect(array.get(index), depth + 1, forms);
            } else if (value instanceof COSDictionary dictionary) {
                COSName subtype = dictionary.getCOSName(COSName.SUBTYPE);
                boolean form = COSName.FORM.equals(subtype);
                if (COSName.IMAGE.equals(subtype)) inspectImage(dictionary, 0);
                if (form && forms >= MAX_FORMS) reject();
                for (var entry : dictionary.entrySet()) inspect(entry.getValue(), depth + 1, forms + (form ? 1 : 0));
            }
        }

        private void inspectImage(COSDictionary image, int depth) {
            if (depth > MAX_DEPTH || activeImages.contains(image)) reject();
            if (!checkedImages.add(image)) return;
            int width = image.getInt(COSName.WIDTH), height = image.getInt(COSName.HEIGHT);
            long pixels = (long) width * height;
            if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                    || pixels > MAX_IMAGE_PIXELS) reject();
            imagePixels += pixels;
            if (imagePixels > MAX_PAGE_IMAGE_PIXELS) reject();
            activeImages.add(image);
            try {
                // PDFBox interprets stream-valued masks as images even if their Subtype is absent.
                // Validate those dimensions from the raw dictionary before getSoftMask/getMask.
                for (COSName key : new COSName[]{COSName.MASK, COSName.SMASK}) {
                    COSBase mask = image.getDictionaryObject(key);
                    if (mask instanceof COSStream stream) inspectImage(stream, depth + 1);
                }
            } finally { activeImages.remove(image); }
        }
    }

    private static final class TransparencyLimits {
        private final Set<COSDictionary> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        private void inspect(COSDictionary resources, int depth) {
            if (resources == null) return;
            if (depth > MAX_FORMS || active.contains(resources)) reject();
            if (!visited.add(resources)) return;
            if (visited.size() > MAX_CONTAINERS) reject();
            active.add(resources);
            try {
                COSDictionary objects = resources.getCOSDictionary(COSName.XOBJECT);
                if (objects != null) for (COSName name : objects.keySet()) {
                    COSBase object = objects.getDictionaryObject(name);
                    if (object instanceof COSDictionary form && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE)))
                        inspect(form.getCOSDictionary(COSName.RESOURCES), depth + 1);
                }
            } finally { active.remove(resources); }
        }
    }

    private static void reject() { throw new IllegalArgumentException("gs1.document_resource_limits"); }
}
