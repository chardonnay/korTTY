package de.kortty.ui;

import de.kortty.core.SessionJournalAiSupport;
import de.kortty.core.SessionJournalReevaluationJob;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * "Re-evaluate with AI…": summarizes stored journals again with a profile the user picks — when
 * the first result was poor or the AI was not reachable while recording. The previous AI
 * summaries and the session summary are replaced; notes, screenshots and agent entries stay.
 * The chosen profile is tested before anything is removed.
 *
 * <p>This dialog only asks; the run belongs to {@link SessionJournalReevaluationJob} and goes on in
 * the background, shown by {@link SessionJournalReevaluateProgressWindow} and the journal manager.</p>
 */
final class SessionJournalReevaluateDialog {

    private final Window owner;
    private final GlobalSettings settings;
    private final SessionJournalReevaluationJob job;
    private final Supplier<Window> progressOwner;

    /**
     * @param progressOwner the window the progress window belongs to — the main window, so it stays
     *                      when the journal manager closes
     */
    SessionJournalReevaluateDialog(Window owner, GlobalSettings settings, SessionJournalReevaluationJob job,
                                   Supplier<Window> progressOwner) {
        this.owner = owner;
        this.settings = settings;
        this.job = job;
        this.progressOwner = progressOwner != null ? progressOwner : () -> owner;
    }

    /** Journals that can be re-evaluated: closed ones with a folder. */
    static List<SessionJournalMeta> eligible(List<SessionJournalMeta> journals) {
        return journals.stream()
            .filter(meta -> meta.getDirectory() != null && !meta.isLive())
            .toList();
    }

    /** {@link #eligible(List)} without the journals a background run already works on or waits for. */
    static List<SessionJournalMeta> eligible(List<SessionJournalMeta> journals, SessionJournalReevaluationJob job) {
        return eligible(journals).stream()
            .filter(meta -> job == null
                || job.stateOf(meta.getDirectory()) == SessionJournalReevaluationJob.JournalState.NONE)
            .toList();
    }

    void show(List<SessionJournalMeta> selection) {
        List<SessionJournalMeta> targets = eligible(selection, job);
        if (targets.isEmpty() || job == null) {
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
        screenshotsCheck.setVisible(anyScreenshots && job.canDescribeScreenshots());
        screenshotsCheck.setManaged(screenshotsCheck.isVisible());
        Runnable updateVision = () -> {
            AiProfile chosen = profileCombo.getValue();
            boolean vision = chosen != null && (de.kortty.core.AiVisionSupport.isVisionCapable(chosen)
                || de.kortty.core.AiVisionLiveCheck.probeEligible(chosen));
            screenshotsCheck.setDisable(!vision);
            if (!vision) {
                screenshotsCheck.setSelected(false);
            }
            screenshotsCheck.setTooltip(vision ? null
                : new javafx.scene.control.Tooltip(I18n.get("journal.reevaluate.noVision")));
        };
        profileCombo.valueProperty().addListener((obs, old, value) -> updateVision.run());
        updateVision.run();

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

    /** Hands the run to the background job and shows its progress; returns at once. */
    private void run(List<SessionJournalMeta> targets, AiProfile profile, boolean screenshots) {
        List<SessionJournalReevaluationJob.Target> jobTargets = targets.stream()
            .map(meta -> new SessionJournalReevaluationJob.Target(meta.getDirectory(), titleOf(meta)))
            .toList();
        job.submit(new SessionJournalReevaluationJob.Request(jobTargets, profile.getId(), profile.getName(),
            SessionJournalReevaluationJob.invokerFor(profile), screenshots));
        SessionJournalReevaluateProgressWindow.show(progressOwner.get(), job);
    }

    static String titleOf(SessionJournalMeta meta) {
        if (meta.getTitle() != null && !meta.getTitle().isBlank()) {
            return meta.getTitle();
        }
        return meta.getConnectionName() != null ? meta.getConnectionName() : String.valueOf(meta.getDirectory().getFileName());
    }
}
