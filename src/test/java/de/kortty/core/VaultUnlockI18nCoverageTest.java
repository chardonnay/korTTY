package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The vault-unlock strings in all eight bundles. A missing key renders as its raw name in a
 * dialog that only appears for users who start locked, so nobody would notice in passing.
 */
class VaultUnlockI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> NEW_KEYS = List.of(
        "menu.security.unlockVault",
        "masterPassword.unlock.title",
        "masterPassword.unlock.header",
        "masterPassword.unlockButton",
        "vault.locked.title",
        "vault.unlock.button",
        "status.vaultUnlocked");

    /** Existing messages whose wording now points to the unlock path. */
    private static final List<String> UPDATED_KEYS = List.of(
        "settings.security.masterPassword.warning",
        "settings.security.masterPassword.requireOnStartup.tooltip",
        "settings.ai.error.vaultLocked",
        "settings.translation.error.vaultLocked",
        "jobscheduler.dialog.error.masterPasswordLocked");

    @Test
    void vaultUnlockKeysExistInEveryBundle() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : concat(NEW_KEYS, UPDATED_KEYS)) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
            }
        }
    }

    /**
     * The startup-prompt warning used to promise a "manual entry" that had no entry point. It must
     * now name the actual menu item, in the user's language.
     */
    @Test
    void startupWarningNamesTheUnlockMenuItem() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            String menuLabel = withoutEllipsis(localized.getProperty("menu.security.unlockVault"));
            for (String key : List.of(
                    "settings.security.masterPassword.warning",
                    "settings.security.masterPassword.requireOnStartup.tooltip")) {
                assertWithMessage(bundle + " " + key + " does not name '" + menuLabel + "'")
                    .that(localized.getProperty(key)).contains(menuLabel);
            }
        }
    }

    @Test
    void labelsAreActuallyTranslated() throws Exception {
        Properties base = loadBundle("messages.properties");
        for (String bundle : BUNDLES) {
            if (bundle.equals("messages.properties")) {
                continue;
            }
            Properties localized = loadBundle(bundle);
            for (String key : NEW_KEYS) {
                assertWithMessage(bundle + " left key " + key + " at the English wording")
                    .that(localized.getProperty(key)).isNotEqualTo(base.getProperty(key));
            }
        }
    }

    private static String withoutEllipsis(String label) {
        String trimmed = label.strip();
        if (trimmed.endsWith("...")) {
            return trimmed.substring(0, trimmed.length() - 3).strip();
        }
        if (trimmed.endsWith("…")) {
            return trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all;
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
