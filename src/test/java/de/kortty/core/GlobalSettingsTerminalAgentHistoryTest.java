package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class GlobalSettingsTerminalAgentHistoryTest {

    @Test
    void historySizeClampsAndDefaults() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getTerminalAgentInputHistorySize()).isEqualTo(20);
        settings.setTerminalAgentInputHistorySize(null);
        assertThat(settings.getTerminalAgentInputHistorySize()).isEqualTo(20);
        settings.setTerminalAgentInputHistorySize(3);
        assertThat(settings.getTerminalAgentInputHistorySize()).isEqualTo(5);   // min clamp
        settings.setTerminalAgentInputHistorySize(500);
        assertThat(settings.getTerminalAgentInputHistorySize()).isEqualTo(100); // max clamp
        settings.setTerminalAgentInputHistorySize(42);
        assertThat(settings.getTerminalAgentInputHistorySize()).isEqualTo(42);
    }

    @Test
    void addInputIsNewestFirstDedupedAndCapped() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalAgentInputHistorySize(5);

        settings.addTerminalAgentInput("a");
        settings.addTerminalAgentInput("b");
        settings.addTerminalAgentInput("a"); // dedupe → moves to front
        assertThat(settings.getTerminalAgentInputHistory()).containsExactly("a", "b").inOrder();

        for (int i = 0; i < 10; i++) {
            settings.addTerminalAgentInput("cmd" + i);
        }
        assertThat(settings.getTerminalAgentInputHistory()).hasSize(5);
        assertThat(settings.getTerminalAgentInputHistory().get(0)).isEqualTo("cmd9");

        settings.addTerminalAgentInput("   ");
        settings.addTerminalAgentInput(null);
        assertThat(settings.getTerminalAgentInputHistory()).hasSize(5);
    }

    @Test
    void historyEntriesWithTimestampsSurviveJaxbRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.addTerminalAgentInput("show logs", 1_234L);
        settings.addTerminalAgentInput("disk usage", 5_678L);

        jakarta.xml.bind.JAXBContext ctx = jakarta.xml.bind.JAXBContext.newInstance(GlobalSettings.class);
        java.io.StringWriter sw = new java.io.StringWriter();
        ctx.createMarshaller().marshal(settings, sw);
        GlobalSettings restored = (GlobalSettings) ctx.createUnmarshaller()
            .unmarshal(new java.io.StringReader(sw.toString()));

        var entries = restored.getTerminalAgentInputHistoryEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getPrompt()).isEqualTo("disk usage");
        assertThat(entries.get(0).getLastUsedEpochMillis()).isEqualTo(5_678L);
        assertThat(entries.get(1).getPrompt()).isEqualTo("show logs");
        assertThat(entries.get(1).getLastUsedEpochMillis()).isEqualTo(1_234L);
    }

    @Test
    void removeInputDeletesMatchingEntryAndReportsResult() {
        GlobalSettings settings = new GlobalSettings();
        settings.addTerminalAgentInput("show logs");
        settings.addTerminalAgentInput("disk usage");
        settings.addTerminalAgentInput("restart nginx");

        // Trimmed comparison: surrounding whitespace must not prevent a match.
        assertThat(settings.removeTerminalAgentInput("  disk usage  ")).isTrue();
        assertThat(settings.getTerminalAgentInputHistory())
            .containsExactly("restart nginx", "show logs").inOrder();

        // No-ops: missing prompt, blank, null.
        assertThat(settings.removeTerminalAgentInput("never recorded")).isFalse();
        assertThat(settings.removeTerminalAgentInput("   ")).isFalse();
        assertThat(settings.removeTerminalAgentInput(null)).isFalse();
        assertThat(settings.getTerminalAgentInputHistory()).hasSize(2);
    }

    @Test
    void removeInputMatchesLegacyEntriesStoredWithSurroundingWhitespace() {
        // Simulate an entry that entered via hand-edited/externally-migrated XML (the in-app add path
        // always trims, but JAXB unmarshalling preserves surrounding whitespace verbatim).
        GlobalSettings settings = new GlobalSettings();
        java.util.List<de.kortty.model.TerminalAgentInputHistoryEntry> seeded = new java.util.ArrayList<>();
        seeded.add(new de.kortty.model.TerminalAgentInputHistoryEntry("  show logs  ", 1_000L));
        settings.setTerminalAgentInputHistory(seeded);

        // The popup passes the raw stored prompt; removal must still match on the trimmed key.
        assertThat(settings.removeTerminalAgentInput("show logs")).isTrue();
        assertThat(settings.getTerminalAgentInputHistory()).isEmpty();
    }

    @Test
    void historyPopupGeometryDefaultsAndClamps() {
        GlobalSettings settings = new GlobalSettings();
        // Sensible defaults when nothing is stored.
        assertThat(settings.getTerminalAgentHistoryPopupWidth()).isEqualTo(460);
        assertThat(settings.getTerminalAgentHistoryPopupHeight()).isEqualTo(260);

        // Stored values round-trip when within range.
        settings.setTerminalAgentHistoryPopupWidth(700);
        settings.setTerminalAgentHistoryPopupHeight(400);
        assertThat(settings.getTerminalAgentHistoryPopupWidth()).isEqualTo(700);
        assertThat(settings.getTerminalAgentHistoryPopupHeight()).isEqualTo(400);

        // Out-of-range values clamp to the allowed bounds.
        settings.setTerminalAgentHistoryPopupWidth(50);
        settings.setTerminalAgentHistoryPopupHeight(50);
        assertThat(settings.getTerminalAgentHistoryPopupWidth()).isEqualTo(280);
        assertThat(settings.getTerminalAgentHistoryPopupHeight()).isEqualTo(120);
        settings.setTerminalAgentHistoryPopupWidth(5000);
        settings.setTerminalAgentHistoryPopupHeight(5000);
        assertThat(settings.getTerminalAgentHistoryPopupWidth()).isEqualTo(1400);
        assertThat(settings.getTerminalAgentHistoryPopupHeight()).isEqualTo(900);
    }

    @Test
    void clearInputHistoryEmptiesEverything() {
        GlobalSettings settings = new GlobalSettings();
        settings.addTerminalAgentInput("a");
        settings.addTerminalAgentInput("b");
        assertThat(settings.getTerminalAgentInputHistory()).hasSize(2);

        settings.clearTerminalAgentInputHistory();
        assertThat(settings.getTerminalAgentInputHistory()).isEmpty();
        assertThat(settings.getTerminalAgentInputHistoryEntries()).isEmpty();

        // Clearing an already-empty history is a safe no-op.
        settings.clearTerminalAgentInputHistory();
        assertThat(settings.getTerminalAgentInputHistory()).isEmpty();
    }

    @Test
    void dedupesByPromptTextEvenWhenTimestampsDiffer() {
        GlobalSettings settings = new GlobalSettings();

        settings.addTerminalAgentInput("show logs", 1_000L);
        settings.addTerminalAgentInput("disk usage", 2_000L);
        settings.addTerminalAgentInput("show logs", 3_000L); // same prompt, newer timestamp

        // Exactly one entry per distinct prompt text, newest first.
        assertThat(settings.getTerminalAgentInputHistory()).containsExactly("show logs", "disk usage").inOrder();

        var entries = settings.getTerminalAgentInputHistoryEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getPrompt()).isEqualTo("show logs");
        // The retained entry carries the most recent execution time.
        assertThat(entries.get(0).getLastUsedEpochMillis()).isEqualTo(3_000L);
        assertThat(entries.get(1).getPrompt()).isEqualTo("disk usage");
        assertThat(entries.get(1).getLastUsedEpochMillis()).isEqualTo(2_000L);
    }
}
