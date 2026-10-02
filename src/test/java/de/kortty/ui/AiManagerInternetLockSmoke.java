package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for the AI Manager's internet-access lock on native Anthropic
 * Messages profiles. korTTY's Anthropic service sends no web tools, so the dropdown is locked to
 * Disabled with a hint. The lock must follow the API URL as it is typed, and loading a profile
 * must never let the half-filled form force Disabled onto a profile that is not an Anthropic one
 * (the URL field still holds the previous profile's value while the connection mode is applied).
 * The profile editor in Settings &gt; AI applies the same rule and is checked the same way.
 * Run via the {@code aiManagerInternetLockSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiManagerInternetLockSmoke {

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final AiInternetAccessMode WEB_MODE = AiInternetAccessMode.KORTTY_TAVILY_TOOL;

    private AiManagerInternetLockSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                AiManagerDialog dialog = new AiManagerDialog(null);
                ObservableList<AiProfile> profiles = get(dialog, "profiles");
                ListView<AiProfile> profileListView = get(dialog, "profileListView");
                ComboBox<AiInternetAccessMode> internetCombo = get(dialog, "internetAccessModeCombo");
                Label hint = get(dialog, "internetUnsupportedHintLabel");
                TextField apiUrlField = get(dialog, "apiUrlField");

                AiProfile legacyAnthropic = profile("p-anthropic", "Anthropic", AiConnectionMode.HTTP_API, ANTHROPIC_URL);
                AiProfile openAi = profile("p-openai", "OpenAI", AiConnectionMode.HTTP_API, OPENAI_URL);
                // A CLI profile never uses its API URL; a leftover Anthropic one must not lock anything.
                AiProfile cliWithLeftoverUrl = profile("p-cli", "CLI", AiConnectionMode.LOCAL_CLI, ANTHROPIC_URL);
                AiProfile openAiAfterCli = profile("p-openai-2", "OpenAI 2", AiConnectionMode.HTTP_API, OPENAI_URL);
                profiles.addAll(legacyAnthropic, openAi, cliWithLeftoverUrl, openAiAfterCli);

                // --- A: a stored Anthropic profile with a web mode from an older version. ---
                profileListView.getSelectionModel().select(legacyAnthropic);
                System.out.println("A: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible() + " profile=" + legacyAnthropic.getInternetAccessMode());
                expect(internetCombo.getValue() == AiInternetAccessMode.DISABLED, "A: combo not forced to Disabled");
                expect(internetCombo.isDisabled(), "A: combo still enabled");
                expect(hint.isVisible() && hint.isManaged(), "A: hint not shown");
                expect(legacyAnthropic.getInternetAccessMode() == AiInternetAccessMode.DISABLED,
                    "A: profile keeps the ignored mode " + legacyAnthropic.getInternetAccessMode());

                // --- B: an OpenAI-compatible profile keeps its mode and an enabled combo. ---
                profileListView.getSelectionModel().select(openAi);
                System.out.println("B: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible() + " profile=" + openAi.getInternetAccessMode());
                expect(internetCombo.getValue() == WEB_MODE, "B: combo lost the stored mode");
                expect(!internetCombo.isDisabled(), "B: combo stays locked");
                expect(!hint.isVisible() && !hint.isManaged(), "B: hint still shown");
                expect(openAi.getInternetAccessMode() == WEB_MODE, "B: profile mode changed");
                expect(legacyAnthropic.getInternetAccessMode() == AiInternetAccessMode.DISABLED,
                    "B: switching away changed the Anthropic profile");

                // --- C: typing an Anthropic URL locks at once; an OpenAI URL unlocks again. ---
                apiUrlField.setText(ANTHROPIC_URL);
                System.out.println("C1: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible());
                expect(internetCombo.getValue() == AiInternetAccessMode.DISABLED, "C1: combo not forced to Disabled");
                expect(internetCombo.isDisabled() && hint.isVisible(), "C1: combo not locked or hint missing");
                apiUrlField.setText(OPENAI_URL);
                System.out.println("C2: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible());
                expect(!internetCombo.isDisabled() && !hint.isVisible(), "C2: lock not lifted for an OpenAI URL");

                // --- D: loading must not force Disabled from the previous profile's URL. ---
                profileListView.getSelectionModel().select(cliWithLeftoverUrl);
                System.out.println("D1: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible() + " profile=" + cliWithLeftoverUrl.getInternetAccessMode());
                expect(!hint.isVisible(), "D1: CLI profile shows the Anthropic hint");
                expect(cliWithLeftoverUrl.getInternetAccessMode() == WEB_MODE, "D1: CLI profile mode was forced");
                profileListView.getSelectionModel().select(openAiAfterCli);
                System.out.println("D2: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
                    + " hint=" + hint.isVisible() + " profile=" + openAiAfterCli.getInternetAccessMode());
                expect(openAiAfterCli.getInternetAccessMode() == WEB_MODE,
                    "D2: loading after a profile with an Anthropic URL forced Disabled onto an OpenAI profile");
                expect(internetCombo.getValue() == WEB_MODE && !internetCombo.isDisabled(),
                    "D2: OpenAI profile shown locked");

                checkSettingsDialog();
            } catch (Throwable t) {
                failure.compareAndSet(null, String.valueOf(t));
            } finally {
                done.countDown();
            }
        });

        if (!done.await(30, TimeUnit.SECONDS)) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        Platform.exit();
        if (failure.get() != null) {
            System.err.println("SMOKE FAILED: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK");
    }

    /** Settings &gt; AI: the same lock, on the dialog's own copies of the stored profiles. */
    private static void checkSettingsDialog() throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-internet-lock-smoke");
        GlobalSettings settings = new GlobalSettings();
        settings.setAiProfiles(new java.util.ArrayList<>(List.of(
            profile("s-anthropic", "Anthropic", AiConnectionMode.HTTP_API, ANTHROPIC_URL),
            profile("s-cli", "CLI", AiConnectionMode.LOCAL_CLI, ANTHROPIC_URL),
            profile("s-openai", "OpenAI", AiConnectionMode.HTTP_API, OPENAI_URL))));
        SettingsDialog dialog = new SettingsDialog(
            null, null,
            new ConfigurationManager(isolatedHome), settings,
            new CredentialManager(isolatedHome), new GPGKeyManager(isolatedHome));
        ListView<AiProfile> listView = get(dialog, "aiProfileListView");
        ComboBox<AiInternetAccessMode> internetCombo = get(dialog, "aiInternetAccessModeCombo");
        Label hint = get(dialog, "aiInternetUnsupportedHintLabel");
        TextField apiUrlField = get(dialog, "aiApiUrlField");
        AiProfile anthropic = listView.getItems().get(0);
        AiProfile cli = listView.getItems().get(1);
        AiProfile openAi = listView.getItems().get(2);

        listView.getSelectionModel().select(anthropic);
        System.out.println("S1: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
            + " hint=" + hint.isVisible() + " profile=" + anthropic.getInternetAccessMode());
        expect(internetCombo.getValue() == AiInternetAccessMode.DISABLED && internetCombo.isDisabled()
            && hint.isVisible(), "S1: Anthropic profile not locked to Disabled");
        expect(anthropic.getInternetAccessMode() == AiInternetAccessMode.DISABLED, "S1: profile keeps the ignored mode");

        listView.getSelectionModel().select(cli);
        listView.getSelectionModel().select(openAi);
        System.out.println("S2: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
            + " hint=" + hint.isVisible() + " profile=" + openAi.getInternetAccessMode());
        expect(cli.getInternetAccessMode() == WEB_MODE, "S2: CLI profile mode was forced");
        expect(openAi.getInternetAccessMode() == WEB_MODE && internetCombo.getValue() == WEB_MODE
            && !internetCombo.isDisabled() && !hint.isVisible(),
            "S2: loading after a profile with an Anthropic URL locked an OpenAI profile");

        apiUrlField.setText(ANTHROPIC_URL);
        System.out.println("S3: combo=" + internetCombo.getValue() + " disabled=" + internetCombo.isDisabled()
            + " hint=" + hint.isVisible());
        expect(internetCombo.getValue() == AiInternetAccessMode.DISABLED && internetCombo.isDisabled()
            && hint.isVisible(), "S3: typing an Anthropic URL did not lock the combo");
        apiUrlField.setText(OPENAI_URL);
        expect(!internetCombo.isDisabled() && !hint.isVisible(), "S3: lock not lifted for an OpenAI URL");
    }

    private static AiProfile profile(String id, String name, AiConnectionMode mode, String apiUrl) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setConnectionMode(mode);
        profile.setApiUrl(apiUrl);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("some-model");
        profile.setInternetAccessMode(WEB_MODE);
        return profile;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
