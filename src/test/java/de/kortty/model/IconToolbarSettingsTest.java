package de.kortty.model;

import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import jakarta.xml.bind.JAXBContext;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

public class IconToolbarSettingsTest {
    @Test
    void defaultLayoutMatchesApprovedGroupsAndOrder() {
        var entries = IconToolbarSettings.defaults().entries();
        assertThat(entries.stream().map(Entry::target).toList()).containsExactly(
            "menu.file.newTab", "toolbar.localShell",
            "menu.connections.sftpClient", "menu.tools.snippets", "menu.tools.sessionJournals",
            "menu.tools.aiManager", "menu.tools.jobScheduler", "menu.tools.videoManager",
            "toolbar.settings.font", "toolbar.settings.colors", "toolbar.settings.keyboard",
            "toolbar.settings.logging", "toolbar.settings.window", "toolbar.settings.terminal",
            "toolbar.settings.ai").inOrder();
        assertThat(entries.subList(0, 2).stream().map(Entry::section).toList())
            .containsExactly(Section.TERMINAL, Section.TERMINAL);
        assertThat(entries.subList(2, 8).stream().map(Entry::section).distinct().toList())
            .containsExactly(Section.TOOLS);
        assertThat(entries.subList(8, 15).stream().map(Entry::section).distinct().toList())
            .containsExactly(Section.SETTINGS);
    }

    @Test
    void iconSizeUsesCompatibleDefaultsAndPersistsIndependentlyOfLayout() throws Exception {
        var context = JAXBContext.newInstance(GlobalSettings.class);
        var legacy = (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader("<globalSettings/>"));
        assertThat(legacy.getToolbarIconSize()).isEqualTo(24);
        for (int size : List.of(16, 24, 32, 64)) {
            legacy.setToolbarIconSize(size);
            assertThat(roundTrip(legacy).getToolbarIconSize()).isEqualTo(size);
            assertThat(legacy.getIconToolbar().entries()).hasSize(15);
        }
        legacy.setToolbarIconSize(0);
        assertThat(legacy.getToolbarIconSize()).isEqualTo(16);
        legacy.setToolbarIconSize(1000);
        assertThat(legacy.getToolbarIconSize()).isEqualTo(64);
        var invalid = (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader(
            "<globalSettings><toolbarIconSize>999</toolbarIconSize></globalSettings>"));
        assertThat(invalid.getToolbarIconSize()).isEqualTo(24);
    }

    @Test
    void oldConfigurationUsesVisibleDefaultsButExplicitEmptySurvivesRestart() throws Exception {
        var context = JAXBContext.newInstance(GlobalSettings.class);
        GlobalSettings old = (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader("<globalSettings/>"));
        assertThat(old.getIconToolbar().isVisible()).isTrue();
        assertThat(old.getIconToolbar().isConnectionsVisible()).isTrue();
        assertThat(old.getIconToolbar().entries()).hasSize(15);
        assertThat(old.getIconToolbar().entries().stream().map(Entry::target).toList())
            .doesNotContain("menu.settings.global");
        old.setIconToolbar(new IconToolbarSettings());
        GlobalSettings restored = roundTrip(old);
        assertThat(restored.getIconToolbar().entries()).isEmpty();
        assertThat(restored.getIconToolbar().isVisible()).isTrue();
    }

    @Test
    void rowVisibilityIsIndependentAndSurvivesRoundTrip() throws Exception {
        for (boolean actions : List.of(false, true)) {
            for (boolean connections : List.of(false, true)) {
                var toolbar = IconToolbarSettings.defaults();
                toolbar.setVisible(actions);
                toolbar.setConnectionsVisible(connections);
                GlobalSettings global = new GlobalSettings();
                global.setIconToolbar(toolbar);
                var restored = roundTrip(global).getIconToolbar();
                assertThat(restored.isVisible()).isEqualTo(actions);
                assertThat(restored.isConnectionsVisible()).isEqualTo(connections);
            }
        }
    }

    @Test
    void legacyVisibilityAppliesToBothRowsUntilOneIsChanged() throws Exception {
        for (boolean visible : List.of(false, true)) {
            String xml = "<globalSettings><iconToolbar visible=\"" + visible + "\"/></globalSettings>";
            var global = (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
                .createUnmarshaller().unmarshal(new StringReader(xml));
            var toolbar = global.getIconToolbar();
            assertThat(toolbar.isVisible()).isEqualTo(visible);
            assertThat(toolbar.isConnectionsVisible()).isEqualTo(visible);
            toolbar.setVisible(!visible);
            assertThat(toolbar.isConnectionsVisible()).isEqualTo(visible);
            toolbar.setConnectionsVisible(!visible);
            assertThat(toolbar.isVisible()).isEqualTo(!visible);
        }
    }

    @Test
    void referencesOrderingAndVisibilityRoundTripWithoutCredentials() throws Exception {
        var toolbar = new IconToolbarSettings();
        toolbar.setVisible(false);
        toolbar.setEntries(List.of(Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work/Prod"),
            Entry.action("toolbar.localShell", Section.TERMINAL),
            Entry.create(Kind.CONNECTION, Section.CONNECTIONS, "server-id"),
            Entry.create(Kind.SEPARATOR, Section.SETTINGS, "")));
        GlobalSettings settings = new GlobalSettings();
        settings.setIconToolbar(toolbar);
        var restored = roundTrip(settings).getIconToolbar();
        assertThat(restored.isVisible()).isFalse();
        assertThat(restored.entries().stream().map(Entry::target).toList())
            .containsExactly("Work/Prod", "toolbar.localShell", "server-id", "").inOrder();
        assertThat(restored.entries().stream().map(Entry::id).toList())
            .containsExactlyElementsIn(toolbar.entries().stream().map(Entry::id).toList()).inOrder();
    }

    @Test
    void modifyingRetrievedSettingsDoesNotLeakIntoStoredSettings() {
        GlobalSettings global = new GlobalSettings();
        global.setIconToolbar(IconToolbarSettings.defaults());
        var editing = global.getIconToolbar();
        editing.setEntries(List.of());
        editing.setVisible(false);
        assertThat(global.getIconToolbar().entries()).hasSize(15);
        assertThat(global.getIconToolbar().isVisible()).isTrue();
    }

    @Test
    void renameMovesGroupAndDescendantsButNeverSimilarlyNamedSiblingOrServerId() {
        var toolbar = new IconToolbarSettings();
        toolbar.setEntries(List.of(Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work/Prod"),
            Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work/Prod/Web"),
            Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work/Production"),
            Entry.create(Kind.CONNECTION, Section.CONNECTIONS, "Work/Prod")));
        var changed = toolbar.renameGroup("Work/Prod", "Work/Live", "/");
        assertThat(changed.entries().stream().map(Entry::target).toList())
            .containsExactly("Work/Live", "Work/Live/Web", "Work/Production", "Work/Prod").inOrder();
        assertThat(toolbar.entries().getFirst().target()).isEqualTo("Work/Prod");
    }

    @Test
    void malformedEntriesAreDiscardedAndUnknownActionsPreservedForRepair() throws Exception {
        String xml = "<globalSettings><iconToolbar visible=\"false\">"
            + "<entry id=\"bad\" kind=\"CONNECTION\" section=\"TOOLS\" target=\"host\"/>"
            + "<entry id=\"future\" kind=\"ACTION\" section=\"TOOLS\" target=\"unknown.action\"/>"
            + "<entry kind=\"GROUP\" section=\"CONNECTIONS\" target=\"Work\"/>"
            + "</iconToolbar></globalSettings>";
        GlobalSettings restored = (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
            .createUnmarshaller().unmarshal(new StringReader(xml));
        assertThat(restored.getIconToolbar().entries()).hasSize(1);
        assertThat(restored.getIconToolbar().entries().getFirst().target()).isEqualTo("unknown.action");
    }

    private static GlobalSettings roundTrip(GlobalSettings settings) throws Exception {
        var context = JAXBContext.newInstance(GlobalSettings.class);
        var output = new StringWriter();
        context.createMarshaller().marshal(settings, output);
        return (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader(output.toString()));
    }
}
