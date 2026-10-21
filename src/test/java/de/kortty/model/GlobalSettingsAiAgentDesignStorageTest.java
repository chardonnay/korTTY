package de.kortty.model;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The KI-Agent design settings survive the round trip through {@code global-settings.xml}; a file
 * written before they existed loads with the standard combination, and built-in combinations are
 * never written.
 */
class GlobalSettingsAiAgentDesignStorageTest {

    private static final AgentDesignCombination OWN = new AgentDesignCombination(
        "custom-1a2b", "Nur Leiste", false, "classic", "d08", "history", "fixed");

    @Test
    void aFileWithoutDesignSettingsUsesTheStandard() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.getAiAgentDesignDefaultId()).isNull();
        assertThat(settings.getAiAgentDesignCombinations()).isEmpty();
        assertThat(settings.getAiAgentDesignAutoHideSeconds()).isEqualTo(6);
        assertThat(settings.isAiAgentDesignReducedAnimations()).isFalse();
        assertThat(marshal(new GlobalSettings())).doesNotContain("aiAgentDesignCombinations");
    }

    @Test
    void ownCombinationsAndTheDefaultSurviveTheRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiAgentDesignCombinations(List.of(AgentDesignPresets.STANDARD, OWN));
        settings.setAiAgentDesignDefaultId(" custom-1a2b ");
        settings.setAiAgentDesignAutoHideSeconds(0);
        settings.setAiAgentDesignReducedAnimations(true);

        String xml = marshal(settings);
        assertThat(xml).contains("<aiAgentDesignCombinations>");
        assertThat(xml).contains("id=\"custom-1a2b\"");
        assertThat(xml).doesNotContain("id=\"standard\"");

        GlobalSettings back = unmarshal(xml);
        assertThat(back.getAiAgentDesignCombinations()).containsExactly(OWN);
        assertThat(back.getAiAgentDesignDefaultId()).isEqualTo("custom-1a2b");
        assertThat(back.getAiAgentDesignAutoHideSeconds()).isEqualTo(0);
        assertThat(back.isAiAgentDesignReducedAnimations()).isTrue();
    }

    @Test
    void handEditedEntriesWithoutIdOrDuplicatedAreSkipped() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><aiAgentDesignCombinations>"
            + "<combination name=\"no id\" history=\"classic\"/>"
            + "<combination id=\"custom-x\" name=\"First\" history=\"d03\" status=\"none\" approval=\"history\" arrangement=\"fixed\"/>"
            + "<combination id=\"custom-x\" name=\"Second\" history=\"classic\"/>"
            + "</aiAgentDesignCombinations></globalSettings>");

        assertThat(settings.getAiAgentDesignCombinations()).hasSize(1);
        assertThat(settings.getAiAgentDesignCombinations().get(0).name()).isEqualTo("First");
    }

    @Test
    void theAutoHideDelayIsClampedAndTheDefaultIsNotWritten() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiAgentDesignAutoHideSeconds(500);
        assertThat(settings.getAiAgentDesignAutoHideSeconds()).isEqualTo(60);
        settings.setAiAgentDesignAutoHideSeconds(-3);
        assertThat(settings.getAiAgentDesignAutoHideSeconds()).isEqualTo(0);
        settings.setAiAgentDesignAutoHideSeconds(6);
        assertThat(marshal(settings)).doesNotContain("aiAgentDesignAutoHideSeconds");
    }

    @Test
    void theSettingsManagerReadsAndWritesThem() throws Exception {
        Path dir = Files.createTempDirectory("kortty-agent-design");
        try {
            de.kortty.core.GlobalSettingsManager manager = new de.kortty.core.GlobalSettingsManager(dir);
            manager.getSettings().setAiAgentDesignCombinations(List.of(OWN));
            manager.getSettings().setAiAgentDesignDefaultId("classic");
            manager.save();

            de.kortty.core.GlobalSettingsManager reloaded = new de.kortty.core.GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getAiAgentDesignCombinations()).containsExactly(OWN);
            assertThat(reloaded.getSettings().getAiAgentDesignDefaultId()).isEqualTo("classic");
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter writer = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
            .createUnmarshaller().unmarshal(new StringReader(xml));
    }
}
