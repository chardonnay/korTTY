package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class TerminalAgentActivityModelTest {

    @Test
    void unknownTokenUsageStaysExplicitlyUnknown() {
        TerminalAgentModels.AgentActivityTokenUsage usage =
            TerminalAgentModels.AgentActivityTokenUsage.unknown();

        assertThat(usage.known()).isFalse();
        assertThat(usage.promptTokens()).isEqualTo(0L);
        assertThat(usage.completionTokens()).isEqualTo(0L);
        assertThat(usage.totalTokens()).isEqualTo(0L);
    }

    @Test
    void knownTokenUsageKeepsTotalAtLeastPromptPlusCompletion() {
        TerminalAgentModels.AgentActivityTokenUsage usage =
            new TerminalAgentModels.AgentActivityTokenUsage(true, 12L, 8L, 5L);

        assertThat(usage.known()).isTrue();
        assertThat(usage.promptTokens()).isEqualTo(12L);
        assertThat(usage.completionTokens()).isEqualTo(8L);
        assertThat(usage.totalTokens()).isEqualTo(20L);
    }

    @Test
    void completedThinkingActivityCanBeCollapsedWithPublicDetail() {
        TerminalAgentModels.AgentActivity activity = new TerminalAgentModels.AgentActivity(
            "run-1:thinking:1",
            TerminalAgentModels.AgentActivityType.THINKING,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "Thinking",
            "The next step is ready.",
            "Used the probe snapshot and command history.",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            6L,
            true,
            true);

        assertThat(activity.type()).isEqualTo(TerminalAgentModels.AgentActivityType.THINKING);
        assertThat(activity.status()).isEqualTo(TerminalAgentModels.AgentActivityStatus.COMPLETED);
        assertThat(activity.collapsible()).isTrue();
        assertThat(activity.collapsed()).isTrue();
        assertThat(activity.detail()).isEqualTo("Used the probe snapshot and command history.");
    }

    @Test
    void commandActivityTitleUsesSingleLinePreviewForScripts() {
        String command = """
            cat > biggest_files.py <<'EOF'
            #!/usr/bin/env python3
            import os
            EOF
            chmod +x biggest_files.py
            """;

        String title = TerminalAgentService.buildCommandActivityTitle("Read", command);

        assertThat(title).isEqualTo("Read(cat > biggest_files.py <<'EOF' ...)");
    }

    @Test
    void commandActivityTitleTruncatesLongSingleLineCommand() {
        String command = "python3 -c '" + "x".repeat(140) + "'";

        String title = TerminalAgentService.buildCommandActivityTitle("Run", command);

        assertThat(title.length() <= 101).isTrue();
        assertThat(title.startsWith("Run(python3 -c '")).isTrue();
    }
}
