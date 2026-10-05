package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalShellPromptsTest {

    @Test
    void bareAndCoalescedPromptsAreIdle() {
        assertThat(SessionJournalShellPrompts.isIdleLine("daniel@fedora:~/Dokumente$")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("daniel@fedora:~/Dokumente$ ")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("[root@fedora ~]# (×3)")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("daniel@mac ~ %")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("(venv) daniel@web01:/srv$")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("bash-5.2#")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine("   ")).isTrue();
        assertThat(SessionJournalShellPrompts.isIdleLine(null)).isTrue();
    }

    @Test
    void commandsAndOutputAreActivity() {
        assertThat(SessionJournalShellPrompts.isIdleLine("daniel@fedora:~$ man links")).isFalse();
        assertThat(SessionJournalShellPrompts.isIdleLine("Abgeschlossen!")).isFalse();
        assertThat(SessionJournalShellPrompts.isIdleLine("total 5$")).isFalse();
    }

    @Test
    void readsTheUserOfAPrompt() {
        assertThat(SessionJournalShellPrompts.promptUser("[root@fedora ~]# dnf update")).isEqualTo("root");
        assertThat(SessionJournalShellPrompts.promptUser("root@web01:/etc#")).isEqualTo("root");
        assertThat(SessionJournalShellPrompts.promptUser("postgres@db01:~$ psql")).isEqualTo("postgres");
        assertThat(SessionJournalShellPrompts.promptUser("sh-5.2# id")).isEqualTo("root");
        assertThat(SessionJournalShellPrompts.promptUser("sh-5.2$ id")).isNull();
        assertThat(SessionJournalShellPrompts.promptUser("mail to admin@example.com failed")).isNull();
    }

    @Test
    void rootWinsOverOtherSwitchedUsersAndTheLoginUserNeverCounts() {
        assertThat(SessionJournalShellPrompts.switchedUser(
            List.of("daniel@fedora:~$ sudo -i", "[root@fedora ~]#"), "daniel")).isEqualTo("root");
        assertThat(SessionJournalShellPrompts.switchedUser(
            List.of("postgres@db:~$ psql", "[root@db ~]# exit"), "daniel")).isEqualTo("root");
        assertThat(SessionJournalShellPrompts.switchedUser(
            List.of("daniel@db:~$ sudo -u postgres -i", "postgres@db:~$"), "daniel")).isEqualTo("postgres");
        assertThat(SessionJournalShellPrompts.switchedUser(
            List.of("daniel@fedora:~$ sudo dnf install links", "Abgeschlossen!"), "daniel")).isNull();
    }
}
