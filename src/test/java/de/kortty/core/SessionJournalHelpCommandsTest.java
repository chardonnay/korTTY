package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Commands that only show help stay out of the journal; a command whose "-h" means something else
 * ("human-readable" for df) stays in.
 */
class SessionJournalHelpCommandsTest {

    private static final List<String> USAGE = List.of("Usage: tar [OPTION...] [FILE]...", "  -c, --create");
    private static final List<String> GERMAN_USAGE = List.of("Aufruf: ls [OPTION]... [DATEI]...", "  -a, --all");
    private static final List<String> DF_TABLE = List.of(
        "Dateisystem    Größe Benutzt Verf. Verw% Eingehängt auf", "/dev/vda3       40G     12G   28G   30% /");

    @Test
    void helpFlagsAndHelpProgramsAreHelpOnly() {
        for (String command : List.of("ls --help", "docker run --help", "sudo dnf --help", "/usr/bin/grep --help",
                "kubectl get pods --help", "git help log", "go help build", "man crontab", "man 5 crontab",
                "info coreutils", "tldr tar", "help cd", "apropos partition")) {
            assertWithMessage(command).that(SessionJournalHelpCommands.isHelpOnly(command, List.of())).isTrue();
        }
    }

    @Test
    void aShortHelpFlagCountsOnlyWhenTheOutputIsHelp() {
        assertThat(SessionJournalHelpCommands.isHelpOnly("tar -h", USAGE)).isTrue();
        assertThat(SessionJournalHelpCommands.isHelpOnly("ls -?", GERMAN_USAGE)).isTrue();
        assertThat(SessionJournalHelpCommands.isHelpOnly("df -h", DF_TABLE)).isFalse();
        assertThat(SessionJournalHelpCommands.isHelpOnly("free -h", List.of("               gesamt       benutzt"))).isFalse();
        assertThat(SessionJournalHelpCommands.isHelpOnly("du -h", List.of())).isFalse();
    }

    @Test
    void commandsThatDoMoreThanShowHelpStay() {
        for (String command : List.of("df -h", "ls", "grep -h needle notes.txt", "curl -s --help", "ls --help | less",
                "tar --help > tar.txt", "make install", "grep help notes.txt")) {
            assertWithMessage(command).that(SessionJournalHelpCommands.isHelpOnly(command, DF_TABLE)).isFalse();
        }
        // an unknown program's "help" word counts when it printed help
        assertThat(SessionJournalHelpCommands.isHelpOnly("./deploy.sh help", USAGE)).isTrue();
    }
}
