package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.isolation.IsolationLevel;
import de.kortty.isolation.IsolationReport;
import de.kortty.isolation.IsolationState;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * What a terminal tab shows about isolation (the shield and the incognito spy), the connection editor's
 * isolation rows, and the pieces of TerminalView that decide a session's isolation, strict mode and
 * incognito state. No JavaFX toolkit needed.
 */
class IsolationMarkersTest {

    private static final IsolationReport SANDBOXED =
        new IsolationReport(IsolationState.SANDBOXED, IsolationLevel.SANDBOX, "sandbox-exec", null);
    private static final IsolationReport PROCESS =
        new IsolationReport(IsolationState.PROCESS, IsolationLevel.PROCESS, null, null);
    private static final IsolationReport DEGRADED =
        new IsolationReport(IsolationState.DEGRADED, IsolationLevel.SANDBOX, "bubblewrap", "bwrap is not installed");

    @Test
    void aTabWithoutIsolationShowsNothing() {
        assertThat(IsolationMarkers.of(List.of(IsolationReport.NONE), false)).isEqualTo(
            new IsolationMarkers.Markers(IsolationState.NONE, null, null, null));
        assertThat(IsolationMarkers.of(List.of(), false).shield()).isEqualTo(IsolationState.NONE);
    }

    @Test
    void aSandboxedTabShowsTheFilledShieldNamingTheBackend() {
        IsolationMarkers.Markers markers = IsolationMarkers.of(List.of(SANDBOXED), false);
        assertThat(markers.shield()).isEqualTo(IsolationState.SANDBOXED);
        assertThat(markers.shieldText()).isEqualTo(I18n.get(IsolationMarkers.SANDBOXED_KEY, "sandbox-exec"));
        assertThat(markers.tooltipLine()).isEqualTo(markers.shieldText());
        assertThat(markers.incognitoText()).isNull();
    }

    @Test
    void aMissingSandboxShowsTheWarningWithTheReason() {
        IsolationMarkers.Markers markers = IsolationMarkers.of(List.of(SANDBOXED, DEGRADED), false);
        assertThat(markers.shield()).isEqualTo(IsolationState.DEGRADED);
        assertThat(markers.shieldText()).startsWith(I18n.get(IsolationMarkers.DEGRADED_KEY, "bwrap is not installed"));
        assertThat(markers.shieldText()).contains(I18n.get(IsolationMarkers.COUNT_SANDBOXED_KEY, 1));
    }

    @Test
    void mixedPanesShowTheWeakestAndCountThem() {
        IsolationMarkers.Markers markers = IsolationMarkers.of(List.of(SANDBOXED, SANDBOXED, PROCESS), false);
        assertThat(markers.shield()).isEqualTo(IsolationState.PROCESS);
        assertThat(markers.tooltipLine()).contains(I18n.get(IsolationMarkers.COUNT_SANDBOXED_KEY, 2));
        assertThat(markers.tooltipLine()).contains(I18n.get(IsolationMarkers.COUNT_PROCESS_KEY, 1));
    }

    @Test
    void anUnisolatedPaneHidesTheShieldButTheTooltipStillCounts() {
        IsolationMarkers.Markers markers = IsolationMarkers.of(List.of(SANDBOXED, IsolationReport.NONE), false);
        assertThat(markers.shield()).isEqualTo(IsolationState.NONE);
        assertThat(markers.shieldText()).isNull();
        assertThat(markers.tooltipLine()).contains(I18n.get(IsolationMarkers.COUNT_NONE_KEY, 1));
    }

    @Test
    void anIncognitoTabShowsTheSpyWithOrWithoutAShield() {
        IsolationMarkers.Markers plain = IsolationMarkers.of(List.of(IsolationReport.NONE), true);
        assertThat(plain.shield()).isEqualTo(IsolationState.NONE);
        assertThat(plain.incognitoText()).isEqualTo(I18n.get(IsolationMarkers.INCOGNITO_KEY));
        assertThat(TerminalTab.isolationTooltipLine(plain)).isEqualTo(plain.incognitoText());

        IsolationMarkers.Markers sandboxed = IsolationMarkers.of(List.of(SANDBOXED), true);
        assertThat(sandboxed.shield()).isEqualTo(IsolationState.SANDBOXED);
        assertThat(TerminalTab.isolationTooltipLine(sandboxed))
            .isEqualTo(sandboxed.tooltipLine() + "\n" + sandboxed.incognitoText());
    }

    @Test
    void eachShieldStateHasItsOwnShapeAndColour() {
        assertThat(TerminalTab.shieldPath(IsolationState.SANDBOXED)).isEqualTo(TerminalTab.SHIELD_FILLED_PATH);
        assertThat(TerminalTab.shieldPath(IsolationState.PROCESS)).contains(TerminalTab.SHIELD_INNER_CUTOUT);
        assertThat(TerminalTab.shieldPath(IsolationState.DEGRADED)).contains(TerminalTab.SHIELD_WARNING_CUTOUT);
        assertThat(TerminalTab.isolationStateStyleClass(IsolationState.SANDBOXED)).isEqualTo("tab-isolation-sandboxed");
        assertThat(TerminalTab.isolationStateStyleClass(IsolationState.DEGRADED)).isEqualTo("tab-isolation-degraded");
        assertThat(TerminalTab.isolationStateStyleClass(IsolationState.PROCESS)).isEqualTo("tab-isolation-process");
    }

    // ---- connection editor ------------------------------------------------------------------------

    @Test
    void useTheDefaultNamesTheFolderOrSettingsThenTheAllowedLevels() {
        List<IsolationConnectionSupport.LevelChoice> global = IsolationConnectionSupport.levelChoices(
            IsolationLevel.PROCESS, null, group -> null, null);
        assertThat(global.getFirst().label()).isEqualTo(I18n.get(IsolationConnectionSupport.DEFAULT_GLOBAL_KEY,
            IsolationConnectionSupport.levelName(IsolationLevel.PROCESS)));
        assertThat(global.stream().map(IsolationConnectionSupport.LevelChoice::storedValue).toList())
            .containsExactly(null, IsolationLevel.NONE, IsolationLevel.PROCESS, IsolationLevel.SANDBOX).inOrder();

        List<IsolationConnectionSupport.LevelChoice> folder = IsolationConnectionSupport.levelChoices(
            IsolationLevel.NONE, "Extern/ACME", Map.of("Extern", IsolationLevel.SANDBOX)::get, IsolationLevel.PROCESS);
        assertThat(folder.getFirst().label()).isEqualTo(I18n.get(IsolationConnectionSupport.DEFAULT_FOLDER_KEY,
            "Extern", IsolationConnectionSupport.levelName(IsolationLevel.SANDBOX)));
        assertThat(folder.stream().map(IsolationConnectionSupport.LevelChoice::storedValue).toList())
            .containsExactly(null, IsolationLevel.PROCESS, IsolationLevel.SANDBOX).inOrder();
    }

    @Test
    void aStoredLevelBelowTheMinimumShowsUseTheDefault() {
        List<IsolationConnectionSupport.LevelChoice> choices = IsolationConnectionSupport.levelChoices(
            IsolationLevel.NONE, null, group -> null, IsolationLevel.SANDBOX);
        assertThat(IsolationConnectionSupport.selectedLevel(choices, IsolationLevel.NONE)).isSameInstanceAs(choices.getFirst());
        assertThat(IsolationConnectionSupport.storedLevel(null)).isNull();
    }

    @Test
    void theHintNamesWhatTheProtocolCannotGet() {
        assertThat(IsolationConnectionSupport.hint(ConnectionProtocol.SSH_TCP, IsolationLevel.SANDBOX, null))
            .isEqualTo(I18n.get(IsolationConnectionSupport.UNSUPPORTED_KEY,
                IsolationConnectionSupport.levelName(IsolationLevel.NONE)));
        assertThat(IsolationConnectionSupport.hint(ConnectionProtocol.LOCAL_SHELL, IsolationLevel.SANDBOX, null)).isNull();
        assertThat(IsolationConnectionSupport.hint(ConnectionProtocol.LOCAL_SHELL, IsolationLevel.SANDBOX, "why"))
            .isEqualTo(I18n.get(IsolationConnectionSupport.SANDBOX_UNAVAILABLE_KEY, "why"));
        assertThat(IsolationConnectionSupport.hint(ConnectionProtocol.SSH_TCP, IsolationLevel.NONE, "why")).isNull();
    }

    // ---- TerminalView decisions ---------------------------------------------------------------------

    private static ServerConnection local(IsolationLevel own) {
        ServerConnection c = new ServerConnection();
        c.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        c.setIsolationLevel(own);
        return c;
    }

    @Test
    void aLocalShellGetsWhatItAsksFor() {
        GlobalSettings global = new GlobalSettings();
        assertThat(TerminalView.isolationRequestFor(global, local(IsolationLevel.SANDBOX), null).level())
            .isEqualTo(IsolationLevel.SANDBOX);
        global.setConnectionIsolationDefault(IsolationLevel.PROCESS);
        assertThat(TerminalView.isolationRequestFor(global, local(null), null).level())
            .isEqualTo(IsolationLevel.PROCESS);
    }

    @Test
    void sshFallsBackWhileItCannotBeIsolatedUnlessThePolicyDemandsIt() {
        ServerConnection ssh = new ServerConnection();
        ssh.setProtocol(ConnectionProtocol.SSH_TCP);
        ssh.setIsolationLevel(IsolationLevel.SANDBOX);
        assertThat(TerminalView.isolationRequestFor(new GlobalSettings(), ssh, null).level())
            .isEqualTo(IsolationLevel.NONE);
        try {
            TerminalView.isolationRequestFor(new GlobalSettings(), ssh, IsolationLevel.PROCESS);
            throw new AssertionError("a demanded isolation SSH cannot get must refuse the session");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo(I18n.get("isolation.error.protocolUnsupported",
                I18n.get("isolation.level.process")));
        }
    }

    @Test
    void thePolicyMinimumMakesASandboxRequestEnforced() {
        assertThat(TerminalView.isolationRequestFor(new GlobalSettings(), local(null), IsolationLevel.SANDBOX).enforced())
            .isTrue();
        assertThat(TerminalView.isolationRequestFor(new GlobalSettings(), local(IsolationLevel.SANDBOX),
            IsolationLevel.PROCESS).enforced()).isFalse();
    }

    @Test
    void strictModeFollowsTheConnectionOrTheSandbox() {
        ServerConnection auto = local(null);
        assertThat(TerminalView.strictTerminalModeFor(auto, null)).isFalse();
        auto.setStrictTerminalMode(Boolean.TRUE);
        assertThat(TerminalView.strictTerminalModeFor(auto, null)).isTrue();

        ServerConnection teamwork = local(null);
        teamwork.setConnectionSource(ConnectionSource.TEAMWORK);
        teamwork.setStrictTerminalMode(Boolean.FALSE);
        assertWithMessage("a teamwork connection cannot switch the automatic mode off")
            .that(TerminalView.strictTerminalModeFor(teamwork, null)).isFalse();
    }

    @Test
    void incognitoNeedsThePolicyAndIgnoresTeamworkConnections() {
        ServerConnection own = local(null);
        own.setIncognito(true);
        assertThat(TerminalView.incognito(false, own, true)).isTrue();
        assertThat(TerminalView.incognito(false, own, false)).isFalse();
        assertThat(TerminalView.incognito(true, local(null), true)).isTrue();

        ServerConnection teamwork = local(null);
        teamwork.setIncognito(true);
        teamwork.setConnectionSource(ConnectionSource.TEAMWORK);
        assertThat(TerminalView.incognito(false, teamwork, true)).isFalse();
    }

    // ---- translations -------------------------------------------------------------------------------

    private static final List<String> BUNDLES = List.of("messages.properties", "messages_de.properties",
        "messages_it.properties", "messages_es.properties", "messages_pt.properties", "messages_fr.properties",
        "messages_hr.properties", "messages_nl.properties");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private static List<String> allKeys() {
        List<String> keys = new ArrayList<>(IsolationMarkers.KEYS);
        keys.addAll(IsolationConnectionSupport.KEYS);
        keys.addAll(List.of("isolation.error.sandboxRequired", "isolation.error.protocolUnsupported",
            "isolation.sandbox.status.available", "isolation.sandbox.status.unsupportedOs",
            "isolation.sandbox.status.flatpak", "isolation.sandbox.status.notInstalled",
            "isolation.sandbox.status.selfTestFailed", "terminal.recording.error.incognito",
            "settings.security.isolation", "settings.security.isolation.info", "settings.security.isolation.default",
            "settings.security.isolation.default.tooltip", "settings.security.isolation.minimum",
            "settings.security.isolation.sandbox.checking", "settings.security.isolation.sandbox.available",
            "settings.security.isolation.sandbox.unavailable", "settings.security.isolation.protocols",
            "connManager.group.isolation", "connManager.group.isolation.default", "menu.file.newIncognitoSession"));
        return keys;
    }

    @Test
    void everyKeyExistsInEveryBundleWithTheEnglishPlaceholders() throws Exception {
        Properties english = load("messages.properties");
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            for (String key : allKeys()) {
                String value = localized.getProperty(key);
                assertWithMessage(bundle + " is missing " + key).that(value).isNotNull();
                assertWithMessage(bundle + " has a blank " + key).that(value.isBlank()).isFalse();
                assertWithMessage(bundle + " changes the placeholders of " + key)
                    .that(placeholders(value)).isEqualTo(placeholders(english.getProperty(key)));
            }
        }
    }

    private static List<String> placeholders(String value) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        found.sort(null);
        return found;
    }

    private static Properties load(String bundle) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = IsolationMarkersTest.class.getResourceAsStream("/i18n/" + bundle)) {
            assertWithMessage("bundle " + bundle).that(in).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
