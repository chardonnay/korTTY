package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.UnmarshalException;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * AI skills never put text into {@code global-settings.xml} that XML 1.0 cannot read back: one such
 * character — pasted into the Markdown editor or imported from a file — would make the whole file fail
 * to load, and every setting would be back at its default.
 */
class GlobalSettingsAiSkillStorageTest {

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }

    /** A setting away from its default, to tell a loaded file from the fallback to defaults. */
    private static GlobalSettings settingsWithAMarker() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiSkillAutoDetectionEnabled(false);
        return settings;
    }

    @Test
    void theFileCannotHoldAControlCharacterWhichIsWhyNoneMayReachIt() {
        String xml = "<globalSettings><aiSkills><aiSkill><id>s1</id><content>Ring\u0007</content>"
            + "</aiSkill></aiSkills></globalSettings>";

        expectThrows(UnmarshalException.class, () -> unmarshal(xml));
    }

    @Test
    void theSetterStripsWhatTheFileCannotHoldSoTheSettingsStillLoad() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setId("s1");
        skill.setName("Bash\u0007 style");
        skill.setDescription("Short￿ answers");
        skill.setTags(List.of("bash", "\u001b[31m", "linux\uD800"));
        skill.setContent("Use set -e.\u0000\n\tIndent with tabs.\r\nNo ￾BOM. Emoji 🚀 stays.");
        GlobalSettings settings = settingsWithAMarker();

        settings.setAiSkills(List.of(skill));

        AiSkill stored = settings.getAiSkills().get(0);
        assertThat(stored.getId()).isEqualTo("s1");
        assertThat(stored.getName()).isEqualTo("Bash style");
        assertThat(stored.getDescription()).isEqualTo("Short answers");
        assertThat(stored.getTags()).containsExactly("bash", "[31m", "linux").inOrder();
        // Tab, LF and CR are XML characters; a supplementary character is a valid surrogate pair.
        assertThat(stored.getContent())
            .isEqualTo("Use set -e.\n\tIndent with tabs.\r\nNo BOM. Emoji 🚀 stays.");
        assertThat(stored.firstUnstorableText()).isNull();

        GlobalSettings reloaded = unmarshal(marshal(settings));

        assertThat(reloaded.isAiSkillAutoDetectionEnabled()).isFalse();
        AiSkill reloadedSkill = reloaded.getAiSkills().get(0);
        assertThat(reloadedSkill.getName()).isEqualTo("Bash style");
        assertThat(reloadedSkill.getTags()).containsExactly("bash", "[31m", "linux").inOrder();
        // The marshaller turns CR into a character reference; the text read back is the text stored.
        assertThat(reloadedSkill.getContent()).isEqualTo(stored.getContent());
    }

    @Test
    void aBuiltinBaselineIsStrippedToo() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setBuiltinId("builtin.lang.bash");
        skill.setBuiltinTopics(List.of("bash\u0001"));
        AiSkillBuiltinBaseline baseline = new AiSkillBuiltinBaseline();
        baseline.setName("Bash\u0002");
        baseline.setContent("Shipped\u001f text");
        baseline.setTags(List.of("\u0003shell"));
        skill.setBuiltinBaseline(baseline);
        GlobalSettings settings = settingsWithAMarker();

        settings.setAiSkills(List.of(skill));
        GlobalSettings reloaded = unmarshal(marshal(settings));

        AiSkill reloadedSkill = reloaded.getAiSkills().get(0);
        assertThat(reloadedSkill.getBuiltinTopics()).containsExactly("bash");
        assertThat(reloadedSkill.getBuiltinBaseline().getName()).isEqualTo("Bash");
        assertThat(reloadedSkill.getBuiltinBaseline().getContent()).isEqualTo("Shipped text");
        assertThat(reloadedSkill.getBuiltinBaseline().getTags()).containsExactly("shell");
    }

    @Test
    void storableSkillsAreStoredUnchanged() throws Exception {
        AiSkill skill = new AiSkill();
        skill.setName("Größe — 日本語");
        skill.setDescription("Emoji 🚀");
        skill.setTags(List.of("bash"));
        skill.setContent("Line one\n\tLine two");
        GlobalSettings settings = settingsWithAMarker();

        settings.setAiSkills(List.of(skill));
        AiSkill reloadedSkill = unmarshal(marshal(settings)).getAiSkills().get(0);

        assertThat(reloadedSkill.getId()).isEqualTo(skill.getId());
        assertThat(reloadedSkill.getName()).isEqualTo("Größe — 日本語");
        assertThat(reloadedSkill.getDescription()).isEqualTo("Emoji 🚀");
        assertThat(reloadedSkill.getContent()).isEqualTo("Line one\n\tLine two");
    }
}
