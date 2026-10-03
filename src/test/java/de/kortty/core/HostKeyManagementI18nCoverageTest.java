package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/** The review-and-replace flow and the Known Hosts dialog are fully translated in every bundle. */
class HostKeyManagementI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
            "menu.security.knownHosts",
            "ssh.knownHosts.title",
            "ssh.knownHosts.search.prompt",
            "ssh.knownHosts.column.host",
            "ssh.knownHosts.column.port",
            "ssh.knownHosts.column.algorithm",
            "ssh.knownHosts.column.fingerprint",
            "ssh.knownHosts.column.trustedAt",
            "ssh.knownHosts.empty",
            "ssh.knownHosts.loadFailed",
            "ssh.knownHosts.remove",
            "ssh.knownHosts.remove.confirm.title",
            "ssh.knownHosts.remove.confirm.header",
            "ssh.knownHosts.remove.confirm.message",
            "ssh.knownHosts.remove.stale",
            "ssh.knownHosts.remove.failed",
            "ssh.knownHosts.policyLocked",
            "ssh.hostKey.mismatch.message",
            "ssh.hostKey.mismatch.reviewReplace",
            "ssh.hostKey.mismatch.policyLocked",
            "ssh.hostKey.mismatch.knownHostsHint",
            "ssh.hostKey.replace.title",
            "ssh.hostKey.replace.header",
            "ssh.hostKey.replace.message",
            "ssh.hostKey.replace.currentKey",
            "ssh.hostKey.replace.newKey",
            "ssh.hostKey.replace.verifiedCheck",
            "ssh.hostKey.replace.confirmButton",
            "ssh.hostKey.replace.failed");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyHostKeyManagementKeyExistsInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + key).that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void everyPlaceholderSurvivesTranslation() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                Matcher matcher = PLACEHOLDER.matcher(english.getProperty(key));
                while (matcher.find()) {
                    assertWithMessage(bundle + " lost " + matcher.group() + " from " + key)
                            .that(localized.getProperty(key)).contains(matcher.group());
                }
            }
        }
    }

    @Test
    void translationsAreNotEnglishCopies() throws Exception {
        Properties english = loadBundle("messages.properties");
        List<String> sentences = List.of(
                "ssh.hostKey.replace.message",
                "ssh.hostKey.replace.verifiedCheck",
                "ssh.hostKey.mismatch.policyLocked",
                "ssh.knownHosts.remove.confirm.message",
                "ssh.knownHosts.remove.stale");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : sentences) {
                assertWithMessage(bundle + " left " + key + " untranslated")
                        .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
            }
        }
    }

    @Test
    void theMismatchTextNoLongerPromisesAnImpossibleUpdate() throws Exception {
        // The old text told the user to "verify the change before updating the trusted key" while
        // korTTY offered no way to update it; it now speaks about the new fingerprint.
        String message = loadBundle("messages.properties").getProperty("ssh.hostKey.mismatch.message");
        assertWithMessage("English mismatch text").that(message).contains("new fingerprint");
        assertWithMessage("English mismatch text").that(message).doesNotContain("Verify the change");
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
