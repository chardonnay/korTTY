package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.XmlStorableText;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.TerminalAgentModels.AgentActivity;
import de.kortty.model.TerminalAgentModels.AgentActivityStatus;
import de.kortty.model.TerminalAgentModels.AgentActivityType;
import de.kortty.model.TerminalAgentModels.Phase;
import org.testng.annotations.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AgentRunChatSeederTest {

    private static final Instant STARTED = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-03T10:00:42Z");

    private static AgentActivity command(String id, String command, String output) {
        return new AgentActivity(id, AgentActivityType.ACTION, AgentActivityStatus.COMPLETED,
            "Run(" + command + ")", "Exit 0", "Command: " + command + "\nExit code: 0\nstdout:\n" + output,
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 3L, false, false);
    }

    private static AgentActivity thinking(String detail) {
        return new AgentActivity("think-1", AgentActivityType.THINKING, AgentActivityStatus.COMPLETED,
            "Reasoning", "", detail, TerminalAgentModels.AgentActivityTokenUsage.unknown(), 1L, true, true);
    }

    private static AgentRunChatSource source(List<AgentActivity> activities, String answer, Phase phase) {
        return new AgentRunChatSource("run-1", "tab-1", "conn-1", "prod-shell",
            "Check the disk usage", answer, phase, "profile-1", "GPT Ops", "gpt-ops-model",
            STARTED, FINISHED, 42L, 1200L, activities, "journal-1", null, "entry-1", null);
    }

    private static SessionJournalRedactor redactorWith(String secret) {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret(secret);
        return redactor;
    }

    @Test
    void buildChatPutsTheTaskFirstAndTheAnswerSecondAndTiesTheChatToTheRun() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(command("a1", "df -h", "/dev/disk1 100G")), "The disk is 80% full.", Phase.DONE),
            null, "Disk usage");

        assertThat(chat.getTitle()).isEqualTo("Disk usage");
        assertThat(chat.getOrigin()).isEqualTo(SavedAiChat.ORIGIN_AGENT_RUN);
        assertThat(chat.isAgentRunChat()).isTrue();
        assertThat(chat.getSourceRunId()).isEqualTo("run-1");
        assertThat(chat.getSourceTerminalSessionId()).isEqualTo("tab-1");
        assertThat(chat.getSourceConnectionId()).isEqualTo("conn-1");
        assertThat(chat.getSourceJournalId()).isEqualTo("journal-1");
        assertThat(chat.getSourceJournalEntryId()).isEqualTo("entry-1");
        assertThat(chat.getActiveAiProfileId()).isEqualTo("profile-1");
        assertThat(chat.getActiveAiProfileName()).isEqualTo("GPT Ops");

        List<SavedAiChatMessage> messages = chat.getMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo(SavedAiChatMessage.ROLE_USER);
        assertThat(messages.get(0).getContent()).isEqualTo("Check the disk usage");
        assertThat(messages.get(0).getCreatedAt()).isEqualTo(STARTED.toEpochMilli());
        assertThat(messages.get(1).getRole()).isEqualTo(SavedAiChatMessage.ROLE_ASSISTANT);
        assertThat(messages.get(1).getContent()).isEqualTo("The disk is 80% full.");
        assertThat(messages.get(1).getCreatedAt()).isEqualTo(FINISHED.toEpochMilli());
        assertThat(messages.get(1).getAiProfileName()).isEqualTo("GPT Ops");
        assertThat(messages.get(1).getAgentSteps()).hasSize(1);
        assertThat(messages.get(0).getAgentSteps()).isEmpty();
    }

    @Test
    void aBlankTitleFallsBackToTheTask() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(), "Done.", Phase.DONE), null, " ");

        assertThat(chat.getTitle()).isEqualTo("Check the disk usage");
    }

    @Test
    void thinkingStepsAreNeverStored() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(source(List.of(
            thinking("private chain of thought"),
            command("a1", "uptime", " 10:00 up 3 days")), "Up for three days.", Phase.DONE), null, "t");

        List<SavedAgentStep> steps = chat.getMessages().get(1).getAgentSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getType()).isEqualTo("ACTION");
        assertThat(steps.get(0).getCommand()).isEqualTo("uptime");
    }

    @Test
    void commandStepKeepsCommandExitCodeAndOutput() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(command("a1", "df -h", "/dev/disk1 100G\n/dev/disk2 50G")), "ok", Phase.DONE),
            null, "t");

        SavedAgentStep step = chat.getMessages().get(1).getAgentSteps().get(0);
        assertThat(step.getTitle()).isEqualTo("Run(df -h)");
        assertThat(step.getCommand()).isEqualTo("df -h");
        assertThat(step.getExitCode()).isEqualTo(0);
        assertThat(step.getOutput()).isEqualTo("/dev/disk1 100G\n/dev/disk2 50G");
        assertThat(step.isTruncated()).isFalse();
        assertThat(step.getElapsedSeconds()).isEqualTo(3L);
    }

    @Test
    void secretsInTheTaskAnswerAndCommandOutputAreMasked() {
        SessionJournalRedactor redactor = redactorWith("hunter2-secret");
        SavedAiChat chat = AgentRunChatSeeder.buildChat(source(List.of(
            command("a1", "cat /etc/app.conf", "password=hunter2-secret")),
            "Use hunter2-secret to log in.", Phase.DONE), redactor, "t");

        assertThat(chat.getMessages().get(0).getContent()).doesNotContain("hunter2-secret");
        assertThat(chat.getMessages().get(1).getContent()).doesNotContain("hunter2-secret");
        assertThat(chat.getMessages().get(1).getContent()).contains("***");
        SavedAgentStep step = chat.getMessages().get(1).getAgentSteps().get(0);
        assertThat(step.getOutput()).doesNotContain("hunter2-secret");
        assertThat(step.getOutput()).contains("***");
    }

    @Test
    void ansiSequencesAndXmlInvalidCharactersAreRemoved() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(source(List.of(
            command("a1", "ls --color", "\u001B[31mred\u001B[0m ok\u0001\u0007 done\u001B]0;title\u0007")),
            "\u001B[1mBold\u001B[0m answer\u0000", Phase.DONE), null, "t");

        SavedAgentStep step = chat.getMessages().get(1).getAgentSteps().get(0);
        assertThat(step.getOutput()).isEqualTo("red ok done");
        assertThat(chat.getMessages().get(1).getContent()).isEqualTo("Bold answer");
        assertThat(XmlStorableText.isStorable(step.getOutput())).isTrue();
    }

    @Test
    void longOutputKeepsHeadAndTailWithAMarkerAndIsFlaggedAsTruncated() {
        String output = "a".repeat(10_000) + "b".repeat(10_000);
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(command("a1", "cat big.log", output)), "ok", Phase.DONE), null, "t");

        SavedAgentStep step = chat.getMessages().get(1).getAgentSteps().get(0);
        assertThat(step.getOutput().length()).isAtMost(AgentRunChatSeeder.STEP_OUTPUT_CHARS);
        assertThat(step.getOutput()).contains(" ... ");
        assertThat(step.getOutput()).startsWith("aaaa");
        assertThat(step.getOutput()).endsWith("bbbb");
        assertThat(step.isTruncated()).isTrue();
    }

    @Test
    void blockedAndCancelledRunsWithoutAnAnswerStillGiveAVisibleAnswer() {
        SavedAiChat blocked = AgentRunChatSeeder.buildChat(source(List.of(), "  ", Phase.BLOCKED), null, "t");
        SavedAiChat cancelled = AgentRunChatSeeder.buildChat(source(List.of(), null, Phase.CANCELLED), null, "t");
        SavedAiChat done = AgentRunChatSeeder.buildChat(source(List.of(), "Final.", Phase.DONE), null, "t");

        String blockedText = blocked.getMessages().get(1).getContent();
        String cancelledText = cancelled.getMessages().get(1).getContent();
        assertThat(blockedText).isNotEmpty();
        assertThat(cancelledText).isNotEmpty();
        assertThat(blockedText).isNotEqualTo(cancelledText);
        assertThat(done.getMessages().get(1).getContent()).isEqualTo("Final.");
    }

    @Test
    void thinkingInTheAnswerIsSeparatedLikeInTheChat() {
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(), "<think>internal</think>Visible answer.", Phase.DONE), null, "t");

        assertThat(chat.getMessages().get(1).getContent()).isEqualTo("Visible answer.");
    }

    @Test
    void defaultTitleIsOneLineAndAtMostEightyCharacters() {
        String title = AgentRunChatSeeder.defaultTitle("first line\n" + "x".repeat(200));

        assertThat(title).doesNotContain("\n");
        assertThat(title.length()).isAtMost(AgentRunChatSeeder.TITLE_CHARS);
        assertThat(title).startsWith("first line xxx");
        assertThat(title).endsWith("…");
        assertThat(AgentRunChatSeeder.defaultTitle("short task")).isEqualTo("short task");
        assertThat(AgentRunChatSeeder.defaultTitle("   ")).isNotEmpty();
    }

    @Test
    void modelStepsKeepTitlesAndExitCodesAndDropOutputOldestFirst() {
        List<SavedAgentStep> steps = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            SavedAgentStep step = new SavedAgentStep();
            step.setType("ACTION");
            step.setStatus("COMPLETED");
            step.setTitle("Run(cmd-" + i + ")");
            step.setCommand("cmd-" + i);
            step.setExitCode(0);
            step.setOutput("line-" + i + " " + "x".repeat(3_000));
            steps.add(step);
        }

        String text = AgentRunChatSeeder.modelSteps(steps, 2_000);

        assertThat(text.length()).isAtMost(2_000 + 200);
        for (int i = 1; i <= 5; i++) {
            assertThat(text).contains("Run(cmd-" + i + ")");
        }
        assertThat(text).contains("exit 0");
        assertThat(text).contains("[output omitted to fit the budget]");
        // The oldest step gives up its output first; the newest keeps it while the budget allows.
        assertThat(text.indexOf("[output omitted", text.indexOf("Run(cmd-1)")))
            .isLessThan(text.indexOf("Run(cmd-2)"));
    }

    @Test
    void modelStepsKeepTheHeadAndTailOfALongOutputWithAMarker() {
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setStatus("COMPLETED");
        step.setTitle("Run(tail)");
        step.setOutput("HEAD" + "m".repeat(9_000) + "TAIL");

        String text = AgentRunChatSeeder.modelSteps(List.of(step));

        assertThat(text).startsWith("- [completed] Run(tail)");
        assertThat(text).contains(" ... ");
        assertThat(text).contains("| HEAD");
        assertThat(text).contains("TAIL");
        assertThat(text).doesNotContain("[output omitted");
    }

    @Test
    void modelStepsWithoutAnyStepsIsEmpty() {
        assertThat(AgentRunChatSeeder.modelSteps(List.of())).isEmpty();
        assertThat(AgentRunChatSeeder.modelSteps(null)).isEmpty();
    }

    @Test
    void journalSnapshotMasksTheCopyAgainAndLeavesFrozenOff() {
        SessionJournalRedactor redactor = redactorWith("hunter2-secret");
        SavedAiChat chat = AgentRunChatSeeder.buildChat(source(List.of(
            command("a1", "echo x", "token hunter2-secret")), "Answer.", Phase.DONE), null, "Chat");
        OffsetDateTime captured = OffsetDateTime.now();

        SessionJournalChatSnapshot snapshot = AgentRunChatSeeder.journalSnapshot(chat, redactor, captured);

        assertThat(snapshot.getTitle()).isEqualTo("Chat");
        assertThat(snapshot.getCapturedAt()).isEqualTo(captured);
        assertThat(snapshot.isFrozen()).isFalse();
        assertThat(snapshot.getMessages()).hasSize(2);
        assertThat(snapshot.getMessages().get(1).getSteps().get(0).getOutput()).doesNotContain("hunter2-secret");
        assertThat(snapshot.getMessages().get(1).getSteps().get(0).getOutput()).contains("***");
        assertThat(snapshot.toSavedMessages().get(1).getAgentSteps().get(0).getOutput()).contains("***");
    }

    @Test
    void aSecretThatStraddlesTheTitleLimitLeavesNoPrefixInTheTitle() {
        // The secret starts before the 79th character and ends after it: cut first, its prefix would survive.
        String prompt = "a".repeat(70) + " hunter2-secret-value and more";
        AgentRunChatSource run = new AgentRunChatSource("run-1", "tab-1", "conn-1", "prod-shell",
            prompt, "answer", Phase.DONE, "profile-1", "GPT Ops", "gpt-ops-model",
            STARTED, FINISHED, 42L, 1200L, List.of(), "journal-1", null, "entry-1", null);
        SavedAiChat chat = AgentRunChatSeeder.buildChat(run, redactorWith("hunter2-secret-value"), null);

        assertThat(chat.getTitle()).doesNotContain("hunter2");
        assertThat(chat.getTitle()).doesNotContain("secret");
        assertThat(chat.getTitle()).contains("***");
        assertThat(chat.getTitle().length()).isAtMost(AgentRunChatSeeder.TITLE_CHARS);
    }

    @Test
    void theRunsClosingMessageIsNotRepeatedAsAStep() {
        AgentActivity closing = new AgentActivity("run-1:message:done-2", AgentActivityType.MESSAGE,
            AgentActivityStatus.COMPLETED, "The disk is 80% full.", "The disk is 80% full.", "Clean /var/log.",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, true, true);
        AgentActivity progress = new AgentActivity("run-1:message:note-1", AgentActivityType.MESSAGE,
            AgentActivityStatus.COMPLETED, "Checking the mounts first", "Checking the mounts first", "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(), 0L, false, false);
        SavedAiChat chat = AgentRunChatSeeder.buildChat(
            source(List.of(progress, command("a1", "df -h", "/dev/disk1 100G"), closing),
                "The disk is 80% full.\n\nClean /var/log.", Phase.DONE),
            null, null);

        List<SavedAgentStep> steps = chat.getMessages().get(1).getAgentSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getTitle()).isEqualTo("Checking the mounts first");
        assertThat(steps.get(1).getCommand()).isEqualTo("df -h");
    }
}
