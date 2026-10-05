package de.kortty.core;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalTypoFilterTest {

    private final List<SessionJournalLogEntry> log = new ArrayList<>();

    @BeforeMethod
    void clearLog() {
        log.clear();
    }

    private void in(String text) {
        log.add(new SessionJournalLogEntry(log.size() + 1, OffsetDateTime.now(),
            SessionJournalLogEntry.Kind.IN, text, false, false, null));
    }

    private void out(String text) {
        log.add(new SessionJournalLogEntry(log.size() + 1, OffsetDateTime.now(),
            SessionJournalLogEntry.Kind.OUT, text, false, false, null));
    }

    private List<String> texts(List<SessionJournalLogEntry> entries) {
        return entries.stream().map(SessionJournalLogEntry::text).toList();
    }

    @Test
    void recognisesTheShellsNotFoundMessages() {
        assertThat(SessionJournalTypoFilter.notFoundCommand("bash: lnks: command not found")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("-bash: lnks: Befehl nicht gefunden.")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("bash: lnks: Befehl nicht gefunden...")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand(
            "bash: ./server_auslastng.pl: Datei oder Verzeichnis nicht gefunden")).isEqualTo("./server_auslastng.pl");
        assertThat(SessionJournalTypoFilter.notFoundCommand("sh: 1: lnks: not found")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("zsh: command not found: lnks")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("sudo: lnks: command not found")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("Command 'lnks' not found, did you mean:")).isEqualTo("lnks");
        assertThat(SessionJournalTypoFilter.notFoundCommand("fish: Unknown command: lnks")).isEqualTo("lnks");
        // a program that ran and failed to find a file is a real result
        assertThat(SessionJournalTypoFilter.notFoundCommand("cat: foo.txt: No such file or directory")).isNull();
        assertThat(SessionJournalTypoFilter.notFoundCommand("Abgeschlossen!")).isNull();
    }

    @Test
    void dropsTheTypedLineItsEchoTheErrorAndTheSuggestions() {
        in("lnks");
        out("daniel@fedora:~$ lnks");
        out("bash: lnks: Befehl nicht gefunden...");
        out("Ähnlicher Befehl ist: 'links'");
        out("daniel@fedora:~$");
        in("links https://example.test");
        out("daniel@fedora:~$ links https://example.test");

        assertThat(texts(SessionJournalTypoFilter.withoutNotFoundAttempts(log))).containsExactly(
            "daniel@fedora:~$", "links https://example.test", "daniel@fedora:~$ links https://example.test").inOrder();
    }

    @Test
    void aMistypedScriptPathIsDroppedEvenWithoutCapturedInput() {
        out("daniel@fedora:~/Dokumente$ ./server_auslastng.pl");
        out("bash: ./server_auslastng.pl: Datei oder Verzeichnis nicht gefunden");
        out("daniel@fedora:~/Dokumente$ ./server_auslastung.pl");
        out("load 0.42");

        assertThat(texts(SessionJournalTypoFilter.withoutNotFoundAttempts(log))).containsExactly(
            "daniel@fedora:~/Dokumente$ ./server_auslastung.pl", "load 0.42").inOrder();
    }

    @Test
    void aMessageWithoutAVisibleStartAttemptStays() {
        in("cat < /etc/nothere");
        out("daniel@fedora:~$ cat < /etc/nothere");
        out("bash: /etc/nothere: No such file or directory");

        assertThat(SessionJournalTypoFilter.withoutNotFoundAttempts(log)).isSameInstanceAs(log);
    }
}
