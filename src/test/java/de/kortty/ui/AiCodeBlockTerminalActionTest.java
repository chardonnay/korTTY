package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.codingagent.CodingAgentState;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.model.AiChatTerminalActions;
import de.kortty.policy.AgentExecutionMode;
import de.kortty.ui.AiCodeBlockTerminalAction.Action;
import de.kortty.ui.AiCodeBlockTerminalAction.Decision;
import de.kortty.ui.AiCodeBlockTerminalAction.PaneState;
import de.kortty.ui.AiCodeBlockTerminalAction.Policy;
import de.kortty.ui.AiCodeBlockTerminalAction.Verdict;
import de.kortty.ui.ShellIntegrationController.PromptState;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * The verdict for inserting an AI chat code block into a terminal pane or running it there, and the pane
 * it goes to. FX-free: the pane is a snapshot record.
 */
class AiCodeBlockTerminalActionTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final Policy ALLOW = new Policy(true, AgentExecutionMode.ALLOW);

    /** A connected, idle pane at a shell prompt that shell integration reports. */
    private static final PaneState READY = new PaneState(true, false, false, null, false, false, PromptState.AT_PROMPT);

    private static PaneState pane(boolean connected, boolean mirrored, boolean terminalAgent,
            CodingAgentState codingAgent, boolean inputBusy, boolean foreign, PromptState prompt) {
        return new PaneState(connected, mirrored, terminalAgent, codingAgent, inputBusy, foreign, prompt);
    }

    @DataProvider
    Object[][] verdicts() {
        return new Object[][] {
            // action, block, policy, pane, insert verdict, run verdict
            {"ready pane", "ls -la", ALLOW, READY, Verdict.OK, Verdict.OK},
            {"no target", "ls", ALLOW, null, Verdict.NO_TARGET, Verdict.NO_TARGET},
            {"chat denied", "ls", new Policy(false, AgentExecutionMode.ALLOW), READY,
                Verdict.POLICY_DENIED, Verdict.POLICY_DENIED},
            {"read-only", "ls", new Policy(true, AgentExecutionMode.READ_ONLY), READY,
                Verdict.OK, Verdict.POLICY_DENIED},
            {"confirm mode still runs after the confirmation", "ls", new Policy(true, AgentExecutionMode.CONFIRM),
                READY, Verdict.OK, Verdict.OK},
            {"full-screen program or paced paste", "ls", ALLOW,
                pane(true, false, false, null, true, false, PromptState.AT_PROMPT),
                Verdict.PANE_BUSY, Verdict.PANE_BUSY},
            {"terminal-agent run", "ls", ALLOW,
                pane(true, false, true, null, false, false, PromptState.AT_PROMPT),
                Verdict.AGENT_BUSY, Verdict.AGENT_BUSY},
            {"coding agent at work", "ls", ALLOW,
                pane(true, false, false, CodingAgentState.WORKING, false, false, PromptState.UNKNOWN),
                Verdict.OK, Verdict.AGENT_BUSY},
            {"coding agent waiting for an approval", "ls", ALLOW,
                pane(true, false, false, CodingAgentState.BLOCKED, false, false, PromptState.UNKNOWN),
                Verdict.CODING_AGENT_BLOCKED, Verdict.CODING_AGENT_BLOCKED},
            {"foreign session", "ls", ALLOW,
                pane(true, false, false, null, false, true, PromptState.AT_PROMPT),
                Verdict.OK, Verdict.FOREIGN_SESSION_CONFIRM},
            {"broadcast or multi-exec", "ls", ALLOW,
                pane(true, true, false, null, false, false, PromptState.AT_PROMPT),
                Verdict.BROADCAST_OR_MULTI_EXEC, Verdict.BROADCAST_OR_MULTI_EXEC},
            {"mask placeholder", "mysql -p" + SessionJournalRedactor.REPLACEMENT, ALLOW, READY,
                Verdict.CONTAINS_MASK_PLACEHOLDER, Verdict.CONTAINS_MASK_PLACEHOLDER},
            {"several lines", "cd /tmp\nls", ALLOW, READY, Verdict.OK, Verdict.RUN_MULTILINE_INSERT_ONLY},
            {"a carriage return splits lines too", "cd /tmp\rls", ALLOW, READY,
                Verdict.OK, Verdict.RUN_MULTILINE_INSERT_ONLY},
            {"a trailing line break is not a second line", "uptime\n", ALLOW, READY, Verdict.OK, Verdict.OK},
            {"command still running", "ls", ALLOW,
                pane(true, false, false, null, false, false, PromptState.COMMAND_RUNNING),
                Verdict.OK, Verdict.RUN_NOT_AT_PROMPT},
            {"disconnected", "ls", ALLOW,
                pane(false, true, true, CodingAgentState.BLOCKED, true, true, PromptState.COMMAND_RUNNING),
                Verdict.DISCONNECTED, Verdict.DISCONNECTED},
        };
    }

    @Test(dataProvider = "verdicts")
    void decidesEachCase(String label, String block, Policy policy, PaneState pane, Verdict insert, Verdict run) {
        assertWithMessage(label + " / insert")
            .that(AiCodeBlockTerminalAction.decide(Action.INSERT, block, policy, pane).verdict()).isEqualTo(insert);
        assertWithMessage(label + " / run")
            .that(AiCodeBlockTerminalAction.decide(Action.RUN, block, policy, pane).verdict()).isEqualTo(run);
    }

    @Test
    void theTableCoversEveryVerdict() {
        Set<Verdict> seen = EnumSet.noneOf(Verdict.class);
        for (Object[] row : verdicts()) {
            seen.add((Verdict) row[4]);
            seen.add((Verdict) row[5]);
        }
        assertThat(seen).containsExactlyElementsIn(EnumSet.allOf(Verdict.class));
    }

    @Test
    void readOnlyDeniesRunButNotInsert() {
        Policy readOnly = new Policy(true, AgentExecutionMode.READ_ONLY);
        assertThat(AiCodeBlockTerminalAction.decide(Action.RUN, "ls", readOnly, READY).verdict())
            .isEqualTo(Verdict.POLICY_DENIED);
        assertThat(AiCodeBlockTerminalAction.decide(Action.INSERT, "ls", readOnly, READY).verdict())
            .isEqualTo(Verdict.OK);
    }

    @Test
    void anUnreadablePolicyAllowsNothing() {
        Policy none = Policy.of(null);
        assertThat(none.allows(Action.INSERT)).isFalse();
        assertThat(none.allows(Action.RUN)).isFalse();
        assertThat(new Policy(true, null).allows(Action.RUN)).isFalse();
    }

    @Test
    void policyAndBlockVerdictsComeBeforeTheTarget() {
        // A button whose block can never act is greyed out with that reason, whichever pane is current.
        assertThat(AiCodeBlockTerminalAction.decide(Action.RUN, "a\nb", ALLOW, null).verdict())
            .isEqualTo(Verdict.RUN_MULTILINE_INSERT_ONLY);
        assertThat(AiCodeBlockTerminalAction.decide(Action.INSERT, "x ***", ALLOW, null).verdict())
            .isEqualTo(Verdict.CONTAINS_MASK_PLACEHOLDER);
        assertThat(AiCodeBlockTerminalAction.decide(Action.RUN, "ls", new Policy(true, AgentExecutionMode.READ_ONLY),
            null).verdict()).isEqualTo(Verdict.POLICY_DENIED);
    }

    @Test
    void runWithoutShellIntegrationMarksIsAllowedAndFlaggedForTheConfirmation() {
        PaneState noMarks = pane(true, false, false, null, false, false, PromptState.UNKNOWN);
        Decision run = AiCodeBlockTerminalAction.decide(Action.RUN, "uptime", ALLOW, noMarks);
        assertThat(run.verdict()).isEqualTo(Verdict.OK);
        assertThat(run.promptUnknown()).isTrue();
        assertThat(run.proceeds()).isTrue();

        assertThat(AiCodeBlockTerminalAction.decide(Action.RUN, "uptime", ALLOW, READY).promptUnknown()).isFalse();
        assertWithMessage("insert runs nothing, so it carries no prompt note")
            .that(AiCodeBlockTerminalAction.decide(Action.INSERT, "uptime", ALLOW, noMarks).promptUnknown()).isFalse();

        PaneState foreignNoMarks = pane(true, false, false, null, false, true, PromptState.UNKNOWN);
        Decision foreign = AiCodeBlockTerminalAction.decide(Action.RUN, "uptime", ALLOW, foreignNoMarks);
        assertThat(foreign.verdict()).isEqualTo(Verdict.FOREIGN_SESSION_CONFIRM);
        assertThat(foreign.promptUnknown()).isTrue();
        assertThat(foreign.proceeds()).isTrue();
    }

    @Test
    void onlyOkAndTheForeignSessionWarningProceed() {
        for (Verdict verdict : Verdict.values()) {
            assertWithMessage(verdict.name()).that(verdict.proceeds())
                .isEqualTo(verdict == Verdict.OK || verdict == Verdict.FOREIGN_SESSION_CONFIRM);
        }
    }

    @Test
    void aBlockWithTheMaskPlaceholderIsRefusedForBothActions() {
        String block = "export TOKEN=" + SessionJournalRedactor.REPLACEMENT;
        for (Action action : Action.values()) {
            assertThat(AiCodeBlockTerminalAction.decide(action, block, ALLOW, READY).verdict())
                .isEqualTo(Verdict.CONTAINS_MASK_PLACEHOLDER);
        }
    }

    @Test
    void runLineDropsOnlyTrailingLineBreaks() {
        assertThat(AiCodeBlockTerminalAction.runLine("ls -la\r\n\n")).isEqualTo("ls -la");
        assertThat(AiCodeBlockTerminalAction.runLine("  echo hi  ")).isEqualTo("  echo hi  ");
        assertThat(AiCodeBlockTerminalAction.runLine(null)).isEmpty();
    }

    @Test
    void anOpenSourcePaneIsTheTarget() {
        List<String> asked = new ArrayList<>();
        String target = AiCodeBlockTerminalAction.chooseTarget("source", p -> true, () -> {
            asked.add("fallback");
            return "focused";
        });
        assertThat(target).isEqualTo("source");
        assertWithMessage("the fallback is not even looked up").that(asked).isEmpty();
    }

    @Test
    void aClosedSourcePaneFallsBackToTheFocusedPane() {
        String target = AiCodeBlockTerminalAction.chooseTarget("closed", p -> !p.equals("closed"), () -> "focused");
        assertThat(target).isEqualTo("focused");
    }

    @Test
    void withoutABindingTheFocusedPaneIsTheTarget() {
        assertThat(AiCodeBlockTerminalAction.<String>chooseTarget(null, p -> true, () -> "focused"))
            .isEqualTo("focused");
    }

    @Test
    void noOpenPaneMeansNoTarget() {
        assertThat(AiCodeBlockTerminalAction.<String>chooseTarget(null, p -> true, () -> null)).isNull();
        assertThat(AiCodeBlockTerminalAction.chooseTarget("closed", p -> false, () -> "alsoClosed")).isNull();
    }

    @Test
    void resolveTargetWithoutBindingOrWindowIsNull() {
        assertThat(AiCodeBlockTerminalAction.resolveTarget(null, null)).isNull();
    }

    @Test
    void messageKeysAreCamelCase() {
        assertThat(Verdict.RUN_MULTILINE_INSERT_ONLY.messageKey())
            .isEqualTo("ai.result.terminal.verdict.runMultilineInsertOnly");
        assertThat(Verdict.OK.messageKey()).isEqualTo("ai.result.terminal.verdict.ok");
    }

    @Test
    void everyVerdictAndPaneTextIsTranslatedInEveryBundle() throws Exception {
        List<String> keys = new ArrayList<>();
        for (Verdict verdict : Verdict.values()) {
            keys.add(verdict.messageKey());
        }
        keys.add("ai.result.terminal.promptUnknown");
        keys.add("ai.result.terminal.paneName");
        keys.add("ai.result.terminal.unnamedTab");
        for (String bundle : BUNDLES) {
            Properties properties = new Properties();
            try (InputStream in = AiCodeBlockTerminalActionTest.class.getResourceAsStream("/i18n/" + bundle)) {
                assertWithMessage(bundle).that(in).isNotNull();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : keys) {
                String value = properties.getProperty(key);
                assertWithMessage(bundle + ": " + key).that(value).isNotNull();
                assertWithMessage(bundle + ": " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + ": " + key + " uses single apostrophes")
                    .that(value).doesNotContain("''");
            }
            assertWithMessage(bundle + ": the pane name carries the title and the number")
                .that(properties.getProperty("ai.result.terminal.paneName")).contains("{1}");
        }
    }

    @Test
    void aSplitPaneIsNamedWithItsNumber() {
        assertThat(TerminalPaneRef.displayName("web-01", 1, 1)).isEqualTo("web-01");
        String split = TerminalPaneRef.displayName("web-01", 2, 3);
        assertThat(split).contains("web-01");
        assertThat(split).contains("2");
        assertWithMessage("controls never reach the name")
            .that(TerminalPaneRef.displayName("evil\u001b[2Jtab", 0, 1)).doesNotContain("\u001b");
        assertThat(TerminalPaneRef.displayName("  ", 0, 1)).isNotEmpty();
    }

    @Test
    void chatsFromATerminalBindTheirPaneAndSavedChatsDoNot() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/de/kortty/ui/MainWindow.java"), StandardCharsets.UTF_8);
        assertWithMessage("the selection actions and Ask Agent bind the source pane")
            .that(occurrences(source, "resultTab.setSourcePane(sourcePane);")).isEqualTo(2);
        assertWithMessage("nothing else binds one: a reopened saved chat has no pane")
            .that(occurrences(source, ".setSourcePane(")).isEqualTo(2);
        assertThat(occurrences(source, "TerminalPaneRef sourcePane = aiSourcePane(terminalTab, runContext);"))
            .isEqualTo(2);
    }

    @Test
    void insertIsOfferedForEveryBlockUnlessTheSettingIsOff() {
        for (String language : new String[] {"bash", "python", "", null, "yaml"}) {
            assertWithMessage(String.valueOf(language))
                .that(AiCodeBlockTerminalAction.offers(Action.INSERT, AiChatTerminalActions.INSERT_ONLY, language, "x"))
                .isTrue();
            assertThat(AiCodeBlockTerminalAction.offers(Action.INSERT, AiChatTerminalActions.INSERT_AND_RUN, language, "x"))
                .isTrue();
            assertThat(AiCodeBlockTerminalAction.offers(Action.INSERT, AiChatTerminalActions.OFF, language, "x"))
                .isFalse();
        }
        assertWithMessage("a missing setting means the default, Insert and Run")
            .that(AiCodeBlockTerminalAction.offers(Action.RUN, null, "bash", "ls -la")).isTrue();
    }

    @Test
    void runIsOfferedOnlyForShellBlocksWhenTheSettingIncludesIt() {
        for (String shell : new String[] {"bash", "sh", "shell", "zsh", "Bash", "powershell", "pwsh", "ps1"}) {
            assertWithMessage(shell)
                .that(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_AND_RUN, shell, "ls -la\n"))
                .isTrue();
            assertWithMessage(shell + " under Insert only")
                .that(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_ONLY, shell, "ls -la"))
                .isFalse();
            assertThat(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.OFF, shell, "ls -la")).isFalse();
        }
        for (String other : new String[] {"python", "sql", "yaml", "json", "text", "", null, "console"}) {
            assertWithMessage(String.valueOf(other))
                .that(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_AND_RUN, other, "ls -la"))
                .isFalse();
        }
        assertWithMessage("an unlabelled block with a shell shebang is a shell block")
            .that(AiCodeBlockTerminalAction.isShellLanguage("", "#!/bin/bash\nls")).isTrue();
        assertWithMessage("a python shebang is not")
            .that(AiCodeBlockTerminalAction.isShellLanguage("", "#!/usr/bin/env python3\nprint(1)")).isFalse();
    }

    @Test
    void runIsNotOfferedForAShellBlockWithControlCharacters() {
        String[] hostile = {
            "echo hi\u001b[2J",       // escape sequence
            "sleep 9\u0003",          // Ctrl+C
            "rm -rf\u0000 /tmp/x",   // NUL
            "echo \u009b31m",         // C1 CSI
            "ls\u007f",               // DEL
            "ls\t-la",                // a tab asks the shell to complete the line
            "echo \u202egnp.exe",     // right-to-left override
            "echo \u2066x\u2069",     // bidi isolate
        };
        for (String line : hostile) {
            assertWithMessage(line.codePoints().mapToObj(Integer::toHexString).toList().toString())
                .that(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_AND_RUN, "bash", line))
                .isFalse();
            assertThat(AiCodeBlockTerminalAction.containsControlCharacters(line)).isTrue();
            assertWithMessage("Insert stays offered: paste protection strips the controls")
                .that(AiCodeBlockTerminalAction.offers(Action.INSERT, AiChatTerminalActions.INSERT_AND_RUN, "bash", line))
                .isTrue();
        }
        assertWithMessage("the trailing line break of a block is not a control character")
            .that(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_AND_RUN, "bash", "uptime\r\n"))
            .isTrue();
        assertThat(AiCodeBlockTerminalAction.containsControlCharacters("grep -E 'a|b' /var/log/syslog | tail -n 5"))
            .isFalse();
        assertThat(AiCodeBlockTerminalAction.containsControlCharacters(null)).isFalse();
    }

    @Test
    void aMultiLineShellBlockOffersRunGreyedOutWithTheReason() {
        String block = "cd /srv\nls\n";
        assertThat(AiCodeBlockTerminalAction.offers(Action.RUN, AiChatTerminalActions.INSERT_AND_RUN, "bash", block))
            .isTrue();
        assertThat(AiCodeBlockTerminalAction.decide(Action.RUN, block, new Policy(true, AgentExecutionMode.ALLOW), null)
            .verdict()).isEqualTo(Verdict.RUN_MULTILINE_INSERT_ONLY);
    }

    @Test
    void theButtonAndConfirmationTextsAreTranslatedInEveryBundle() throws Exception {
        List<String> keys = List.of(
            "ai.result.terminal.insert", "ai.result.terminal.insert.tooltip", "ai.result.terminal.run",
            "ai.result.terminal.run.tooltip", "ai.result.terminal.run.confirm.title",
            "ai.result.terminal.run.confirm.header", "ai.result.terminal.run.confirm.untrusted",
            "ai.result.terminal.run.confirm.run", "ai.result.terminal.status.inserted",
            "ai.result.terminal.status.ran", "ai.result.terminal.status.cancelled",
            "ai.result.terminal.status.failed", "settings.ai.chatTerminalActions",
            "settings.ai.chatTerminalActions.tooltip", "settings.ai.chatTerminalActions.off",
            "settings.ai.chatTerminalActions.insertOnly", "settings.ai.chatTerminalActions.insertAndRun");
        for (String bundle : BUNDLES) {
            Properties properties = new Properties();
            try (InputStream in = AiCodeBlockTerminalActionTest.class.getResourceAsStream("/i18n/" + bundle)) {
                assertWithMessage(bundle).that(in).isNotNull();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : keys) {
                String value = properties.getProperty(key);
                assertWithMessage(bundle + ": " + key).that(value).isNotNull();
                assertWithMessage(bundle + ": " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + ": " + key + " uses single apostrophes").that(value).doesNotContain("''");
            }
            for (String named : List.of("ai.result.terminal.insert.tooltip", "ai.result.terminal.run.tooltip",
                    "ai.result.terminal.run.confirm.header", "ai.result.terminal.status.inserted")) {
                assertWithMessage(bundle + ": " + named + " names the pane")
                    .that(properties.getProperty(named)).contains("{0}");
            }
        }
        for (AiChatTerminalActions value : AiChatTerminalActions.values()) {
            assertThat(SettingsDialog.aiChatTerminalActionsKey(value)).startsWith("settings.ai.chatTerminalActions.");
        }
    }

    @Test
    void theChatSendsThroughThePanePrecisePrimitivesOnly() throws Exception {
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/de/kortty/ui/AiResultTab.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertWithMessage("Insert goes through paste protection as AI text")
            .that(source).contains("view.pasteIntoPane(pane, text, PasteSource.AI);");
        assertWithMessage("Run writes one line to that pane only, never as a hidden one-liner")
            .that(source).contains("view.sendInputLineToPane(pane, line, false)");
        assertWithMessage("Cancel is the default button of the Run confirmation")
            .that(source).contains("cancelNode.setDefaultButton(true);");
        assertThat(occurrences(source, "AiCodeBlockTerminalButtons.create(")).isEqualTo(1);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
