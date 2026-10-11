package de.kortty.ui;

import de.kortty.model.IconToolbarSettings;
import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import java.util.ArrayList;
import java.util.List;

/** Isolated edit transaction. The persisted settings are only replaced on Finish. */
final class ToolbarEditorModel {
    private final List<Entry> entries;

    ToolbarEditorModel(IconToolbarSettings settings) { entries = new ArrayList<>(settings.entries()); }
    List<Entry> entries() { return List.copyOf(entries); }
    void remove(String id) { entries.removeIf(entry -> entry.id().equals(id)); }
    void reset() { entries.clear(); entries.addAll(IconToolbarSettings.defaults().entries()); }

    void renameGroup(String oldPath, String newPath, String separator) {
        List<Entry> renamed = finish(true).renameGroup(oldPath, newPath, separator).entries();
        entries.clear();
        entries.addAll(renamed);
    }

    static boolean accepts(Entry entry, Section section) {
        return entry.kind() == Kind.SEPARATOR
            || (entry.kind() == Kind.ACTION) == (section != Section.CONNECTIONS);
    }

    /** Move an existing entry or insert a catalog entry before the target; null means section end. */
    void place(Entry entry, Section section, String beforeId) {
        if (!accepts(entry, section) || entry.id().equals(beforeId)) return;
        remove(entry.id());
        int index = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            Entry candidate = entries.get(i);
            if (candidate.section() == section) {
                index = i + 1;
                if (candidate.id().equals(beforeId)) { index = i; break; }
            }
        }
        entries.add(index, entry.inSection(section));
    }

    void step(String id, int direction) {
        Entry entry = entries.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return;
        List<Entry> row = entries.stream().filter(item -> item.section() == entry.section()).toList();
        int index = row.indexOf(entry);
        int destination = index + direction;
        if (destination < 0 || destination >= row.size()) return;
        String before = direction < 0 ? row.get(destination).id()
            : destination + 1 < row.size() ? row.get(destination + 1).id() : null;
        place(entry, entry.section(), before);
    }

    IconToolbarSettings finish(boolean visible) {
        IconToolbarSettings result = new IconToolbarSettings();
        result.setVisible(visible);
        result.setEntries(entries);
        return result;
    }
}
