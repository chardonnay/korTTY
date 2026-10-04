package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.ServerConnection;
import de.kortty.paste.PasteWarningMode;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import org.testng.annotations.Test;

/**
 * The paste protection rows of the connection editor's Terminal behavior section: Use the default (naming
 * the global mode) first, then the three modes by the Settings page's names, the stored mode selected, and
 * the round trip of the mode and the line delay to what the connection stores. No JavaFX toolkit needed.
 */
class PasteConnectionSupportTest {

    private static List<PasteWarningMode> storedValues(List<PasteConnectionSupport.ModeChoice> choices) {
        List<PasteWarningMode> values = new ArrayList<>();
        for (PasteConnectionSupport.ModeChoice choice : choices) {
            values.add(choice.storedValue());
        }
        return values;
    }

    @Test
    void useTheDefaultComesFirstThenTheModesFromTheLeastToTheMostCautious() {
        List<PasteConnectionSupport.ModeChoice> choices = PasteConnectionSupport.modeChoices(PasteWarningMode.DEFAULT);

        assertThat(storedValues(choices)).containsExactly(null, PasteWarningMode.OFF,
            PasteWarningMode.UNLESS_BRACKETED, PasteWarningMode.ALWAYS).inOrder();
        assertThat(choices.get(1).label()).isEqualTo(I18n.get("settings.terminal.paste.mode.off"));
        assertThat(choices.get(2).label()).isEqualTo(I18n.get("settings.terminal.paste.mode.unlessBracketed"));
        assertThat(choices.get(3).label()).isEqualTo(I18n.get("settings.terminal.paste.mode.always"));
        assertThat(choices.get(3).toString()).isEqualTo(choices.get(3).label());
    }

    @Test
    void useTheDefaultNamesTheGlobalModeAsItIsNow() {
        assertThat(PasteConnectionSupport.modeChoices(PasteWarningMode.ALWAYS).getFirst().label()).isEqualTo(
            I18n.get(PasteConnectionSupport.MODE_DEFAULT_KEY, I18n.get("settings.terminal.paste.mode.always")));
        assertThat(PasteConnectionSupport.modeChoices(null).getFirst().label()).isEqualTo(
            I18n.get(PasteConnectionSupport.MODE_DEFAULT_KEY,
                I18n.get("settings.terminal.paste.mode.unlessBracketed")));
    }

    @Test
    void theStoredModeIsSelectedAndNullSelectsUseTheDefault() {
        List<PasteConnectionSupport.ModeChoice> choices = PasteConnectionSupport.modeChoices(PasteWarningMode.OFF);

        assertThat(PasteConnectionSupport.selectedMode(choices, null)).isSameInstanceAs(choices.getFirst());
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            assertThat(PasteConnectionSupport.selectedMode(choices, mode).storedValue()).isEqualTo(mode);
        }
    }

    @Test
    void everyChoiceStoresWhatItShows() {
        ServerConnection connection = new ServerConnection();
        for (PasteConnectionSupport.ModeChoice choice : PasteConnectionSupport.modeChoices(PasteWarningMode.DEFAULT)) {
            connection.setPasteWarningMode(PasteConnectionSupport.storedMode(choice));
            assertThat(connection.getPasteWarningMode()).isEqualTo(choice.storedValue());
        }
        assertWithMessage("no choice follows Settings").that(PasteConnectionSupport.storedMode(null)).isNull();
    }

    @Test
    void theModeNamesAreTheOnesOfTheSettingsPage() {
        assertThat(PasteConnectionSupport.modeKey(PasteWarningMode.OFF)).isEqualTo("settings.terminal.paste.mode.off");
        assertThat(PasteConnectionSupport.modeKey(PasteWarningMode.UNLESS_BRACKETED))
            .isEqualTo("settings.terminal.paste.mode.unlessBracketed");
        assertThat(PasteConnectionSupport.modeKey(PasteWarningMode.ALWAYS))
            .isEqualTo("settings.terminal.paste.mode.always");
    }

    @Test
    void theSpinnerStartsAtThePauseThatAppliesNow() {
        assertThat(PasteConnectionSupport.initialLineDelay(null, 200)).isEqualTo(200);
        assertThat(PasteConnectionSupport.initialLineDelay(50, 200)).isEqualTo(50);
        assertThat(PasteConnectionSupport.initialLineDelay(0, 200)).isEqualTo(0);
        assertThat(PasteConnectionSupport.initialLineDelay(null, 5_000)).isEqualTo(1000);
    }

    @Test
    void theLineDelayIsStoredOnlyWhileTheBoxIsTicked() {
        assertThat(PasteConnectionSupport.storedLineDelay(false, 300)).isNull();
        assertThat(PasteConnectionSupport.storedLineDelay(true, 300)).isEqualTo(300);
        assertWithMessage("a ticked 0 pastes this connection at once whatever Settings says")
            .that(PasteConnectionSupport.storedLineDelay(true, 0)).isEqualTo(0);
        assertThat(PasteConnectionSupport.storedLineDelay(true, null)).isEqualTo(0);
        assertThat(PasteConnectionSupport.storedLineDelay(true, 4_000)).isEqualTo(1000);
        assertThat(PasteConnectionSupport.storedLineDelay(true, -1)).isEqualTo(0);
    }

    @Test
    void theKeysListNamesEveryConnectionEditorPasteText() throws Exception {
        Properties english = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/messages.properties")) {
            english.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        TreeSet<String> declared = new TreeSet<>();
        for (String key : english.stringPropertyNames()) {
            if (key.startsWith("connEdit.paste.")) {
                declared.add(key);
            }
        }
        assertThat(declared).containsExactlyElementsIn(PasteConnectionSupport.KEYS);
    }
}
