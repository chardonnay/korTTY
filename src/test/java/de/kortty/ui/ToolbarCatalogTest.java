package de.kortty.ui;

import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import de.kortty.model.ServerConnection;
import de.kortty.ui.actions.ActionRegistry;
import de.kortty.ui.actions.AppAction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

/** Command callbacks are test doubles; descriptors and saved connection objects are real models. */
public class ToolbarCatalogTest {
    @Test
    void actionsRefreshTheirEnabledStateBeforeRunningAndRespectPolicyLocks() {
        // Explicit test-double command callbacks: count dispatch without opening an application UI.
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean enabled = new AtomicBoolean(true);
        ActionRegistry registry = new ActionRegistry();
        registry.register(new AppAction("test.action", "Action", "", null, List.of(), enabled::get,
            null, calls::incrementAndGet, true, false));
        registry.register(new AppAction("test.locked", "Locked", "", null, List.of(), () -> true,
            null, calls::incrementAndGet, true, true));
        var catalog = new ToolbarCatalog(registry, List::of, id -> { }, path -> { }, () -> enabled.set(false));
        catalog.run(Entry.action("test.action", Section.TOOLS));
        catalog.run(Entry.action("test.locked", Section.TOOLS));
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void connectionNamesResolveLiveAndDeletedTargetsAreDisabled() {
        List<ServerConnection> saved = new ArrayList<>();
        ServerConnection server = new ServerConnection("Before", "demo.invalid", 22, "demo");
        saved.add(server);
        var catalog = new ToolbarCatalog(new ActionRegistry(), () -> saved, id -> { }, group -> { }, () -> { });
        Entry reference = Entry.create(Kind.CONNECTION, Section.CONNECTIONS, server.getId());
        assertThat(catalog.describe(reference).label()).isEqualTo("Before");
        server.setName("After");
        assertThat(catalog.describe(reference).label()).isEqualTo("After");
        saved.clear();
        assertThat(catalog.describe(reference).enabled()).isFalse();
    }

    @Test
    void groupWithoutDirectMembersDoesNotOfferToConnectItsDescendants() {
        ServerConnection child = new ServerConnection("Child", "child.invalid", 22, "demo");
        child.setGroup("Work/Child");
        var catalog = new ToolbarCatalog(new ActionRegistry(), () -> List.of(child), id -> { }, group -> { }, () -> { });
        assertThat(catalog.describe(Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work")).enabled()).isFalse();
        assertThat(catalog.describe(Entry.action("unknown.action", Section.TOOLS)).enabled()).isFalse();
        assertThat(catalog.actions().stream().filter(entry -> entry.target().startsWith("toolbar.settings.")).count())
            .isEqualTo(SettingsSection.values().length);
        assertThat(catalog.actions().stream().map(Entry::target).toList()).contains("menu.settings.global");
        assertThat(catalog.describe(Entry.action("menu.tools.snippets", Section.TOOLS)).icon())
            .isEqualTo("snippetEditor");
        assertThat(catalog.describe(Entry.action("menu.tools.videoManager", Section.TOOLS)).icon())
            .isEqualTo("video");
        // The logfile manager is offered in the customizer with its own glyph, without being a default button.
        assertThat(catalog.actions().stream().map(Entry::target).toList()).contains("menu.tools.logfileManager");
        assertThat(catalog.describe(Entry.action("menu.tools.logfileManager", Section.TOOLS)).icon())
            .isEqualTo("logfile");
    }
}
