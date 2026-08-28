package de.kortty.core;

import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

class TerminalEffectI18nCoverageTest {

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
            "plugin.terminalEffects.preview.title",
            "plugin.terminalEffects.preview.none",
            "plugin.terminalEffects.preview.unavailable",
            "plugin.terminalEffects.desc.mother",
            "plugin.terminalEffects.desc.amber-crt-90",
            "plugin.terminalEffects.desc.commodore-blue",
            "plugin.terminalEffects.desc.neon-city",
            "plugin.terminalEffects.desc.digital-rain",
            "plugin.terminalEffects.desc.hologram-hud",
            "plugin.terminalEffects.desc.poltergeist",
            "plugin.terminalEffects.desc.vhs-1987",
            "plugin.terminalEffects.desc.synthwave-horizon",
            "plugin.terminalEffects.desc.deep-space-radar",
            "plugin.terminalEffects.desc.typewriter-noir");

    @Test
    void allTerminalEffectKeysExistInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String localizedValue = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(localizedValue).isNotNull();
                assertWithMessage(bundle + " has blank value for key " + key)
                        .that(!localizedValue.isBlank()).isTrue();
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
}
