package de.kortty.core.sftp;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class RemoteAccountNamesTest {

    @Test
    void namesComeFromGetentOrTheEtcFiles() {
        RemoteAccountNames names = new RemoteAccountNames();
        names.claim(List.of(1000, 0), List.of(1000, 27));

        names.accept("""
            daniel:x:1000:1000:Daniel,,,:/home/daniel:/bin/bash
            root:x:0:0:root:/root:/bin/bash
            #kortty-groups
            daniel:x:1000:
            sudo:x:27:daniel
            """);

        assertThat(names.user(1000)).hasValue("daniel");
        assertThat(names.user(0)).hasValue("root");
        assertThat(names.group(1000)).hasValue("daniel");
        assertThat(names.group(27)).hasValue("sudo");
        assertThat(names.user(27)).isEmpty();
    }

    @Test
    void getentWinsOverTheFallbackThatRepeatsAnId() {
        // getent knows the LDAP name; its non-zero exit for a missing ID then appends /etc/passwd.
        RemoteAccountNames names = new RemoteAccountNames();
        names.accept("""
            jdoe:*:1500:1500:John:/home/jdoe:/bin/zsh
            local-jdoe:x:1500:1500::/home/local:/bin/sh
            #kortty-groups
            """);

        assertThat(names.user(1500)).hasValue("jdoe");
    }

    @Test
    void implausibleLinesAreIgnored() {
        RemoteAccountNames names = new RemoteAccountNames();
        names.accept("""
            sh: getent: not found
            bad name:x:5:5::/:/bin/sh
            nonumber:x:abc:0::/:/bin/sh
            :x:6:6::/:/bin/sh
            ok:x:7:7::/:/bin/sh
            #kortty-groups
            grp:x:-1:
            """);

        assertThat(names.user(5)).isEmpty();
        assertThat(names.user(6)).isEmpty();
        assertThat(names.user(7)).hasValue("ok");
        assertThat(names.group(-1)).isEmpty();
    }

    @Test
    void eachIdIsAskedForOnceAndNeverAfterTheServerFailed() {
        RemoteAccountNames names = new RemoteAccountNames();

        RemoteAccountNames.Pending first = names.claim(List.of(1000, 0, 1000), List.of(1000));
        assertThat(first.uids()).containsExactly(0, 1000).inOrder();
        assertThat(first.gids()).containsExactly(1000);
        assertThat(names.claim(List.of(1000, 0), List.of(1000)).isEmpty()).isTrue();
        assertThat(names.claim(List.of(33), List.of()).uids()).containsExactly(33);
        // Negative or missing IDs are never looked up.
        assertThat(names.claim(List.of(-1), List.of()).isEmpty()).isTrue();

        names.markUnavailable();
        assertThat(names.isUnavailable()).isTrue();
        assertThat(names.claim(List.of(42), List.of(42)).isEmpty()).isTrue();
    }

    @Test
    void theCommandCarriesOnlyNumbersAndAlwaysSucceeds() {
        RemoteAccountNames names = new RemoteAccountNames();
        String command = RemoteAccountNames.lookupCommand(names.claim(List.of(1000, 0), List.of(27)));

        assertThat(command).isEqualTo("{ getent passwd 0 1000 || cat /etc/passwd; } 2>/dev/null; "
            + "echo '#kortty-groups'; { getent group 27 || cat /etc/group; } 2>/dev/null; true");

        String groupsOnly = RemoteAccountNames.lookupCommand(names.claim(List.of(), List.of(100)));
        assertThat(groupsOnly).doesNotContain("passwd");
        assertThat(groupsOnly).endsWith("; true");
    }
}
