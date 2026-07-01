package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class MainWindowAiAskRequestTextTest {

    /**
     * The AI-agent "Ask" context-menu flow must answer the question about the captured terminal
     * selection. Regression guard: the question used to be duplicated as the "selected terminal
     * text", so the AI never saw the selection.
     */
    @Test
    void usesTerminalSelectionAsRequestTextWhenPresent() {
        assertThat(MainWindow.askRequestText("#!/bin/sh\necho hello", "was tut dieses script?"))
            .isEqualTo("#!/bin/sh\necho hello");
    }

    @Test
    void fallsBackToQuestionWithoutSelection() {
        assertThat(MainWindow.askRequestText(null, "was tut dieses script?"))
            .isEqualTo("was tut dieses script?");
        assertThat(MainWindow.askRequestText("   ", "was tut dieses script?"))
            .isEqualTo("was tut dieses script?");
    }
}
