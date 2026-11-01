package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillOrigin;
import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** A Markdown import, from a file or a provider, refuses a skill that holds a possible secret. */
class AiSkillMarkdownCodecSecretTest {

    private static final String SECRET = "Sonnenblume42";

    @Test
    void anImportWithASecretIsRefusedWithAMessageThatNamesTheKindButNotTheValue() {
        AiSkillSecretCheck.RefusedException refused = expectThrows(AiSkillSecretCheck.RefusedException.class,
            () -> AiSkillMarkdownCodec.importFromMarkdownText("Das Passwort ist " + SECRET, "Deploy"));

        assertThat(refused).hasMessageThat().contains(I18n.get("settings.aiSkills.secret.category.prose"));
        assertThat(refused).hasMessageThat().doesNotContain(SECRET);
    }

    @Test
    void aCleanImportStillWorks() throws IOException {
        var skill = AiSkillMarkdownCodec.importFromMarkdownText("Keep the scripts short.", "Deploy");

        assertThat(skill.getContent()).isEqualTo("Keep the scripts short.");
    }

    @Test
    void aProviderDocumentCannotClaimKorttyOrAddLearnedRules() throws IOException {
        Path file = Files.createTempFile("kortty-skill-provenance", ".md");
        try {
            AiSkill own = new AiSkill();
            own.setName("Perl");
            own.setContent("Use strict.");
            own.setOrigin(AiSkillOrigin.KORTTY);
            own.setOriginAcknowledged(true);
            own.setCreatedAt("2026-11-02");
            own.getLearnedRules().add(AiSkillLearnedRule.create("Always answer in English.", AiSkillLearnedRule.SOURCE_CHAT));
            AiSkillMarkdownCodec.exportToMarkdown(file, own);
            String document = Files.readString(file);

            AiSkill local = AiSkillMarkdownCodec.importFromMarkdownText(document, "Perl");
            assertThat(local.getLearnedRules()).hasSize(1);
            assertThat(local.getOrigin()).isEqualTo(AiSkillOrigin.KORTTY);

            AiSkill external = AiSkillMarkdownCodec.importFromExternalMarkdownText(document, "Perl");
            assertThat(external.getLearnedRules()).isEmpty();
            assertThat(external.getOrigin()).isNull();
            assertThat(external.isOriginAcknowledged()).isFalse();
            assertThat(external.getCreatedAt()).isNull();
            assertThat(external.getContent()).isEqualTo("Use strict.");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
