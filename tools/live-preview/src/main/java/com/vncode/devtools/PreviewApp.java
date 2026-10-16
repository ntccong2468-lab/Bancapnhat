package com.vncode.devtools;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static java.nio.file.StandardWatchEventKinds.*;

/** Isolated development-only JavaFX FXML preview. Never load production controllers. */
public final class PreviewApp extends Application {
    private final Path file = Paths.get("sample", "dashboard.fxml").toAbsolutePath().normalize();
    private final BorderPane frame = new BorderPane();
    private final TextField text = new TextField(), css = new TextField(), width = new TextField(), height = new TextField();
    private final Label selection = new Label("Click a component in the preview"), status = new Label("Ready");
    private Parent preview;
    private Node selected;
    private String selectedId;
    private WatchService watcher;
    private ExecutorService watchThread;
    private ScheduledExecutorService delay;
    private ScheduledFuture<?> pending;
    private boolean dirty;

    @Override public void start(Stage stage) throws Exception {
        VBox inspector = new VBox(10, new Label("VISUAL EDITOR"), selection,
            new Label("Text"), text, new Label("Inline CSS"), css,
            new Label("Preferred width (-1 = default)"), width,
            new Label("Preferred height (-1 = default)"), height);
        inspector.setStyle("-fx-padding: 16; -fx-background-color: #eef2f7;");
        inspector.setPrefWidth(305);
        Button apply = new Button("Apply (preview)"), save = new Button("Save to FXML"), cancel = new Button("Cancel");
        apply.setOnAction(e -> applyPreview());
        save.setOnAction(e -> saveChanges());
        cancel.setOnAction(e -> reload());
        inspector.getChildren().addAll(new HBox(7, apply, save, cancel), status);
        frame.setRight(new ScrollPane(inspector));
        frame.setCenter(new Label("Loading FXML..."));
        stage.setScene(new Scene(frame, 1000, 640));
        stage.setTitle("VN Code - JavaFX Live Preview (DEVELOPMENT ONLY)");
        stage.show();
        reload();
        startWatcher();
    }

    private void reload() {
        try {
            if (!Files.isRegularFile(file)) throw new IOException("Missing " + file);
            Document document = parse();
            if (document.getDocumentElement().hasAttributeNS("http://javafx.com/fxml", "controller"))
                throw new SecurityException("Controller-bearing FXML is not accepted by this isolated preview");
            String xml = Files.readString(file);
            if (xml.contains("<?language") || xml.contains("<fx:script") || xml.contains("<fx:include") || xml.contains("<fx:root"))
                throw new SecurityException("Script/include/root FXML is not accepted");
            FXMLLoader loader = new FXMLLoader();
            loader.setControllerFactory(type -> { throw new SecurityException("Controllers disabled: " + type); });
            Parent root;
            try (InputStream stream = Files.newInputStream(file)) { root = loader.load(stream); }
            preview = root;
            StackPane viewport = new StackPane(preview);
            viewport.setStyle("-fx-padding: 25; -fx-background-color: white;");
            frame.setCenter(viewport);
            installClicks(preview);
            selected = null; selectedId = null; dirty = false;
            selection.setText("Click a component in the preview");
            status.setText("Loaded " + file.getFileName());
        } catch (Exception error) {
            frame.setCenter(new Label("Preview error: " + error.getMessage()));
            status.setText("Reload failed; source unchanged");
        }
    }

    private void installClicks(Node node) {
        if (node.getId() != null && !node.getId().isBlank()) {
            node.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                event.consume();
                select(node);
            });
        }
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) installClicks(child);
    }

    private void select(Node node) {
        if (dirty) { status.setText("Save or Cancel pending changes before selecting another node"); return; }
        selected = node; selectedId = node.getId();
        selection.setText("Selected fx:id: " + selectedId);
        text.setText(node instanceof Labeled labeled ? labeled.getText() : "");
        text.setDisable(!(node instanceof Labeled));
        css.setText(node.getStyle());
        if (node instanceof Region region) {
            width.setText(Double.toString(region.getPrefWidth()));
            height.setText(Double.toString(region.getPrefHeight()));
            width.setDisable(false); height.setDisable(false);
        } else {
            width.setDisable(true); height.setDisable(true);
            width.clear(); height.clear();
        }
    }

    private void applyPreview() {
        if (selected == null) { status.setText("Select a control first"); return; }
        try {
            if (selected instanceof Labeled labeled) labeled.setText(text.getText());
            selected.setStyle(css.getText());
            if (selected instanceof Region region) {
                region.setPrefWidth(Double.parseDouble(width.getText()));
                region.setPrefHeight(Double.parseDouble(height.getText()));
            }
            dirty = true;
            status.setText("Preview only; source is unchanged");
        } catch (Exception error) { status.setText("Invalid edit: " + error.getMessage()); }
    }

    private Document parse() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private void saveChanges() {
        if (selected == null) { status.setText("Select a control first"); return; }
        try {
            // Reject non-previewable input before persisting any bytes.
            if (selected instanceof Region) {
                Double.parseDouble(width.getText()); Double.parseDouble(height.getText());
            }
            Document doc = parse();
            NodeList all = doc.getElementsByTagName("*");
            Element match = null;
            int count = 0;
            for (int i = 0; i < all.getLength(); i++) {
                Element el = (Element) all.item(i);
                if (Objects.equals(selectedId, el.getAttributeNS("http://javafx.com/fxml", "id"))) {
                    match = el; count++;
                }
            }
            if (count != 1) throw new IllegalStateException("fx:id must uniquely identify one FXML element");
            if (selected instanceof Labeled) match.setAttribute("text", text.getText());
            match.setAttribute("style", css.getText());
            if (selected instanceof Region) {
                match.setAttribute("prefWidth", width.getText());
                match.setAttribute("prefHeight", height.getText());
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transform = tf.newTransformer();
            transform.setOutputProperty(OutputKeys.INDENT, "yes");
            Path temp = Files.createTempFile(file.getParent(), ".preview-", ".fxml");
            try {
                transform.transform(new DOMSource(doc), new StreamResult(temp.toFile()));
                // Validate rewritten XML before atomic replacement.
                DocumentBuilderFactory validation = DocumentBuilderFactory.newInstance();
                validation.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                validation.newDocumentBuilder().parse(temp.toFile());
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            dirty = false; reload();
            status.setText("Saved to " + file);
        } catch (Exception error) { status.setText("Not saved: " + error.getMessage()); }
    }

    private void startWatcher() {
        try {
            watcher = FileSystems.getDefault().newWatchService();
            file.getParent().register(watcher, ENTRY_MODIFY, ENTRY_CREATE);
            delay = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "preview-debounce"); t.setDaemon(true); return t; });
            watchThread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "preview-watch"); t.setDaemon(true); return t; });
            watchThread.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watcher.take();
                        boolean changed = key.pollEvents().stream().anyMatch(e -> file.getFileName().equals(e.context()));
                        if (!key.reset()) break;
                        if (changed) {
                            synchronized (this) {
                                if (pending != null) pending.cancel(false);
                                pending = delay.schedule(() -> Platform.runLater(() -> {
                                    if (!dirty) reload(); else status.setText("External edit detected: Save or Cancel to reload");
                                }), 250, TimeUnit.MILLISECONDS);
                            }
                        }
                    } catch (InterruptedException | ClosedWatchServiceException done) { break; }
                }
            });
        } catch (IOException error) { status.setText("Watcher unavailable: " + error.getMessage()); }
    }

    @Override public void stop() throws Exception {
        if (watcher != null) watcher.close();
        if (watchThread != null) watchThread.shutdownNow();
        if (delay != null) delay.shutdownNow();
    }
    public static void main(String[] args) { launch(args); }
}
