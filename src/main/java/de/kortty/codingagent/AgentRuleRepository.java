package de.kortty.codingagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Holds the effective rule set per {@link CodingAgentKind}: the bundled files enumerated by
 * {@code coding-agents/coding-agents.index} on the classpath, each replaced entirely (no merging) by a
 * valid user override {@code <configDir>/coding-agents/<kind-id>.json}. Every load failure is logged at
 * WARN and reported through {@link #problems()}; {@link #reload()} never throws and keeps the bundled
 * file in effect when an override is rejected. The published state is an immutable map behind a
 * volatile reference, so lookups from the detector thread never block on a reload.
 */
public final class AgentRuleRepository {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuleRepository.class);

    static final String INDEX_RESOURCE = "coding-agents/coding-agents.index";
    static final String RESOURCE_DIR = "coding-agents/";
    static final String USER_DIR_NAME = "coding-agents";
    static final long MAX_RULE_FILE_BYTES = 262_144L;
    static final Pattern VALID_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.json");

    private static final String JSON_SUFFIX = ".json";

    private final Path userDir;
    private volatile Map<CodingAgentKind, AgentRuleSet> ruleSets = Map.of();
    private volatile List<RuleLoadProblem> problems = List.of();

    /**
     * @param configDir korTTY's configuration directory (normally {@code ~/.kortty}); the override
     *                  directory is {@code configDir/coding-agents} and need not exist
     */
    public AgentRuleRepository(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        this.userDir = configDir.toAbsolutePath().normalize().resolve(USER_DIR_NAME);
        reload();
    }

    /** Re-reads the bundled files and the user override directory; never throws. */
    public synchronized void reload() {
        Map<CodingAgentKind, AgentRuleSet> loaded = new EnumMap<>(CodingAgentKind.class);
        List<RuleLoadProblem> found = new ArrayList<>();
        loadBundled(loaded, found);
        loadUserOverrides(loaded, found);
        this.ruleSets = Collections.unmodifiableMap(loaded);
        this.problems = List.copyOf(found);
        logger.debug("Coding-agent rule sets loaded: {} ({} problem(s))", loaded.keySet(), found.size());
    }

    /** The effective rule set for {@code kind}; empty for null, UNKNOWN or when no file loaded. */
    public Optional<AgentRuleSet> ruleSetFor(CodingAgentKind kind) {
        if (kind == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ruleSets.get(kind));
    }

    /** All effective rule sets in {@link CodingAgentKind} declaration order. */
    public List<AgentRuleSet> ruleSets() {
        return List.copyOf(ruleSets.values());
    }

    /** Files ignored or rejected by the last {@link #reload()}, in load order. */
    public List<RuleLoadProblem> problems() {
        return problems;
    }

    /** {@code <configDir>/coding-agents}; may not exist. */
    public Path userOverrideDirectory() {
        return userDir;
    }

    private void loadBundled(Map<CodingAgentKind, AgentRuleSet> target, List<RuleLoadProblem> problems) {
        for (String fileName : readIndex(problems)) {
            String resourceName = RESOURCE_DIR + fileName;
            String location = "classpath:" + resourceName;
            try (InputStream stream = AgentRuleRepository.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (stream == null) {
                    report(problems, location, "listed in " + INDEX_RESOURCE + " but not found");
                    continue;
                }
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                AgentRuleSet ruleSet = AgentRuleSet.parse(json, AgentRuleSet.Source.BUNDLED, null);
                if (!fileNameMatchesKind(fileName, ruleSet.kind())) {
                    report(problems, location, "kind " + ruleSet.kind() + " does not match file name " + fileName);
                    continue;
                }
                target.put(ruleSet.kind(), ruleSet);
            } catch (IOException e) {
                report(problems, location, e.getMessage());
            }
        }
    }

    private List<String> readIndex(List<RuleLoadProblem> problems) {
        List<String> fileNames = new ArrayList<>();
        String location = "classpath:" + INDEX_RESOURCE;
        try (InputStream stream = AgentRuleRepository.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE)) {
            if (stream == null) {
                report(problems, location, "index resource not found; no bundled coding-agent rules");
                return fileNames;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String fileName = line.strip();
                if (fileName.isEmpty() || fileName.startsWith("#")) {
                    continue;
                }
                if (!VALID_FILE_NAME.matcher(fileName).matches()) {
                    report(problems, location, "invalid file name in index: " + fileName);
                    continue;
                }
                fileNames.add(fileName);
            }
        } catch (IOException e) {
            report(problems, location, "failed to read index: " + e.getMessage());
        }
        return fileNames;
    }

    private void loadUserOverrides(Map<CodingAgentKind, AgentRuleSet> target, List<RuleLoadProblem> problems) {
        if (!Files.isDirectory(userDir)) {
            return;
        }
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(userDir)) {
            stream.forEach(candidates::add);
        } catch (IOException e) {
            report(problems, userDir.toString(), "cannot list override directory: " + e.getMessage());
            return;
        }
        candidates.sort(null);
        for (Path file : candidates) {
            String fileName = file.getFileName().toString();
            if (!VALID_FILE_NAME.matcher(fileName).matches()) {
                logger.debug("Ignoring non-rule file in {}: {}", userDir, fileName);
                continue;
            }
            loadUserOverride(file, fileName, target, problems);
        }
    }

    private void loadUserOverride(Path file, String fileName, Map<CodingAgentKind, AgentRuleSet> target,
                                  List<RuleLoadProblem> problems) {
        String location = file.toString();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                report(problems, location, "not a regular file (symbolic links are not followed)");
                return;
            }
            long size = Files.size(file);
            if (size > MAX_RULE_FILE_BYTES) {
                report(problems, location, "file is " + size + " bytes, larger than the limit of "
                    + MAX_RULE_FILE_BYTES + " bytes");
                return;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            AgentRuleSet ruleSet = AgentRuleSet.parse(json, AgentRuleSet.Source.USER_OVERRIDE, file);
            if (!fileNameMatchesKind(fileName, ruleSet.kind())) {
                report(problems, location, "kind " + ruleSet.kind() + " does not match file name " + fileName
                    + " (expected " + ruleSet.kind().id() + JSON_SUFFIX + ")");
                return;
            }
            target.put(ruleSet.kind(), ruleSet);
            logger.info("Using user override for {} coding-agent rules: {}", ruleSet.kind().displayName(), file);
        } catch (IOException | RuntimeException e) {
            report(problems, location, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static boolean fileNameMatchesKind(String fileName, CodingAgentKind kind) {
        return kind != null && kind.id() != null && (kind.id() + JSON_SUFFIX).equals(fileName);
    }

    private static void report(List<RuleLoadProblem> problems, String location, String message) {
        logger.warn("Ignoring coding-agent rule file {}: {}", location, message);
        problems.add(new RuleLoadProblem(location, message));
    }
}
