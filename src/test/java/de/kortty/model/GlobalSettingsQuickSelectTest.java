package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.QuickSelectLabels;
import de.kortty.core.QuickSelectSettings;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Quick select's label letters and the user's own patterns in the global settings: what a fresh
 * installation and an old settings file use, that both survive the XML round trip, and that a value
 * broken by hand never breaks quick select.
 */
class GlobalSettingsQuickSelectTest {

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        Unmarshaller unmarshaller = JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller();
        return (GlobalSettings) unmarshaller.unmarshal(new StringReader(xml));
    }

    private static QuickSelectSettings effective(GlobalSettings settings) {
        return QuickSelectSettings.from(settings.getTerminalQuickSelectAlphabet(), settings.getTerminalQuickSelectPatterns());
    }

    @Test
    void aFreshInstallationUsesTheDefaultLettersAndNoPatterns() {
        GlobalSettings settings = new GlobalSettings();

        assertThat(settings.getTerminalQuickSelectAlphabet()).isNull();
        assertThat(settings.getTerminalQuickSelectPatterns()).isEmpty();
        assertThat(effective(settings).alphabet()).isEqualTo(QuickSelectLabels.DEFAULT_ALPHABET);
        assertThat(effective(settings).patterns().isEmpty()).isTrue();
    }

    @Test
    void aSettingsFileWrittenBeforeTheFeatureLoadsWithTheDefaults() throws Exception {
        GlobalSettings restored = unmarshal("<globalSettings><terminalLinkDetectionEnabled>true</terminalLinkDetectionEnabled>"
            + "</globalSettings>");

        assertThat(restored.getTerminalQuickSelectAlphabet()).isNull();
        assertThat(restored.getTerminalQuickSelectPatterns()).isEmpty();
        assertThat(effective(restored)).isEqualTo(QuickSelectSettings.DEFAULTS);
    }

    @Test
    void lettersAndPatternsSurviveAnXmlRoundTripInOrder() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalQuickSelectAlphabet("hjkl");
        settings.setTerminalQuickSelectPatterns(List.of("JIRA-\\d+", "web\\d+\\.example\\.com", "<&>"));

        String xml = marshal(settings);

        assertThat(xml).contains("<terminalQuickSelectAlphabet>hjkl</terminalQuickSelectAlphabet>");
        assertThat(xml).contains("<terminalQuickSelectPatterns>");
        GlobalSettings restored = unmarshal(xml);
        assertThat(restored.getTerminalQuickSelectAlphabet()).isEqualTo("hjkl");
        assertThat(restored.getTerminalQuickSelectPatterns())
            .containsExactly("JIRA-\\d+", "web\\d+\\.example\\.com", "<&>").inOrder();
        assertThat(effective(restored).alphabet()).isEqualTo("hjkl");
        assertThat(effective(restored).patterns().size()).isEqualTo(3);
    }

    @Test
    void blankLettersAreStoredAsTheDefaultAndBlankPatternsAreNotStored() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalQuickSelectAlphabet("  ");
        settings.setTerminalQuickSelectPatterns(Arrays.asList("INC\\d+", null, " ", ""));

        assertThat(settings.getTerminalQuickSelectAlphabet()).isNull();
        assertThat(settings.getTerminalQuickSelectPatterns()).containsExactly("INC\\d+");
        settings.setTerminalQuickSelectAlphabet(" jk ");
        assertThat(settings.getTerminalQuickSelectAlphabet()).isEqualTo("jk");
        settings.setTerminalQuickSelectPatterns(null);
        assertThat(settings.getTerminalQuickSelectPatterns()).isEmpty();
    }

    @Test
    void valuesBrokenByHandFallBackInsteadOfBreakingQuickSelect() throws Exception {
        GlobalSettings restored = unmarshal("<globalSettings>"
            + "<terminalQuickSelectAlphabet>ABC</terminalQuickSelectAlphabet>"
            + "<terminalQuickSelectPatterns><pattern>(broken</pattern><pattern>a*</pattern>"
            + "<pattern>INC\\d+</pattern></terminalQuickSelectPatterns>"
            + "</globalSettings>");

        QuickSelectSettings effective = effective(restored);

        assertThat(effective.alphabet()).isEqualTo(QuickSelectLabels.DEFAULT_ALPHABET);
        assertThat(effective.patterns().size()).isEqualTo(1);
        // The stored values stay as they are, so the settings page can show what is wrong with them.
        assertThat(restored.getTerminalQuickSelectAlphabet()).isEqualTo("ABC");
        assertThat(restored.getTerminalQuickSelectPatterns()).hasSize(3);
    }
}
