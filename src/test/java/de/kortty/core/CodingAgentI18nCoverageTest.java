package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertWithMessage;

/** Every coding-agent settings key must exist, be non-blank and keep its placeholders in all bundled locales. */
class CodingAgentI18nCoverageTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+\\}");

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
        "settings.codingAgent.header",
        "settings.codingAgent.detectionEnabled",
        "settings.codingAgent.detectionEnabled.tooltip",
        "settings.codingAgent.detectionEnabled.info",
        // Stage 2: settings rows
        "settings.codingAgent.notificationsEnabled",
        "settings.codingAgent.notificationsEnabled.tooltip",
        "settings.codingAgent.appBadgeEnabled",
        "settings.codingAgent.appBadgeEnabled.tooltip",
        // Stage 2: View menu
        "menu.codingAgent.panel",
        "menu.codingAgent.panel.left",
        "menu.codingAgent.panel.right",
        "menu.codingAgent.panel.toggle",
        "menu.codingAgent.nextBlocked",
        // Stage 2: dashboard marking
        "dashboard.paneTitle",
        "dashboard.footerAgents",
        "dashboard.agent.tooltip",
        "dashboard.codingAgent.focusPane",
        "dashboard.codingAgent.openPanel",
        "dashboard.codingAgent.sendEnter",
        "dashboard.codingAgent.sendEsc",
        "dashboard.codingAgent.interrupt",
        // Stage 2: states, panel, strip, notifications, title badge
        "codingAgent.state.blocked",
        "codingAgent.state.working",
        "codingAgent.state.done",
        "codingAgent.state.idle",
        "codingAgent.state.unknown",
        "codingAgent.panel.title",
        "codingAgent.panel.empty",
        "codingAgent.panel.nextBlocked",
        "codingAgent.panel.dockLeft",
        "codingAgent.panel.dockRight",
        "codingAgent.panel.hide",
        "codingAgent.panel.focus",
        "codingAgent.panel.key.tooltip",
        "codingAgent.panel.explain",
        "codingAgent.panel.rename",
        "codingAgent.panel.rename.title",
        "codingAgent.panel.rename.prompt",
        "codingAgent.panel.location",
        "codingAgent.panel.locationNoPane",
        "codingAgent.panel.window",
        "codingAgent.panel.notConnected",
        "codingAgent.panel.prompt.placeholder",
        "codingAgent.panel.prompt.send",
        "codingAgent.panel.prompt.blocked",
        "codingAgent.panel.prompt.hostShortcut",
        "codingAgent.panel.sent",
        "codingAgent.panel.error.paneNotFound",
        "codingAgent.panel.error.notConnected",
        "codingAgent.panel.error.writeFailed",
        "codingAgent.strip.tooltip",
        "codingAgent.notify.blocked.title",
        "codingAgent.notify.done.title",
        "codingAgent.notify.body",
        "codingAgent.title.badge");

    @Test
    void allCodingAgentKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
        Properties base = loadBundle("messages.properties");

        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String baseValue = base.getProperty(key);
                String localizedValue = localized.getProperty(key);
                assertWithMessage("Base bundle is missing key " + key).that(baseValue).isNotNull();
                assertWithMessage(bundle + " is missing key " + key).that(localizedValue).isNotNull();
                assertWithMessage(bundle + " has different placeholder count for key " + key)
                    .that(countPlaceholders(localizedValue)).isEqualTo(countPlaceholders(baseValue));
                assertWithMessage(bundle + " has blank value for key " + key)
                    .that(!localizedValue.isBlank()).isTrue();
                // LanguageManager.getString substitutes {n} with a plain String.replace and never runs
                // MessageFormat, so a MessageFormat-style doubled apostrophe is shown literally.
                assertWithMessage(bundle + " uses a MessageFormat-style doubled apostrophe for key " + key
                    + " (korTTY substitutes placeholders with a plain replace): " + localizedValue)
                    .that(localizedValue).doesNotContain("''");
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            return properties;
        }
    }

    private int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
