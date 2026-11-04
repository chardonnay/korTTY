package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * "Re-evaluate with AI…" runs in the background: no window owns the run, closing one does not cancel
 * it, the manager shows it and guards the journals it works on, and quitting asks first.
 */
class SessionJournalReevaluationWiringSourceScanTest {

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/de/kortty/ui", name));
    }

    @Test
    void theSetupDialogHandsTheRunToTheJob() throws Exception {
        String dialog = source("SessionJournalReevaluateDialog.java");

        assertThat(dialog).contains("job.submit(new SessionJournalReevaluationJob.Request(");
        assertThat(dialog).doesNotContain("new Thread(");
        assertThat(dialog).doesNotContain("reevaluateClosedJournal");
    }

    @Test
    void closingTheProgressWindowLeavesTheRunGoing() throws Exception {
        String window = source("SessionJournalReevaluateProgressWindow.java");
        String onHidden = window.substring(window.indexOf("dialog.setOnHidden("), window.indexOf("job.addListener(listener);"));

        assertThat(onHidden).doesNotContain("job.cancel()");
        assertThat(window).contains("ButtonBar.ButtonData.CANCEL_CLOSE");
    }

    @Test
    void theManagerShowsTheRunAndGuardsItsJournals() throws Exception {
        String manager = source("SessionJournalManagerDialog.java");

        assertThat(manager).contains("reevaluationBar.getNode()");
        assertThat(manager).contains("reevaluationBar.dispose()");
        assertThat(manager).contains("noneSelected.or(anyLiveSelected).or(anyReevaluatingSelected)");
        assertThat(manager).contains("SessionJournalReevaluateDialog.eligible(selectedJournals(), reevaluationJob)");
        assertThat(manager).contains("SessionJournalBadges.reevaluation(reevaluationState(node))");
    }

    @Test
    void quittingAsksWhileARunGoesOn() throws Exception {
        String mainWindow = source("MainWindow.java");

        assertThat(mainWindow).contains("if (closesApplication && !confirmQuitWhileReevaluatingJournals()) {");
    }
}
