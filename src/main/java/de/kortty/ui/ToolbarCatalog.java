package de.kortty.ui;

import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import de.kortty.model.ServerConnection;
import de.kortty.ui.actions.ActionRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Resolves live action/connection references; the toolbar never owns authentication data. */
final class ToolbarCatalog {
    record Presentation(String label, String icon, String shortcut, boolean enabled, boolean checked) { }
    private final ActionRegistry actions;
    private final Supplier<List<ServerConnection>> connections;
    private final Consumer<String> connect;
    private final Consumer<String> connectGroup;
    private final Runnable refreshActions;

    ToolbarCatalog(ActionRegistry actions, Supplier<List<ServerConnection>> connections,
                   Consumer<String> connect, Consumer<String> connectGroup, Runnable refreshActions) {
        this.actions = actions;
        this.connections = connections;
        this.connect = connect;
        this.connectGroup = connectGroup;
        this.refreshActions = refreshActions;
    }

    List<Entry> actions() {
        List<Entry> result = new ArrayList<>(de.kortty.model.IconToolbarSettings.defaults().entries());
        result.add(Entry.action("menu.settings.global", Section.SETTINGS));
        for (SettingsSection section : SettingsSection.values()) {
            if (result.stream().noneMatch(entry -> entry.target().equals(section.actionId()))) {
                result.add(Entry.action(section.actionId(), Section.SETTINGS));
            }
        }
        return List.copyOf(result);
    }

    List<Entry> connections() {
        List<Entry> result = new ArrayList<>();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (ServerConnection connection : connections.get()) {
            if (!connection.isPlaceholder()) {
                result.add(Entry.create(Kind.CONNECTION, Section.CONNECTIONS, connection.getId()));
            }
            if (connection.getGroup() != null && !connection.getGroup().isBlank()) groups.add(connection.getGroup());
        }
        for (String group : groups) result.add(Entry.create(Kind.GROUP, Section.CONNECTIONS, group));
        return List.copyOf(result);
    }

    Presentation describe(Entry entry) {
        if (entry.kind() == Kind.SEPARATOR) return new Presentation(I18n.get("toolbar.separator"), "separator", "", true, false);
        if (entry.kind() == Kind.CONNECTION) {
            return connections.get().stream().filter(c -> !c.isPlaceholder() && entry.target().equals(c.getId())).findFirst()
                .map(c -> new Presentation(c.getDisplayName(), "server", "",
                    de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(c).isEmpty(), false))
                .orElseGet(() -> missing(entry.target(), "server"));
        }
        if (entry.kind() == Kind.GROUP) {
            boolean available = connections.get().stream().anyMatch(c -> !c.isPlaceholder()
                && entry.target().equals(c.getGroup()) && de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(c).isEmpty());
            return new Presentation(entry.target(), "group", "", available, false);
        }
        return actions.find(entry.target()).map(action -> new Presentation(action.label(), icon(entry.target()),
                action.accelerator() == null ? "" : action.accelerator().getDisplayText(),
                !action.policyLocked() && action.isEnabled(), action.isChecked()))
            .orElseGet(() -> missing(entry.target(), icon(entry.target())));
    }

    private static Presentation missing(String target, String icon) {
        return new Presentation(I18n.get("toolbar.unavailable", target), icon, "", false, false);
    }

    void refresh() { refreshActions.run(); }

    void run(Entry entry) {
        refresh();
        if (!describe(entry).enabled()) return;
        switch (entry.kind()) {
            case ACTION -> actions.run(entry.target());
            case CONNECTION -> connect.accept(entry.target());
            case GROUP -> connectGroup.accept(entry.target());
            case SEPARATOR -> { }
        }
    }

    private static String icon(String action) {
        if (action.startsWith("toolbar.settings.")) return action.substring("toolbar.settings.".length());
        return switch (action) {
            case "menu.file.newTab" -> "tab";
            case "toolbar.localShell" -> "shell";
            case "menu.connections.sftpClient" -> "sftp";
            case "menu.tools.snippets" -> "snippetEditor";
            case "menu.tools.aiManager" -> "ai";
            case "menu.tools.jobScheduler" -> "jobs";
            case "menu.tools.sessionJournals" -> "journal";
            case "menu.tools.videoManager" -> "video";
            case "menu.tools.logfileManager" -> "logfile";
            default -> "gear";
        };
    }
}
