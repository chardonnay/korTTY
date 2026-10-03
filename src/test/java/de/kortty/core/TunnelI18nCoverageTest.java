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

/**
 * Every SSH tunnel text, at runtime and in the tunnel editor, exists in every bundled locale and
 * keeps its placeholders.
 */
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
        "tunnel.approval.skip",
        // The tunnel editor: type names, per-type field labels, the bind warnings and the
        // connection editor's switch over every tunnel.
        "tunnel.type.local",
        "tunnel.type.remote",
        "tunnel.type.dynamic",
        "tunnel.localHost",
        "tunnel.localPort",
        "tunnel.remoteHost",
        "tunnel.remotePort",
        "tunnel.localBindAddress",
        "tunnel.remoteBindAddress",
        "tunnel.nonLoopbackWarning",
        "tunnel.nonLoopbackWarning.remote",
        "connEdit.enableTunnels",
        "connEdit.enableTunnelsTooltip");

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

    @Test
    void theBindWarningsNameTheRemedyAndTheServerSettingVerbatimEverywhere() throws Exception {
        // localhost is what the user types into the field; GatewayPorts is the sshd_config keyword
        // that decides whether the server honours a non-loopback remote bind.
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle + " must name localhost in the local bind warning")
                .that(localized.getProperty("tunnel.nonLoopbackWarning")).contains("localhost");
            assertWithMessage(bundle + " must name localhost in the remote bind warning")
                .that(localized.getProperty("tunnel.nonLoopbackWarning.remote")).contains("localhost");
            assertWithMessage(bundle + " must name GatewayPorts literally")
                .that(localized.getProperty("tunnel.nonLoopbackWarning.remote")).contains("GatewayPorts");
        }
    }

    @Test
    void theTypeNamesKeepTheOpenSshOptionLetters() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            assertWithMessage(bundle).that(localized.getProperty("tunnel.type.local")).contains("(-L)");
            assertWithMessage(bundle).that(localized.getProperty("tunnel.type.remote")).contains("(-R)");
            assertWithMessage(bundle).that(localized.getProperty("tunnel.type.dynamic")).contains("(-D)");
            assertWithMessage(bundle).that(localized.getProperty("tunnel.type.dynamic")).contains("SOCKS");
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
