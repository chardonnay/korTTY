package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Pins every bundled rule to at least one fixture screen under {@code src/test/resources/coding-agents/<kind-id>/}.
 * Test resources are plain files on disk, so the directory is walked directly (no index file needed).
 */
class BundledRuleFixturesTest {

    private static final String FIXTURE_ROOT = "coding-agents";
    /** A fixture that must exist; its location anchors the walk so the main-resources directory of the same name is never picked. */
    private static final String ANCHOR_FIXTURE = FIXTURE_ROOT + "/claude-code/idle-prompt.txt";

    private Path configDir;
    private AgentRuleRepository repository;
    private CodingAgentDetector detector;

    @BeforeClass
    void loadBundledRules() throws IOException {
        configDir = Files.createTempDirectory("kortty-coding-agents-fixtures");
        repository = new AgentRuleRepository(configDir);
        assertThat(repository.problems()).isEmpty();
        detector = new CodingAgentDetector(repository);
    }

    @AfterClass
    void deleteConfigDir() throws IOException {
        if (configDir != null && Files.exists(configDir)) {
            try (Stream<Path> walk = Files.walk(configDir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Path fixtureRoot() throws Exception {
        URL anchor = BundledRuleFixturesTest.class.getClassLoader().getResource(ANCHOR_FIXTURE);
        assertWithMessage("anchor fixture %s on the test classpath", ANCHOR_FIXTURE).that(anchor).isNotNull();
        return Path.of(anchor.toURI()).getParent().getParent();
    }

    /** Every fixture file as {kindId, fileName, fixture}. */
    private static List<Object[]> allFixtures() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        Path root = fixtureRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".txt")) {
                    continue;
                }
                String kindId = root.relativize(file).getName(0).toString();
                rows.add(new Object[] {kindId, file.getFileName().toString(), ScreenFixture.load(file)});
            }
        }
        assertThat(rows).isNotEmpty();
        return rows;
    }

    @DataProvider(name = "fixtures")
    Object[][] fixtures() throws Exception {
        return allFixtures().toArray(Object[][]::new);
    }

    @Test(dataProvider = "fixtures")
    void fixtureIsClassifiedAsExpected(String kindId, String fileName, ScreenFixture fixture) {
        CodingAgentKind kind = CodingAgentKind.forId(kindId).orElseThrow();
        DetectionResult result = detector.classify(kind, fixture.snapshot());
        String where = kindId + "/" + fileName + " -> " + result.explain();

        assertWithMessage(where).that(result.kind()).isEqualTo(kind);
        assertWithMessage(where).that(result.state()).isEqualTo(fixture.expectedState());
        if (fixture.expectsFallback()) {
            assertWithMessage(where).that(result.matchedRuleId()).isNull();
            assertWithMessage(where).that(result.evidence()).isNull();
            assertWithMessage(where).that(result.fallbackApplied()).isTrue();
            assertWithMessage(where).that(fixture.expectedState())
                .isEqualTo(repository.ruleSetFor(kind).orElseThrow().fallbackState());
        } else {
            assertWithMessage(where).that(result.matchedRuleId()).isEqualTo(fixture.expectedRuleId());
            assertWithMessage(where).that(result.evidence()).isNotEmpty();
            assertWithMessage(where).that(result.ruleMatched()).isTrue();
        }
    }

    @Test
    void everyBundledRuleIsPinnedByAFixture() throws Exception {
        Set<String> bundled = new TreeSet<>();
        for (AgentRuleSet set : repository.ruleSets()) {
            for (AgentRule rule : set.rules()) {
                bundled.add(set.kind().id() + "/" + rule.id());
            }
        }
        Set<String> pinned = new TreeSet<>();
        for (Object[] row : allFixtures()) {
            ScreenFixture fixture = (ScreenFixture) row[2];
            if (!fixture.expectsFallback()) {
                pinned.add(row[0] + "/" + fixture.expectedRuleId());
            }
        }

        // Drift guard: a rule without a fixture is untested, a fixture naming a removed rule is stale.
        assertThat(pinned).isEqualTo(bundled);
        assertThat(bundled).isNotEmpty();
    }

    @Test
    void everyFixtureDirectoryIsAKnownKind() throws Exception {
        Path root = fixtureRoot();
        Set<String> directories = new TreeSet<>();
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.filter(Files::isDirectory).toList()) {
                directories.add(dir.getFileName().toString());
            }
        }
        for (String directory : directories) {
            assertWithMessage("fixture directory %s", directory)
                .that(CodingAgentKind.forId(directory)).isPresent();
        }
        Set<String> expected = new TreeSet<>();
        for (CodingAgentKind kind : CodingAgentKind.values()) {
            if (kind.id() != null) {
                expected.add(kind.id());
            }
        }
        // Every kind with a bundled rule file has fixtures, and no fixture directory is orphaned.
        assertThat(directories).isEqualTo(expected);
    }

    @Test
    void everyFixtureDirectiveIsWellFormed() throws Exception {
        for (Object[] row : allFixtures()) {
            ScreenFixture fixture = (ScreenFixture) row[2];
            assertWithMessage("%s/%s", row[0], row[1]).that(fixture.screenLines().size()).isAtLeast(10);
            assertWithMessage("%s/%s must not start with a directive line", row[0], row[1])
                .that(fixture.screenLines().get(0)).doesNotContain("#! ");
        }
    }
}
