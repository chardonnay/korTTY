package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * The run-to-chat handoff in the main window: only terminal runs with a task are handed over, the chat is
 * linked to its journal card only after the card is written, and a seeded chat opens through the saved-chat
 * path, which never runs the answer-rule learning of a fresh answer. A handoff binds the chat to the run's
 * pane, connection and secrets, keeps a question the chat cannot take, and is offered only where AI chats are
 * allowed.
 */
class AgentChatHandoffWiringSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static int countOf(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void theFinishedRunIsOfferedOnlyWhenItHasATaskAndBeforeItFinishes() throws IOException {
        String window = source("MainWindow");
        String finallyBlock = window.substring(window.indexOf("} finally {\n                // Every activity is published"));
        assertThat(finallyBlock).contains("runPrompt == null || runPrompt.isBlank() ? null : new AgentRunChatSource(");
        assertThat(finallyBlock).contains("AgentDesignHost.RunChatTarget chatTarget = new AgentDesignHost.RunChatTarget(");
        assertThat(finallyBlock).contains("aiSourcePane(terminalTab, resolvedRunContext), agentConnection, agentRunSecrets.get());");
        assertThat(finallyBlock.indexOf("activityPanel.offerChatHandoff(runId, chatSource, chatTarget)"))
            .isLessThan(finallyBlock.indexOf("activityPanel.finishRun(runId)"));
        assertThat(window).contains("activityPanel.setChatHandoffHandler(new AgentDesignHost.ChatHandoffHandler()");
    }

    @Test
    void askRunsNeverReachTheTerminalHandoff() throws IOException {
        String window = source("MainWindow");
        String launch = methodBody(window, "private boolean launchTerminalAgent(\n        TerminalTab terminalTab,\n        TerminalAgentModels.Request request,\n        TerminalView.TerminalAgentRunContext runContext,\n        String askSelectedText,");
        int askBranch = launch.indexOf("if (request.queryOnly()) {");
        int terminalRun = launch.indexOf("runTerminalAgentInTerminalWindow(");
        assertThat(askBranch).isAtLeast(0);
        assertThat(terminalRun).isGreaterThan(askBranch);
        assertThat(launch.substring(askBranch, terminalRun)).contains("return true;");
    }

    @Test
    void theRunsCardIsWaitedForWithATimeoutAndAFailedWriteFallsBackToANewCard() throws IOException {
        String window = source("MainWindow");
        String save = methodBody(window, "private SavedAiChat saveAgentRunChat(");
        assertThat(save).contains("awaitRunJournalCard(source)");
        assertThat(save.indexOf("awaitRunJournalCard(source)")).isLessThan(save.indexOf("saveChat(chat)"));
        assertThat(save).contains("if (journalDir == null && liveJournal != null) {");
        assertThat(save).doesNotContain("source.journalWrite().join()");
        String await = methodBody(window, "private boolean awaitRunJournalCard(");
        assertThat(await).contains("write.get(JOURNAL_CARD_WAIT_SECONDS");
        assertThat(await).doesNotContain(".join()");
        assertThat(window).contains("JOURNAL_CARD_WAIT_SECONDS = 10;");
    }

    @Test
    void theRunsCardCarriesTheMaskedTaskAndAnswer() throws IOException {
        String window = source("MainWindow");
        String save = methodBody(window, "private SavedAiChat saveAgentRunChat(");
        assertThat(save).contains("String title = chat.getTitle()");
        assertThat(save).doesNotContain("source.prompt().strip()");
        String card = methodBody(window, "private @Nullable AgentJournalCard appendAgentJournalEntry(");
        assertThat(card).contains("AiOutboundRedaction.redact(prompt.strip(), redactor).text()");
        assertThat(card).contains("entry.setText(AiOutboundRedaction.redact(answer.strip(), redactor).text());");
    }

    @Test
    void theJournalLinkIsWrittenBeforeTheChatFollowsTheJournal() throws IOException {
        String window = source("MainWindow");
        String link = methodBody(window, "private void linkJournalChat(");
        assertThat(link).contains("snapshot.setFrozen(!live)");
        int linked = link.indexOf("journals.linkChat(journalDir, entryId, chat.getId(), snapshot)");
        int registered = link.indexOf("register(chat.getId(), journalDir, entryId, redactor)");
        assertThat(linked).isAtLeast(0);
        assertThat(registered).isGreaterThan(linked);
        assertThat(link).contains("if (live) {");
        assertThat(link).doesNotContain("journalStopped(journalDir)");
    }

    @Test
    void aSeededChatOpensThroughTheSavedChatPathWithoutRuleLearning() throws IOException {
        String window = source("MainWindow");
        String open = methodBody(window, "boolean openAgentRunInChat(");
        assertThat(open).contains("openSavedAiChat(");
        assertThat(open).doesNotContain("showResult(");
        assertThat(open).doesNotContain("offerRuleLearning");
        assertThat(open).contains("findBySourceRunId(source.runId())");
        String tabs = source("AiResultTab");
        String followUp = methodBody(tabs, "boolean sendFollowUp(String question)");
        assertThat(followUp).doesNotContain("showResult(");
    }

    @Test
    void theHandoffBindsTheRunsPaneConnectionAndSecretsToTheExistingChatToo() throws IOException {
        String window = source("MainWindow");
        String open = methodBody(window, "boolean openAgentRunInChat(");
        // Once for the chat that already exists, once for the chat that is saved now.
        assertThat(countOf(open, "bindAgentRunChat(tab, redactor, bound);")).isEqualTo(2);
        assertThat(open.indexOf("bindAgentRunChat(tab, redactor, bound);"))
            .isLessThan(open.indexOf("sendHandoffQuestion(tab, question);"));
        String bind = methodBody(window, "private static void bindAgentRunChat(");
        assertThat(bind).contains("tab.setOutboundSecrets(redactor);");
        assertThat(bind).contains("tab.setSourcePane(target.pane());");
        assertThat(bind).contains("tab.setSourceConnection(target.connection());");
    }

    @Test
    void aQuestionTheChatCannotTakeIsKeptInItsComposerWithAStatusLine() throws IOException {
        String window = source("MainWindow");
        String send = methodBody(window, "private void sendHandoffQuestion(");
        assertThat(send).contains("if (question.isEmpty() || tab.sendFollowUp(question)) {");
        assertThat(send).contains("tab.keepInComposer(question);");
        assertThat(send).contains("updateStatus(I18n.get(\"aiAgentDesign.chatHandoff.kept\"));");
        String notSent = methodBody(window, "private void keepHandoffQuestionInStatus(");
        assertThat(notSent).contains("I18n.get(\"aiAgentDesign.chatHandoff.notSent\", question)");
        String open = methodBody(window, "boolean openAgentRunInChat(");
        assertThat(open).contains("return false;");
        assertThat(open).contains("return true;");
    }

    @Test
    void theHandoffIsOfferedOnlyWhereAiChatsAreAllowed() throws IOException {
        String window = source("MainWindow");
        String available = methodBody(window, "private boolean aiChatAvailable()");
        assertThat(available).contains("isAiFeaturesEnabled()");
        assertThat(available).contains("PolicyManager.effective().aiChatAllowed()");
        assertThat(methodBody(window, "boolean openAgentRunInChat(")).contains("!aiChatAvailable()");
        assertThat(window).contains("return aiChatAvailable();");
        assertThat(methodBody(window, "void openJournalChat(String chatId")).contains("chat.isPresent() && aiChatAvailable()");
    }

    @Test
    void theJournalChatOpensTheSavedChatOrItsReadOnlyCopy() throws IOException {
        String window = source("MainWindow");
        String open = methodBody(window, "void openJournalChat(String chatId");
        assertThat(open.indexOf("findById(chatId)")).isAtLeast(0);
        assertThat(open.indexOf("openSavedAiChat(chat.get())")).isLessThan(open.indexOf("showJournalSnapshotOnly()"));
        assertThat(open).contains("ai.result.agentRun.chatMissing");
    }

    @Test
    void theHostKeepsTheSourceAndTheTargetOfEachKeptRunAndOffersItInTheMenuAndTheBar() throws IOException {
        String host = source("AgentDesignHost");
        String prune = methodBody(host, "private void pruneFinishedRuns()");
        assertThat(prune).contains("chatSources.remove(runId)");
        assertThat(prune).contains("chatTargets.remove(runId)");
        String menu = methodBody(host, "List<javafx.scene.control.MenuItem> runMenuItems(String runId, @Nullable Node owner)");
        assertThat(menu.indexOf("I18n.get(\"aiAgentDesign.menu.continueInChat\")"))
            .isGreaterThan(menu.indexOf("I18n.get(\"aiAgentDesign.menu.copyAnswer\")"));
        assertThat(host).contains("getChildren().addAll(sentLink, chatHandoffBar, approvalStrip)");
        assertThat(methodBody(host, "public void beginRun(")).contains("hideChatHandoff();");
    }

    @Test
    void theBarLetsTheChatTakeTheQuestionAndHidesOnlyWhenItDid() throws IOException {
        String host = source("AgentDesignHost");
        String continueInChat = methodBody(host, "boolean continueInChat(String runId, @Nullable String followUp)");
        int opened = continueInChat.indexOf("if (!handler.open(runId, source, target, question.isEmpty() ? null : question)) {");
        assertThat(opened).isAtLeast(0);
        assertThat(continueInChat.indexOf("hideChatHandoff();")).isGreaterThan(opened);
        String bar = source("AgentChatHandoffBar");
        assertThat(bar).contains("void show(String runId, String runTitle, boolean finishedCleanly, boolean available) {");
        assertThat(bar).contains("if (!Objects.equals(this.runId, runId)) {");
        assertThat(bar).doesNotContain("boolean isShowing()");
    }

    @Test
    void theRunHooksThatNothingCallsAreGone() throws IOException {
        assertThat(source("AgentRunPresenter")).doesNotContain("continueInChat");
        assertThat(source("AgentDesignHost")).doesNotContain("public void continueInChat(String runId, String followUp)");
    }

    @Test
    void theJournalStopFreezesTheChatsBeforeTheSummaryCanFail() throws IOException {
        String view = source("TerminalView");
        String stop = methodBody(view, "public void stopSessionJournal()");
        assertThat(stop.indexOf("journalStopped(session.getDirectory())"))
            .isLessThan(stop.indexOf("onSessionClosing(session)"));
        assertThat(stop.indexOf("journalStopped(session.getDirectory())"))
            .isLessThan(stop.indexOf("session.close()"));
    }

    @Test
    void theHandoffQuestionTypedForAnotherRunStaysOutOfTheRunsChat() throws IOException {
        String window = source("MainWindow");
        assertThat(methodBody(window, "boolean openAgentRunInChat(")).contains("String question = followUp != null ? followUp.strip() : \"\";");
        String host = source("AgentDesignHost");
        assertThat(methodBody(host, "private void showChatHandoff(")).contains("chatHandoffBar.show(runId,");
    }

    @Test
    void theRunTranscriptForTheModelCarriesTheStepsOfAnAnswer() throws IOException {
        String tabs = source("AiResultTab");
        String transcript = methodBody(tabs, "private void appendToPlainTranscript(SavedAiChatMessage message)");
        assertThat(transcript).contains("AgentRunChatSeeder.modelSteps(message.getAgentSteps())");
    }

    @Test
    void theSnapshotOfAnAgentRunCollectsEveryActivityAndTheFinalAnswer() throws IOException {
        String window = source("MainWindow");
        String run = methodBody(window, "private void runTerminalAgentInTerminalWindow(");
        assertThat(run).contains("agentActivities.put(activity.id(), activity)");
        assertThat(run).contains("agentFinalAnswer.set(message)");
        assertThat(run).contains("agentFinalPhase.set(state.phase())");
    }

    @Test
    void theChatIsMaskedWithTheSecretsTheRunLearned() throws IOException {
        // A sudo password typed during the run lives only in the run's own redactor; the run publishes it.
        String service = Files.readString(Path.of("src/main/java/de/kortty/core/TerminalAgentService.java"));
        assertThat(service).contains("ui.publishRunSecrets(runId, outbound.knownSecrets());");
        String window = source("MainWindow");
        String run = methodBody(window, "private void runTerminalAgentInTerminalWindow(");
        assertThat(run).contains("agentRunSecrets.set(secrets);");
        assertThat(run).contains("aiSourcePane(terminalTab, resolvedRunContext), agentConnection, agentRunSecrets.get());");
        assertThat(methodBody(window, "boolean openAgentRunInChat(")).contains(
            "AgentOutboundContext.combine(\n            aiSecretRedactor(terminalTab), bound.runSecrets());");
    }

    @Test
    void theJournalCardIsMaskedLikeTheChatAndAJournalCopyIsNeverSaved() throws IOException {
        String window = source("MainWindow");
        assertThat(methodBody(window, "private @Nullable AgentJournalCard appendAgentJournalEntry(")).contains(
            "AgentOutboundContext.combine(\n            aiSecretRedactor(terminalTab), runSecrets);");
        assertThat(countOf(window, "agentTokensTotal.get(), agentRunSecrets.get()));")).isEqualTo(2);
        String tabs = source("AiResultTab");
        assertThat(methodBody(tabs, "private void updateSendAvailability()")).contains(
            "saveButton.setDisable(messageEntries.isEmpty() || journalSnapshotOnly);");
        assertThat(methodBody(tabs, "void showJournalSnapshotOnly()")).contains("updateSendAvailability();");
    }
}
