package de.kortty.codingagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One fixture screen under {@code src/test/resources/coding-agents/<kind-id>/<case>.txt}.
 * Leading lines starting with {@code #! } are directives: {@code expect state=<STATE> rule=<id|none>}
 * (required), {@code title=<text>} and {@code alt=<true|false>} (optional). Everything after the
 * directives is the verbatim screen, normalised by {@link ScreenSnapshot#ofLines}.
 */
record ScreenFixture(String name, CodingAgentState expectedState, String expectedRuleId, String title,
                     boolean alternateScreen, List<String> screenLines) {

    private static final String DIRECTIVE_PREFIX = "#! ";
    private static final String RULE_NONE = "none";

    /** True when the fixture pins the fallback path (directive {@code rule=none}). */
    boolean expectsFallback() {
        return expectedRuleId == null;
    }

    ScreenSnapshot snapshot() {
        int width = screenLines.stream().mapToInt(String::length).max().orElse(0);
        return ScreenSnapshot.ofLines(screenLines, width, title, alternateScreen);
    }

    static ScreenFixture load(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    static ScreenFixture parse(String name, String text) {
        List<String> lines = List.of(text.split("\n", -1));
        CodingAgentState state = null;
        String ruleId = null;
        boolean ruleSeen = false;
        String title = null;
        boolean alternate = false;
        int index = 0;
        while (index < lines.size() && lines.get(index).startsWith(DIRECTIVE_PREFIX)) {
            String directive = lines.get(index).substring(DIRECTIVE_PREFIX.length()).strip();
            if (directive.startsWith("expect ")) {
                for (String token : directive.substring("expect ".length()).trim().split("\\s+")) {
                    if (token.startsWith("state=")) {
                        state = CodingAgentState.valueOf(token.substring("state=".length()));
                    } else if (token.startsWith("rule=")) {
                        String value = token.substring("rule=".length());
                        ruleId = RULE_NONE.equals(value) ? null : value;
                        ruleSeen = true;
                    } else {
                        throw new IllegalArgumentException(name + ": unknown expect token '" + token + "'");
                    }
                }
            } else if (directive.startsWith("title=")) {
                title = directive.substring("title=".length());
            } else if (directive.startsWith("alt=")) {
                alternate = Boolean.parseBoolean(directive.substring("alt=".length()));
            } else {
                throw new IllegalArgumentException(name + ": unknown directive '" + directive + "'");
            }
            index++;
        }
        if (state == null || !ruleSeen) {
            throw new IllegalArgumentException(name + ": missing '#! expect state=<STATE> rule=<id|none>' directive");
        }
        List<String> screen = new ArrayList<>(lines.subList(index, lines.size()));
        return new ScreenFixture(name, state, ruleId, title, alternate, List.copyOf(screen));
    }
}
