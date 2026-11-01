package de.kortty.memory;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.BuiltinAiSkillCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class RuleDetectorTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String TABS_RULE = "Verwende in Perl immer Tabs zum Einrücken.";
    private static final RuleDetector.Origin CHAT = new RuleDetector.Origin("run-1", RuleSource.CHAT, null, null, null);

    private Path dir;
    private SteppingClock clock;
    private LearningState learning;
    private AtomicInteger calls;

    @BeforeMethod
    void createState() throws IOException {
        dir = Files.createTempDirectory("kortty-detector-");
        clock = new SteppingClock(NOW);
        learning = new LearningState(dir, clock);
        calls = new AtomicInteger();
    }

    @AfterMethod(alwaysRun = true)
    void deleteDirectory() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ----- local cue filter

    @Test
    void germanAndEnglishMultiWordCuesTrigger() {
        List<String> messages = List.of(
            "Ab jetzt immer Tabs verwenden.",
            "Ab sofort bitte Hochdeutsch antworten.",
            "In Zukunft nur kurze Antworten.",
            "Das ist künftig der Standard.",
            "Merk dir: Perl-Variablen mit state deklarieren.",
            "Merke dir, dass der Server in Berlin steht.",
            "Verwende immer Prepared Statements.",
            "From now on answer in English.",
            "Remember that the database is PostgreSQL.",
            "Always use tabs for Perl.",
            "Never use sudo without asking.",
            "Going forward, keep the answers short.",
            "In the future write unit tests.",
            "Make sure to always check the exit code.");
        for (String message : messages) {
            assertWithMessage(message).that(RuleDetector.mayContainRule(message)).isTrue();
        }
    }

    @Test
    void singleCommonWordsNeverTrigger() {
        List<String> messages = List.of(
            "Das ist immer so gewesen.",
            "Ich prüfe immer die Logs.",
            "I always check the logs.",
            "Please always be brief.",
            "Ich habe das Passwort nie gespeichert.",
            "Remember the milk?",
            "Das Problem tritt immer wenn ich ssh nutze auf.",
            "Denk daran, die Logs zu prüfen.",
            "Bitte immer die Zeitzone UTC nutzen.",
            "Immer wenn du Perl schreibst, nutze strict.",
            "Nie wieder sudo ohne Rückfrage.",
            "Remember to check the disk usage.");
        for (String message : messages) {
            assertWithMessage(message).that(RuleDetector.mayContainRule(message)).isFalse();
        }
    }

    @Test
    void aProseSentenceWithAParenthesisOrAnEqualsSignIsStillACandidate() {
        assertThat(RuleDetector.mayContainRule("Ab jetzt immer Versionsnummern (z. B. [1.2.3]) angeben.")).isTrue();
        assertThat(RuleDetector.mayContainRule("Ab jetzt immer die Option --foo=bar (siehe Doku) nutzen.")).isTrue();
        assertThat(RuleDetector.mayContainRule("Ab jetzt immer <json> mit Schlüssel = Wert (ohne Text) liefern.")).isTrue();
        assertThat(RuleDetector.mayContainRule("Going forward: always use f(x) = x*2 style.")).isTrue();
    }

    @Test
    void questionsThatOpenWithAQuestionWordAreSkipped() {
        assertThat(RuleDetector.mayContainRule("Wie setze ich ab jetzt immer Tabs ein?")).isFalse();
        assertThat(RuleDetector.mayContainRule("Warum soll ich ab jetzt immer Tabs verwenden?")).isFalse();
        assertThat(RuleDetector.mayContainRule("How do I always use tabs in Perl?")).isFalse();
    }

    @Test
    void aPoliteRequestInTheQuestionFormIsStillARequest() {
        assertThat(RuleDetector.mayContainRule("Kannst du ab jetzt immer Tabs verwenden?")).isTrue();
        assertThat(RuleDetector.mayContainRule("Could you always use tabs from now on?")).isTrue();
    }

    @Test
    void aYesNoQuestionWithoutAQuestionWordIsLeftToTheModel() {
        assertThat(RuleDetector.mayContainRule("Ab jetzt immer Tabs verwenden?")).isTrue();
    }

    @Test
    void aCueInAStatementCountsEvenWhenAnotherSentenceIsAQuestion() {
        assertThat(RuleDetector.mayContainRule("Wie setze ich das um? Ab jetzt immer Tabs.")).isTrue();
    }

    @Test
    void codeOnlyMessagesAreSkipped() {
        assertThat(RuleDetector.mayContainRule("```perl\n# ab jetzt immer\nmy $x = 1;\n```")).isFalse();
        assertThat(RuleDetector.mayContainRule(
            "ab jetzt immer\n{ a(); b = c; }\n$ ls -la\n//comment\nfoo(bar[1]);")).isFalse();
    }

    @Test
    void aProseSentenceWithASmallCodeBlockIsKept() {
        assertThat(RuleDetector.mayContainRule(
            "Ab jetzt immer so formatieren:\n```perl\nprint 1;\n```\nDas ist wichtig.")).isTrue();
    }

    @Test
    void messagesLongerThanTwoThousandCharactersAreSkipped() {
        String longMessage = "Ab jetzt immer Tabs. " + "a".repeat(RuleDetector.MAX_MESSAGE_CHARACTERS);
        assertThat(RuleDetector.mayContainRule(longMessage)).isFalse();
        assertThat(RuleDetector.mayContainRule("Ab jetzt immer Tabs. " + "a".repeat(1000))).isTrue();
    }

    // ----- detection, gates and budgets

    @Test
    void aSecretInTheMessageMakesNoCall() {
        RuleDetector detector = detector(allowed());
        Optional<RuleProposal> proposal = detector.detect(
            "Ab jetzt immer das Passwort ist Sommer2026 verwenden.", CHAT, reply(true, TABS_RULE));

        assertThat(proposal).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aPasswordNamedByItsKeywordMakesNoCall() {
        RuleDetector detector = detector(allowed());
        for (String message : List.of(
            "Ab jetzt immer das Passwort hunter2 verwenden.",
            "Ab jetzt immer mit Passwort hunter2 verbinden.",
            "Ab jetzt immer die Zugangsdaten root / hunter2 nutzen.")) {
            assertThat(detector.detect(message, CHAT, reply(true, TABS_RULE))).isEmpty();
        }
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aRejectedMessageIsNotAskedAboutAgainEvenWhenTheRuleIsWordedDifferently() {
        RuleDetector detector = detector(allowed());
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isPresent();
        learning.reject(TABS_RULE);

        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("run-2", RuleSource.CHAT,
            null, null, null), reply(true, "Benutze immer Tabs."))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aSnoozedMessageIsNotAskedAboutUntilTheSnoozeRunsOut() {
        RuleDetector detector = detector(allowed());
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isPresent();
        learning.snooze(TABS_RULE);

        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("run-2", RuleSource.CHAT,
            null, null, null), reply(true, "Benutze immer Tabs."))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);

        clock.advance(LearningState.SNOOZE.plusDays(1));
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("run-3", RuleSource.CHAT,
            null, null, null), reply(true, "Benutze immer Tabs."))).isPresent();
    }

    @Test
    void learningOffMakesNoCall() {
        assertThat(detector(settings(false, true, true, RuleTargetPolicy.unrestricted()))
            .detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aPolicyThatDeniesTheMemoryMakesNoCall() {
        assertThat(detector(settings(true, false, true, RuleTargetPolicy.unrestricted()))
            .detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void theKillSwitchOrADisabledMemoryMakesNoCall() {
        assertThat(detector(settings(true, true, false, RuleTargetPolicy.unrestricted()))
            .detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void noAllowedTargetMakesNoCall() {
        assertThat(detector(settings(true, true, true, new RuleTargetPolicy(false, false, 1500))).detect(
            "Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aMessageWithoutACueMakesNoCall() {
        assertThat(detector(allowed()).detect("Erkläre mir Tabs.", CHAT, reply(true, TABS_RULE))).isEmpty();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aRuleFromTheModelBecomesAChatProposal() {
        Optional<RuleProposal> proposal = detector(allowed()).detect(
            "Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(proposal).isPresent();
        assertThat(proposal.get().text()).isEqualTo(TABS_RULE);
        assertThat(proposal.get().source()).isEqualTo(RuleSource.CHAT);
    }

    @Test
    void theTopicOfTheReplyBecomesTheTopicHint() {
        Optional<RuleProposal> proposal = detector(allowed()).detect(
            "Ab jetzt immer Tabs verwenden.", CHAT, (system, user) ->
                "{\"isRule\": true, \"rule\": \"Verwende Tabs.\", \"topic\": \"Perl\", \"scopeHint\": \"skill\"}");

        assertThat(proposal.get().topicHint()).isEqualTo("Perl");
    }

    @Test
    void aNonRuleReplyGivesNoCardButUsesTheBudget() {
        Optional<RuleProposal> proposal = detector(allowed()).detect(
            "Ab jetzt immer Tabs verwenden.", CHAT, reply(false, ""));

        assertThat(proposal).isEmpty();
        assertThat(learning.detectionCallsToday()).isEqualTo(1);
    }

    @Test
    void aRuleLongerThanThreeHundredCharactersIsDropped() {
        String tooLong = "Verwende immer " + "lang ".repeat(70).strip();
        assertThat(tooLong.length()).isGreaterThan(300);
        assertThat(detector(allowed()).detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, tooLong))).isEmpty();
    }

    @Test
    void aRuleWithAPasswordIsDropped() {
        assertThat(detector(allowed()).detect("Ab jetzt immer Tabs verwenden.", CHAT,
            reply(true, "Verwende das Passwort ist Sommer2026 für den Server."))).isEmpty();
    }

    @Test
    void aRejectedRuleIsDropped() {
        learning.reject(TABS_RULE);

        assertThat(detector(allowed()).detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();
    }

    @Test
    void aSnoozedRuleIsDroppedUntilTheSnoozeRunsOut() {
        learning.snooze(TABS_RULE);
        RuleDetector detector = detector(allowed());
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE))).isEmpty();

        clock.advance(LearningState.SNOOZE.plusDays(1));
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("run-later",
            RuleSource.CHAT, null, null, null), reply(true, TABS_RULE))).isPresent();
    }

    @Test
    void aMalformedReplyGivesNoCard() {
        assertThat(detector(allowed()).detect("Ab jetzt immer Tabs verwenden.", CHAT,
            (system, user) -> "{\"isRule\": true, \"rule\": ")).isEmpty();
    }

    @Test
    void aFailingCallIsSilent() {
        RuleDetector detector = detector(allowed());
        Optional<RuleProposal> proposal = detector.detect("Ab jetzt immer Tabs verwenden.", CHAT,
            (system, user) -> {
                throw new IllegalStateException("timeout");
            });

        assertThat(proposal).isEmpty();
    }

    @Test
    void theDetectionCallsAreLimitedPerDay() {
        RuleDetector detector = detector(allowed());
        for (int call = 0; call < LearningState.MAX_DETECTION_CALLS_PER_DAY; call++) {
            detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("run-" + call, RuleSource.CHAT,
                null, null, null), reply(false, ""));
        }
        assertThat(calls.get()).isEqualTo(LearningState.MAX_DETECTION_CALLS_PER_DAY);

        detector.detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE));
        assertThat(calls.get()).isEqualTo(LearningState.MAX_DETECTION_CALLS_PER_DAY);
    }

    @Test
    void oneCardPerRunEvenWhenTwoMessagesOfTheRunQualify() {
        RuleDetector detector = detector(allowed());
        Optional<RuleProposal> first = detector.detect("Ab jetzt immer Tabs verwenden.", CHAT, reply(true, TABS_RULE));
        Optional<RuleProposal> second = detector.detect("Ab jetzt immer Leerzeichen verwenden.", CHAT,
            reply(true, "Verwende Leerzeichen."));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void theCardsAreLimitedPerDay() {
        RuleDetector detector = detector(allowed());
        for (int run = 0; run < LearningState.MAX_PROPOSAL_CARDS_PER_DAY; run++) {
            assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("card-" + run,
                RuleSource.AGENT, null, null, null), reply(true, "Regel " + run + " für Perl."))).isPresent();
        }
        assertThat(detector.detect("Ab jetzt immer Tabs verwenden.", new RuleDetector.Origin("card-x",
            RuleSource.AGENT, null, null, null), reply(true, "Regel für Ruby."))).isEmpty();
    }

    @Test
    void theConnectionOfTheMessageIsKeptOnTheProposal() {
        RuleDetector.Origin origin = new RuleDetector.Origin("run-9", RuleSource.AGENT, "conn-1", "Build-Server",
            "build.example.org");
        Optional<RuleProposal> proposal = detector(allowed()).detect("Ab jetzt immer Tabs verwenden.", origin,
            reply(true, TABS_RULE));

        assertThat(proposal.get().source()).isEqualTo(RuleSource.AGENT);
        assertThat(proposal.get().connectionId()).isEqualTo("conn-1");
        assertThat(proposal.get().connectionHost()).isEqualTo("build.example.org");
    }

    @Test
    void theSettingsOfNothingTurnLearningOff() {
        RuleDetector.Settings off = RuleDetector.Settings.of(null, null, null, BuiltinAiSkillCatalog.load());

        assertThat(off.learningOn()).isFalse();
        assertThat(off.memoryEnabled()).isFalse();
    }

    // ----- helpers

    private RuleDetector detector(RuleDetector.Settings settings) {
        return new RuleDetector(learning, () -> settings);
    }

    private static RuleDetector.Settings allowed() {
        return settings(true, true, true, RuleTargetPolicy.unrestricted());
    }

    private static RuleDetector.Settings settings(boolean learningOn, boolean policyAllows, boolean memoryOn,
                                                  RuleTargetPolicy policy) {
        return new RuleDetector.Settings(learningOn, policyAllows, memoryOn, policy, new ArrayList<>(),
            BuiltinAiSkillCatalog.load());
    }

    /** An assistant that counts its calls and answers with the given rule decision. */
    private RuleDetector.Assistant reply(boolean isRule, String rule) {
        return (system, user) -> {
            calls.incrementAndGet();
            return "{\"isRule\": " + isRule + ", \"rule\": \"" + rule + "\", \"topic\": \"\", \"scopeHint\": \"global\"}";
        };
    }
}
