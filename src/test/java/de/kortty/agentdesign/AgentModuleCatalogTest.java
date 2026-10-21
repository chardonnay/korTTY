package de.kortty.agentdesign;

import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The catalogue lists all twenty numbered designs once, plus today's panel and one neutral choice per
 * role, and every role has at least one module a combination can pick today.
 */
class AgentModuleCatalogTest {

    @Test
    void everyNumberedDesignIsListedOnceWithAStableId() {
        Set<Integer> numbers = new HashSet<>();
        for (AgentModuleInfo module : AgentModuleCatalog.all()) {
            if (module.number() > 0) {
                assertWithMessage("design %s listed twice", module.number()).that(numbers.add(module.number())).isTrue();
                assertThat(module.id()).isEqualTo(String.format("d%02d", module.number()));
            }
        }
        assertThat(numbers).hasSize(20);
        assertThat(AgentModuleCatalog.all().stream().map(AgentModuleInfo::id).distinct().count())
            .isEqualTo(AgentModuleCatalog.all().size());
    }

    @Test
    void everyRoleHasAnImplementedChoice() {
        for (AgentRole role : AgentRole.values()) {
            assertWithMessage("role %s", role)
                .that(AgentModuleCatalog.forRole(role).stream().anyMatch(AgentModuleInfo::implemented))
                .isTrue();
        }
    }

    @Test
    void allTwentyDesignsCanBeChosen() {
        for (AgentModuleInfo module : AgentModuleCatalog.all()) {
            assertWithMessage("module %s", module.id()).that(module.implemented()).isTrue();
        }
    }

    @Test
    void theStandardAndClassicCombinationsUseOnlyImplementedModules() {
        for (AgentDesignCombination preset : AgentDesignPresets.all()) {
            for (AgentRole role : AgentRole.values()) {
                assertWithMessage("%s %s", preset.id(), role)
                    .that(AgentModuleCatalog.isUsable(preset.moduleFor(role), role)).isTrue();
            }
        }
        assertThat(AgentDesignPresets.STANDARD.usesInlineBlocks()).isTrue();
        assertThat(AgentDesignPresets.STANDARD.usesMetroBar()).isTrue();
        assertThat(AgentDesignPresets.CLASSIC.usesClassicHistory()).isTrue();
    }

    @Test
    void aModuleIsOnlyUsableInItsOwnRole() {
        assertThat(AgentModuleCatalog.isUsable("d08", AgentRole.STATUS)).isTrue();
        assertThat(AgentModuleCatalog.isUsable("d08", AgentRole.HISTORY)).isFalse();
        assertThat(AgentModuleCatalog.isUsable("d02", AgentRole.HISTORY)).isTrue();
        assertThat(AgentModuleCatalog.isUsable("d02", AgentRole.STATUS)).isFalse();
        assertThat(AgentModuleCatalog.isUsable("d14", AgentRole.APPROVAL)).isTrue();
        assertThat(AgentModuleCatalog.isUsable("d20", AgentRole.ARRANGEMENT)).isTrue();
        assertThat(AgentModuleCatalog.isUsable(" d03 ", AgentRole.HISTORY)).isTrue();
        assertThat(AgentModuleCatalog.isUsable(null, AgentRole.HISTORY)).isFalse();
    }

    @Test
    void numberLabelsArePaddedAndNeutralChoicesHaveNone() {
        List<String> labels = AgentModuleCatalog.forRole(AgentRole.STATUS).stream()
            .map(AgentModuleInfo::numberLabel).toList();
        assertThat(labels).contains("08");
        assertThat(AgentModuleCatalog.find("none").orElseThrow().numberLabel()).isEmpty();
    }
}
