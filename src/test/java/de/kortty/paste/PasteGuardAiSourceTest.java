package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.testng.annotations.Test;

/**
 * The strict floor {@link PasteGuard} gives text from {@link PasteSource#AI}: line breaks and control
 * characters always ask, even under relaxed per-connection rules; control and bidi characters never
 * reach the pane; a mirrored pane (broadcast or multi-exec) gets nothing and no prompt.
 */
class PasteGuardAiSourceTest {

    private static final String ESC = "\u001b";

    /** The rules of a connection that switched paste warnings off for itself. */
    private static final PasteRules RELAXED_BY_CONNECTION =
        new PasteDecision(PasteProtectionSettings.DISABLED, true);

    private static final class FakeTarget implements PasteTarget {
        final Object key = new Object();
        final List<String> sent = new ArrayList<>();
        final Object session = new Object();
        boolean bracketed;
        boolean broadcast;
        boolean multiExec;
        RuntimeException mirrorFailure;

        @Override
        public Object key() {
            return key;
        }

        @Override
        public boolean bracketedPasteMode() {
            return bracketed;
        }

        @Override
        public boolean canReceive() {
            return true;
        }

        @Override
        public Object session() {
            return session;
        }

        @Override
        public void send(String payload) {
            sent.add(payload);
        }

        @Override
        public String label() {
            return "web-01";
        }

        @Override
        public boolean broadcastActive() {
            if (mirrorFailure != null) {
                throw mirrorFailure;
            }
            return broadcast;
        }

        @Override
        public boolean multiExecActive() {
            return multiExec;
        }
    }

    private static final class RecordingConfirmer implements PasteConfirmer {
        final List<PasteConfirmationRequest> requests = new ArrayList<>();
        final List<Consumer<Boolean>> answers = new ArrayList<>();

        @Override
        public void confirm(PasteConfirmationRequest request, Consumer<Boolean> answer) {
            requests.add(request);
            answers.add(answer);
        }
    }

    @Test
    void multiLineAiTextAsksEvenWhenTheConnectionRelaxesPasteProtection() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(target -> RELAXED_BY_CONNECTION, confirmer, null, target -> 0);
        FakeTarget target = new FakeTarget();
        target.bracketed = true;

        guard.paste(target, "cd /srv\nls -la\n", PasteSource.AI);

        assertWithMessage("bracketed or not, a line break in AI text asks (D2)")
            .that(confirmer.requests).hasSize(1);
        PasteConfirmationRequest request = confirmer.requests.get(0);
        assertThat(request.reasons()).containsExactly(PasteReason.MULTI_LINE);
        assertThat(request.source()).isEqualTo(PasteSource.AI);
        assertThat(request.text()).isEqualTo("cd /srv\nls -la\n");
        assertThat(target.sent).isEmpty();

        confirmer.answers.get(0).accept(true);

        assertThat(target.sent).containsExactly(ESC + "[200~cd /srv\rls -la\r" + ESC + "[201~");
    }

    @Test
    void aCancelledAiPasteSendsNothing() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> RELAXED_BY_CONNECTION, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.AI);
        confirmer.answers.get(0).accept(false);

        assertThat(target.sent).isEmpty();
    }

    @Test
    void controlCharactersInAiTextAskAndAreStrippedBeforeSending() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> RELAXED_BY_CONNECTION, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "echo hi\u0003" + ESC + "[31m‮\u007fdone\u0085", PasteSource.AI);

        assertThat(confirmer.requests).hasSize(1);
        assertThat(confirmer.requests.get(0).reasons()).containsExactly(PasteReason.CONTROL_CHARACTERS);
        confirmer.answers.get(0).accept(true);

        assertWithMessage("Ctrl+C, ESC, DEL, C1 and the bidi override never reach the pane; the rest stays")
            .that(target.sent).containsExactly("echo hi[31mdone");
    }

    @Test
    void aSingleLineAiTextWithoutControlCharactersGoesStraightThrough() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> RELAXED_BY_CONNECTION, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "git status\t-s", PasteSource.AI);

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).containsExactly("git status\t-s");
    }

    @Test
    void theRulesOfThePaneAndThePolicyFloorStillApply() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        // What the pane's rules raise under the policy's paste warning floor (ALWAYS) and the size check.
        PasteRules floorRules = new PasteDecision(new PasteProtectionSettings(PasteWarningMode.ALWAYS, 1));
        PasteGuard guard = new PasteGuard(() -> floorRules, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "x".repeat(2000), PasteSource.AI);
        guard.paste(new FakeTarget(), "a\n", PasteSource.AI);

        assertThat(confirmer.requests.get(0).reasons()).containsExactly(PasteReason.LARGE);
        assertThat(confirmer.requests.get(1).reasons()).containsExactly(PasteReason.MULTI_LINE);
        assertThat(PasteGuard.withAiFloor(Set.of(PasteReason.LARGE), "a\n\u0007"))
            .containsExactly(PasteReason.MULTI_LINE, PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE).inOrder();
    }

    @Test
    void aBroadcastPaneGetsNoAiTextAndNoPrompt() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, confirmer);
        FakeTarget target = new FakeTarget();
        target.broadcast = true;

        guard.paste(target, "ls", PasteSource.AI);
        guard.paste(target, "rm -rf /tmp/x\n", PasteSource.AI);

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).isEmpty();
    }

    @Test
    void aMultiExecPaneGetsNoAiTextAndNoPrompt() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, confirmer);
        FakeTarget target = new FakeTarget();
        target.multiExec = true;

        guard.paste(target, "ls", PasteSource.AI);

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).isEmpty();
    }

    @Test
    void anUnreadableMirrorStateCountsAsMirrored() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, confirmer);
        FakeTarget target = new FakeTarget();
        target.mirrorFailure = new IllegalStateException("pane is closing");

        guard.paste(target, "ls", PasteSource.AI);

        assertThat(target.sent).isEmpty();
    }

    @Test
    void broadcastSwitchedOnWhileTheConfirmationIsOpenDropsTheAiText() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.AI);
        target.multiExec = true;
        confirmer.answers.get(0).accept(true);

        assertThat(target.sent).isEmpty();
        assertThat(guard.isPending(target.key)).isFalse();
    }

    @Test
    void clipboardPastesKeepTheirBehaviour() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> RELAXED_BY_CONNECTION, confirmer);
        FakeTarget target = new FakeTarget();
        target.broadcast = true;
        target.multiExec = true;

        guard.paste(target, "a\nb\u0003", PasteSource.CLIPBOARD);

        assertWithMessage("the connection's relaxed rules decide a clipboard paste, mirrored or not")
            .that(confirmer.requests).isEmpty();
        assertWithMessage("a clipboard paste keeps its control characters, as before")
            .that(target.sent).containsExactly("a\rb\u0003");
    }

    @Test
    void stripControlCharactersKeepsTabsAndLineBreaksAndReturnsTheSameStringWhenClean() {
        String clean = "a\tb\r\nc";
        assertThat(PasteSanitizer.stripControlCharacters(clean)).isSameInstanceAs(clean);
        assertThat(PasteSanitizer.stripControlCharacters("\u0000a\u001b‎b⁦")).isEqualTo("ab");
        assertThat(PasteSanitizer.stripControlCharacters(null)).isEmpty();
        assertWithMessage("emoji and other supplementary characters survive")
            .that(PasteSanitizer.stripControlCharacters("ok 😀\u0007")).isEqualTo("ok 😀");
    }

    @Test
    void theWidgetReportsMultiExecMembershipToTheGuard() throws IOException {
        String widget = source("src/main/java/de/kortty/ui/KorttyTermWidget.java");
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");

        assertThat(widget).contains("public boolean multiExecActive() {\n            return pasteTargetMultiExec.getAsBoolean();");
        assertThat(view).contains("() -> MultiExecCoordinator.shared().isMember(korttyWidget));\n"
            + "        korttyWidget.setPasteHandler(pasteGuard::paste);");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
