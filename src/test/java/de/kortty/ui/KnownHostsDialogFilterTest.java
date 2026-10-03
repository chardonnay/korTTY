package de.kortty.ui;

import de.kortty.core.SshHostKeyTrustManager.TrustedHostKey;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class KnownHostsDialogFilterTest {

    private static final TrustedHostKey WEB = new TrustedHostKey(
        "web.example.com", 22, "ssh-ed25519", "SHA256:AbCdEfWebFingerprint", "2026-01-02T03:04:05Z");
    private static final TrustedHostKey DB = new TrustedHostKey(
        "db.internal", 2222, "ssh-rsa", "SHA256:XyZDatabaseFingerprint", "2026-02-03T04:05:06Z");
    private static final TrustedHostKey IPV6 = new TrustedHostKey(
        "2001:db8::1", 22, "ecdsa-sha2-nistp256", "SHA256:Ipv6Fingerprint", "2026-03-04T05:06:07Z");
    private static final List<TrustedHostKey> ALL = List.of(DB, IPV6, WEB);

    @Test
    void blankQueryReturnsEveryKeyInOrder() {
        assertThat(KnownHostsDialog.filter(ALL, null)).containsExactlyElementsIn(ALL).inOrder();
        assertThat(KnownHostsDialog.filter(ALL, "")).containsExactlyElementsIn(ALL).inOrder();
        assertThat(KnownHostsDialog.filter(ALL, "   ")).containsExactlyElementsIn(ALL).inOrder();
    }

    @Test
    void matchesHostSubstringsIgnoringCase() {
        assertThat(KnownHostsDialog.filter(ALL, "WEB.Example")).containsExactly(WEB);
        assertThat(KnownHostsDialog.filter(ALL, "internal")).containsExactly(DB);
        assertThat(KnownHostsDialog.filter(ALL, "db8::")).containsExactly(IPV6);
    }

    @Test
    void matchesPortAndHostColonPort() {
        assertThat(KnownHostsDialog.filter(ALL, "2222")).containsExactly(DB);
        assertThat(KnownHostsDialog.filter(ALL, "web.example.com:22")).containsExactly(WEB);
        assertThat(KnownHostsDialog.filter(ALL, ":22")).containsExactly(DB, IPV6, WEB).inOrder();
    }

    @Test
    void matchesFingerprintSubstringsIgnoringCase() {
        assertThat(KnownHostsDialog.filter(ALL, "xyzdatabase")).containsExactly(DB);
        assertThat(KnownHostsDialog.filter(ALL, "sha256:abcdef")).containsExactly(WEB);
        assertThat(KnownHostsDialog.filter(ALL, "  Ipv6Finger  ")).containsExactly(IPV6);
    }

    @Test
    void matchesAlgorithm() {
        assertThat(KnownHostsDialog.filter(ALL, "ed25519")).containsExactly(WEB);
    }

    @Test
    void unmatchedQueryAndMissingListYieldNothing() {
        assertThat(KnownHostsDialog.filter(ALL, "no-such-host")).isEmpty();
        assertThat(KnownHostsDialog.filter(null, "web")).isEmpty();
        assertThat(KnownHostsDialog.filter(List.of(), null)).isEmpty();
    }
}
