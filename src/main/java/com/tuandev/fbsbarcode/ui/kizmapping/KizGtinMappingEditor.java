package com.tuandev.fbsbarcode.ui.kizmapping;

import com.tuandev.fbsbarcode.features.kizmapping.KizMappingRepository;
import com.tuandev.fbsbarcode.features.kizmapping.ZnackGtinMappingSelection;
import com.tuandev.fbsbarcode.integration.znack.ZnackGtinMappingRule;
import com.tuandev.fbsbarcode.shared.AlertService;
import com.tuandev.fbsbarcode.shared.AppTaskExecutor;
import com.tuandev.fbsbarcode.shared.I18nService;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GTIN ↔ KIZ category mapping dialog shared by the KIZ mapping page and the supply KIZ pane. */
public class KizGtinMappingEditor {

    /** UI hooks of the pane that opened the editor; all callbacks run on the FX thread. */
    public interface Host {
        void busy(boolean busy);

        void saved();

        void error(Throwable error);

        /** False once the pane moved on (e.g. the shop changed) — the dialog is then not opened. */
        default boolean isCurrent() {
            return true;
        }
    }

    private final KizMappingRepository mappingRepository = new KizMappingRepository();

    public void open(int shopId, String gtin, Host host) {
        Task<MappingDialogData> task = new Task<>() {
            @Override protected MappingDialogData call() {
                List<String> subjects = mappingRepository.findSubjects(shopId);
                Map<String, SelectionState> state = loadState(shopId, gtin);
                Map<String, List<String>> gendersBySubject = new LinkedHashMap<>();
                Map<String, Map<String, String>> ownersBySubject = new LinkedHashMap<>();
                for (String subject : subjects) {
                    gendersBySubject.put(subject, mappingRepository.findGendersForSubject(shopId, subject));
                    ownersBySubject.put(subject, mappingRepository.findOwnersForSubject(shopId, subject));
                }
                return new MappingDialogData(subjects, state, gendersBySubject, ownersBySubject);
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            if (host.isCurrent()) {
                openDialog(shopId, gtin, task.getValue(), host);
            }
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private void openDialog(int shopId, String gtin, MappingDialogData data, Host host) {
        Dialog<List<ZnackGtinMappingSelection>> dialog = new Dialog<>();
        AlertService.applyTheme(dialog);
        dialog.setTitle(tr("kiz_mapping.mapping.title"));
        ButtonType save = new ButtonType(tr("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        VBox subjects = new VBox(4);
        VBox genders = new VBox(8);
        VBox selectedRules = new VBox(8);
        Map<String, SelectionState> state = data.state();
        String[] activeSubject = {data.subjects().stream().filter(state::containsKey).findFirst()
                .orElse(data.subjects().isEmpty() ? null : data.subjects().getFirst())};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            renderCategories(gtin, subjects, activeSubject, state, data, refresh[0]);
            renderGenders(gtin, activeSubject[0], genders, state, data, refresh[0]);
            renderSelectedRules(selectedRules, state, refresh[0]);
        };
        ScrollPane subjectScroll = new ScrollPane(subjects);
        subjectScroll.setFitToWidth(true);
        VBox subjectPane = titledPane(tr("kiz_mapping.mapping.categories"), subjectScroll);
        VBox genderPane = titledPane(tr("kiz_mapping.mapping.genders"), new ScrollPane(genders));
        VBox summaryPane = titledPane(tr("kiz_mapping.mapping.summary"), new ScrollPane(selectedRules));
        SplitPane content = new SplitPane(subjectPane, genderPane, summaryPane);
        content.setDividerPositions(0.32, 0.68);
        content.setPrefSize(980, 520);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? flatten(state) : null);
        refresh[0].run();
        dialog.showAndWait().ifPresent(selections -> save(shopId, gtin, selections, host));
    }

    private void save(int shopId, String gtin, List<ZnackGtinMappingSelection> selections, Host host) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                mappingRepository.replaceRulesForGtin(shopId, gtin, selections);
                return null;
            }
        };
        host.busy(true);
        task.setOnSucceeded(event -> {
            host.busy(false);
            host.saved();
        });
        task.setOnFailed(event -> {
            host.busy(false);
            host.error(task.getException());
        });
        AppTaskExecutor.execute(task);
    }

    private Map<String, SelectionState> loadState(int shopId, String gtin) {
        Map<String, SelectionState> result = new LinkedHashMap<>();
        for (ZnackGtinMappingRule rule : mappingRepository.findRulesForGtin(shopId, gtin)) {
            SelectionState value = result.computeIfAbsent(rule.subjectName(), ignored -> new SelectionState(false));
            value.wildcard = rule.wildcardGender();
            if (!rule.wildcardGender()) value.genders.add(rule.genderValue());
        }
        return result;
    }

    void renderCategories(String gtin, VBox box, String[] activeSubject, Map<String, SelectionState> state,
                          MappingDialogData data, Runnable refresh) {
        box.getChildren().clear();
        for (String subject : data.subjects()) {
            SelectionState selection = state.get(subject);
            Set<String> otherOwners = otherOwners(gtin, subject, data);
            boolean blocked = selection == null && fullyOwnedByOther(gtin, subject, data);
            CheckBox enabled = new CheckBox();
            enabled.getProperties().put("mappingSubject", subject);
            enabled.getProperties().put("ownedByOther", blocked);
            enabled.setSelected(selection != null && !selection.empty() || blocked);
            enabled.setIndeterminate(selection == null && !blocked && !otherOwners.isEmpty());
            enabled.setDisable(blocked);
            Label name = new Label(subject);
            Label count = new Label(blocked ? String.join(", ", otherOwners) : selectionCount(selection));
            count.getStyleClass().add("text-muted");
            javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
            HBox row = new HBox(8, enabled, name, spacer, count);
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            row.getStyleClass().add("surface");
            row.getProperties().put("mappingSubject", subject);
            if (subject.equals(activeSubject[0])) row.setStyle("-fx-border-color: -accent;");
            enabled.setOnAction(event -> {
                event.consume();
                activeSubject[0] = subject;
                if (state.containsKey(subject)) state.remove(subject);
                else enableSubject(gtin, subject, state, data);
                refresh.run();
            });
            row.setOnMouseClicked(event -> {
                activeSubject[0] = subject;
                refresh.run();
            });
            box.getChildren().add(row);
        }
    }

    private void enableSubject(String gtin, String subject, Map<String, SelectionState> state, MappingDialogData data) {
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        boolean hasOtherOwner = owners.values().stream().anyMatch(owner -> owner != null && !owner.equals(gtin));
        SelectionState selection = new SelectionState(!hasOtherOwner);
        if (hasOtherOwner) {
            String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
            for (String gender : data.gendersBySubject().getOrDefault(subject, List.of())) {
                String owner = first(owners.get(gender), wildcardOwner);
                if (owner == null || owner.equals(gtin)) selection.genders.add(gender);
            }
        }
        if (!selection.empty()) state.put(subject, selection);
    }

    void renderGenders(String gtin, String subject, VBox box, Map<String, SelectionState> state,
                       MappingDialogData data, Runnable refresh) {
        box.getChildren().clear();
        if (subject == null) return;
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        List<String> availableGenders = data.gendersBySubject().getOrDefault(subject, List.of());
        Set<String> otherOwners = otherOwners(gtin, subject, data);
        SelectionState selection = state.get(subject);
        boolean enabled = selection != null;
        if (selection == null) {
            Label help = new Label(tr("kiz_mapping.mapping.enable_category"));
            help.getStyleClass().add("text-muted");
            help.setWrapText(true);
            box.getChildren().add(help);
            selection = new SelectionState(false);
        }
        SelectionState activeSelection = selection;
        CheckBox all = new CheckBox(tr("kiz_mapping.gender.all"));
        all.getProperties().put("mappingGender", KizMappingRepository.WILDCARD_GENDER);
        all.setSelected(activeSelection.wildcard || (!enabled && owners.get(KizMappingRepository.WILDCARD_GENDER) != null));
        all.setIndeterminate(enabled && !activeSelection.wildcard && !activeSelection.genders.isEmpty());
        String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
        all.setDisable(!enabled || !otherOwners.isEmpty());
        if (!otherOwners.isEmpty()) all.setText(all.getText() + " · " + String.join(", ", otherOwners));
        all.getProperties().put("ownedByOther", !otherOwners.isEmpty());
        box.getChildren().add(all);
        for (String gender : availableGenders) {
            String owner = first(owners.get(gender), wildcardOwner);
            boolean ownedByOther = owner != null && !owner.equals(gtin);
            CheckBox check = new CheckBox(displayGender(gender) + (owner != null && !owner.equals(gtin) ? " · " + owner : ""));
            check.setSelected(ownedByOther || activeSelection.wildcard || activeSelection.genders.contains(gender));
            check.setDisable(!enabled || ownedByOther);
            check.getProperties().put("mappingGender", gender);
            check.getProperties().put("ownedByOther", ownedByOther);
            check.setOnAction(event -> {
                boolean selectedNow = check.isSelected();
                if (activeSelection.wildcard) {
                    activeSelection.wildcard = false;
                    activeSelection.genders.clear();
                    for (String candidate : availableGenders) {
                        String candidateOwner = first(owners.get(candidate), wildcardOwner);
                        boolean candidateOwnedByOther = candidateOwner != null && !candidateOwner.equals(gtin);
                        if (!candidateOwnedByOther && (!candidate.equals(gender) || selectedNow)) {
                            activeSelection.genders.add(candidate);
                        }
                    }
                } else if (selectedNow) {
                    activeSelection.genders.add(gender);
                } else {
                    activeSelection.genders.remove(gender);
                }
                if (activeSelection.empty()) state.remove(subject);
                refresh.run();
            });
            box.getChildren().add(check);
        }
        all.setOnAction(event -> {
            activeSelection.wildcard = all.isSelected();
            activeSelection.genders.clear();
            if (!activeSelection.wildcard) {
                for (String gender : availableGenders) {
                    String owner = first(owners.get(gender), wildcardOwner);
                    if (owner == null || owner.equals(gtin)) activeSelection.genders.add(gender);
                }
            }
            if (activeSelection.empty()) state.remove(subject);
            refresh.run();
        });
    }

    private Set<String> otherOwners(String gtin, String subject, MappingDialogData data) {
        return data.ownersBySubject().getOrDefault(subject, Map.of()).values().stream()
                .filter(owner -> owner != null && !owner.equals(gtin))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean fullyOwnedByOther(String gtin, String subject, MappingDialogData data) {
        Map<String, String> owners = data.ownersBySubject().getOrDefault(subject, Map.of());
        String wildcardOwner = owners.get(KizMappingRepository.WILDCARD_GENDER);
        if (wildcardOwner != null && !wildcardOwner.equals(gtin)) return true;
        List<String> genders = data.gendersBySubject().getOrDefault(subject, List.of());
        return !genders.isEmpty() && genders.stream().allMatch(gender -> {
            String owner = owners.get(gender);
            return owner != null && !owner.equals(gtin);
        });
    }

    private void renderSelectedRules(VBox box, Map<String, SelectionState> state, Runnable refresh) {
        box.getChildren().clear();
        state.forEach((subject, selection) -> {
            if (selection.empty()) return;
            Label name = new Label(subject);
            name.getStyleClass().add("text-strong");
            Label genders = new Label(selection.wildcard
                    ? tr("kiz_mapping.gender.all")
                    : selection.genders.stream().map(this::displayGender).collect(java.util.stream.Collectors.joining(", ")));
            genders.getStyleClass().add("text-muted");
            genders.setWrapText(true);
            Button remove = new Button(tr("common.delete"));
            remove.setOnAction(event -> {
                state.remove(subject);
                refresh.run();
            });
            HBox row = new HBox(8, new VBox(3, name, genders), new javafx.scene.layout.Pane(), remove);
            HBox.setHgrow(row.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
            row.getStyleClass().add("surface");
            box.getChildren().add(row);
        });
        if (box.getChildren().isEmpty()) {
            Label empty = new Label(tr("kiz_mapping.mapping.summary_empty"));
            empty.getStyleClass().add("text-muted");
            empty.setWrapText(true);
            box.getChildren().add(empty);
        }
    }

    private VBox titledPane(String title, javafx.scene.Node content) {
        Label label = new Label(title);
        label.getStyleClass().add("h3");
        VBox pane = new VBox(8, label, content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);
        return pane;
    }

    private String selectionCount(SelectionState selection) {
        if (selection == null || selection.empty()) return "";
        return selection.wildcard ? tr("kiz_mapping.gender.all") : String.valueOf(selection.genders.size());
    }

    List<ZnackGtinMappingSelection> flatten(Map<String, SelectionState> state) {
        List<ZnackGtinMappingSelection> result = new ArrayList<>();
        state.forEach((subject, selection) -> {
            if (selection.wildcard) result.add(new ZnackGtinMappingSelection(subject, null, true));
            else selection.genders.forEach(gender -> result.add(new ZnackGtinMappingSelection(subject, gender, false)));
        });
        return result;
    }

    private String displayGender(String gender) {
        return KizMappingRepository.UNSPECIFIED_GENDER.equals(gender) ? tr("kiz_mapping.gender.unspecified") : gender;
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String tr(String key) {
        return I18nService.getInstance().tr(key);
    }

    static final class SelectionState {
        boolean wildcard;
        final Set<String> genders = new LinkedHashSet<>();

        SelectionState(boolean wildcard) {
            this.wildcard = wildcard;
        }

        boolean empty() {
            return !wildcard && genders.isEmpty();
        }
    }

    record MappingDialogData(List<String> subjects,
                             Map<String, SelectionState> state,
                             Map<String, List<String>> gendersBySubject,
                             Map<String, Map<String, String>> ownersBySubject) {
    }
}
