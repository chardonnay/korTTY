package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.TranslationProfile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** Translation profiles in GlobalSettings: migration from the single-provider fields, normalisation, the XML round trip. */
public class TranslationProfilesTest {

    private Path configDir;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-translation-profiles");
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (Stream<Path> files = Files.walk(configDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void legacyFieldsBecomeOneDefaultProfile() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTranslationApiProvider(TranslationApiProvider.DEEPL);
        settings.setEncryptedTranslationApiKey("enc-key");
        settings.setTranslationApiUrl("https://api-free.deepl.com/v2/translate");

        List<TranslationProfile> profiles = settings.getTranslationProfiles();
        assertThat(profiles).hasSize(1);
        TranslationProfile migrated = profiles.get(0);
        assertThat(migrated.getId()).isEqualTo(GlobalSettings.MIGRATED_TRANSLATION_PROFILE_ID);
        assertThat(migrated.getProvider()).isEqualTo(TranslationApiProvider.DEEPL);
        assertThat(migrated.getEncryptedApiKey()).isEqualTo("enc-key");
        assertThat(migrated.getApiUrl()).isEqualTo("https://api-free.deepl.com/v2/translate");
        assertThat(migrated.getName()).isEqualTo("DeepL");
        assertThat(settings.getDefaultTranslationProfileId()).isEqualTo(migrated.getId());
        assertThat(settings.getDefaultTranslationProfile()).isSameInstanceAs(migrated);
    }

    @Test
    void emptyLegacyFieldsCreateNoProfile() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getTranslationProfiles()).isEmpty();
        assertThat(settings.getDefaultTranslationProfileId()).isNull();
        assertThat(settings.getDefaultTranslationProfile()).isNull();
    }

    @Test
    void exactlyOneDefaultAndTheDefaultIsMirroredIntoTheLegacyFields() {
        GlobalSettings settings = new GlobalSettings();
        TranslationProfile google = new TranslationProfile("Google", TranslationApiProvider.GOOGLE_TRANSLATE);
        google.setEncryptedApiKey("g-key");
        TranslationProfile ai = new TranslationProfile("AI", TranslationApiProvider.LOCAL_AI_PROFILE);
        ai.setAiProfileId("profile-1");
        settings.setTranslationProfiles(List.of(google, ai));
        // No default named: the first profile becomes it, and the legacy fields follow.
        assertThat(settings.getDefaultTranslationProfileId()).isEqualTo(google.getId());
        assertThat(settings.getTranslationApiProvider()).isEqualTo(TranslationApiProvider.GOOGLE_TRANSLATE);
        assertThat(settings.getEncryptedTranslationApiKey()).isEqualTo("g-key");

        settings.setDefaultTranslationProfileId(ai.getId());
        assertThat(settings.getDefaultTranslationProfile()).isEqualTo(ai);
        assertThat(settings.getTranslationApiProvider()).isEqualTo(TranslationApiProvider.LOCAL_AI_PROFILE);
        assertThat(settings.getEncryptedTranslationApiKey()).isNull();

        // An unknown default id falls back to the first profile; deleting the default reassigns it.
        settings.setDefaultTranslationProfileId("nope");
        assertThat(settings.getDefaultTranslationProfileId()).isEqualTo(google.getId());
        settings.setTranslationProfiles(List.of(ai));
        assertThat(settings.getDefaultTranslationProfileId()).isEqualTo(ai.getId());
    }

    @Test
    void duplicateAndMissingIdsAreRepaired() {
        GlobalSettings settings = new GlobalSettings();
        TranslationProfile a = new TranslationProfile("A", TranslationApiProvider.GOOGLE_TRANSLATE);
        TranslationProfile b = new TranslationProfile("B", TranslationApiProvider.DEEPL);
        b.setId(a.getId());
        TranslationProfile c = new TranslationProfile();
        c.setProvider(TranslationApiProvider.YANDEX);
        settings.setTranslationProfiles(List.of(a, b, c));
        List<TranslationProfile> profiles = settings.getTranslationProfiles();
        assertThat(profiles.stream().map(TranslationProfile::getId).distinct().count()).isEqualTo(3);
        assertThat(profiles.get(2).getName()).isEqualTo("Yandex Translate");
    }

    @Test
    void profilesRoundTripThroughTheXmlFile() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(configDir);
        manager.load();
        GlobalSettings settings = manager.getSettings();
        TranslationProfile microsoft = new TranslationProfile("Azure", TranslationApiProvider.MICROSOFT);
        microsoft.setEncryptedApiKey("ms-key");
        microsoft.setApiRegion("germanywestcentral");
        TranslationProfile ai = new TranslationProfile("Local", TranslationApiProvider.LOCAL_AI_PROFILE);
        ai.setAiProfileId("p1");
        settings.setTranslationProfiles(List.of(microsoft, ai));
        settings.setDefaultTranslationProfileId(ai.getId());
        manager.save();

        GlobalSettingsManager reloaded = new GlobalSettingsManager(configDir);
        reloaded.load();
        List<TranslationProfile> profiles = reloaded.getSettings().getTranslationProfiles();
        assertThat(profiles).hasSize(2);
        assertThat(profiles.get(0).getApiRegion()).isEqualTo("germanywestcentral");
        assertThat(profiles.get(0).getEncryptedApiKey()).isEqualTo("ms-key");
        assertThat(profiles.get(1).getAiProfileId()).isEqualTo("p1");
        assertThat(reloaded.getSettings().getDefaultTranslationProfileId()).isEqualTo(ai.getId());
        // Downgrade safety: the legacy fields in the file describe the default.
        String xml = Files.readString(configDir.resolve(GlobalSettingsManager.SETTINGS_FILE));
        assertThat(xml).contains("<translationApiProvider>LOCAL_AI_PROFILE</translationApiProvider>");
        assertThat(xml).contains("<translationProfiles>");
    }

    @Test
    void factoryBuildsTheRightClientOrReportsWhy() {
        GlobalSettings settings = new GlobalSettings();
        TranslationServiceFactory.SecretResolver secrets = encrypted -> "plain-" + encrypted;

        TranslationProfile google = new TranslationProfile("G", TranslationApiProvider.GOOGLE_TRANSLATE);
        assertThat(TranslationServiceFactory.problem(google, settings, secrets, null)).isEqualTo(TranslationServiceFactory.Problem.NO_KEY);
        assertThat(TranslationServiceFactory.create(google, settings, secrets)).isNull();
        google.setEncryptedApiKey("k");
        assertThat(TranslationServiceFactory.problem(google, settings, secrets, null)).isNull();
        assertThat(TranslationServiceFactory.create(google, settings, secrets)).isInstanceOf(GoogleTranslationService.class);
        // A typed key beats the stored one, and a locked vault still works with a typed key.
        assertThat(TranslationServiceFactory.create(google, settings, TranslationServiceFactory.SecretResolver.NONE, "typed"))
            .isInstanceOf(GoogleTranslationService.class);
        assertThat(TranslationServiceFactory.create(google, settings, TranslationServiceFactory.SecretResolver.NONE)).isNull();

        TranslationProfile libre = new TranslationProfile("L", TranslationApiProvider.LIBRETRANSLATE);
        assertThat(TranslationServiceFactory.problem(libre, settings, secrets, null)).isNull();
        assertThat(TranslationServiceFactory.create(libre, settings, secrets)).isInstanceOf(LibreTranslateTranslationService.class);

        TranslationProfile deepl = new TranslationProfile("D", TranslationApiProvider.DEEPL);
        deepl.setEncryptedApiKey("k");
        assertThat(TranslationServiceFactory.create(deepl, settings, secrets)).isInstanceOf(DeepLTranslationService.class);
        TranslationProfile ms = new TranslationProfile("M", TranslationApiProvider.MICROSOFT);
        ms.setEncryptedApiKey("k");
        assertThat(TranslationServiceFactory.create(ms, settings, secrets)).isInstanceOf(MicrosoftTranslationService.class);
        TranslationProfile yandex = new TranslationProfile("Y", TranslationApiProvider.YANDEX);
        yandex.setEncryptedApiKey("k");
        assertThat(TranslationServiceFactory.create(yandex, settings, secrets)).isInstanceOf(YandexTranslationService.class);

        TranslationProfile ai = new TranslationProfile("AI", TranslationApiProvider.LOCAL_AI_PROFILE);
        assertThat(TranslationServiceFactory.problem(ai, settings, secrets, null)).isEqualTo(TranslationServiceFactory.Problem.NO_AI_PROFILE);
        ai.setAiProfileId("gone");
        assertThat(TranslationServiceFactory.problem(ai, settings, secrets, null)).isEqualTo(TranslationServiceFactory.Problem.AI_PROFILE_MISSING);
        assertThat(TranslationServiceFactory.problem(null, settings, secrets, null)).isEqualTo(TranslationServiceFactory.Problem.NO_PROFILE);
        assertThat(TranslationServiceFactory.createDefault(settings, secrets)).isNull();
    }
}
