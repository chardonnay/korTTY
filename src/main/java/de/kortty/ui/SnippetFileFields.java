package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetExecutableSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.model.Snippet;
import javafx.beans.Observable;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The snippet editor's file fields: the library folder, the file name used when the snippet is
 * exported or copied to a server, and the executable flag (automatic / on / off).
 */
final class SnippetFileFields {

    /** A folder entry of the editor's folder box; {@code folderId == null} is the top level. */
    record FolderChoice(String folderId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    enum ExecutableChoice {
        AUTO("snippets.executable.auto"), ON("snippets.executable.on"), OFF("snippets.executable.off");

        private final String key;

        ExecutableChoice(String key) {
            this.key = key;
        }

        Boolean value() {
            return this == AUTO ? null : this == ON;
        }

        static ExecutableChoice of(Boolean value) {
            return value == null ? AUTO : value ? ON : OFF;
        }

        @Override
        public String toString() {
            return I18n.get(key);
        }
    }

    private SnippetFileFields() {
    }

    /** The file fields a snippet was loaded with; {@link #NONE} for a new snippet (every field is written). */
    record Loaded(boolean known, String folderId, String fileName, Boolean executable) {
        static final Loaded NONE = new Loaded(false, null, null, null);

        static Loaded of(Snippet snippet) {
            return new Loaded(true, snippet.getFolderId(), snippet.getFileName(), snippet.getExecutable());
        }

        /** Writes each field that differs from what was loaded (all of them for a new snippet). */
        void applyChanges(Snippet target, String folderId, String fileName, Boolean executable) {
            String normalizedFileName = fileName == null || fileName.isBlank() ? null : fileName;
            if (!known || !Objects.equals(this.folderId, folderId)) {
                target.setFolderId(folderId);
            }
            if (!known || !Objects.equals(this.fileName, normalizedFileName)) {
                target.setFileName(normalizedFileName);
            }
            if (!known || !Objects.equals(this.executable, executable)) {
                target.setExecutable(executable);
            }
        }
    }

    static void setUp(ComboBox<FolderChoice> folderCombo, TextField fileNameField,
                      ComboBox<ExecutableChoice> executableCombo, Supplier<String> language,
                      Supplier<String> name, Supplier<String> content, Runnable changed, Observable... triggers) {
        folderCombo.getItems().setAll(folderChoices());
        folderCombo.getSelectionModel().selectFirst();
        folderCombo.setPrefWidth(180);
        folderCombo.valueProperty().addListener((obs, was, now) -> changed.run());

        fileNameField.setPrefColumnCount(14);
        fileNameField.textProperty().addListener((obs, was, now) -> changed.run());

        executableCombo.getItems().setAll(ExecutableChoice.values());
        executableCombo.setValue(ExecutableChoice.AUTO);
        executableCombo.valueProperty().addListener((obs, was, now) -> changed.run());

        Runnable refreshHints = () -> {
            String derived = derivedFileName(name.get(), language.get(), content.get(), null);
            fileNameField.setPromptText(derived);
            String effectiveName = fileNameField.getText() != null && !fileNameField.getText().isBlank()
                ? fileNameField.getText() : derived;
            boolean automatic = SnippetExecutableSupport.defaultExecutable(effectiveName, content.get());
            executableCombo.setTooltip(new Tooltip(I18n.get(automatic
                ? "snippets.executable.autoOn" : "snippets.executable.autoOff", effectiveName)));
        };
        refreshHints.run();
        for (Observable trigger : triggers) {
            trigger.addListener(observable -> refreshHints.run());
        }
        fileNameField.textProperty().addListener(observable -> refreshHints.run());
        executableCombo.setOnShowing(event -> refreshHints.run());
    }

    static void load(ComboBox<FolderChoice> folderCombo, TextField fileNameField,
                     ComboBox<ExecutableChoice> executableCombo, Snippet snippet) {
        String folderId = snippet.getFolderId();
        FolderChoice match = folderCombo.getItems().stream()
            .filter(choice -> Objects.equals(choice.folderId(), folderId))
            .findFirst()
            .orElse(null);
        if (match == null && folderId != null) {
            match = new FolderChoice(folderId, folderId);
            folderCombo.getItems().add(match);
        }
        folderCombo.setValue(match != null ? match : folderCombo.getItems().getFirst());
        fileNameField.setText(snippet.getFileName() != null ? snippet.getFileName() : "");
        executableCombo.setValue(ExecutableChoice.of(snippet.getExecutable()));
    }

    static String folderId(ComboBox<FolderChoice> folderCombo) {
        FolderChoice choice = folderCombo.getValue();
        return choice != null ? choice.folderId() : null;
    }

    static Boolean executable(ComboBox<ExecutableChoice> executableCombo) {
        ExecutableChoice choice = executableCombo.getValue();
        return choice != null ? choice.value() : null;
    }

    private static String derivedFileName(String name, String language, String content, String fileName) {
        Snippet probe = new Snippet(name != null && !name.isBlank() ? name : "snippet", content,
            SnippetLanguageSupport.detectSnippetLanguage(language, content));
        probe.setFileName(fileName);
        return SnippetExecutableSupport.fileNameOf(probe);
    }

    private static List<FolderChoice> folderChoices() {
        List<FolderChoice> choices = new ArrayList<>();
        choices.add(new FolderChoice(null, I18n.get("snippets.folder.topLevel")));
        SnippetManager manager = snippetManager();
        if (manager != null) {
            manager.getAllFolders().stream()
                .map(folder -> new FolderChoice(folder.getId(), manager.folderPath(folder.getId())))
                .sorted(Comparator.comparing(FolderChoice::label, String.CASE_INSENSITIVE_ORDER))
                .forEach(choices::add);
        }
        return choices;
    }

    private static SnippetManager snippetManager() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            return app != null ? app.getSnippetManager() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
