package de.kortty.memory;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class RuleTopicTest {

    @Test
    void aSingleWordToolNameCountsAsALanguageOrToolButCommonWordsDoNot() {
        assertThat(RuleTopic.isLanguageOrTool("svelte", List.of())).isTrue();
        assertThat(RuleTopic.isLanguageOrTool("Svelte", List.of())).isTrue();
        assertThat(RuleTopic.isLanguageOrTool("c++", List.of())).isTrue();
        assertThat(RuleTopic.isLanguageOrTool("style", List.of())).isFalse();
        assertThat(RuleTopic.isLanguageOrTool("two words", List.of())).isFalse();
        assertThat(RuleTopic.isLanguageOrTool("7zip", List.of())).isFalse();
        assertThat(RuleTopic.isLanguageOrTool(null, List.of())).isFalse();
    }

    @Test
    void aKnownHintWinsAndAnUnknownMultiWordHintDoesNotReplaceTheTopicOfTheText() {
        RuleProposal exact = new RuleProposal("Declare variables with state", RuleSource.CHAT, null, null, null, "Perl");
        assertThat(RuleTopic.of(exact, List.of("perl"))).hasValue("perl");

        RuleProposal text = new RuleProposal("Perl: use state", RuleSource.CHAT, null, null, null, "Perl 5");
        assertThat(RuleTopic.of(text, List.of("perl"))).hasValue("perl");
    }
}
