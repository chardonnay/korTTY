package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.AiSkillProviderAuth;
import de.kortty.model.AiSkillProviderType;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** External AI-skill providers and the source of imported skills survive a save and reload. */
class GlobalSettingsAiSkillProvidersTest {

    @Test
    void aFreshLibraryStartsWithTheGitHubAndSkillsMpProviders() {
        List<AiSkillProvider> providers = new GlobalSettings().getAiSkillProviders();

        assertThat(providers.stream().map(AiSkillProvider::getId).toList())
            .containsExactly(AiSkillProvider.DEFAULT_GITHUB_ID, AiSkillProvider.DEFAULT_SKILLSMP_ID).inOrder();
        assertThat(providers.get(0).effectiveBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(providers.get(1).effectiveBaseUrl()).isEqualTo("https://skillsmp.com");
    }

    @Test
    void providersAndExternalSourcesRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("kortty-skill-providers");
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        AiSkillProvider intranet = new AiSkillProvider();
        intranet.setId("intranet");
        intranet.setName("Intranet");
        intranet.setType(AiSkillProviderType.HTTP);
        intranet.setBaseUrl("https://skills.example.test/");
        intranet.setAuth(AiSkillProviderAuth.BASIC);
        intranet.setUsername("alice");
        intranet.setEncryptedSecret("encrypted-password");
        intranet.setEnabled(false);
        List<AiSkillProvider> providers = new ArrayList<>(AiSkillProvider.defaults());
        providers.add(intranet);
        manager.getSettings().setAiSkillProviders(providers);

        AiSkill skill = new AiSkill();
        skill.setId("ext-1");
        skill.setName("pdf");
        skill.setContent("# PDF");
        skill.setEnabled(false);
        AiSkillExternalSource source = new AiSkillExternalSource();
        source.setProviderId(AiSkillProvider.DEFAULT_GITHUB_ID);
        source.setReference("https://github.com/anthropics/skills/tree/main/skills/pdf");
        source.setSourceUrl("https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md");
        source.setRevision("abc123");
        source.setContentSha256("feed");
        source.setImportedAt(1_700_000_000_000L);
        skill.setExternalSource(source);
        manager.getSettings().setAiSkills(List.of(skill));
        manager.save();

        GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
        reloaded.load();

        AiSkillProvider loaded = reloaded.getSettings().findAiSkillProvider("intranet");
        assertThat(reloaded.getSettings().getAiSkillProviders()).hasSize(3);
        assertThat(loaded.getName()).isEqualTo("Intranet");
        assertThat(loaded.getType()).isEqualTo(AiSkillProviderType.HTTP);
        assertThat(loaded.effectiveBaseUrl()).isEqualTo("https://skills.example.test");
        assertThat(loaded.getAuth()).isEqualTo(AiSkillProviderAuth.BASIC);
        assertThat(loaded.getUsername()).isEqualTo("alice");
        assertThat(loaded.getEncryptedSecret()).isEqualTo("encrypted-password");
        assertThat(loaded.isEnabled()).isFalse();

        AiSkill loadedSkill = reloaded.getSettings().getAiSkills().get(0);
        assertThat(loadedSkill.isExternal()).isTrue();
        AiSkillExternalSource loadedSource = loadedSkill.getExternalSource();
        assertThat(loadedSource.getProviderId()).isEqualTo(AiSkillProvider.DEFAULT_GITHUB_ID);
        assertThat(loadedSource.getReference()).isEqualTo("https://github.com/anthropics/skills/tree/main/skills/pdf");
        assertThat(loadedSource.getSourceUrl()).isEqualTo("https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md");
        assertThat(loadedSource.getRevision()).isEqualTo("abc123");
        assertThat(loadedSource.getContentSha256()).isEqualTo("feed");
        assertThat(loadedSource.getImportedAt()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void anEmptiedProviderListStaysEmptyAfterReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-skill-providers-empty");
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        manager.getSettings().setAiSkillProviders(List.of());
        manager.save();

        GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
        reloaded.load();

        assertThat(reloaded.getSettings().getAiSkillProviders()).isEmpty();
    }

    @Test
    void duplicateProviderIdsAreRepairedAndBuiltinsCannotBeExternal() {
        GlobalSettings settings = new GlobalSettings();
        AiSkillProvider first = AiSkillProvider.defaults().get(0);
        AiSkillProvider second = AiSkillProvider.defaults().get(0);
        settings.setAiSkillProviders(List.of(first, second));

        AiSkill builtin = new AiSkill();
        builtin.setBuiltinId("builtin.lang.python");
        builtin.setExternalSource(new AiSkillExternalSource());
        settings.setAiSkills(List.of(builtin));

        assertThat(settings.getAiSkillProviders().get(0).getId()).isNotEqualTo(settings.getAiSkillProviders().get(1).getId());
        assertThat(settings.getAiSkills().get(0).isExternal()).isFalse();
    }
}
