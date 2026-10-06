package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KizMappingEditorTest {
    private static final String CURRENT_GTIN = "04601234567890";
    private static final String OTHER_GTIN = "04601234567891";

    @BeforeAll
    static void initToolkit() throws Exception {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit is already running.
        }
        runOnFxThread(() -> {
        });
    }

    @Test
    void selectedAndConflictingMappingsRemainVisibleWhileAvailableGendersCanBeChanged() throws Exception {
        runOnFxThread(() -> {
            KizGtinMappingEditor editor = new KizGtinMappingEditor();
            Map<String, KizGtinMappingEditor.SelectionState> state = new LinkedHashMap<>();
            state.put("Jackets", new KizGtinMappingEditor.SelectionState(true));
            KizGtinMappingEditor.MappingDialogData data = new KizGtinMappingEditor.MappingDialogData(
                    List.of("Jackets", "Blocked", "Split"),
                    state,
                    Map.of(
                            "Jackets", List.of("Female", "Male"),
                            "Blocked", List.of("Female", "Male"),
                            "Split", List.of("Female", "Male")),
                    Map.of(
                            "Jackets", Map.of(KizMappingRepository.WILDCARD_GENDER, CURRENT_GTIN),
                            "Blocked", Map.of(KizMappingRepository.WILDCARD_GENDER, OTHER_GTIN),
                            "Split", Map.of("Male", OTHER_GTIN)));
            VBox categories = new VBox();
            VBox genders = new VBox();
            String[] active = {"Jackets"};
            Runnable[] refresh = new Runnable[1];
            refresh[0] = () -> {
                editor.renderCategories(CURRENT_GTIN, categories, active, state, data, refresh[0]);
                editor.renderGenders(CURRENT_GTIN, active[0], genders, state, data, refresh[0]);
            };
            refresh[0].run();

            CheckBox blocked = categoryCheck(categories, "Blocked");
            assertTrue(blocked.isSelected());
            assertTrue(blocked.isDisable());

            CheckBox female = genderCheck(genders, "Female");
            assertTrue(female.isSelected());
            assertFalse(female.isDisable());
            female.fire();

            List<ZnackGtinMappingSelection> jacketRules = editor.flatten(state);
            assertEquals(List.of(new ZnackGtinMappingSelection("Jackets", "Male", false)), jacketRules);

            CheckBox split = categoryCheck(categories, "Split");
            assertTrue(split.isIndeterminate());
            assertFalse(split.isDisable());
            split.fire();

            assertTrue(genderCheck(genders, "Male").isSelected());
            assertTrue(genderCheck(genders, "Male").isDisable());
            assertTrue(genderCheck(genders, "Female").isSelected());
            assertFalse(genderCheck(genders, "Female").isDisable());
            assertTrue(editor.flatten(state).contains(new ZnackGtinMappingSelection("Split", "Female", false)));
        });
    }

    private static CheckBox categoryCheck(VBox categories, String subject) {
        return categories.getChildren().stream()
                .filter(node -> subject.equals(node.getProperties().get("mappingSubject")))
                .map(HBox.class::cast)
                .map(row -> (CheckBox) row.getChildren().getFirst())
                .findFirst()
                .orElseThrow();
    }

    private static CheckBox genderCheck(VBox genders, String gender) {
        return genders.getChildren().stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(check -> gender.equals(check.getProperties().get("mappingGender")))
                .findFirst()
                .orElseThrow();
    }

    private static void runOnFxThread(ThrowingRunnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
