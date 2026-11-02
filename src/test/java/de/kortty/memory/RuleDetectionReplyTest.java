package de.kortty.memory;

import static com.google.common.truth.Truth.assertThat;

import java.util.Optional;
import org.testng.annotations.Test;

class RuleDetectionReplyTest {

    @Test
    void readsTheFourFieldsOfAPlainObject() {
        Optional<RuleDetectionReply.Candidate> candidate = RuleDetectionReply.parse(
            "{\"isRule\": true, \"rule\": \"Verwende Tabs in Perl.\", \"topic\": \"perl\", \"scopeHint\": \"skill\"}");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().isRule()).isTrue();
        assertThat(candidate.get().rule()).isEqualTo("Verwende Tabs in Perl.");
        assertThat(candidate.get().topic()).isEqualTo("perl");
        assertThat(candidate.get().scopeHint()).isEqualTo("skill");
    }

    @Test
    void toleratesAFenceAndTextAroundTheObject() {
        Optional<RuleDetectionReply.Candidate> candidate = RuleDetectionReply.parse(
            "Here you go:\n```json\n{\"isRule\": false, \"rule\": \"\", \"topic\": \"\", \"scopeHint\": \"global\"}\n```");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().isRule()).isFalse();
    }

    @Test
    void acceptsTheBooleanAsText() {
        assertThat(RuleDetectionReply.parse("{\"isRule\": \"true\", \"rule\": \"Antworte kurz.\"}").get().isRule()).isTrue();
    }

    @Test
    void missingFieldsReadAsEmptyAndFalse() {
        RuleDetectionReply.Candidate candidate = RuleDetectionReply.parse("{}").get();

        assertThat(candidate.isRule()).isFalse();
        assertThat(candidate.rule()).isEmpty();
        assertThat(candidate.topic()).isEmpty();
        assertThat(candidate.scopeHint()).isEmpty();
    }

    @Test
    void anythingThatIsNotAnObjectGivesNothing() {
        assertThat(RuleDetectionReply.parse(null)).isEmpty();
        assertThat(RuleDetectionReply.parse("")).isEmpty();
        assertThat(RuleDetectionReply.parse("no json at all")).isEmpty();
        assertThat(RuleDetectionReply.parse("[1, 2, 3]")).isEmpty();
        assertThat(RuleDetectionReply.parse("{\"isRule\": true, \"rule\": ")).isEmpty();
    }
}
