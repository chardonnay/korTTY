package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * The parser's contract: it is a pure function of {@code argv}, it knows every documented flag, and
 * it refuses everything it cannot map without ever opening a socket.
 */
class CliArgumentsTest {

    @Test
    void everyGlobalFlagLandsOnItsOwnComponentOfTheInvocation() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {
            "--config-dir", "/tmp/kt-home", "--timeout", "1234", "--raw", "--pretty", "--quiet",
            "pane", "list"});

        assertThat(invocation.group()).isEqualTo("pane");
        assertThat(invocation.verb()).isEqualTo("list");
        assertThat(invocation.configDir()).isEqualTo(Path.of("/tmp/kt-home"));
        assertThat(invocation.timeoutMillis()).isEqualTo(1234L);
        assertThat(invocation.raw()).isTrue();
        assertThat(invocation.pretty()).isTrue();
        assertThat(invocation.quiet()).isTrue();
    }

    @Test
    void theShortQuietFlagIsTheSameFlagAsTheLongOne() throws Exception {
        assertThat(CliArguments.parse(new String[] {"-q", "ping"}).quiet()).isTrue();
        assertThat(CliArguments.looksQuiet(new String[] {"-q", "ping"})).isTrue();
        assertThat(CliArguments.looksQuiet(new String[] {"ping"})).isFalse();
    }

    @Test
    void aFlagMayCarryItsValueAfterAnEqualsSign() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"pane", "read", "--pane=p1a2b",
            "--lines=40"});

        assertThat(invocation.flag("pane", null)).isEqualTo("p1a2b");
        assertThat(invocation.flag("lines", null)).isEqualTo("40");
    }

    @Test
    void thereIsNoTokenFlagSoASecretCanNeverReachArgv() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"--token", "kQ7f", "ping"}));

        assertThat(failure.message()).contains("--token");
    }

    @Test
    void anUnknownFlagIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "list", "--nope"}));

        assertThat(failure.message()).contains("--nope");
    }

    @Test
    void aKnownFlagTheVerbDoesNotAcceptIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "list", "--text", "hello"}));

        assertWithMessage("the diagnostic must name the verb and the flag")
            .that(failure.message())
            .contains("pane list");
        assertThat(failure.message()).contains("--text");
    }

    @Test
    void anUnknownGroupIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"panes", "list"}));

        assertThat(failure.message()).contains("panes");
    }

    @Test
    void anUnknownVerbIsASyntaxErrorThatListsTheKnownOnes() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "readd", "--focused"}));

        assertThat(failure.message()).contains("readd");
        assertThat(failure.message()).contains("read");
    }

    @Test
    void aGroupWithoutItsVerbIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"agent"}));

        assertThat(failure.message()).contains("verb");
    }

    @Test
    void noCommandAtAllIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {}));

        assertThat(failure.message()).contains("no command given");
    }

    @Test
    void aMissingRequiredFlagIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"agent", "rename", "--focused"}));

        assertThat(failure.message()).contains("--alias");
    }

    @Test
    void anEitherOrRequirementIsSatisfiedByEitherSide() throws Exception {
        assertThat(CliArguments.parse(new String[] {"pane", "send-text", "--focused", "--text", "ls"})
            .flag("text", null)).isEqualTo("ls");
        assertThat(CliArguments.parse(new String[] {"pane", "send-text", "--focused", "--stdin"})
            .has("stdin")).isTrue();

        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "send-text", "--focused"}));
        assertThat(failure.message()).contains("--stdin");
        assertThat(failure.message()).contains("--text");
    }

    @Test
    void aRepeatedFlagIsASyntaxErrorRatherThanASilentLastOneWins() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "get", "--pane", "p1", "--pane", "p2"}));

        assertThat(failure.message()).contains("twice");
    }

    @Test
    void twoDifferentSelectorsCannotBeCombined() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "get", "--focused", "--current"}));

        assertThat(failure.message()).contains("--current");
        assertThat(failure.message()).contains("--focused");
    }

    @Test
    void twoReadModesCannotBeCombined() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "read", "--focused", "--recent",
                "--visible"}));

        assertThat(failure.message()).contains("cannot be combined");
    }

    @Test
    void anUnparsableNumberIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"--timeout", "soon", "ping"}));

        assertThat(failure.message()).contains("--timeout");
        assertThat(failure.message()).contains("soon");
    }

    @Test
    void aNonPositiveLineCountIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "read", "--focused", "--lines", "0"}));

        assertThat(failure.message()).contains("--lines");
    }

    @Test
    void aValueFlagAtTheEndOfTheLineIsASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "get", "--pane"}));

        assertThat(failure.message()).contains("needs a value");
    }

    @Test
    void aSwitchRejectsAValue() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "get", "--focused=p1"}));

        assertThat(failure.message()).contains("switch");
    }

    @Test
    void versionParsesWithoutAnyCommandOrSelector() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"--version"});

        assertThat(invocation.group()).isNull();
        assertThat(invocation.verb()).isNull();
        assertThat(invocation.has(CliArguments.FLAG_VERSION)).isTrue();
    }

    @Test
    void helpParsesWithoutASelectorAtEveryLevel() throws Exception {
        assertThat(CliArguments.parse(new String[] {"--help"}).group()).isNull();
        assertThat(CliArguments.parse(new String[] {"pane", "--help"}).verb()).isNull();
        assertThat(CliArguments.parse(new String[] {"-h"}).has(CliArguments.FLAG_HELP)).isTrue();

        CliInvocation verbHelp = CliArguments.parse(new String[] {"pane", "read", "--help"});
        assertThat(verbHelp.group()).isEqualTo("pane");
        assertThat(verbHelp.verb()).isEqualTo("read");
    }

    @Test
    void helpForAnUnknownGroupIsStillASyntaxError() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"paness", "--help"}));

        assertThat(failure.message()).contains("paness");
    }

    @Test
    void keyNamesArriveAsOperandsRatherThanFlags() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"pane", "send-keys", "--focused",
            "y", "enter"});

        assertThat(invocation.operands()).containsExactly("y", "enter").inOrder();
    }

    @Test
    void aVerbThatTakesNoOperandsRefusesThem() {
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliArguments.parse(new String[] {"pane", "list", "stray"}));

        assertThat(failure.message()).contains("positional");
    }

    @Test
    void theDoubleDashMarkerEndsFlagParsingSoAKeyMayLookLikeAFlag() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"raw", "--", "pane.list"});

        assertThat(invocation.operands()).containsExactly("pane.list");
    }

    @Test
    void theDefaultClientDeadlineIsFiveSecondsForANonWaitingVerb() throws Exception {
        assertThat(CliArguments.parse(new String[] {"ping"}).timeoutMillis())
            .isEqualTo(CliArguments.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void aWaitingVerbAddsItsOwnServerWaitToTheClientDeadline() throws Exception {
        long explicit = CliArguments.parse(new String[] {"agent", "wait", "--focused", "--until",
            "done", "--timeout-ms", "120000"}).timeoutMillis();

        assertWithMessage("the client must outlive the wait the server was asked for")
            .that(explicit)
            .isEqualTo(120_000L + CliArguments.WAIT_SLACK_MILLIS);
    }

    @Test
    void aSplitOutlivesTheServersTenSecondJavaFxBudget() throws Exception {
        assertWithMessage("the five-second default would abandon a split still being performed")
            .that(CliArguments.parse(new String[] {"pane", "split", "--focused"}).timeoutMillis())
            .isGreaterThan(10_000L);
    }

    @Test
    void anExplicitTimeoutOverridesTheDerivedOne() throws Exception {
        assertThat(CliArguments.parse(new String[] {"agent", "wait", "--focused", "--until", "done",
            "--timeout-ms", "120000", "--timeout", "700"}).timeoutMillis()).isEqualTo(700L);
    }

    @Test
    void eventsHasNoClientDeadlineUnlessOneIsAskedFor() throws Exception {
        assertWithMessage("a stream must not be cut off by the five-second default")
            .that(CliArguments.parse(new String[] {"events"}).timeoutMillis())
            .isEqualTo(0L);
        assertThat(CliArguments.parse(new String[] {"events", "--timeout", "2000"}).timeoutMillis())
            .isEqualTo(2000L);
    }

    @Test
    void theDefaultConfigDirIsTheKorttyHomeAndIsNeverNull() throws Exception {
        assertThat(CliArguments.parse(new String[] {"ping"}).configDir())
            .isEqualTo(ControlDiscovery.defaultConfigDir());
    }

    @Test
    void withFlagAndWithOperandProduceCopiesRatherThanMutatingTheInvocation() throws Exception {
        CliInvocation original = CliArguments.parse(new String[] {"pane", "send-text", "--focused",
            "--stdin"});

        CliInvocation filled = CliArguments.withFlag(original, CliArguments.FLAG_TEXT, "piped");
        CliInvocation extended = CliArguments.withOperand(original, "extra");

        assertThat(original.has(CliArguments.FLAG_TEXT)).isFalse();
        assertThat(original.operands()).isEmpty();
        assertThat(filled.flag(CliArguments.FLAG_TEXT, null)).isEqualTo("piped");
        assertThat(extended.operands()).containsExactly("extra");
    }
}
