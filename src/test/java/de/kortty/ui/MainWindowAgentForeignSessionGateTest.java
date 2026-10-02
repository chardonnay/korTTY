package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression guard for the foreign-session gate of the terminal AI agent. After {@code su},
 * {@code sudo -i} or a nested {@code ssh}, the agent still runs its commands over the tab's
 * original connection, so every executing run must pass {@code confirmAgentTargetInForeignSession}
 * before it starts: the dialog, the {@code agent ...} shortcut, the activity panel's reload button
 * and the execution of an accepted plan.
 *
 * <p>The launch paths cannot run without a live {@code App}, JavaFX stage and terminal, so this
 * test reads the MainWindow source (like {@link MainWindowAcceleratorUniquenessTest}) and pins the
 * structure: the only places that start an agent or planning run sit after the gate, and every
 * other overload just delegates to the gated one.</p>
 */
class MainWindowAgentForeignSessionGateTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final String GATE = "confirmAgentTargetInForeignSession(";

    @Test
    void everyTerminalAgentRunStartsAfterTheForeignSessionGate() throws IOException {
        String source = source();
        List<String> overloads = methodBodies(source, "private void launchTerminalAgent(");
        assertThat(overloads).isNotEmpty();

        List<String> gated = new ArrayList<>();
        for (String body : overloads) {
            if (body.contains(GATE)) {
                gated.add(body);
            } else {
                // Any other overload may only forward to another launchTerminalAgent overload.
                assertThat(isSingleDelegation(body, "launchTerminalAgent(")).isTrue();
            }
        }
        assertThat(gated).hasSize(1);
        String launch = gated.get(0);
        int gate = launch.indexOf(GATE);
        // Only an Ask, which runs nothing on the target, skips the warning.
        int queryOnlyGuard = launch.indexOf("if (!request.queryOnly())");
        assertThat(queryOnlyGuard).isAtLeast(0);
        assertThat(queryOnlyGuard).isLessThan(gate);
        assertThat(launch.indexOf("runTerminalAgentInTerminalWindow(")).isGreaterThan(gate);
        assertThat(launch.indexOf("new AiAgentRunTab(")).isGreaterThan(gate);
        assertThat(launch.indexOf("agentRunnerFor(")).isGreaterThan(gate);
    }

    @Test
    void everyPlanningRunStartsAfterTheForeignSessionGate() throws IOException {
        String source = source();
        List<String> overloads = methodBodies(source, "private void launchTerminalAgentPlan(");
        assertThat(overloads).isNotEmpty();

        List<String> gated = new ArrayList<>();
        for (String body : overloads) {
            if (body.contains(GATE)) {
                gated.add(body);
            } else {
                assertThat(isSingleDelegation(body, "launchTerminalAgentPlan(")).isTrue();
            }
        }
        assertThat(gated).hasSize(1);
        String plan = gated.get(0);
        int gate = plan.indexOf(GATE);
        assertThat(plan.indexOf("new AiAgentPlanTab(")).isGreaterThan(gate);
        assertThat(plan.indexOf("agentRunnerFor(")).isGreaterThan(gate);
    }

    @Test
    void agentAndPlanningRunsAreStartedNowhereElse() throws IOException {
        String code = stripCommentsAndStrings(source());

        // Declaration + the single call in the gated launchTerminalAgent.
        assertThat(count(code, "runTerminalAgentInTerminalWindow(")).isEqualTo(2);
        assertThat(count(code, "new AiAgentRunTab(")).isEqualTo(1);
        assertThat(count(code, "new AiAgentPlanTab(")).isEqualTo(1);
        assertThat(count(code, "terminalAgentService.runAgent(")).isEqualTo(1);
        List<String> terminalWindowRuns = methodBodies(source(), "private void runTerminalAgentInTerminalWindow(");
        assertThat(terminalWindowRuns).hasSize(1);
        assertThat(terminalWindowRuns.get(0)).contains("terminalAgentService.runAgent(");
        // Retry and the accepted plan's execution go back through the gated launcher.
        assertThat(methodBodies(source(), "private void relaunchTerminalAgentWithCurrentProfile(").get(0))
            .contains("launchTerminalAgent(");
        assertThat(methodBodies(source(), "private void startAcceptedPlanExecution(").get(0))
            .contains("launchTerminalAgent(");
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    /** True when a method body is exactly one {@code target(...)} statement (plus whitespace). */
    private static boolean isSingleDelegation(String body, String target) {
        String code = stripCommentsAndStrings(body).strip();
        return code.startsWith(target) && code.endsWith(");") && count(code, ";") == 1;
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** The bodies (between the outer braces) of every method declared with this prefix. */
    private static List<String> methodBodies(String source, String declarationPrefix) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = Pattern.compile(Pattern.quote(declarationPrefix)).matcher(source);
        while (matcher.find()) {
            int open = findBodyStart(source, matcher.end());
            if (open < 0) {
                continue;
            }
            int close = matchingBrace(source, open);
            assertThat(close).isGreaterThan(open);
            bodies.add(source.substring(open + 1, close));
        }
        return bodies;
    }

    /** Index of the '{' that opens the body after the parameter list starting at {@code from}. */
    private static int findBodyStart(String source, int from) {
        int depth = 1; // already inside the parameter list's '('
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '{' && depth == 0) {
                return i;
            } else if (c == ';' && depth == 0) {
                return -1; // a call or abstract declaration, not a definition
            }
        }
        return -1;
    }

    /** Index of the '}' matching the '{' at {@code open}, skipping strings, chars and comments. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        int i = open;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i + 2);
                if (i < 0) {
                    return -1;
                }
                i += 2;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                i = source.indexOf("\"\"\"", i + 3);
                if (i < 0) {
                    return -1;
                }
                i += 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int skipLiteral(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** The source with comments removed and string and char literals emptied. */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? source.length() : end + 3;
                out.append("\"\"");
                continue;
            }
            if (c == '"' || c == '\'') {
                i = skipLiteral(source, i, c);
                out.append(c).append(c);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
