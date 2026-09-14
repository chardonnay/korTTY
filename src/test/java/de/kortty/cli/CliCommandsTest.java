package de.kortty.cli;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonObject;
import de.kortty.control.ControlKeyTable;
import org.testng.annotations.Test;

/**
 * The mapping from a parsed command line onto one wire call.
 *
 * <p>Every assertion here runs without a socket, which is the point of keeping
 * {@link CliCommands#toCall} pure: the whole documented command table is checked against the wire
 * protocol's parameter names in the unit suite.
 */
class CliCommandsTest {

    @Test
    void everyDocumentedCommandMapsToItsDocumentedMethod() throws Exception {
        assertMethod("ping", "ping");
        assertMethod("api.schema", "schema");
        assertMethod("events.subscribe", "events");
        assertMethod("window.list", "window", "list");
        assertMethod("tab.list", "tab", "list");
        assertMethod("tab.focus", "tab", "focus", "--tab", "t9f3a");
        assertMethod("pane.list", "pane", "list");
        assertMethod("pane.current", "pane", "current");
        assertMethod("pane.get", "pane", "get", "--focused");
        assertMethod("pane.focus", "pane", "focus", "--focused");
        assertMethod("pane.read", "pane", "read", "--focused");
        assertMethod("pane.send_text", "pane", "send-text", "--focused", "--text", "ls");
        assertMethod("pane.run", "pane", "run", "--focused", "--command", "make");
        assertMethod("pane.send_keys", "pane", "send-keys", "--focused", "enter");
        assertMethod("pane.wait_output", "pane", "wait-output", "--focused", "--contains", "$ ");
        assertMethod("pane.split", "pane", "split", "--focused");
        assertMethod("pane.close", "pane", "close", "--focused");
        assertMethod("agent.list", "agent", "list");
        assertMethod("agent.get", "agent", "get", "--focused");
        assertMethod("agent.explain", "agent", "explain", "--focused");
        assertMethod("agent.wait", "agent", "wait", "--focused", "--until", "done");
        assertMethod("agent.prompt", "agent", "prompt", "--focused", "--text", "hi");
        assertMethod("agent.send_keys", "agent", "send-keys", "--focused", "y");
        assertMethod("agent.rename", "agent", "rename", "--focused", "--alias", "backend");
        assertMethod("agent.start", "agent", "start", "--focused", "--kind", "claude-code");
        assertMethod("notification.show", "notify", "--title", "Build", "--body", "green");
    }

    @Test
    void aPaneIdSelectorTravelsAsThePaneParameter() throws Exception {
        assertThat(call("pane", "get", "--pane", "w1:t9f3a:p1a2b").params().get("pane").getAsString())
            .isEqualTo("w1:t9f3a:p1a2b");
    }

    @Test
    void theFocusedSelectorBecomesTheWireAliasAndCurrentBecomesAClientSentinel() throws Exception {
        assertThat(call("pane", "get", "--focused").params().get("pane").getAsString())
            .isEqualTo(CliCommands.FOCUSED_SELECTOR);
        assertWithMessage("--current is resolved by pane.resolve, never by the wire")
            .that(call("pane", "get", "--current").params().get("pane").getAsString())
            .isEqualTo(CliCommands.CURRENT_SELECTOR);
    }

    @Test
    void aTabSelectorAddressesThatTabsFocusedPaneForAPaneVerb() throws Exception {
        assertThat(call("pane", "get", "--tab", "t9f3a").params().get("pane").getAsString())
            .isEqualTo("t9f3a");
    }

    @Test
    void aTabFlagIsAPlainFilterForAListVerb() throws Exception {
        JsonObject params = call("pane", "list", "--tab", "t9f3a", "--local-shell-only").params();

        assertThat(params.has("pane")).isFalse();
        assertThat(params.get("tab").getAsString()).isEqualTo("t9f3a");
        assertThat(params.get("local_shell_only").getAsBoolean()).isTrue();
    }

    @Test
    void aSelectorLessPaneVerbIsASyntaxError() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"pane", "get"});

        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliCommands.toCall(invocation));

        assertThat(failure.message()).contains("exactly one selector");
    }

    @Test
    void recentVisibleAndDetectionAllMapToTheModeParameter() throws Exception {
        assertThat(call("pane", "read", "--focused", "--recent").params().get("mode").getAsString())
            .isEqualTo("recent");
        assertThat(call("pane", "read", "--focused", "--visible").params().get("mode").getAsString())
            .isEqualTo("visible");
        assertThat(call("pane", "read", "--focused", "--detection").params().get("mode").getAsString())
            .isEqualTo("detection");
        assertWithMessage("with no mode flag the server's own default must apply")
            .that(call("pane", "read", "--focused").params().has("mode"))
            .isFalse();
    }

    @Test
    void verticalAndHorizontalMapToTheOrientationParameter() throws Exception {
        assertThat(call("pane", "split", "--focused", "--vertical").params()
            .get("orientation").getAsString()).isEqualTo("vertical");
        assertThat(call("pane", "split", "--focused", "--horizontal").params()
            .get("orientation").getAsString()).isEqualTo("horizontal");
        assertThat(call("pane", "split", "--focused").params().has("orientation")).isFalse();
    }

    @Test
    void theNegativeSwitchesInvertTheServersDefaultTrue() throws Exception {
        assertThat(call("pane", "focus", "--focused", "--no-raise").params()
            .get("raise").getAsBoolean()).isFalse();
        assertThat(call("pane", "split", "--focused", "--no-focus").params()
            .get("focus").getAsBoolean()).isFalse();
        assertThat(call("pane", "focus", "--focused").params().has("raise")).isFalse();
    }

    @Test
    void readLineCountsTravelAsNumbersRatherThanStrings() throws Exception {
        JsonObject params = call("pane", "read", "--focused", "--recent", "--lines", "200").params();

        assertThat(params.get("lines").getAsJsonPrimitive().isNumber()).isTrue();
        assertThat(params.get("lines").getAsInt()).isEqualTo(200);
    }

    @Test
    void sendTextCarriesEveryDocumentedSwitch() throws Exception {
        JsonObject params = call("pane", "send-text", "--pane", "p1a2b", "--text", "ls -l",
            "--submit", "--bracketed", "always", "--allow-shortcut-conflict").params();

        assertThat(params.get("text").getAsString()).isEqualTo("ls -l");
        assertThat(params.get("submit").getAsBoolean()).isTrue();
        assertThat(params.get("bracketed").getAsString()).isEqualTo("always");
        assertThat(params.get("allow_shortcut_conflict").getAsBoolean()).isTrue();
    }

    @Test
    void aClosedChoiceOutsideItsPublishedSetIsALocalSyntaxError() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"pane", "send-text", "--focused",
            "--text", "ls", "--bracketed", "sometimes"});

        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliCommands.toCall(invocation));

        assertThat(failure.message()).contains("auto");
        assertThat(failure.message()).contains("sometimes");
    }

    @Test
    void waitOutputCarriesTheMatcherTheModeAndBothBudgets() throws Exception {
        JsonObject params = call("pane", "wait-output", "--focused", "--regex", "\\$ $",
            "--recent", "--lines", "60", "--timeout-ms", "600000", "--poll-ms", "200").params();

        assertThat(params.get("regex").getAsString()).isEqualTo("\\$ $");
        assertThat(params.get("mode").getAsString()).isEqualTo("recent");
        assertThat(params.get("lines").getAsInt()).isEqualTo(60);
        assertThat(params.get("timeout_ms").getAsLong()).isEqualTo(600_000L);
        assertThat(params.get("poll_ms").getAsLong()).isEqualTo(200L);
    }

    @Test
    void keyNamesAreNormalisedAgainstTheBuiltInTableBeforeTheyAreSent() throws Exception {
        JsonObject params = call("pane", "send-keys", "--focused", "CTRL-C", "Escape", "y").params();

        assertThat(params.getAsJsonArray("keys").get(0).getAsString()).isEqualTo("ctrl+c");
        assertThat(params.getAsJsonArray("keys").get(1).getAsString()).isEqualTo("esc");
        assertThat(params.getAsJsonArray("keys").get(2).getAsString()).isEqualTo("y");
    }

    @Test
    void anUnknownKeyNameIsALocalSyntaxErrorSoNoRequestIsSent() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"agent", "send-keys", "--focused",
            "entre"});

        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliCommands.toCall(invocation));

        assertThat(failure.message()).contains("entre");
        assertWithMessage("the diagnostic must publish the vocabulary so a script can be fixed")
            .that(failure.message())
            .contains(ControlKeyTable.knownKeys().get(0));
    }

    @Test
    void aSendKeysVerbWithoutAnyKeyIsASyntaxError() throws Exception {
        CliInvocation invocation = CliArguments.parse(new String[] {"pane", "send-keys", "--focused"});

        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliCommands.toCall(invocation));

        assertThat(failure.message()).contains("key name");
    }

    @Test
    void agentStartTakesEitherASelectorOrASplitSourceButNotBoth() throws Exception {
        JsonObject split = call("agent", "start", "--split-from", "p1a2b", "--vertical", "--kind",
            "claude-code").params();

        assertThat(split.get("split_from").getAsString()).isEqualTo("p1a2b");
        assertThat(split.get("orientation").getAsString()).isEqualTo("vertical");
        assertThat(split.has("pane")).isFalse();

        CliInvocation both = CliArguments.parse(new String[] {"agent", "start", "--split-from",
            "p1a2b", "--tab", "t9f3a", "--kind", "claude-code"});
        CliSyntaxException failure = expectThrows(CliSyntaxException.class,
            () -> CliCommands.toCall(both));
        assertThat(failure.message()).contains("--split-from");
    }

    @Test
    void agentStartSplitsItsLaunchCommandIntoAnArgumentVector() throws Exception {
        JsonObject params = call("agent", "start", "--focused", "--kind", "codex", "--command",
            "codex --sandbox", "--wait", "--until", "idle", "--timeout-ms", "60000").params();

        assertThat(params.getAsJsonArray("command").size()).isEqualTo(2);
        assertThat(params.getAsJsonArray("command").get(1).getAsString()).isEqualTo("--sandbox");
        assertThat(params.get("wait").getAsBoolean()).isTrue();
        assertThat(params.get("until").getAsString()).isEqualTo("idle");
        assertThat(params.get("timeout_ms").getAsLong()).isEqualTo(60_000L);
    }

    @Test
    void agentPromptUsesTheWaitUntilParameterName() throws Exception {
        JsonObject params = call("agent", "prompt", "--focused", "--text", "go", "--wait-until",
            "done", "--timeout-ms", "300000").params();

        assertThat(params.get("wait_until").getAsString()).isEqualTo("done");
        assertThat(params.get("timeout_ms").getAsLong()).isEqualTo(300_000L);
    }

    @Test
    void agentRenameSendsABlankAliasToClearIt() throws Exception {
        assertThat(call("agent", "rename", "--focused", "--alias", "").params()
            .get("alias").getAsString()).isEmpty();
    }

    @Test
    void eventsSplitsItsCommaSeparatedFiltersAndKeepsCountOffTheWire() throws Exception {
        CliCommands.Call call = call("events", "--kinds", "agent.added,agent.state_changed",
            "--panes", "p1,p2", "--include-evidence", "--count", "3");

        assertThat(call.stream()).isTrue();
        assertThat(call.params().getAsJsonArray("kinds").size()).isEqualTo(2);
        assertThat(call.params().getAsJsonArray("panes").get(1).getAsString()).isEqualTo("p2");
        assertThat(call.params().get("include_evidence").getAsBoolean()).isTrue();
        assertWithMessage("--count bounds the client's printing, it is not a subscribe parameter")
            .that(call.params().has("count"))
            .isFalse();
    }

    @Test
    void onlyEventsAsksTheCallerToKeepReading() throws Exception {
        assertThat(call("ping").stream()).isFalse();
        assertThat(call("pane", "list").stream()).isFalse();
    }

    @Test
    void rawTakesItsMethodAndParamsFromOperands() throws Exception {
        CliCommands.Call call = call("raw", "pane.read", "{\"pane\":\"p1a2b\",\"lines\":5}");

        assertThat(call.method()).isEqualTo("pane.read");
        assertThat(call.params().get("pane").getAsString()).isEqualTo("p1a2b");
        assertThat(call.params().get("lines").getAsInt()).isEqualTo(5);
    }

    @Test
    void rawWithoutAMethodAndRawWithUnparsableParamsAreSyntaxErrors() throws Exception {
        CliInvocation bare = CliArguments.parse(new String[] {"raw"});
        assertThat(expectThrows(CliSyntaxException.class, () -> CliCommands.toCall(bare)).message())
            .contains("method name");

        CliInvocation broken = CliArguments.parse(new String[] {"raw", "ping", "{oops"});
        assertThat(expectThrows(CliSyntaxException.class, () -> CliCommands.toCall(broken)).message())
            .contains("JSON object");
    }

    @Test
    void aReservedTabVerbIsRefusedWithTheDocumentedHint() throws Exception {
        for (String verb : new String[] {"create", "close", "rename"}) {
            CliInvocation invocation = CliArguments.parse(new String[] {"tab", verb});
            CliSyntaxException failure = expectThrows(CliSyntaxException.class,
                () -> CliCommands.toCall(invocation));
            assertWithMessage("tab %s must point the caller at --pane", verb)
                .that(failure.message())
                .contains("--pane");
            assertThat(failure.message()).contains("not implemented in this version");
        }
    }

    @Test
    void schemaMayAskForOneMethodOrForTheWholeDocument() throws Exception {
        assertThat(call("schema").params().has("method")).isFalse();
        assertThat(call("schema", "--method", "pane.read").params().get("method").getAsString())
            .isEqualTo("pane.read");
    }

    @Test
    void everyTableEntryHasASynopsisASummaryAndEitherAMethodOrAReason() {
        for (CliCommands.Command command : CliCommands.commands()) {
            assertWithMessage("synopsis of %s", command.name()).that(command.synopsis()).isNotEmpty();
            assertWithMessage("summary of %s", command.name()).that(command.summary()).isNotEmpty();
            boolean callable = command.method() != null || "raw".equals(command.group());
            assertWithMessage("%s must either be callable or explain why it is not", command.name())
                .that(callable || command.reserved() != null)
                .isTrue();
        }
    }

    private static void assertMethod(String method, String... args) throws Exception {
        assertWithMessage("method of '%s'", String.join(" ", args))
            .that(call(args).method())
            .isEqualTo(method);
    }

    private static CliCommands.Call call(String... args) throws Exception {
        return CliCommands.toCall(CliArguments.parse(args));
    }
}
