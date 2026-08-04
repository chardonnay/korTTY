package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;
import org.testng.annotations.Test;

import java.awt.Color;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalMarkersTest {

    private static SessionJournalMarkerDefinition custom(String id, String name, String color) {
        return new SessionJournalMarkerDefinition(id, name, color, false, SessionJournalMarker.IMPORTANT);
    }

    @Test
    void resolvesALegacyEntryToTheBuiltInDefinitionWithoutAnExplicitColour() {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setMarker(SessionJournalMarker.ERROR);

        SessionJournalMarkerDefinition resolved =
            SessionJournalMarkers.resolve(entry, new SessionJournalDocument());

        assertThat(resolved.getId()).isEqualTo(SessionJournalMarkerDefinition.ID_ERROR);
        assertThat(resolved.isBuiltIn()).isTrue();
        // null means "use the renderer palette", which is what keeps legacy pages identical.
        assertThat(resolved.getColor()).isNull();
    }

    @Test
    void applyWritesBothTheIdAndTheLegacyEnumValue() {
        SessionJournalEntry entry = new SessionJournalEntry();

        SessionJournalMarkers.apply(entry, custom("deploy", "Deployment", "#7c3aed"));

        assertThat(entry.getMarkerId()).isEqualTo("deploy");
        assertThat(entry.getMarker()).isEqualTo(SessionJournalMarker.IMPORTANT);
    }

    @Test
    void applyOfABuiltInClearsTheIdSoLegacyDocumentsStayUnchanged() {
        SessionJournalEntry entry = new SessionJournalEntry();
        SessionJournalMarkers.apply(entry, custom("deploy", "Deployment", "#7c3aed"));

        SessionJournalMarkers.apply(entry, SessionJournalMarkers.builtIn(SessionJournalMarker.INFO));

        assertThat(entry.getMarkerId()).isNull();
        assertThat(entry.getMarker()).isEqualTo(SessionJournalMarker.INFO);
    }

    @Test
    void resolveKeepsAnOrphanedMarkerVisibleInsteadOfDroppingItToUnmarked() {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setMarkerId("gone");
        entry.setMarker(SessionJournalMarker.ERROR);

        SessionJournalMarkerDefinition resolved =
            SessionJournalMarkers.resolve(entry, new SessionJournalDocument());

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("gone");
        assertThat(resolved.getLegacyMarker()).isEqualTo(SessionJournalMarker.ERROR);
    }

    @Test
    void snapshotStoresCustomDefinitionsReplacesRenamesAndNeverStoresBuiltIns() {
        SessionJournalDocument document = new SessionJournalDocument();

        assertThat(SessionJournalMarkers.snapshot(document, custom("deploy", "Deployment", "#7c3aed"))).isTrue();
        assertThat(SessionJournalMarkers.snapshot(document, custom("deploy", "Deployment", "#7c3aed"))).isFalse();
        assertThat(SessionJournalMarkers.snapshot(document, custom("deploy", "Rollout", "#7c3aed"))).isTrue();
        assertThat(SessionJournalMarkers.snapshot(
            document, SessionJournalMarkers.builtIn(SessionJournalMarker.ERROR))).isFalse();

        assertThat(document.getMarkerDefinitions()).hasSize(1);
        assertThat(document.getMarkerDefinitions().get(0).getName()).isEqualTo("Rollout");
    }

    @Test
    void pruneUnusedKeepsDefinitionsThatAnotherEntryStillReferences() {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalMarkers.snapshot(document, custom("deploy", "Deployment", "#7c3aed"));
        SessionJournalMarkers.snapshot(document, custom("outage", "Outage", "#f85149"));
        SessionJournalEntry entry = new SessionJournalEntry();
        SessionJournalMarkers.apply(entry, custom("deploy", "Deployment", "#7c3aed"));
        document.getEntries().add(entry);

        assertThat(SessionJournalMarkers.pruneUnused(document)).isEqualTo(1);
        assertThat(document.getMarkerDefinitions()).hasSize(1);
        assertThat(document.getMarkerDefinitions().get(0).getId()).isEqualTo("deploy");
    }

    @Test
    void registryOffersTheBuiltInsFirstAndDropsCustomDefinitionsSquattingABuiltInId() {
        GlobalSettings settings = new GlobalSettings();
        settings.getSessionJournalMarkers().add(custom("error", "Hijacked", "#000000"));
        settings.getSessionJournalMarkers().add(custom("deploy", "Deployment", "#7c3aed"));

        List<SessionJournalMarkerDefinition> registry = SessionJournalMarkers.registry(settings);

        assertThat(registry.stream().map(SessionJournalMarkerDefinition::getId).toList())
            .containsExactly("none", "info", "important", "error", "deploy").inOrder();
        assertThat(SessionJournalMarkers.byId("error", registry).getName()).isNull();
    }

    @Test
    void normalizeIdLowercasesCollapsesSeparatorsAndCapsTheLength() {
        assertThat(SessionJournalMarkerDefinition.normalizeId("  Software Installation "))
            .isEqualTo("software-installation");
        assertThat(SessionJournalMarkerDefinition.normalizeId("A//B")).isEqualTo("a-b");
        assertThat(SessionJournalMarkerDefinition.normalizeId("!!!")).isNull();
        assertThat(SessionJournalMarkerDefinition.normalizeId(""))
            .isNull();
        assertThat(SessionJournalMarkerDefinition.normalizeId("x".repeat(80)))
            .hasLength(SessionJournalMarkerDefinition.MAX_ID_LENGTH);
    }

    @Test
    void awtColorFallsBackToTheCallerPaletteForBuiltInsAndUnparsableValues() {
        Color fallback = new Color(0x12, 0x34, 0x56);

        assertThat(SessionJournalMarkers.awtColor(
            SessionJournalMarkers.builtIn(SessionJournalMarker.ERROR), fallback)).isEqualTo(fallback);
        assertThat(SessionJournalMarkers.awtColor(custom("x", "X", "nonsense"), fallback)).isEqualTo(fallback);
        assertThat(SessionJournalMarkers.awtColor(custom("x", "X", "#7c3aed"), fallback))
            .isEqualTo(new Color(0x7c, 0x3a, 0xed));
    }
}
