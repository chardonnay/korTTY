package de.kortty.core;

import de.kortty.core.SessionRestoreDecision.Action;
import de.kortty.core.SessionRestoreDecision.Decision;
import de.kortty.core.SessionRestoreDecision.Facts;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Project;
import de.kortty.model.SessionRestoreMode;
import de.kortty.model.SessionSnapshot;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * What korTTY does at startup with the previous session: every Session Restore mode against no
 * snapshot, an empty one, one left by a clean quit and one left by a crash right after a restore
 * (the crash-loop guard), first as the pure decision, then with the facts a real
 * {@link SessionSnapshotStore} finds in a temporary directory.
 */
class SessionRestoreDecisionTest {

    private Path configDir;
    private final List<SessionSnapshotStore> stores = new ArrayList<>();

    @BeforeMethod
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("kortty-session-restore-decision-");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws Exception {
        stores.forEach(SessionSnapshotStore::close);
        stores.clear();
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- the decision -------------------------------------------------------------------------

    @Test
    void offWithAnySnapshotDoesNothing() {
        for (Facts facts : List.of(none(), empty(), clean(), crashed())) {
            assertThat(SessionRestoreDecision.decide(SessionRestoreMode.OFF, facts).action()).isEqualTo(Action.NONE);
        }
    }

    @Test
    void askOffersEveryRestorableSessionAndNeverRestoresByItself() {
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.ASK, none()).action()).isEqualTo(Action.NONE);
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.ASK, empty()).action()).isEqualTo(Action.NONE);
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.ASK, clean())).isEqualTo(new Decision(Action.OFFER, false));
        assertWithMessage("ask asks anyway; the bar need not explain why")
            .that(SessionRestoreDecision.decide(SessionRestoreMode.ASK, crashed())).isEqualTo(new Decision(Action.OFFER, false));
    }

    @Test
    void autoRestoresUnlessKorttyEndedRightAfterTheLastRestore() {
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, none()).action()).isEqualTo(Action.NONE);
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, empty()).action()).isEqualTo(Action.NONE);
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, clean())).isEqualTo(new Decision(Action.AUTO, false));
        assertWithMessage("the crash-loop guard downgrades auto to an offer that says why")
            .that(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, crashed())).isEqualTo(new Decision(Action.OFFER, true));
    }

    @Test
    void aSessionThisStartDidNotMakeThePreviousOneIsNeverOffered() {
        // A second korTTY (no lock, no rotation) must not reopen the first one's tabs.
        Facts notRotated = new Facts(false, 2, 5, false);
        for (SessionRestoreMode mode : SessionRestoreMode.values()) {
            assertThat(SessionRestoreDecision.decide(mode, notRotated).action()).isEqualTo(Action.NONE);
        }
    }

    @Test
    void aMissingModeMeansAsk() {
        assertThat(SessionRestoreDecision.decide(null, clean())).isEqualTo(new Decision(Action.OFFER, false));
    }

    @Test
    void onlyARestoreThatNeverBecameStableAndNeverQuitNormallyCountsAsACrash() {
        assertThat(SessionRestoreDecision.endedRightAfterRestore(null)).isFalse();
        assertThat(SessionRestoreDecision.endedRightAfterRestore(marked(true, false, false))).isTrue();
        assertWithMessage("korTTY ran for a minute after the restore")
            .that(SessionRestoreDecision.endedRightAfterRestore(marked(true, true, false))).isFalse();
        assertWithMessage("korTTY quit normally right after the restore")
            .that(SessionRestoreDecision.endedRightAfterRestore(marked(true, false, true))).isFalse();
        assertWithMessage("a crash in a run that restored nothing is not the restore's fault")
            .that(SessionRestoreDecision.endedRightAfterRestore(marked(false, false, false))).isFalse();
    }

    // ---- with a real store --------------------------------------------------------------------

    @Test
    void theLastRunsSessionWithTabsIsOfferableWithItsCounts() throws Exception {
        leaveLastSession(snapshot(2, 3, false, false, true));

        Facts facts = Facts.of(startUp());

        assertThat(facts).isEqualTo(new Facts(true, 2, 6, false));
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, facts).action()).isEqualTo(Action.AUTO);
    }

    @Test
    void anEmptyLastSessionOffersNothingAlthoughAnOlderPreviousSessionExists() throws Exception {
        leaveLastSession(snapshot(1, 2, false, false, true));
        startUp();
        stores.forEach(SessionSnapshotStore::close);
        stores.clear();
        // The next run opened nothing (or the user dismissed the offer) and quit.
        leaveLastSession(snapshot(0, 0, false, false, true));

        SessionSnapshotStore.StartupState startup = startUp();

        assertThat(startup.rotated()).isFalse();
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.ASK, Facts.of(startup)).action()).isEqualTo(Action.NONE);
        assertWithMessage("File › Restore Previous Session still has the older session")
            .that(stores.get(0).isPreviousAvailable()).isTrue();
    }

    @Test
    void aSecondKorttyFindsNothingToOffer() throws Exception {
        leaveLastSession(snapshot(1, 2, false, false, true));
        SessionSnapshotStore first = SessionSnapshotStore.open(configDir);
        stores.add(first);
        // The first korTTY is still starting; the second one opens the store meanwhile.
        SessionSnapshotStore second = SessionSnapshotStore.open(configDir);
        stores.add(second);

        Facts facts = Facts.of(second.startUp());

        assertThat(facts.offerable()).isFalse();
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, facts).action()).isEqualTo(Action.NONE);
    }

    @Test
    void aCrashRightAfterAnAutomaticRestoreIsSeenThroughTheFile() throws Exception {
        leaveLastSession(snapshot(1, 4, true, false, false));

        Facts facts = Facts.of(startUp());

        assertThat(facts.afterCrash()).isTrue();
        assertThat(SessionRestoreDecision.decide(SessionRestoreMode.AUTO, facts)).isEqualTo(new Decision(Action.OFFER, true));
        assertThat(Facts.of(null)).isEqualTo(new Facts(false, 0, 0, false));
    }

    // ---- the setting --------------------------------------------------------------------------

    @Test
    void theSettingDefaultsToAskAndADamagedValueNeverMeansAuto() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.ASK);
        assertThat(SessionRestoreMode.fromId(null)).isEqualTo(SessionRestoreMode.ASK);
        assertThat(SessionRestoreMode.fromId("")).isEqualTo(SessionRestoreMode.ASK);
        assertThat(SessionRestoreMode.fromId("always")).isEqualTo(SessionRestoreMode.ASK);
        assertThat(SessionRestoreMode.fromId(" AUTO ")).isEqualTo(SessionRestoreMode.AUTO);
        assertThat(SessionRestoreMode.fromId("off")).isEqualTo(SessionRestoreMode.OFF);

        settings.setSessionRestoreMode(SessionRestoreMode.AUTO);
        String xml = marshal(settings);
        assertThat(xml).contains("<sessionRestoreMode>auto</sessionRestoreMode>");
        assertThat(unmarshal(xml).getSessionRestoreMode()).isEqualTo(SessionRestoreMode.AUTO);

        settings.setSessionRestoreMode(null);
        assertThat(settings.getSessionRestoreMode()).isEqualTo(SessionRestoreMode.ASK);
        assertWithMessage("a settings file from before the setting asks")
            .that(unmarshal(marshal(new GlobalSettings()).replace("<sessionRestoreMode>ask</sessionRestoreMode>", ""))
                .getSessionRestoreMode()).isEqualTo(SessionRestoreMode.ASK);
        assertThat(unmarshal(xml.replace(">auto<", ">sometimes<")).getSessionRestoreMode()).isEqualTo(SessionRestoreMode.ASK);
    }

    @Test
    void theRestoreMarksSurviveTheFileAndACleanExitCopy() throws Exception {
        SessionSnapshot marked = snapshot(1, 1, true, false, false);
        SessionSnapshot read = SessionSnapshotStore.unmarshal(
            SessionSnapshotStore.marshal(marked).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(read.isSessionRestored()).isTrue();
        assertThat(read.isStable()).isFalse();

        SessionSnapshot quit = marked.withCleanExit(true);
        assertThat(quit.isSessionRestored()).isTrue();
        assertThat(quit.isCleanExit()).isTrue();
        assertThat(SessionRestoreDecision.endedRightAfterRestore(quit)).isFalse();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Facts none() {
        return new Facts(false, 0, 0, false);
    }

    private static Facts empty() {
        return new Facts(false, 0, 0, false);
    }

    private static Facts clean() {
        return new Facts(true, 1, 3, false);
    }

    private static Facts crashed() {
        return new Facts(true, 1, 3, true);
    }

    private static SessionSnapshot marked(boolean restored, boolean stable, boolean cleanExit) {
        return snapshot(1, 1, restored, stable, cleanExit);
    }

    private SessionSnapshotStore.StartupState startUp() {
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        stores.add(store);
        return store.startUp();
    }

    /** Writes {@code snapshot} as the last run's session, as that run's store would have. */
    private void leaveLastSession(SessionSnapshot snapshot) throws Exception {
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        try {
            store.write(snapshot);
        } finally {
            store.close();
        }
    }

    private static SessionSnapshot snapshot(int windows, int tabsPerWindow, boolean restored, boolean stable,
                                            boolean cleanExit) {
        Project project = new Project("Session");
        project.setAutoReconnect(true);
        for (int w = 0; w < windows; w++) {
            WindowState window = new WindowState("w" + w);
            for (int t = 0; t < tabsPerWindow; t++) {
                window.addTab(new SessionState("s-" + w + "-" + t, "conn-" + w + "-" + t));
            }
            project.addWindow(window);
        }
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setProject(project);
        snapshot.setSessionRestored(restored);
        snapshot.setStable(stable);
        snapshot.setCleanExit(cleanExit);
        return snapshot;
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter xml = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, xml);
        return xml.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }
}
