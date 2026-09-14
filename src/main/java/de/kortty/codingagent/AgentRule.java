package de.kortty.codingagent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One screen-classification rule. All present matchers must pass (they are ANDed):
 * <ul>
 *   <li>{@code region} = {@code snapshot.bottomNonEmptyLines(bottomNonEmptyLines)} (0 = whole screen);</li>
 *   <li>{@code alternateScreen} (nullable) must equal {@code snapshot.alternateScreen()};</li>
 *   <li>{@code titleRegex} (nullable) must {@code find()} in a present OSC title;</li>
 *   <li>{@code regex} (nullable, compiled with MULTILINE|DOTALL) must {@code find()} in
 *       {@link ScreenSnapshot#joined(List)} of the region;</li>
 *   <li>every {@code contains} substring must occur in the joined region;</li>
 *   <li>no {@code notContains} substring may occur in the joined region;</li>
 *   <li>if {@code anyLineRegex} is non-empty, at least one region line must be found by one of the patterns.</li>
 * </ul>
 * The evidence is the first region line found by an {@code anyLineRegex} pattern, otherwise the text
 * matched by {@code regex}, otherwise the OSC title (when {@code titleRegex} is set), otherwise the rule id.
 */
public record AgentRule(String id, CodingAgentState state, int priority, int bottomNonEmptyLines,
                        List<Pattern> anyLineRegex, Pattern regex, List<String> contains, List<String> notContains,
                        Pattern titleRegex, Boolean alternateScreen) {

    public AgentRule {
        anyLineRegex = List.copyOf(anyLineRegex == null ? List.of() : anyLineRegex);
        contains = List.copyOf(contains == null ? List.of() : contains);
        notContains = List.copyOf(notContains == null ? List.of() : notContains);
    }

    /**
     * Evaluates every matcher against {@code snapshot}.
     *
     * @return the evidence when all matchers pass, otherwise {@link Optional#empty()}
     */
    public Optional<String> match(ScreenSnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }
        if (alternateScreen != null && alternateScreen != snapshot.alternateScreen()) {
            return Optional.empty();
        }
        if (titleRegex != null && (!snapshot.hasOscTitle() || !titleRegex.matcher(snapshot.oscTitle()).find())) {
            return Optional.empty();
        }
        List<String> region = snapshot.bottomNonEmptyLines(bottomNonEmptyLines);
        String joined = ScreenSnapshot.joined(region);
        for (String needle : contains) {
            if (!joined.contains(needle)) {
                return Optional.empty();
            }
        }
        for (String veto : notContains) {
            if (joined.contains(veto)) {
                return Optional.empty();
            }
        }
        String lineEvidence = null;
        if (!anyLineRegex.isEmpty()) {
            lineEvidence = firstMatchingLine(region);
            if (lineEvidence == null) {
                return Optional.empty();
            }
        }
        String regexEvidence = null;
        if (regex != null) {
            Matcher matcher = regex.matcher(joined);
            if (!matcher.find()) {
                return Optional.empty();
            }
            regexEvidence = matcher.group();
        }
        if (lineEvidence != null) {
            return Optional.of(lineEvidence);
        }
        if (regexEvidence != null) {
            return Optional.of(regexEvidence);
        }
        if (titleRegex != null) {
            return Optional.of(snapshot.oscTitle());
        }
        return Optional.of(id);
    }

    private String firstMatchingLine(List<String> region) {
        for (String line : region) {
            for (Pattern pattern : anyLineRegex) {
                if (pattern.matcher(line).find()) {
                    return line;
                }
            }
        }
        return null;
    }
}
