package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.clipboard.ClipboardError;
import com.sithtermfx.core.clipboard.ClipboardException;
import com.sithtermfx.core.clipboard.ClipboardLocation;
import com.sithtermfx.core.clipboard.ClipboardReadRequest;
import com.sithtermfx.core.clipboard.ClipboardWriteRequest;
import de.kortty.model.GlobalSettings;
import de.kortty.paste.PasteWarningMode;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import de.kortty.shellintegration.Osc52Support;
import de.kortty.ui.TerminalClipboardAccess.ReadDecision;
import de.kortty.ui.TerminalClipboardAccess.ReadPromptRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TerminalClipboardAccess}, korTTY's handler for OSC 5522: writes follow the OSC 52 setting and the
 * policy, reads need their own setting, the policy and the user's answer (unless a paste granted them), only
 * text is exchanged, and nothing runs on the emulator thread.
 */
class TerminalClipboardAccessTest {

    private GlobalSettings settings;
    private EffectivePolicy policy;
    private FakeClipboard clipboard;
    private List<String> status;
    private List<ReadPromptRequest> prompts;
    private Queue<Consumer<ReadDecision>> openPrompts;
    /** Tasks handed to the "FX thread"; run by {@link #runFx()}. */
    private Queue<Runnable> fx;
    private long now;
    private TerminalClipboardAccess access;

    private static final class FakeClipboard implements TerminalClipboardAccess.TextClipboard {
        @Nullable String text;
        final List<String> written = new ArrayList<>();

        @Override
        public @Nullable String getText() {
            return text;
        }

        @Override
        public void setText(String text) {
            this.text = text;
            written.add(text);
        }

        @Override
        public boolean hasText() {
            return text != null && !text.isEmpty();
        }
    }

    @BeforeMethod
    void setUp() {
        settings = new GlobalSettings();
        policy = EffectivePolicy.unrestricted();
        clipboard = new FakeClipboard();
        status = new ArrayList<>();
        prompts = new ArrayList<>();
        openPrompts = new ArrayDeque<>();
        fx = new ArrayDeque<>();
        now = 1_000_000_000L;
        TerminalClipboardAccess.Host host = new TerminalClipboardAccess.Host() {
            @Override
            public String tabName() {
                return "prod‮-db";
            }

            @Override
            public void showStatus(String message) {
                status.add(message);
            }
        };
        access = new TerminalClipboardAccess(() -> settings, () -> policy, clipboard, host,
            (request, answer) -> {
                prompts.add(request);
                openPrompts.add(answer);
            },
            fx::add, () -> now, (key, args) -> key + Arrays.toString(args));
    }

    private void runFx() {
        Runnable task;
        while ((task = fx.poll()) != null) {
            task.run();
        }
    }

    private static EffectivePolicy policy(PolicyRule rule) {
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), new PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ClipboardWriteRequest write(String mimeType, byte[] data) {
        return new ClipboardWriteRequest(ClipboardLocation.CLIPBOARD, Map.of(mimeType, data), Map.of(), null, null);
    }

    private static ClipboardReadRequest read(List<String> types, @Nullable String name, @Nullable String password,
            boolean byPaste) {
        return new ClipboardReadRequest(ClipboardLocation.CLIPBOARD, types, name, password, byPaste);
    }

    private static ClipboardError errorOf(CompletionStage<?> stage) throws InterruptedException {
        try {
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ClipboardException.class);
            return ((ClipboardException) e.getCause()).getError();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the stage did not complete", e);
        }
        throw new AssertionError("the stage completed normally");
    }

    private static <T> T valueOf(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    private static String text(Map<String, byte[]> data, String type) {
        return new String(data.get(type), StandardCharsets.UTF_8);
    }

    // ---- writes ---------------------------------------------------------------------------------

    @Test
    void writesAreRefusedWhileTheOsc52SettingIsOff() throws Exception {
        CompletionStage<Void> result = access.write(write("text/plain", utf8("rm -rf ~\n")));

        assertWithMessage("nothing is decided on the emulator thread").that(result.toCompletableFuture().isDone())
            .isFalse();
        runFx();
        assertThat(errorOf(result)).isEqualTo(ClipboardError.EPERM);
        assertThat(clipboard.written).isEmpty();
        assertThat(status).containsExactly("terminal.osc5522.writeBlocked[prod-db]");
    }

    @Test
    void writesAreRefusedWhenThePolicyForbidsClipboardWrites() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);
        policy = policy(PolicyRule.builder().allowOsc52ClipboardWrite(false).build());

        CompletionStage<Void> result = access.write(write("text/plain", utf8("x")));
        runFx();

        assertThat(errorOf(result)).isEqualTo(ClipboardError.EPERM);
        assertThat(clipboard.written).isEmpty();
    }

    @Test
    void withTheSettingOnTextGoesOnTheClipboardAndTheStatusBarNamesTheTab() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);
        String copied = "copied in nvim 🙂";

        CompletionStage<Void> result = access.write(write("text/plain;charset=utf-8", utf8(copied)));
        runFx();

        assertThat(valueOf(result)).isNull();
        assertThat(clipboard.written).containsExactly(copied);
        assertWithMessage("characters as the user counts them, and the tab's name without its bidi override")
            .that(status).containsExactly("terminal.osc5522.copied[" + copied.codePointCount(0, copied.length())
                + ", prod-db]");
    }

    @Test
    void anAliasToTextCountsAsTextAndOtherTypesAreLeftOut() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);
        Map<String, byte[]> data = new LinkedHashMap<>();
        data.put("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        data.put("text/x-plain", utf8("via alias"));
        ClipboardWriteRequest request = new ClipboardWriteRequest(ClipboardLocation.CLIPBOARD, data,
            Map.of("text/x-plain", List.of("text/plain")), null, null);

        CompletionStage<Void> result = access.write(request);
        runFx();

        assertThat(valueOf(result)).isNull();
        assertThat(clipboard.written).containsExactly("via alias");
    }

    @Test
    void whatKorttyCannotTakeIsRefusedWithTheMatchingStatus() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);

        CompletionStage<Void> image = access.write(write("image/png", new byte[] {1, 2, 3}));
        CompletionStage<Void> notUtf8 = access.write(write("text/plain", new byte[] {(byte) 0xC3, (byte) 0x28}));
        CompletionStage<Void> tooLarge = access.write(write("text/plain", new byte[Osc52Support.MAX_DECODED_BYTES + 1]));
        CompletionStage<Void> primary = access.write(new ClipboardWriteRequest(ClipboardLocation.PRIMARY,
            Map.of("text/plain", utf8("x")), Map.of(), null, null));
        runFx();

        assertThat(errorOf(image)).isEqualTo(ClipboardError.EPERM);
        assertThat(errorOf(notUtf8)).isEqualTo(ClipboardError.EIO);
        assertThat(errorOf(tooLarge)).isEqualTo(ClipboardError.EFBIG);
        assertThat(errorOf(primary)).isEqualTo(ClipboardError.ENOSYS);
        assertThat(clipboard.written).isEmpty();
        assertThat(status).containsExactly("terminal.osc5522.writeRejected[prod-db]",
            "terminal.osc5522.writeRejected[prod-db]", "terminal.osc5522.writeRejected[prod-db]");
    }

    @Test
    void anEmptyWriteLeavesTheClipboardAloneSilently() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);
        clipboard.text = "before";

        CompletionStage<Void> result = access.write(write("text/plain", new byte[0]));
        runFx();

        assertThat(valueOf(result)).isNull();
        assertThat(clipboard.text).isEqualTo("before");
        assertThat(status).isEmpty();
    }

    // ---- reads ----------------------------------------------------------------------------------

    @Test
    void readsAreRefusedWithoutAPromptWhileTheReadSettingIsOff() throws Exception {
        settings.setOsc52ClipboardWriteEnabled(true);
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> result = access.read(read(List.of("text/plain"), "nvim", null, false));
        runFx();

        assertThat(errorOf(result)).isEqualTo(ClipboardError.EPERM);
        assertWithMessage("the OSC 52 write setting does not allow reads").that(prompts).isEmpty();
        assertThat(status).containsExactly("terminal.osc5522.readBlocked[prod-db]");
    }

    @Test
    void thePolicyBanRefusesReadsEvenAfterAPaste() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        policy = policy(PolicyRule.builder().allowOsc5522ClipboardRead(false).build());
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> asked = access.read(read(List.of("text/plain"), null, null, false));
        CompletionStage<Map<String, byte[]>> pasted = access.read(read(List.of("text/plain"), null, null, true));
        runFx();

        assertThat(errorOf(asked)).isEqualTo(ClipboardError.EPERM);
        assertThat(errorOf(pasted)).isEqualTo(ClipboardError.EPERM);
        assertThat(prompts).isEmpty();
        assertWithMessage("no paste events while reads are forbidden").that(access.pasteEventsAllowed()).isFalse();
    }

    @Test
    void aReadAsksFirstAndAllowOnceHandsOutTheTextOnce() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "the clipboard";

        CompletionStage<Map<String, byte[]>> result = access.read(read(
            List.of("image/png", "text/plain", "text/html"), "nvim\u001B[2J", null, false));
        runFx();

        assertThat(result.toCompletableFuture().isDone()).isFalse();
        assertThat(prompts).hasSize(1);
        ReadPromptRequest prompt = prompts.get(0);
        assertWithMessage("the program's name without its escape char").that(prompt.programName())
            .isEqualTo("nvim[2J");
        assertThat(prompt.tabName()).isEqualTo("prod-db");
        assertThat(prompt.mimeTypes()).containsExactly("image/png", "text/plain", "text/html").inOrder();
        assertWithMessage("without a password nothing can be remembered").that(prompt.canRemember()).isFalse();

        openPrompts.poll().accept(ReadDecision.ALLOW_ONCE);
        Map<String, byte[]> data = valueOf(result);
        assertWithMessage("only text is handed out").that(data.keySet()).containsExactly("text/plain");
        assertThat(text(data, "text/plain")).isEqualTo("the clipboard");
        assertThat(status).containsExactly("terminal.osc5522.read[13, prod-db]");

        access.read(read(List.of("text/plain"), "nvim", null, false));
        runFx();
        assertWithMessage("allowed once is asked again").that(prompts).hasSize(2);
    }

    @Test
    void denyRefusesTheReadAndKeepsTheTabQuietForAWhile() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> denied = access.read(read(List.of("text/plain"), null, null, false));
        runFx();
        openPrompts.poll().accept(ReadDecision.DENY);
        assertThat(errorOf(denied)).isEqualTo(ClipboardError.EPERM);

        CompletionStage<Map<String, byte[]>> again = access.read(read(List.of("text/plain"), null, null, false));
        runFx();
        assertThat(errorOf(again)).isEqualTo(ClipboardError.EPERM);
        assertWithMessage("no second prompt right after a Deny").that(prompts).hasSize(1);

        now += TimeUnit.SECONDS.toNanos(TerminalClipboardAccess.DENY_QUIET_SECONDS);
        access.read(read(List.of("text/plain"), null, null, false));
        runFx();
        assertThat(prompts).hasSize(2);
        assertWithMessage("no data, no status").that(status).isEmpty();
    }

    @Test
    void closingThePromptCountsAsDeny() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> result = access.read(read(List.of("text/plain"), null, null, false));
        runFx();
        openPrompts.poll().accept(null);

        assertThat(errorOf(result)).isEqualTo(ClipboardError.EPERM);
    }

    @Test
    void allowForThisProgramNeedsANameAndPasswordAndLastsForTheTab() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "shared";

        CompletionStage<Map<String, byte[]>> first = access.read(read(List.of("text/plain"), "nvim", "pw1", false));
        runFx();
        assertThat(prompts.get(0).canRemember()).isTrue();
        openPrompts.poll().accept(ReadDecision.ALLOW_FOR_PROGRAM);
        assertThat(text(valueOf(first), "text/plain")).isEqualTo("shared");

        CompletionStage<Map<String, byte[]>> second = access.read(read(List.of("text/plain"), "nvim", "pw1", false));
        runFx();
        assertThat(text(valueOf(second), "text/plain")).isEqualTo("shared");
        assertWithMessage("the same name and password are not asked again").that(prompts).hasSize(1);

        access.read(read(List.of("text/plain"), "nvim", "other", false));
        runFx();
        assertWithMessage("another password is another program").that(prompts).hasSize(2);

        access.close();
        CompletionStage<Map<String, byte[]>> afterClose = access.read(read(List.of("text/plain"), "nvim", "pw1", false));
        runFx();
        assertThat(errorOf(afterClose)).isEqualTo(ClipboardError.EPERM);
    }

    @Test
    void aReadGrantedByAPasteIsNotAskedAgain() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "pasted";

        CompletionStage<Map<String, byte[]>> result = access.read(read(List.of("text/plain"), null, null, true));
        runFx();

        assertThat(prompts).isEmpty();
        assertThat(text(valueOf(result), "text/plain")).isEqualTo("pasted");
    }

    @Test
    void aReadOfOnlyTypesKorttyNeverHandsOutAsksNothingAndGetsNothing() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> result = access.read(read(List.of("image/png"), null, null, false));
        runFx();

        assertThat(valueOf(result)).isEmpty();
        assertThat(prompts).isEmpty();
    }

    @Test
    void thePromptListsAtMostEightTypesAndCountsTheRest() {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            types.add("application/x-" + i);
        }

        ReadPromptRequest prompt = access.promptRequest(read(types, "  ", null, false));

        assertThat(prompt.mimeTypes()).hasSize(TerminalClipboardAccess.MAX_SHOWN_TYPES);
        assertThat(prompt.moreTypes()).isEqualTo(3);
        assertWithMessage("a blank name is no name").that(prompt.programName()).isNull();
    }

    @Test
    void anAnswerAfterTheSettingWasSwitchedOffHandsOutNothing() throws Exception {
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "secret";

        CompletionStage<Map<String, byte[]>> result = access.read(read(List.of("text/plain"), null, null, false));
        runFx();
        settings.setOsc5522ClipboardReadEnabled(false);
        openPrompts.poll().accept(ReadDecision.ALLOW_ONCE);

        assertThat(errorOf(result)).isEqualTo(ClipboardError.EPERM);
    }

    // ---- MIME types, internal mode and paste events -------------------------------------------------

    @Test
    void listingTheTypesNeedsNoPermissionAndReportsOnlyText() throws Exception {
        CompletionStage<List<String>> empty = access.getMimeTypes(ClipboardLocation.CLIPBOARD);
        runFx();
        assertThat(valueOf(empty)).isEmpty();

        clipboard.text = "x";
        CompletionStage<List<String>> text = access.getMimeTypes(ClipboardLocation.CLIPBOARD);
        runFx();
        assertThat(valueOf(text)).containsExactly("text/plain");
        assertThat(errorOf(access.getMimeTypes(ClipboardLocation.PRIMARY))).isEqualTo(ClipboardError.ENOSYS);
        assertThat(prompts).isEmpty();
    }

    @Test
    void theInternalClipboardModeKeepsWritesAndReadsInKortty() throws Exception {
        // The real clipboard of the handler is KorttyClipboard, which keeps both inside korTTY in that mode.
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/de/kortty/ui/TerminalClipboardAccess.java"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(source).contains("return KorttyClipboard.getText();");
        assertThat(source).contains("KorttyClipboard.setText(text);");
        assertThat(source).contains("return KorttyClipboard.hasText();");
        assertWithMessage("no other clipboard API").that(source).doesNotContain("Clipboard.getSystemClipboard");
    }

    @Test
    void pasteEventsNeedReadsAndNoPasteWarningFloor() {
        assertThat(access.pasteEventsAllowed()).isFalse();
        settings.setOsc5522ClipboardReadEnabled(true);
        assertThat(access.pasteEventsAllowed()).isTrue();

        policy = policy(PolicyRule.builder().pasteWarningFloor(PasteWarningMode.UNLESS_BRACKETED).build());
        assertWithMessage("with a paste warning floor every paste goes through korTTY's paste warning")
            .that(access.pasteEventsAllowed()).isFalse();

        policy = EffectivePolicy.unrestricted();
        access.close();
        assertThat(access.pasteEventsAllowed()).isFalse();
    }

    @Test
    void theHandlerNeverCompletesOnTheCallersThread() {
        settings.setOsc52ClipboardWriteEnabled(true);
        settings.setOsc5522ClipboardReadEnabled(true);
        clipboard.text = "x";

        List<CompletableFuture<?>> stages = List.of(
            access.write(write("text/plain", utf8("y"))).toCompletableFuture(),
            access.read(read(List.of("text/plain"), null, null, true)).toCompletableFuture(),
            access.getMimeTypes(ClipboardLocation.CLIPBOARD).toCompletableFuture());

        for (CompletableFuture<?> stage : stages) {
            assertThat(stage.isDone()).isFalse();
        }
        assertWithMessage("the clipboard is not touched before the FX thread runs").that(clipboard.written).isEmpty();
        runFx();
        for (CompletableFuture<?> stage : stages) {
            assertThat(stage.isDone()).isTrue();
        }
    }
}
