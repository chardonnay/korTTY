package de.kortty.ui;

import de.kortty.core.SessionJournalAiPreflight;
import de.kortty.core.SessionJournalAiSupport;
import de.kortty.core.SessionJournalScreenshotAnalyzer;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSummarizer;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Re-evaluate with AI…": summarizes stored journals again with a profile the user picks — when
 * the first result was poor or the AI was not reachable while recording. The previous AI
 * summaries and the session summary are replaced; notes, screenshots and agent entries stay.
 * The chosen profile is tested before anything is removed.
 */
final class SessionJournalReevaluateDialog {

    private final Window owner;
    private final SessionJournalService service;
    private final SessionJournalSummarizer summarizer;
    private final SessionJournalScreenshotAnalyzer screenshotAnalyzer;
    private final GlobalSettings settings;
    private final Runnable onChanged;

    SessionJournalReevaluateDialog(Window owner, SessionJournalService service, SessionJournalSummarizer summarizer,
                                   SessionJournalScreenshotAnalyzer screenshotAnalyzer, GlobalSettings settings,
                                   Runnable onChanged) {
        this.owner = owner;
        this.service = service;
        this.summarizer = summarizer;
        this.screenshotAnalyzer = screenshotAnalyzer;
        this.settings = settings;
        this.onChanged = onChanged != null ? onChanged : () -> { };
    }

    /** Journals that can be re-evaluated: closed ones with a folder. */
    static List<SessionJournalMeta> eligible(List<SessionJournalMeta> journals) {
        return journals.stream()
            .filter(meta -> meta.getDirectory() != null && !meta.isLive())
            .toList();
    }

    void show(List<SessionJournalMeta> selection) {
        List<SessionJournalMeta> targets = eligible(selection);
        if (targets.isEmpty() || summarizer == null) {
            return;
        }
        List<AiProfile> profiles = settings != null && settings.getAiProfiles() != null
            ? settings.getAiProfiles() : List.of();
        ComboBox<AiProfile> profileCombo = new ComboBox<>();
        profileCombo.getItems().addAll(profiles);
        profileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiProfile profile) {
                return profile != null ? AutomationJournalConfigPane.profileLabel(profile) : "";
            }

            @Override
            public AiProfile fromString(String text) {
                return null;
            }
        });
        profileCombo.setMaxWidth(Double.MAX_VALUE);
        profileCombo.setValue(initialProfile(targets, profiles));

        boolean anyScreenshots = targets.stream().anyMatch(meta -> meta.getScreenshotCount() > 0);
        CheckBox screenshotsCheck = new CheckBox(I18n.get("journal.reevaluate.screenshots"));
        screenshotsCheck.setSelected(false);
        screenshotsCheck.setVisible(anyScreenshots && screenshotAnalyzer != null);
        screenshotsCheck.setManaged(screenshotsCheck.isVisible());

        Label info = new Label(I18n.get("journal.reevaluate.info"));
        info.setWrapText(true);
        info.setMaxWidth(460);
        Label countLabel = new Label(targets.size() == 1
            ? I18n.get("journal.reevaluate.single", titleOf(targets.get(0)))
            : I18n.get("journal.reevaluate.multiple", targets.size()));
        countLabel.setWrapText(true);
        countLabel.setMaxWidth(460);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(countLabel, 0, row++, 2, 1);
        grid.add(new Label(I18n.get("journal.reevaluate.profile")), 0, row);
        grid.add(profileCombo, 1, row++);
        grid.add(screenshotsCheck, 0, row++, 2, 1);
        grid.add(info, 0, row, 2, 1);

        ButtonType start = new ButtonType(I18n.get("journal.reevaluate.start"), ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(I18n.get("journal.reevaluate.title"));
        dialog.setHeaderText(I18n.get("journal.reevaluate.header"));
        dialog.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(start).disableProperty().bind(profileCombo.valueProperty().isNull());

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != start || profileCombo.getValue() == null) {
            return;
        }
        run(targets, profileCombo.getValue(), screenshotsCheck.isVisible() && screenshotsCheck.isSelected());
    }

    /** The profile that wrote the journal, else the journal profile from the settings, else the first one. */
    private AiProfile initialProfile(List<SessionJournalMeta> targets, List<AiProfile> profiles) {
        String usedId = targets.get(0).getAiProfileId();
        for (AiProfile profile : profiles) {
            if (usedId != null && usedId.equals(profile.getId())) {
                return profile;
            }
        }
        AiProfile configured = settings != null ? SessionJournalAiSupport.resolveProfile(settings) : null;
        if (configured != null) {
            for (AiProfile profile : profiles) {
                if (Objects.equals(profile.getId(), configured.getId())) {
                    return profile;
                }
            }
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private void run(List<SessionJournalMeta> targets, AiProfile profile, boolean screenshots) {
        Dialog<ButtonType> progressDialog = new Dialog<>();
        DialogThemeHelper.applyTheme(progressDialog);
        progressDialog.initOwner(owner);
        progressDialog.initModality(Modality.NONE);
        progressDialog.setTitle(I18n.get("journal.reevaluate.title"));
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        ProgressBar bar = new ProgressBar(-1);
        bar.setPrefWidth(380);
        Label statusLabel = new Label(I18n.get("journal.reevaluate.testing", profile.getName()));
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(380);
        VBox content = new VBox(8, statusLabel, bar);
        content.setPadding(new Insets(10));
        progressDialog.getDialogPane().setContent(content);
        AtomicBoolean cancelled = new AtomicBoolean();
        progressDialog.setOnHidden(event -> cancelled.set(true));
        progressDialog.show();

        SessionJournalAiSupport.AiInvoker invoker = SessionJournalAiSupport.profileInvoker(profile.getId());
        Thread worker = new Thread(() -> {
            SessionJournalAiPreflight.Result test = SessionJournalAiPreflight.check(invoker);
            if (!test.ok()) {
                // nothing was removed yet: the old evaluation stays untouched
                Platform.runLater(() -> finish(progressDialog, bar, statusLabel,
                    I18n.get("journal.reevaluate.testFailed", profile.getName(),
                        test.message() != null ? test.message() : ""), 0));
                return;
            }
            long tokensBefore = totalTokens(targets);
            List<String> failures = new ArrayList<>();
            int done = 0;
            for (SessionJournalMeta meta : targets) {
                if (cancelled.get()) {
                    break;
                }
                int index = done;
                Platform.runLater(() -> {
                    bar.setProgress((double) index / targets.size());
                    statusLabel.setText(I18n.get("journal.reevaluate.progress",
                        index + 1, targets.size(), titleOf(meta)));
                });
                try {
                    summarizer.reevaluateClosedJournal(meta.getDirectory(), invoker).get();
                    if (screenshots) {
                        describeScreenshots(meta.getDirectory(), invoker);
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    failures.add(titleOf(meta) + ": " + cause.getMessage());
                }
                done++;
            }
            long tokens = Math.max(0, totalTokens(targets) - tokensBefore);
            int processed = done - failures.size();
            String summary = I18n.get("journal.reevaluate.done", processed, String.format("%,d", tokens));
            if (!failures.isEmpty()) {
                summary += "\n" + I18n.get("journal.reevaluate.failed", failures.size(), String.join("\n", failures));
            }
            String text = summary;
            Platform.runLater(() -> finish(progressDialog, bar, statusLabel, text, 1));
        }, "SessionJournal-Reevaluate");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(Dialog<ButtonType> progressDialog, ProgressBar bar, Label statusLabel, String text, double progress) {
        onChanged.run();
        if (!progressDialog.isShowing()) {
            return;
        }
        bar.setProgress(progress);
        statusLabel.setText(text);
        progressDialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
    }

    private void describeScreenshots(Path directory, SessionJournalAiSupport.AiInvoker invoker) throws Exception {
        for (SessionJournalEntry entry : service.loadDocument(directory).getEntries()) {
            if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT) {
                screenshotAnalyzer.analyzeNow(directory, entry.getId(), invoker);
            }
        }
    }

    private long totalTokens(List<SessionJournalMeta> targets) {
        long total = 0;
        for (SessionJournalMeta meta : targets) {
            try {
                total += service.loadDocument(meta.getDirectory()).getMeta().getAiTotalTokens();
            } catch (Exception ignored) {
                // a journal that cannot be read contributes nothing
            }
        }
        return total;
    }

    private static String titleOf(SessionJournalMeta meta) {
        if (meta.getTitle() != null && !meta.getTitle().isBlank()) {
            return meta.getTitle();
        }
        return meta.getConnectionName() != null ? meta.getConnectionName() : String.valueOf(meta.getDirectory().getFileName());
    }
}
