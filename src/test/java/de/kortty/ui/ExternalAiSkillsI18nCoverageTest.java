package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.ExternalAiSkillException;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Every string of the external AI skills (AI Skills → External, the import dialog, the providers dialog)
 * exists, translated, in all eight bundles, keeps the English placeholders, and writes an apostrophe once —
 * LanguageManager fills {0} with String.replace rather than MessageFormat.
 */
class ExternalAiSkillsI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> PREFIXES = List.of(
        "settings.aiSkills.external.",
        "settings.aiSkills.providers.",
        "settings.aiSkills.view.",
        "settings.aiSkills.badge.edited");

    /** Words several languages share with English: product names, "Skill", "Token (Bearer)", "Name", "Type". */
    private static final Set<String> MAY_EQUAL_ENGLISH = Set.of(
        "settings.aiSkills.external.dialog.column.name",
        "settings.aiSkills.external.dialog.column.source",
        "settings.aiSkills.external.dialog.column.description",
        "settings.aiSkills.providers.auth.token",
        "settings.aiSkills.providers.column.name",
        "settings.aiSkills.providers.column.type",
        "settings.aiSkills.providers.name",
        "settings.aiSkills.providers.type",
        "settings.aiSkills.external.dialog.provider",
        "settings.aiSkills.external.providers",
        "settings.aiSkills.providers.secret",
        "settings.aiSkills.providers.type.http");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    private Set<String> keys() throws Exception {
        Set<String> keys = new TreeSet<>();
        for (String name : loadBundle("messages.properties").stringPropertyNames()) {
            if (PREFIXES.stream().anyMatch(name::startsWith)) {
                keys.add(name);
            }
        }
        return keys;
    }

    @Test
    void everyReasonTypeAndSignInHasItsText() throws Exception {
        Set<String> keys = keys();
        for (ExternalAiSkillException.Reason reason : ExternalAiSkillException.Reason.values()) {
            assertWithMessage("message of " + reason).that(keys)
                .contains("settings.aiSkills.external.error." + reason.name().toLowerCase(Locale.ROOT));
        }
        for (AiSkillProviderType type : AiSkillProviderType.values()) {
            String suffix = type.name().toLowerCase(Locale.ROOT);
            assertWithMessage("label of " + type).that(keys).contains("settings.aiSkills.providers.type." + suffix);
            assertWithMessage("provider hint of " + type).that(keys).contains("settings.aiSkills.providers.hint." + suffix);
            assertWithMessage("search hint of " + type).that(keys).contains("settings.aiSkills.external.hint." + suffix);
        }
        for (AiSkillProviderAuth auth : AiSkillProviderAuth.values()) {
            assertWithMessage("label of " + auth).that(keys)
                .contains("settings.aiSkills.providers.auth." + auth.name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void everyKeyExistsInEveryBundleWithTheEnglishPlaceholders() throws Exception {
        Properties english = loadBundle("messages.properties");
        Set<String> keys = keys();
        assertWithMessage("external skill keys").that(keys.size()).isAtLeast(80);
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing key " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for key " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " doubles an apostrophe in " + key).that(value).doesNotContain("''");
                assertWithMessage(bundle + " placeholders of " + key)
                    .that(placeholders(value)).isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    @Test
    void translationsAreNotLeftInEnglish() throws Exception {
        Properties english = loadBundle("messages.properties");
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            Properties localized = loadBundle(bundle);
            for (String key : keys()) {
                if (!MAY_EQUAL_ENGLISH.contains(key)) {
                    assertWithMessage(bundle + " still has the English text for " + key)
                        .that(localized.getProperty(key)).isNotEqualTo(english.getProperty(key));
                }
            }
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value != null ? value : "");
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
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
