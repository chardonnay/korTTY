package de.kortty.model;

import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted toolbar references, never connection credentials. Missing settings use defaults. */
@XmlAccessorType(XmlAccessType.FIELD)
public final class IconToolbarSettings {
    public enum Section { TERMINAL, TOOLS, SETTINGS, CONNECTIONS }
    public enum Kind { ACTION, CONNECTION, GROUP, SEPARATOR }

    @XmlAttribute
    private boolean visible = true;
    @XmlAttribute
    private Boolean connectionsVisible;
    @XmlAttribute
    private boolean logfileManagerMigrated = true;
    @XmlElement(name = "entry")
    private List<Entry> entries = new ArrayList<>();

    public IconToolbarSettings() { }

    public IconToolbarSettings(IconToolbarSettings source) {
        visible = source.visible;
        connectionsVisible = source.isConnectionsVisible();
        logfileManagerMigrated = source.logfileManagerMigrated;
        entries = new ArrayList<>(source.entries());
    }

    /** A missing XML marker identifies layouts saved before the logfile-manager default. */
    private void beforeUnmarshal(Unmarshaller unmarshaller, Object parent) {
        logfileManagerMigrated = false;
    }

    private void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
        if (logfileManagerMigrated) return;
        logfileManagerMigrated = true;
        entries = new ArrayList<>(entries());
        if (entries.stream().anyMatch(entry -> entry.kind == Kind.ACTION
                && "menu.tools.logfileManager".equals(entry.target))) return;

        int insertionIndex = entries.size();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.section != Section.TOOLS) continue;
            insertionIndex = index + 1;
            if (entry.kind == Kind.ACTION && "menu.tools.sessionJournals".equals(entry.target)) break;
        }
        entries.add(insertionIndex, Entry.action("menu.tools.logfileManager", Section.TOOLS));
    }

    public boolean isVisible() { return visible; }
    /** Visibility of the action row. Preserve the other row when upgrading an older layout. */
    public void setVisible(boolean value) {
        if (connectionsVisible == null) connectionsVisible = visible;
        visible = value;
    }
    /** Old layouts had a single switch, which applies to both rows until separately changed. */
    public boolean isConnectionsVisible() { return connectionsVisible == null ? visible : connectionsVisible; }
    public void setConnectionsVisible(boolean value) { connectionsVisible = value; }

    public List<Entry> entries() {
        if (entries == null) return List.of();
        return entries.stream().filter(Objects::nonNull).filter(Entry::valid).map(Entry::copy).toList();
    }

    public void setEntries(List<Entry> value) {
        entries = value.stream().filter(Objects::nonNull).filter(Entry::valid).map(Entry::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static IconToolbarSettings defaults() {
        IconToolbarSettings settings = new IconToolbarSettings();
        settings.entries.add(Entry.action("menu.file.newTab", Section.TERMINAL));
        settings.entries.add(Entry.action("toolbar.localShell", Section.TERMINAL));
        for (String id : List.of("menu.connections.sftpClient", "menu.tools.snippets",
                "menu.tools.sessionJournals", "menu.tools.logfileManager", "menu.tools.aiManager",
                "menu.tools.jobScheduler", "menu.tools.videoManager")) {
            settings.entries.add(Entry.action(id, Section.TOOLS));
        }
        for (String id : List.of("toolbar.settings.font",
                "toolbar.settings.colors", "toolbar.settings.keyboard", "toolbar.settings.logging",
                "toolbar.settings.window", "toolbar.settings.terminal")) {
            settings.entries.add(Entry.action(id, Section.SETTINGS));
        }
        return settings;
    }

    /** Renames exact paths and descendants; similarly prefixed sibling folders are untouched. */
    public IconToolbarSettings renameGroup(String oldPath, String newPath, String separator) {
        IconToolbarSettings result = new IconToolbarSettings(this);
        result.entries.replaceAll(entry -> {
            if (entry.kind != Kind.GROUP) return entry;
            if (entry.target.equals(oldPath) || entry.target.startsWith(oldPath + separator)) {
                return new Entry(entry.id, entry.kind, entry.section, newPath + entry.target.substring(oldPath.length()));
            }
            return entry;
        });
        return result;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static final class Entry {
        @XmlAttribute private String id;
        @XmlAttribute private Kind kind;
        @XmlAttribute private Section section;
        @XmlAttribute private String target;

        public Entry() { }

        private Entry(String id, Kind kind, Section section, String target) {
            this.id = id;
            this.kind = kind;
            this.section = section;
            this.target = target;
        }

        public static Entry create(Kind kind, Section section, String target) {
            Entry entry = new Entry(UUID.randomUUID().toString(), kind, section, target);
            if (!entry.valid()) throw new IllegalArgumentException("Invalid toolbar entry");
            return entry;
        }

        public static Entry action(String target, Section section) {
            return create(Kind.ACTION, section, target);
        }

        public String id() { return id; }
        public Kind kind() { return kind; }
        public Section section() { return section; }
        public String target() { return target; }
        public Entry copy() { return new Entry(id, kind, section, target); }
        public Entry inSection(Section value) { return new Entry(id, kind, value, target); }

        private boolean valid() {
            return id != null && !id.isBlank() && kind != null && section != null
                && (kind == Kind.SEPARATOR || target != null && !target.isBlank())
                && (kind != Kind.CONNECTION && kind != Kind.GROUP || section == Section.CONNECTIONS)
                && (kind != Kind.ACTION || section != Section.CONNECTIONS);
        }
    }
}
