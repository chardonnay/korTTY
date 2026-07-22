package de.kortty.policy;

import de.kortty.core.KorttyClipboard;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Internal-clipboard mode end to end through the real locator/loader/manager chain. The internal
 * path never touches the OS clipboard (and needs no JavaFX toolkit), which is exactly the point.
 */
class PolicyClipboardTest {

    private Path policyFile;

    @AfterMethod
    void reset() throws IOException {
        System.clearProperty(PolicyLocator.OVERRIDE_PROPERTY);
        PolicyManager.resetForTests();
        if (policyFile != null) {
            Files.deleteIfExists(policyFile);
        }
    }

    private void activate(String clipboardMode) throws IOException {
        policyFile = Files.createTempFile("kortty-policy", ".toml");
        Files.writeString(policyFile, """
            [meta]
            schema-version = 1

            [[rule]]
            [rule.security]
            clipboard-mode = "%s"
            """.formatted(clipboardMode));
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, policyFile.toString());
        PolicyManager.initialize();
    }

    @Test
    void internalModeRoundTripsWithoutTheOsClipboard() throws IOException {
        activate("internal");
        assertThat(KorttyClipboard.isInternalMode()).isTrue();

        KorttyClipboard.setText("copied inside korTTY");
        assertThat(KorttyClipboard.hasText()).isTrue();
        assertThat(KorttyClipboard.getText()).isEqualTo("copied inside korTTY");

        // Whatever another application put into the OS clipboard is invisible here by
        // construction — the internal path never consults it.
    }

    @Test
    void systemModeIsReportedWhenConfigured() throws IOException {
        activate("system");
        assertThat(KorttyClipboard.isInternalMode()).isFalse();
        assertThat(PolicyManager.effective().isManaged(ManagedSetting.CLIPBOARD)).isTrue();
    }

    @Test
    void withoutPolicyTheSystemClipboardModeApplies() {
        PolicyManager.resetForTests();
        assertThat(KorttyClipboard.isInternalMode()).isFalse();
    }
}
