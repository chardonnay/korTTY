package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Wiring that needs the full app or a live WebView to exercise: the manager's include-chats choice, the viewer
 * keeping chat transcripts open across reloads, the Local AI and General panes keeping unsaved edits and their
 * baselines, the single owner of the agent step blocks, and no English fallbacks for journal keys.
 */
class SessionJournalExportWiringSourceScanTest {

    private static String source(String package_, String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/" + package_ + "/" + name + ".java"))
            .replace("\r\n", "\n");
    }

    /** The body of a method from its signature to the closing brace at the same indent. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void theManagerPassesTheIncludeChatsChoiceAndNoOptionsConstructorDefaultsIt() throws IOException {
        String manager = source("ui", "SessionJournalManagerDialog");
        assertThat(manager).contains("new SessionJournalExportService.Options(choice.includeScreenshots(), choice.filter(),\n"
            + "                        choice.includeChats());");
        assertThat(manager).doesNotContain("Options(choice.includeScreenshots(), choice.filter())");

        String service = source("core", "SessionJournalExportService");
        // Only the canonical constructor; a shorter one would re-enable chats without anyone choosing it.
        assertThat(service.split("public Options\\(", -1).length - 1).isEqualTo(0);
        assertThat(service).contains("public Options {");
        assertThat(service).contains("public record Options(boolean includeScreenshots, SessionJournalExportFilter filter, boolean includeChats)");
    }

    @Test
    void theViewerRemembersOpenChatTranscriptsAcrossAReloadAndRestoresThemBeforeTheScroll() throws IOException {
        String viewer = source("ui", "SessionJournalViewerPane");
        assertThat(viewer).contains("private List<String> pendingOpenChatKeys = List.of();");
        assertThat(methodBody(viewer, "void renderAndLoad(String anchorEntryId)"))
            .contains("pendingOpenChatKeys = readOpenChatKeys();");
        assertThat(viewer).contains("details.chat-transcript");
        assertThat(viewer).contains("details.chat-steps");

        int restore = viewer.indexOf("runScript(restoreOpenChatsScript(pendingOpenChatKeys));");
        int scroll = viewer.indexOf("if (pendingScrollY > 0) {", restore);
        assertThat(restore).isAtLeast(0);
        assertThat(scroll).isGreaterThan(restore);
    }

    @Test
    void theLocalAiRefreshKeepsUnsavedEditsAndOnlyReloadsACleanForm() throws IOException {
        String pane = source("ui", "AiLocalPreferencesPane");
        String refresh = methodBody(pane, "void refresh(List<AiProfile> profiles)");
        int unsaved = refresh.indexOf("if (unsaved) {");
        int reload = refresh.indexOf("select(textProfile, settings != null");
        assertThat(unsaved).isAtLeast(0);
        assertThat(reload).isGreaterThan(unsaved);
        assertThat(refresh.substring(unsaved, reload)).contains("return;");
        assertThat(refresh).contains("FormState before = formState();");
        assertThat(pane).contains("savedState = formState();");
        // Saving is the only other point that moves the baseline.
        assertThat(methodBody(pane, "private void save()")).contains("savedState = formState();");
    }

    @Test
    void theGeneralPaneBaselineIsTakenAfterAnUntouchedProfileRefresh() throws IOException {
        String pane = source("ui", "AiGeneralSettingsPane");
        String refresh = methodBody(pane, "void refreshProfiles(List<AiProfile> profiles)");
        assertThat(refresh).contains("boolean untouched = !hasUnsavedChanges();");
        assertThat(refresh).contains("if (untouched) {\n            markSaved();");
        assertThat(pane).contains("void markSaved() {");
        // The quiet save path moves the baseline through the same method, not by a second copy of the assignment.
        assertThat(methodBody(pane, "boolean save(boolean quiet)")).contains("markSaved();");
    }

    @Test
    void everyChatExportWritesTheAgentStepsThroughTheFormatterOnly() throws IOException {
        String chat = source("core", "AiChatExportService");
        String journal = source("core", "SessionJournalExportService");
        for (String file : new String[] {chat, journal}) {
            assertThat(file).doesNotContain("private static String stepHeadline(");
            assertThat(file).doesNotContain("private static List<String> stepBody(");
            assertThat(file).doesNotContain("private static void appendPlainSteps(");
            assertThat(file).contains("AgentChatTranscriptFormatter.");
        }
        assertThat(chat).contains("AgentChatTranscriptFormatter.plainSteps(");
        assertThat(chat).contains("AgentChatTranscriptFormatter.markdownSteps(");
        assertThat(journal).contains("AgentChatTranscriptFormatter.stepHeadline(");
        assertThat(journal).contains("AgentChatTranscriptFormatter.stepBody(");
    }

    @Test
    void theFormatterHasNoDeadPlainTextRenderer() throws IOException {
        assertThat(source("core", "AgentChatTranscriptFormatter")).doesNotContain("toPlainText(");
    }

    @Test
    void journalChatAndExportTextUseTheBundleDirectlyWithoutEnglishFallbacks() throws IOException {
        for (String name : new String[] {"core/SessionJournalChatTranscript", "core/SessionJournalExportService"}) {
            String file = Files.readString(Path.of("src/main/java/de/kortty/" + name + ".java")).replace("\r\n", "\n");
            assertThat(file).doesNotContain("i18n(\"");
            assertThat(file).doesNotContain("private static String text(");
            assertThat(file).doesNotContain("The full chat is in KI-Chat.");
            assertThat(file).doesNotContain("MessageFormat");
        }
    }
}
