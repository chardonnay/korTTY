package de.kortty.ui;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Chooses the korTTY entries to move into the archive. Every candidate starts checked. */
final class AiMemoryArchiveDialog extends ThemeAwareDialog<Set<String>> {

    private final Map<String, BooleanProperty> checked = new LinkedHashMap<>();

    private AiMemoryArchiveDialog(Window owner, List<KorttyEntry> candidates) {
        setTitle(I18n.get("settings.ai.memory.cleanup.archiveTitle"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }
        for (KorttyEntry entry : candidates) {
            checked.put(entry.id(), new SimpleBooleanProperty(true));
        }
        ListView<KorttyEntry> list = new ListView<>(FXCollections.observableArrayList(candidates));
        list.setPrefHeight(260);
        list.setCellFactory(CheckBoxListCell.forListView(entry -> checked.get(entry.id()), new StringConverter<>() {
            @Override
            public String toString(KorttyEntry entry) {
                return entry == null ? "" : entry.created() + " · " + entry.source() + " · " + entry.body();
            }

            @Override
            public KorttyEntry fromString(String text) {
                return null;
            }
        }));
        Label header = new Label(I18n.get("settings.ai.memory.cleanup.archiveHeader"));
        header.setWrapText(true);
        VBox root = new VBox(10, header, list);
        root.setPadding(new Insets(14));
        getDialogPane().setContent(root);
        ButtonType archive = new ButtonType(I18n.get("settings.ai.memory.cleanup.archiveApply"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(archive, ButtonType.CANCEL);
        getDialogPane().setPrefSize(640, 420);
        setResultConverter(button -> button == archive ? selectedIds() : null);
    }

    private Set<String> selectedIds() {
        Set<String> ids = new LinkedHashSet<>();
        checked.forEach((id, selected) -> {
            if (selected.get()) {
                ids.add(id);
            }
        });
        return ids;
    }

    /** The ids of the chosen entries, or empty when the user cancelled or chose none. */
    static Optional<Set<String>> choose(Window owner, List<KorttyEntry> candidates) {
        return new AiMemoryArchiveDialog(owner, candidates).showAndWait().filter(ids -> !ids.isEmpty());
    }
}
