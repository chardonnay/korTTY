package de.kortty.core;

import de.kortty.core.SessionJournalCommandBlocks.Block;
import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Cutting the capture log into commands: a summary must never split one, and a line typed into a
 * running program is a reply, not a command. The log shapes follow a real {@code dnf upgrade}
 * session: the typed line comes before its echo, the prompt being typed at as partial lines before both.
 */
class SessionJournalCommandBlocksTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-04T14:11:44+01:00");

    /** Builds log entries with increasing sequence numbers, one second apart. */
    private static final class Log {
        final List<SessionJournalLogEntry> entries = new ArrayList<>();

        private Log add(SessionJournalLogEntry.Kind kind, String text, boolean redacted, boolean partial) {
            long seq = entries.size() + 1;
            entries.add(new SessionJournalLogEntry(seq, START.plusSeconds(seq), kind, text, redacted, partial, null));
            return this;
        }

        Log seed(String text) {
            return add(SessionJournalLogEntry.Kind.SEED, text, false, false);
        }

        Log out(String text) {
            return add(SessionJournalLogEntry.Kind.OUT, text, false, false);
        }

        Log partial(String text) {
            return add(SessionJournalLogEntry.Kind.OUT, text, false, true);
        }

        Log typed(String text) {
            return add(SessionJournalLogEntry.Kind.IN, text, false, false);
        }

        Log hidden() {
            return add(SessionJournalLogEntry.Kind.IN, "", true, false);
        }

        Log note(String text) {
            return add(SessionJournalLogEntry.Kind.NOTE, text, false, false);
        }

        /** A command typed at the Fedora prompt: the prompt typed at, the line, its echo. */
        Log command(String line) {
            return partial("daniel@fedora:~$ " + line.substring(0, Math.min(2, line.length())))
                .typed(line).out("daniel@fedora:~$ " + line);
        }
    }

    private static List<String> commands(List<Block> blocks) {
        return blocks.stream().map(Block::command).toList();
    }

    @Test
    void aLongCommandWithItsRepliesIsOneBlock() {
        Log log = new Log()
            .seed("Last login: Mon Nov  2 15:25:18 2026 from 10.211.55.12")
            .seed("daniel@fedora:~$")
            .note("journal enabled retroactively; 2 seed lines above")
            .typed("pwd").out("pwd").out("/home/daniel").partial("daniel@fedora:~$")
            .command("yast upgrade").out("bash: yast: Befehl nicht gefunden...").out("daniel@fedora:~$")
            .command("sudo dnf upgrade")
            .partial("[sudo] Passwort für daniel:").hidden().out("[sudo] Passwort für daniel:")
            .out("Paketquellen geladen.");
        for (int i = 0; i < 40; i++) {
            log.out(" kernel-module-" + i + "   aarch64   0:7.2.15-200.fc44   updates");
        }
        log.partial("Is this ok [y/N]:").typed("y").out("Is this ok [y/N]: y")
            .partial("[ 12/418] kernel-modules-extra   40% [=====     ]")
            .out("Abgeschlossen!").partial("daniel@fedora:~$")
            .command("uptime").out(" 14:17:41 up 1 day, 22:58,  2 users").partial("daniel@fedora:~$");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(commands(blocks)).containsExactly(null, "pwd", "yast upgrade", "sudo dnf upgrade", "uptime")
            .inOrder();
        Block dnf = blocks.get(3);
        // the password and the "y" stayed in the command they answered
        assertThat(dnf.entries().stream().filter(e -> e.kind() == SessionJournalLogEntry.Kind.IN).count())
            .isEqualTo(3);
        assertThat(dnf.entries().stream().anyMatch(e -> "Abgeschlossen!".equals(e.text()))).isTrue();
        assertThat(blocks.stream().allMatch(Block::complete)).isTrue();
        // every entry belongs to exactly one block, in order
        assertThat(blocks.stream().mapToInt(b -> b.entries().size()).sum()).isEqualTo(log.entries.size());
        assertThat(blocks.get(4).endSeq()).isEqualTo(log.entries.size());
    }

    @Test
    void aCommandStillPrintingIsIncomplete() {
        Log log = new Log().command("sudo dnf upgrade").out("Paketquellen geladen.")
            .partial("Is this ok [y/N]:").typed("y").out("Is this ok [y/N]: y")
            .partial("[ 12/418] kernel-modules-extra   45%");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(commands(blocks)).containsExactly("sudo dnf upgrade");
        assertThat(blocks.get(0).complete()).isFalse();
    }

    @Test
    void aQuestionWaitingForItsAnswerIsNoPrompt() {
        Log log = new Log().command("sudo dnf upgrade").out("Paketquellen geladen.").partial("Is this ok [y/N]:");

        assertThat(SessionJournalCommandBlocks.cut(log.entries).get(0).complete()).isFalse();
    }

    @Test
    void aJustTypedCommandIsRunningUntilItsOutputEndsAtAPrompt() {
        Log log = new Log().command("sleep 600");

        assertThat(SessionJournalCommandBlocks.cut(log.entries).get(0).complete()).isFalse();
    }

    @Test
    void whatKorttyWritesAfterThePromptReturnedDoesNotKeepTheCommandRunning() {
        // korTTY's AI agent: the typed line, the prompt back at once, then the agent's reply block
        Log log = new Log().command("agent configure nginx on port 9090").out("daniel@fedora:~$")
            .out("  ◆ AI agent · 13 steps · Exit 1 · 2:30").out("  ✓ Inspect(SSH session)  Server state refreshed.");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(blocks.get(0).complete()).isTrue();
    }

    @Test
    void theShellIntegrationsNoteEndsACommandAndCarriesItsExitStatus() {
        // A prompt no rule knows: only the shell's own report says the command ended.
        Log log = new Log().typed("make test").out("make test").out("FAILED: 2 of 40")
            .note(SessionJournalCommandBlocks.finishedNote(2)).partial("build-box ~/src │")
            .typed("git status").out("build-box ~/src │ git status").out("On branch main");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(commands(blocks)).containsExactly("make test", "git status").inOrder();
        assertThat(blocks.get(0).exitStatus()).isEqualTo(2);
        assertThat(blocks.get(0).complete()).isTrue();
        assertThat(blocks.get(1).complete()).isFalse();
    }

    @Test
    void aPromptOfItsOwnIsLearnedFromTheLineTypedAt() {
        Log log = new Log().partial("~/src ❯").typed("make").out("~/src ❯ make").out("cc -o app main.c")
            .partial("~/src ❯ gi").typed("git log").out("~/src ❯ git log").out("commit 1a2b3c")
            .partial("~/src ❯");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(commands(blocks)).containsExactly("make", "git log").inOrder();
        assertThat(blocks.get(1).complete()).isTrue();
    }

    @Test
    void aStretchStartingAtTheNextPromptHasNoEmptyBlockBeforeTheCommand() {
        // The pass before ended with the previous command; this one starts at the prompt typed at.
        Log log = new Log().partial("daniel@fedora:~$ up").partial("daniel@fedora:~$ upti")
            .typed("uptime").out("daniel@fedora:~$ uptime").out(" 14:17:41 up 1 day").partial("daniel@fedora:~$");

        List<Block> blocks = SessionJournalCommandBlocks.cut(log.entries);

        assertThat(commands(blocks)).containsExactly("uptime");
        assertThat(blocks.get(0).startSeq()).isEqualTo(1);
    }

    @Test
    void progressAndCountersAreNoPrompts() {
        for (String line : List.of("Downloading 45%", "[####", "Progress: 100%", "########")) {
            Log log = new Log().command("make").partial(line);
            assertThat(SessionJournalCommandBlocks.cut(log.entries).get(0).complete()).isFalse();
        }
    }

    @Test
    void finishedNotesRoundTripTheirExitStatus() {
        Log log = new Log().note(SessionJournalCommandBlocks.finishedNote(130))
            .note(SessionJournalCommandBlocks.finishedNote(null)).note("session reconnected");

        assertThat(SessionJournalCommandBlocks.isFinishedNote(log.entries.get(0))).isTrue();
        assertThat(SessionJournalCommandBlocks.exitStatusOf(log.entries.get(0))).isEqualTo(130);
        assertThat(SessionJournalCommandBlocks.exitStatusOf(log.entries.get(1))).isNull();
        assertThat(SessionJournalCommandBlocks.isFinishedNote(log.entries.get(2))).isFalse();
    }
}
