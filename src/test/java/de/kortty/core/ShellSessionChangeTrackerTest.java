package de.kortty.core;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class ShellSessionChangeTrackerTest {

    @DataProvider(name = "sessionChangeCommands")
    Object[][] sessionChangeCommands() {
        return new Object[][] {
            {"su"},
            {"su - root"},
            {"su root"},
            {"sudo -i"},
            {"sudo -s"},
            {"sudo su -"},
            {"sudo -u postgres -i"},
            {"sudo bash"},
            {"sudo /bin/bash"},
            {"ssh host"},
            {"ssh daniel@server.example.com"},
            {"sshpass -p secret ssh host"},
            {"autossh -M 0 host"},
            {"mosh host"},
            {"telnet host"},
            {"slogin host"},
            {"rlogin host"},
            {"exec su - root"},
            {"command ssh host"},
            {"echo hi && ssh host"},
            {"ssh host; ls"},
            {"SSH HOST"},
            {"/usr/bin/ssh host"},
        };
    }

    @Test(dataProvider = "sessionChangeCommands")
    void recognizesSessionChangeCommands(String line) {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine(line);
        assertThat(tracker.isForeignSessionSuspected()).isTrue();
    }

    @DataProvider(name = "harmlessCommands")
    Object[][] harmlessCommands() {
        return new Object[][] {
            {"sudo systemctl restart nginx"},
            {"sudo -u postgres psql"},
            {"suzy"},
            {"sshfs host:/data /mnt"},
            {"echo ssh host"},
            {"echo 'ssh host'"},
            {"echo \"su - root\""},
            {"bash"},
            {"zsh -l"},
            {"ls -la"},
            {"man ssh"},
            {""},
            {"   "},
        };
    }

    @Test(dataProvider = "harmlessCommands")
    void ignoresCommandsThatDoNotChangeTheSession(String line) {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine(line);
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void quotedSessionCommandInsideArgumentsDoesNotCount() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("grep 'ssh host; su -' notes.txt");
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void exitAndLogoutReduceTheSuspicionDepth() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("su - root");
        tracker.onSubmittedLine("ssh inner-host");
        assertThat(tracker.isForeignSessionSuspected()).isTrue();
        tracker.onSubmittedLine("exit");
        assertThat(tracker.isForeignSessionSuspected()).isTrue();
        tracker.onSubmittedLine("logout");
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void endOfFileOnEmptyLineActsLikeExit() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("su - root");
        tracker.onEndOfFileOnEmptyLine();
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void depthNeverDropsBelowZero() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("exit");
        tracker.onEndOfFileOnEmptyLine();
        tracker.onSubmittedLine("su - root");
        assertThat(tracker.isForeignSessionSuspected()).isTrue();
        tracker.onSubmittedLine("exit");
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void confirmNativeIdentityClearsAnyDepth() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("su - root");
        tracker.onSubmittedLine("su - postgres");
        tracker.onSubmittedLine("ssh host");
        tracker.confirmNativeIdentity();
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void resetClearsTheState() {
        ShellSessionChangeTracker tracker = new ShellSessionChangeTracker();
        tracker.onSubmittedLine("ssh host");
        tracker.reset();
        assertThat(tracker.isForeignSessionSuspected()).isFalse();
    }
}
