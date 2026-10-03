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

/** Every runtime SSH tunnel text exists in every bundled locale and keeps its placeholders. */
class TunnelI18nCoverageTest {

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
        "statusBar.tunnels",
        "statusBar.tunnelFailed",
        "statusBar.tunnelExposed",
        "statusBar.tunnelsUnsupported",
        "statusBar.tunnelsPolicyDenied",
        "statusBar.tunnelsNotConfirmed",
        "tunnel.state.starting",
        "tunnel.state.active",
        "tunnel.state.failed",
        "tunnel.state.stopped",
        "tunnel.state.notStarted",
        "tunnel.state.exposed",
        "tunnel.error.invalidPort",
        "tunnel.error.sharedNonLoopback",
        "tunnel.error.sharedRemote",
        "tunnel.error.bindFailed",
        "tunnel.error.serverRejected",
        "tunnel.error.policyDenied",
        "tunnel.error.notConfirmed",
        "tunnel.error.unsupported",
        "tunnel.notConfirmedHint",
        "tunnel.approval.title",
        "tunnel.approval.header",
        "tunnel.approval.body",
        "tunnel.approval.shared",
        "tunnel.approval.note",
        "tunnel.approval.open",
        "tunnel.approval.skip");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everyTunnelKeyExistsInEveryBundledLocale() throws Exception {
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
    void theServerSettingNameIsQuotedVerbatimEverywhere() throws Exception {
        // AllowTcpForwarding is the sshd_config keyword an administrator has to look up.
        for (String bundle : BUNDLES) {
            assertWithMessage(bundle + " must name AllowTcpForwarding literally")
                .that(loadBundle(bundle).getProperty("tunnel.error.serverRejected")).contains("AllowTcpForwarding");
        }
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
