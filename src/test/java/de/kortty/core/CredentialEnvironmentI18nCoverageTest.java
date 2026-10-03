package de.kortty.core;

import de.kortty.model.EnvironmentDefinition;
import de.kortty.model.StoredCredential;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** The built-in credential environments are labelled from every bundle instead of fixed German text. */
class CredentialEnvironmentI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    @Test
    void everyBuiltInEnvironmentHasALabelInEveryBundledLocale() throws Exception {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (StoredCredential.Environment environment : StoredCredential.Environment.values()) {
                String value = localized.getProperty(environment.i18nKey());
                assertWithMessage(bundle + " is missing key " + environment.i18nKey()).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank value for " + environment.i18nKey())
                        .that(value.isBlank()).isFalse();
            }
        }
    }

    @Test
    void labelsMatchTheEnvironmentsNamedInTheCredentialManagerInfo() throws Exception {
        // credential.info lists the four built-in environments; the dialogs must use the same words.
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            String info = localized.getProperty("credential.info");
            for (StoredCredential.Environment environment : StoredCredential.Environment.values()) {
                assertWithMessage(bundle + " credential.info vs " + environment.i18nKey())
                        .that(info).contains(localized.getProperty(environment.i18nKey()));
            }
        }
    }

    @Test
    void translationsAreNotEnglishCopies() throws Exception {
        // Test, Staging and the French Production legitimately match English; Development never does.
        String key = StoredCredential.Environment.DEVELOPMENT.i18nKey();
        String english = loadBundle("messages.properties").getProperty(key);
        for (String bundle : BUNDLES.subList(1, BUNDLES.size())) {
            assertWithMessage(bundle + " left " + key + " untranslated")
                    .that(loadBundle(bundle).getProperty(key)).isNotEqualTo(english);
        }
    }

    @Test
    void environmentManagerLabelsBuiltInsFromTheBundleButKeepsTheirIds() throws Exception {
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
        EnvironmentManager manager = new EnvironmentManager(Files.createTempDirectory("kortty-env-i18n"));

        assertThat(manager.getDisplayName("PRODUCTION")).isEqualTo("Production");
        assertThat(manager.getDisplayName("DEVELOPMENT")).isEqualTo("Development");
        assertThat(manager.getDisplayName(null)).isEqualTo("Production");
        assertThat(manager.getEnvironments().stream().map(EnvironmentDefinition::getId).toList())
                .containsExactly("PRODUCTION", "DEVELOPMENT", "TEST", "STAGING").inOrder();
        assertThat(manager.getEnvironments().stream().map(EnvironmentDefinition::getDisplayName).toList())
                .containsExactly("Production", "Development", "Test", "Staging").inOrder();
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
